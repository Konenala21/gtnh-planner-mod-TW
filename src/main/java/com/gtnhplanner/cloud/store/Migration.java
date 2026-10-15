package com.gtnhplanner.cloud.store;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;

/**
 * Sends a world's local plans to the signed-in account as new cloud plans, once each. The local save is never changed
 * beyond one field on a slot, {@link Graph#getCloudKey()}, which is what makes a plan done; the caller takes a backup
 * of the save first and saves after {@link #prepare}.
 * <p>
 * The steps are separate because they belong on different threads: {@link #prepare} and the save that follows read and
 * change the plans (client thread), {@link #upload} is the network (background). A slot gets its doc key in
 * {@code prepare}, before anything is sent, so a crash or a refusal mid-way leaves a key whose plan the account does
 * not have: the next {@code prepare} sees that (its id is not among the account's) and sends that one again, under the
 * same id, so nothing is ever uploaded twice. {@link #run} does all of it on one thread, for callers that have one.
 */
public final class Migration {

    private Migration() {}

    /** A plan to send: its slot's graph, the cloud plan's id and doc key, and the plan as the website holds it. */
    public record Item(Graph graph, String designId, String key, JsonObject plan) {}

    /** A plan that did not go, and why in the site's words. */
    public record Failure(String name, String reason) {}

    /** What {@link #upload} did: the doc keys that went up, and the plans that did not. */
    public record Result(List<String> uploaded, List<Failure> failed) {}

    /** Whether a slot has anything worth keeping in the cloud: not empty, not one that failed to read. */
    public static boolean eligible(final Graph g) {
        if (g.getUnreadable() != null && g.nodes.isEmpty()) return false;
        return !g.nodes.isEmpty() || !g.getDrawers()
            .isEmpty() || !g.notes.isEmpty();
    }

    /** The plans that have never been sent: what to tell the player about before offering to. */
    public static List<Graph> waiting(final Plan plan) {
        final List<Graph> out = new ArrayList<>();
        for (final Graph g : plan.getGraphs()) if (g.getCloudKey() == null && eligible(g)) out.add(g);
        return out;
    }

    /**
     * Gives each plan still to send its doc key and makes its cloud plan: the slots with no key yet, and the ones keyed
     * to this account whose id is not in {@code knownIds} (the ids of every plan the account holds, deleted ones too,
     * from {@link CloudApi#library}). Slots keyed to another account are left alone. Client thread; save the plans
     * afterwards so the keys are kept.
     *
     * @param toPlanJson the mapping from a graph to the website's plan
     */
    public static List<Item> prepare(final Plan plan, final String accountId, final Set<String> knownIds,
        final Function<Graph, JsonObject> toPlanJson) {
        final List<Item> items = new ArrayList<>();
        for (final Graph g : plan.getGraphs()) {
            if (!eligible(g)) continue;
            final String existing = g.getCloudKey();
            final String id;
            if (existing == null) {
                id = UUID.randomUUID()
                    .toString();
                g.setCloudKey(accountId + ":" + id);
            } else {
                final int colon = existing.indexOf(':');
                if (colon < 0 || !existing.substring(0, colon)
                    .equals(accountId)) continue;
                id = existing.substring(colon + 1);
                if (knownIds.contains(id)) continue;
            }
            items.add(new Item(g, id, accountId + ":" + id, toPlanJson.apply(g)));
        }
        return items;
    }

    /**
     * Sends each plan as a new cloud plan. A plan the site refuses is reported and the rest still go. {@code each}
     * hears every plan that went up (for progress). Background thread: it reads only what {@code prepare} gathered.
     */
    public static Result upload(final List<Item> items, final String token, @Nullable final Consumer<Item> each) {
        final List<String> uploaded = new ArrayList<>();
        final List<Failure> failed = new ArrayList<>();
        for (final Item item : items) {
            final String name = name(item.graph());
            try {
                final String now = Instant.now()
                    .truncatedTo(ChronoUnit.MILLIS)
                    .toString();
                final long opened = item.graph()
                    .getLastOpen();
                final String created = opened > 0 ? Instant.ofEpochMilli(opened)
                    .toString() : now;
                final CloudApi.Put put = CloudApi.put(
                    token,
                    item.designId(),
                    new CloudApi.Upsert(
                        name,
                        null,
                        null,
                        !item.graph()
                            .isOpen(),
                        false,
                        null,
                        created,
                        now,
                        now,
                        item.plan()));
                if (put.live()) failed.add(new Failure(name, "someone is editing it right now"));
                else {
                    uploaded.add(item.key());
                    if (each != null) each.accept(item);
                }
            } catch (final IOException e) {
                failed.add(new Failure(name, e.getMessage()));
            }
        }
        return new Result(uploaded, failed);
    }

    /**
     * All of it on the calling thread: {@code backUp} first (a copy of the save), then the account's list, the keys,
     * {@code save} (so they are kept before anything is sent) and the upload.
     *
     * @return the outcome, or null when nothing was waiting
     */
    @Nullable
    public static Result run(final Plan plan, final String accountId, final String token,
        final Function<Graph, JsonObject> toPlanJson, final Runnable backUp, final Runnable save) throws IOException {
        final Set<String> known = new java.util.HashSet<>();
        for (final CloudApi.Design d : CloudApi.library(token, accountId)
            .own()) known.add(d.id());
        final boolean any = !prepareless(plan, accountId, known);
        if (!any) return null;
        backUp.run();
        final List<Item> items = prepare(plan, accountId, known, toPlanJson);
        save.run();
        return upload(items, token, null);
    }

    /** True when {@link #prepare} would have nothing to do. */
    private static boolean prepareless(final Plan plan, final String accountId, final Set<String> known) {
        for (final Graph g : plan.getGraphs()) {
            if (!eligible(g)) continue;
            final String key = g.getCloudKey();
            if (key == null) return false;
            final int colon = key.indexOf(':');
            if (colon >= 0 && key.substring(0, colon)
                .equals(accountId) && !known.contains(key.substring(colon + 1))) return false;
        }
        return true;
    }

    private static String name(final Graph g) {
        String n = g.getName() == null ? ""
            : g.getName()
                .trim();
        if (n.isEmpty()) n = "Plan";
        return n.length() > 80 ? n.substring(0, 80) : n;
    }
}
