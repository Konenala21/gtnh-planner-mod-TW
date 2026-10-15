package com.gtnhplanner.cloud.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.cloud.net.CloudApi;

/**
 * This device's copy of one account's cloud plans, under {@code <game dir>/gtnhplanner/cloud/<accountId>/}: per doc key
 * the last plan the service confirmed with its seq, the batches sent or waiting to be that have not been acknowledged
 * yet, and the pins; and the plan list (names, icons, members, times). What a plan is opened from when offline, and
 * what a crash cannot take: a batch is on disk before it is sent and leaves when its echo comes.
 * <p>
 * Reads are served from memory (loaded from disk on first use). Every change is applied in memory at once and written
 * by one writer thread in the order it was made, each file whole: written beside as {@code .new}, forced to disk, then
 * moved over the old one. The returned future completes when the file is down; wait for it before sending a batch.
 */
public final class CloudCache {

    /** The last plan the service confirmed, and the seq it was at. */
    public record Doc(long seq, JsonObject plan) {}

    /** One local edit as it was sent: the seq it was based on, its id (the echo's), and its ops. */
    public record Batch(String cid, long base, JsonArray ops) {}

    private static final Gson GSON = new Gson();

    private final File dir;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner cloud cache");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, State> docs = new HashMap<>();
    @Nullable
    private CloudApi.Library list;
    private boolean listRead;

    private static final class State {

        @Nullable
        Doc doc;
        final List<Batch> pending = new ArrayList<>();
        List<String> pins = new ArrayList<>();
    }

    /** The cache of {@code accountId} under {@code mcDataDir}. */
    public static CloudCache forAccount(final File mcDataDir, final String accountId) {
        return new CloudCache(new File(mcDataDir, "gtnhplanner/cloud/" + safe(accountId)));
    }

    public CloudCache(final File dir) {
        this.dir = dir;
    }

    // region Plans

    /** The last confirmed plan for a doc key, or null if this device has never had it. */
    @Nullable
    public synchronized Doc doc(final String key) {
        return state(key).doc;
    }

    /** Records the plan as the service confirmed it at {@code seq}. */
    public synchronized CompletableFuture<Void> saveDoc(final String key, final long seq, final JsonObject plan) {
        final State s = state(key);
        s.doc = new Doc(seq, plan);
        final JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("seq", seq);
        o.add("plan", plan);
        return write(file(key, "plan.json"), GSON.toJson(o));
    }

    // endregion

    // region Pending batches

    /** The batches not yet acknowledged, oldest first. */
    public synchronized List<Batch> pending(final String key) {
        return new ArrayList<>(state(key).pending);
    }

    /** Keeps a batch before it is sent. Send it only once the future is done. */
    public synchronized CompletableFuture<Void> addPending(final String key, final Batch batch) {
        final State s = state(key);
        s.pending.add(batch);
        return writePending(key, s);
    }

    /** The batch with this id came back: it is no longer pending. */
    public synchronized CompletableFuture<Void> ack(final String key, final String cid) {
        final State s = state(key);
        if (!s.pending.removeIf(
            b -> b.cid()
                .equals(cid)))
            return CompletableFuture.completedFuture(null);
        return writePending(key, s);
    }

    /** Drops every pending batch of a doc (the plan was taken from the service afresh, or left). */
    public synchronized CompletableFuture<Void> clearPending(final String key) {
        final State s = state(key);
        if (s.pending.isEmpty()) return CompletableFuture.completedFuture(null);
        s.pending.clear();
        return writePending(key, s);
    }

    /** Doc keys that have batches waiting, found on disk too: what to rejoin and flush after a restart. */
    public synchronized List<String> keysWithPending() {
        final List<String> keys = new ArrayList<>();
        final File[] subs = new File(dir, "docs").listFiles(File::isDirectory);
        if (subs == null) return keys;
        for (final File d : subs) {
            final JsonObject o = readJson(new File(d, "pending.json"));
            if (o == null || !(o.get("key") instanceof com.google.gson.JsonPrimitive)) continue;
            final String key = o.get("key")
                .getAsString();
            if (!state(key).pending.isEmpty()) keys.add(key);
        }
        return keys;
    }

    private CompletableFuture<Void> writePending(final String key, final State s) {
        final JsonObject o = new JsonObject();
        o.addProperty("key", key);
        final JsonArray a = new JsonArray();
        for (final Batch b : s.pending) {
            final JsonObject bo = new JsonObject();
            bo.addProperty("cid", b.cid());
            bo.addProperty("base", b.base());
            bo.add("ops", b.ops());
            a.add(bo);
        }
        o.add("batches", a);
        return write(file(key, "pending.json"), GSON.toJson(o));
    }

