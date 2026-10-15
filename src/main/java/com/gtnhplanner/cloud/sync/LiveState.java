package com.gtnhplanner.cloud.sync;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One client's copy of a live plan, rule for rule the website's {@code client-state.ts}:
 *
 * <ul>
 * <li>{@link #confirmed()} is the plan at the last seq heard from the service; {@link #pending()} holds this client's
 * own batches the service has not echoed yet. What the board shows ({@link #view()}) is the confirmed plan with every
 * pending batch on top.</li>
 * <li>A local edit becomes a batch with a fresh cid and its inverse (for undo), goes to the end of the pending list,
 * and is sent.</li>
 * <li>A batch from someone else lands on the confirmed plan and the pending ones go on top again. The echo of an own
 * batch (its cid) lands on the confirmed plan and leaves the pending list.</li>
 * <li>Reconnecting takes the snapshot as the confirmed plan and sends every pending batch again; the service
 * acknowledges a batch it already applied without applying it twice.</li>
 * <li>Undo undoes only this client's own edits: the recorded inverse, sent as a new edit. A field someone else changed
 * since is overwritten, as in Figma.</li>
 * </ul>
 * Pure apart from the cid source, and saved whole ({@link #save()}) so pending edits outlive a closed game. One thread
 * owns it.
 */
public final class LiveState {

    /** One of this client's own batches: its cid, its ops, and what undoes it (against the view it was made on). */
    public record Batch(String cid, List<LiveOp> ops, List<LiveOp> inverse) {}

    /** What a batch from the service did: whether it was this client's own, and whether batches were missed. */
    public record Received(boolean own, boolean gap) {}

    private long seq;
    private JsonObject confirmed;
    private final List<Batch> pending = new ArrayList<>();
    /** The view, kept until something changes it. */
    @Nullable
    private JsonObject shown;
    private final List<List<LiveOp>> undo = new ArrayList<>(), redo = new ArrayList<>();
    private final Supplier<String> nextCid;

    /** A client starting from a snapshot, its cids from {@link #makeCid()}. */
    public LiveState(final long seq, final JsonObject plan) {
        this(seq, plan, LiveState::makeCid);
    }

    /** A client starting from a snapshot, its cids from {@code nextCid} (tests give fixed ones). */
    public LiveState(final long seq, final JsonObject plan, final Supplier<String> nextCid) {
        this.seq = seq;
        this.confirmed = plan;
        this.nextCid = nextCid;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static int cidCounter;

    /** A batch id unique enough across every client of one plan: 16 random hex digits and a counter. */
    public static synchronized String makeCid() {
        cidCounter = (cidCounter + 1) % 1_000_000;
        return String.format("%016x", RANDOM.nextLong()) + Integer.toString(cidCounter, 36);
    }

    public long seq() {
        return seq;
    }

    /** The plan at the last seq heard. */
    public JsonObject confirmed() {
        return confirmed;
    }

    /** This client's batches not yet echoed, oldest first. */
    public List<Batch> pending() {
        return Collections.unmodifiableList(pending);
    }

    /** Their cids, oldest first. */
    public List<String> pendingCids() {
        final List<String> out = new ArrayList<>();
        for (final Batch b : pending) out.add(b.cid());
        return out;
    }

    /** What the board shows: the confirmed plan with every pending batch on top. */
    public JsonObject view() {
        if (shown == null) {
            JsonObject plan = confirmed;
            for (final Batch b : pending) plan = LiveOps.apply(plan, b.ops());
            shown = plan;
        }
        return shown;
    }

    /** A local edit given as the plan it made: its difference from the view becomes a batch. Null when none. */
    @Nullable
    public Batch edit(final JsonObject next) {
        return local(LiveOps.diff(view(), next), false, true);
    }

    /** A local edit given as ops: undoable, and it clears what redo held. Null when there are none. */
    @Nullable
    public Batch local(final List<LiveOp> ops) {
        return local(ops, false, true);
    }

    @Nullable
    private Batch local(final List<LiveOp> ops, final boolean keepRedo, final boolean undoable) {
        if (ops.isEmpty()) return null;
        final JsonObject before = view();
        final Batch batch = new Batch(nextCid.get(), List.copyOf(ops), LiveOps.invert(before, ops));
        pending.add(batch);
        shown = LiveOps.apply(before, ops);
        if (undoable) undo.add(batch.inverse());
        if (!keepRedo) redo.clear();
        return batch;
    }

    /** The frame that sends a batch, based on the seq heard so far. */
    public JsonObject frame(final String doc, final Batch batch) {
        final JsonObject f = new JsonObject();
        f.addProperty("t", "ops");
        f.addProperty("doc", doc);
        f.addProperty("base", seq);
        f.addProperty("cid", batch.cid());
        f.add("ops", LiveOp.toJson(batch.ops()));
        return f;
    }

    /**
     * A batch from the service, someone else's or the echo of one of ours: it lands on the confirmed plan. A seq that
     * jumps past the next one is a gap (batches were missed): the copy can no longer be trusted, so join again for a
     * snapshot.
     */
    public Received receive(final long seq, @Nullable final String cid, final List<LiveOp> ops) {
        final boolean gap = seq > this.seq + 1;
        final int mine = cid == null ? -1 : indexOf(cid);
        confirmed = LiveOps.apply(confirmed, ops);
        this.seq = Math.max(this.seq, seq);
        if (mine >= 0) pending.remove(mine);
        shown = null;
        return new Received(mine >= 0, gap);
    }

    /** The service refused one of our batches (an error frame naming its cid): it never happened. */
    public boolean refuse(final String cid) {
        final int at = indexOf(cid);
        if (at < 0) return false;
        pending.remove(at);
        shown = null;
        return true;
    }

    private int indexOf(final String cid) {
        for (int i = 0; i < pending.size(); i++) if (pending.get(i)
            .cid()
            .equals(cid)) return i;
        return -1;
    }

    /**
     * A fresh snapshot after joining again: it becomes the confirmed plan, and every pending batch is returned, oldest
     * first, to be sent again (they stay pending until echoed).
     */
    public List<Batch> resync(final long seq, final JsonObject plan) {
        confirmed = plan;
        this.seq = seq;
        shown = null;
        return new ArrayList<>(pending);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Undoes this client's last own edit: its recorded inverse, sent as a new edit. Null when there is none. */
    @Nullable
    public Batch undo() {
        if (undo.isEmpty()) return null;
        final Batch batch = local(undo.remove(undo.size() - 1), true, false);
        if (batch != null) redo.add(batch.inverse());
        return batch;
    }

    @Nullable
    public Batch redo() {
        if (redo.isEmpty()) return null;
        return local(redo.remove(redo.size() - 1), true, true);
    }

    // region Saving

    /** What to keep on disk: the seq, the confirmed plan and the pending batches (undo lasts a session). */
    public JsonObject save() {
        final JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.add("confirmed", confirmed);
        final JsonArray batches = new JsonArray();
        for (final Batch b : pending) {
            final JsonObject batch = new JsonObject();
            batch.addProperty("cid", b.cid());
            batch.add("ops", LiveOp.toJson(b.ops()));
            batch.add("inverse", LiveOp.toJson(b.inverse()));
            batches.add(batch);
        }
        o.add("pending", batches);
        return o;
    }

    /** A client from {@link #save()}; null when the saved state does not read. */
    @Nullable
    public static LiveState restore(final JsonObject saved) {
        return restore(saved, LiveState::makeCid);
    }

    @Nullable
    public static LiveState restore(final JsonObject saved, final Supplier<String> nextCid) {
        final JsonElement seq = saved.get("seq"), confirmed = saved.get("confirmed"), pending = saved.get("pending");
        if (seq == null || !seq.isJsonPrimitive() || confirmed == null || !confirmed.isJsonObject()) return null;
        final LiveState state = new LiveState(seq.getAsLong(), confirmed.getAsJsonObject(), nextCid);
        if (pending != null && pending.isJsonArray()) for (final JsonElement e : pending.getAsJsonArray()) {
            if (!e.isJsonObject()) return null;
            final JsonObject b = e.getAsJsonObject();
            final JsonElement cid = b.get("cid");
            final LiveOps.Read ops = LiveOps.read(b.get("ops")), inverse = LiveOps.read(b.get("inverse"));
            if (cid == null || !cid.isJsonPrimitive() || ops.ops() == null || inverse.ops() == null) return null;
            state.pending.add(new Batch(cid.getAsString(), ops.ops(), inverse.ops()));
        }
        return state;
    }

    // endregion
}