    // endregion

    // region Pins and the list

    /** The pinned card ids of a plan on this device. */
    public synchronized List<String> pins(final String key) {
        return new ArrayList<>(state(key).pins);
    }

    public synchronized CompletableFuture<Void> setPins(final String key, final List<String> ids) {
        state(key).pins = new ArrayList<>(ids);
        final JsonObject o = new JsonObject();
        final JsonArray a = new JsonArray();
        for (final String id : ids) a.add(new com.google.gson.JsonPrimitive(id));
        o.add("ids", a);
        return write(file(key, "pins.json"), GSON.toJson(o));
    }

    /** The plan list as last fetched, or null before the first. */
    @Nullable
    public synchronized CloudApi.Library list() {
        if (!listRead) {
            listRead = true;
            final JsonObject o = readJson(new File(dir, "list.json"));
            if (o != null) {
                try {
                    list = CloudApi.Library.fromJson(o);
                } catch (final RuntimeException e) {
                    GtnhPlanner.LOG.warn("Could not read the cloud plan list", e);
                }
            }
        }
        return list;
    }

    public synchronized CompletableFuture<Void> saveList(final CloudApi.Library library) {
        list = library;
        listRead = true;
        return write(new File(dir, "list.json"), GSON.toJson(library.toJson()));
    }

    // endregion

    /** Forgets a plan on this device: the plan, its pending batches and its pins. */
    public synchronized CompletableFuture<Void> forget(final String key) {
        docs.remove(key);
        final File d = docDir(key);
        return CompletableFuture.runAsync(() -> {
            final File[] files = d.listFiles();
            if (files != null) for (final File f : files) f.delete();
            d.delete();
        }, writer);
    }

    /** Waits for every write made so far. */
    public void flush() {
        CompletableFuture.runAsync(() -> {}, writer)
            .join();
    }

    /** Waits for the writes and stops the writer thread. */
    public void close() {
        flush();
        writer.shutdown();
    }

    // region Files

    private State state(final String key) {
        State s = docs.get(key);
        if (s != null) return s;
        s = new State();
        // A leftover from a write that never finished: the real file is whole, so it is not read.
        for (final String n : new String[] { "plan.json", "pending.json", "pins.json" })
            new File(docDir(key), n + ".new").delete();
        final JsonObject plan = readJson(file(key, "plan.json"));
        if (plan != null && plan.get("plan") instanceof JsonObject) {
            s.doc = new Doc(
                plan.get("seq")
                    .getAsLong(),
                plan.getAsJsonObject("plan"));
        }
        final JsonObject pending = readJson(file(key, "pending.json"));
        if (pending != null && pending.get("batches") instanceof JsonArray) {
            for (final JsonElement e : pending.getAsJsonArray("batches")) {
                final JsonObject b = e.getAsJsonObject();
                s.pending.add(
                    new Batch(
                        b.get("cid")
                            .getAsString(),
                        b.get("base")
                            .getAsLong(),
                        b.getAsJsonArray("ops")));
            }
        }
        final JsonObject pins = readJson(file(key, "pins.json"));
        if (pins != null && pins.get("ids") instanceof JsonArray) {
            for (final JsonElement e : pins.getAsJsonArray("ids")) s.pins.add(e.getAsString());
        }
        docs.put(key, s);
        return s;
    }

    private File docDir(final String key) {
        return new File(new File(dir, "docs"), safe(key));
    }

    private File file(final String key, final String name) {
        return new File(docDir(key), name);
    }

    /** A key as a file name: doc keys carry a colon, which Windows does not allow. */
    private static String safe(final String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** A file's JSON, or null when it is missing or unreadable (a copy that failed to read is as good as none). */
    @Nullable
    private static JsonObject readJson(final File f) {
        if (!f.isFile()) return null;
        try {
            return new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (final IOException | RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not read {}", f, e);
            return null;
        }
    }

    private CompletableFuture<Void> write(final File file, final String text) {
        return CompletableFuture.runAsync(() -> {
            try {
                file.getParentFile()
                    .mkdirs();
                final File next = new File(file.getParentFile(), file.getName() + ".new");
                try (FileOutputStream out = new FileOutputStream(next)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.getFD()
                        .sync();
                }
                try {
                    Files.move(
                        next.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (final AtomicMoveNotSupportedException e) {
                    Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                GtnhPlanner.LOG.warn("Could not write {}", file, e);
                throw new java.io.UncheckedIOException(e);
            }
        }, writer);
    }

    // endregion
}
