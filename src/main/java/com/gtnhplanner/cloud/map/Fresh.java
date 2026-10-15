package com.gtnhplanner.cloud.map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.library.PlanExport;

/**
 * Everything on a board as a shared plan holds it, each thing written afresh: its website fields (the posting
 * export's, {@link PlanExport}, at the count the card is set to), the game's own data for it, and its state (that data
 * in one spelling) to tell later whether it changed. Places are kept apart: a move changes only a thing's position.
 */
final class Fresh {

    /**
     * One board thing written: the plan collection it goes in, its plan id, what it is on the board, its state
     * ({@link Canon} of its game data, with what else it hangs on for a wire; place left out), the game's own data for
     * it (written as its {@code gtnhplanner.game}; null on a task) and its place. Its website fields (and a card's
     * recipe) are worked out when first asked: a write asks only for the things that changed.
     */
    static final class Thing {

        private final String c, id, gameKey, state;
        @Nullable
        private final JsonObject game;
        private final boolean placed;
        private final int x, y;
        private final Supplier<JsonObject[]> make;
        @Nullable
        private JsonObject[] made;

        Thing(final String c, final String id, final String gameKey, final String state,
            @Nullable final JsonObject game, final boolean placed, final int x, final int y,
            final Supplier<JsonObject[]> make) {
            this.c = c;
            this.id = id;
            this.gameKey = gameKey;
            this.state = state;
            this.game = game;
            this.placed = placed;
            this.x = x;
            this.y = y;
            this.make = make;
        }

        String c() {
            return c;
        }

        String id() {
            return id;
        }

        String gameKey() {
            return gameKey;
        }

        String state() {
            return state;
        }

        @Nullable
        JsonObject game() {
            return game;
        }

        boolean placed() {
            return placed;
        }

        int x() {
            return x;
        }

        int y() {
            return y;
        }

        /** Its website fields, place and game data left out. */
        JsonObject site() {
            if (made == null) made = make.get();
            return made[0];
        }

        /** A card's recipe as the game writes it; null on anything else. */
        @Nullable
        JsonObject recipe() {
            if (made == null) made = make.get();
            return made.length > 1 ? made[1] : null;
        }
    }

    /** The plan-wide game data: how the plan balances, setting pins, spare shares, groups and its icon's key. */
    record Meta(JsonObject game, JsonObject site) {}

    private Fresh() {}

    /** Every board thing written, by game key, cards first, then drawers, their links, wires, notes and tasks. */
    static Map<String, Thing> of(final Graph g, final CloudGame game, final Basis names) {
        final Map<String, Thing> out = new LinkedHashMap<>();
        for (final Node n : g.getNodes()) {
            final String gameKey = "node:" + n.id;
            if (names.isCarried(gameKey)) continue;
            final String id = names.id("node", n.id);
            final JsonObject data = nodeGame(n);
            out.put(gameKey, new Thing("nodes", id, gameKey, Canon.of(data), data, true, n.x, n.y, () -> {
                final PlanExport.Card card = PlanExport
                    .card(n, FfPlan.GAME_RECIPE_PREFIX + id, game, n.machineConfig.getMachineCount());
                final JsonObject site = withoutPlace(card.node());
                site.addProperty("id", id);
                return new JsonObject[] { site, Derived.recipe(card.recipe()) };
            }));
        }
        for (final Drawer d : g.getDrawers()) {
            final String gameKey = "drawer:" + d.getId();
            final String id = names.id("drawer", d.getId());
            final JsonObject data = drawerGame(d);
            out.put(gameKey, new Thing("storages", id, gameKey, Canon.of(data), data, true, d.getX(), d.getY(), () -> {
                final JsonObject site = withoutPlace(PlanExport.storage(d, resource(d, game)));
                site.addProperty("id", id);
                return new JsonObject[] { site };
            }));
        }
        for (final Drawer d : g.getDrawers()) {
            final String drawerId = names.id("drawer", d.getId());
            final boolean feeds = d.getKind()
                .linksInputs();
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = g.nodes.get(link.nodeId());
                final String gameKey = linkKey(d, link);
                if (n == null || unwritable(names, gameKey, n)) continue;
                final String nodeId = names.id("node", n.id);
                final String id = drawerId + ":" + nodeId + ":" + link.portIndex();
                final JsonObject data = new JsonObject();
                data.addProperty("port", link.portIndex());
                out.put(gameKey, new Thing("edges", id, gameKey, Canon.of(data), data, false, 0, 0, () -> {
                    final String from = feeds ? drawerId : nodeId, to = feeds ? nodeId : drawerId;
                    return new JsonObject[] { PlanExport.edge(id, from, to, resource(d, game)) };
                }));
            }
        }
        for (final Edge e : g.getEdges()) {
            final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
            if (from == null || to == null || e.sourceOutputIndex < 0 || e.sourceOutputIndex >= from.outputs.size())
                continue;
            final String gameKey = "edge:" + e.id;
            if (unwritable(names, gameKey, from) || unwritable(names, gameKey, to)) continue;
            final String id = names.id("edge", e.id), source = names.id("node", from.id),
                target = names.id("node", to.id);
            final Port<?> carried = from.outputs.get(e.sourceOutputIndex);
            final JsonObject data = new JsonObject();
            data.addProperty("srcOut", e.sourceOutputIndex);
            data.addProperty("dstIn", e.targetInputIndex);
            final JsonObject state = LiveJson.shallow(data);
            state.addProperty("src", e.sourceNodeId.toString());
            state.addProperty("dst", e.targetNodeId.toString());
            out.put(
                gameKey,
                new Thing(
                    "edges",
                    id,
                    gameKey,
                    Canon.of(state),
                    data,
                    false,
                    0,
                    0,
                    () -> new JsonObject[] { PlanExport.edge(id, source, target, port(game, carried)) }));
        }
        for (final Note n : g.getNotes()) {
            final String gameKey = "note:" + n.getId();
            final String id = names.id("note", n.getId());
            final JsonObject data = graphData(Serializer.GSON.toJsonTree(n));
            out.put(
                gameKey,
                new Thing("annotations", id, gameKey, Canon.of(data), data, true, n.getX(), n.getY(), () -> {
                    final JsonObject site = withoutPlace(PlanExport.note(n));
                    site.addProperty("id", id);
                    return new JsonObject[] { site };
                }));
        }
        for (final Tasks.Task t : g.tasks) task(out, "tasks", "task:", t.id(), Tasks.toJson(t));
        for (final Tasks.Item i : g.taskItems) task(out, "taskItems", "item:", i.id(), Tasks.toJson(i));
        for (final Tasks.Comment c : g.taskComments) task(out, "taskComments", "comment:", c.id(), Tasks.toJson(c));
        return out;
    }

    /**
     * Whether a wire to a card is one the plan cannot hold: one the game made to a later recipe of a shared machine the
     * website made, which the plan knows only as its card. Those the plan had are its own and are written as they were.
     */
    private static boolean unwritable(final Basis names, final String gameKey, final Node card) {
        return names.isCarried("node:" + card.id) && names.thingOf(gameKey) == null;
    }

    private static void task(final Map<String, Thing> out, final String c, final String kind, final String id,
        final JsonObject json) {
        out.put(
            kind + id,
            new Thing(c, id, kind + id, Canon.of(json), null, false, 0, 0, () -> new JsonObject[] { json }));
    }

    /** The plan-wide game data and website fields. */
    static Meta meta(final Graph g, final CloudGame game, final Basis names) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "balanceMode",
            g.getBalanceMode()
                .name());
        if (!g.settingPins.isEmpty()) data.add("settingPins", Serializer.settingPinsToJson(g));
        if (!g.spareShares.isEmpty()) data.add("spareShares", Serializer.spareSharesToJson(g));
        final JsonArray groups = new JsonArray();
        for (final Group group : g.getGroups()) {
            if (names.isCarried("group:" + group.getId())) continue;
            groups.add(Serializer.GSON.toJsonTree(group));
        }
        if (groups.size() > 0) data.add("groups", groups);
        if (g.getIcon() != null) data.addProperty("icon", g.getIcon());

        final JsonObject site = new JsonObject();
        site.addProperty("schemaVersion", 1);
        // The website's plans always have a name: a nameless board leaves the plan's as it is.
        if (!g.getName()
            .isBlank()) site.addProperty("name", g.getName());
        if (!g.getDescription()
            .isEmpty()) site.addProperty("description", g.getDescription());
        final PlanExport.Res face = g.getIcon() == null ? null : game.resource(g.getIcon());
        if (face != null) site.add("icon", PlanExport.icon(face));
        site.addProperty("solveMode", true);
        if (g.getPrimaryView() != null) site.addProperty("primaryView", g.getPrimaryView());
        final JsonObject meta = new JsonObject();
        meta.addProperty("source", "GTNH Planner");
        site.add("metadata", meta);
        return new Meta(data, site);
    }

    // region The game's own data

    /** A node's save, without its id and place (the plan's id and position say them). */
    static JsonObject nodeGame(final Node n) {
        final JsonObject o = Serializer.nodeToJson(n);
        o.remove("id");
        o.remove("x");
        o.remove("y");
        return o;
    }

    /** A drawer's save, without its id and place, and without its links: the plan's wires are its links. */
    static JsonObject drawerGame(final Drawer d) {
        final JsonObject o = graphData(Serializer.GSON.toJsonTree(d, Drawer.class));
        o.remove("links");
        return o;
    }

    /** A board thing's save without its id and place. */
    private static JsonObject graphData(final JsonElement saved) {
        final JsonObject o = saved.getAsJsonObject();
        o.remove("id");
        o.remove("x");
        o.remove("y");
        return o;
    }

    static String linkKey(final Drawer d, final Drawer.Link link) {
        return "link:" + d.getId() + ":" + link.nodeId() + ":" + link.portIndex();
    }

    // endregion

    // region Resources the website could not name

    /**
     * A drawer's resource as the site names it, else as its key spells it: a drawer of something the game's names do
     * not reach still goes into the plan (its key rides in its game data).
     */
    static PlanExport.Res resource(final Drawer d, final CloudGame game) {
        final PlanExport.Res r = PlanExport.resource(d, game);
        return r != null ? r : fromKey(d.getResourceKey(), d.getLabel());
    }

    /** A port's resource as a wire names it: as the export names it, else as the importer reads it. */
    static PlanExport.Res port(final CloudGame game, final Port<?> port) {
        if (port.getValue() instanceof com.gtnhplanner.power.Energy) return PlanExport.EU;
        final PlanExport.Res r = game.port(port);
        return r != null ? r : described(game, port);
    }

    /** A port's resource as the importer reads it, for a port the export leaves out. */
    static PlanExport.Res described(final CloudGame game, final Port<?> port) {
        final NodeMaker.PortInfo info = game.maker()
            .describe(port);
        final List<String> ids = info.ids();
        final String id = ids.isEmpty() ? info.key() : ids.getFirst();
        return new PlanExport.Res(
            info.kind()
                .isEmpty() ? "item" : info.kind(),
            id.isEmpty() ? "unknown" : id,
            info.label());
    }

    /** {@code item:<registry name>:<meta>} or {@code fluid:<name>} as the site's ids spell them. */
    static PlanExport.Res fromKey(final String key, final String label) {
        if (key.startsWith("fluid:")) return new PlanExport.Res("fluid", key.substring(6), label);
        if (key.startsWith("item:")) {
            final String rest = key.substring(5);
            final int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                final String meta = rest.substring(colon + 1);
                final String name = rest.substring(0, colon)
                    .toLowerCase(java.util.Locale.ROOT);
                return new PlanExport.Res("item", "0".equals(meta) ? name : name + "@" + meta, label);
            }
        }
        return new PlanExport.Res("item", key.isEmpty() ? "unknown" : key, label);
    }

    // endregion

    /** A copy without the position, which a thing's place gives. */
    static JsonObject withoutPlace(final JsonObject site) {
        final JsonObject o = LiveJson.shallow(site);
        o.remove("position");
        return o;
    }

    /** The game data a thing carries: {@code {v: 1, game, sum}}, the sum over its website fields, place left out. */
    static JsonObject extension(final JsonObject game, final JsonObject thing) {
        final JsonObject ext = new JsonObject();
        ext.addProperty("v", 1);
        ext.add("game", game);
        ext.add("sum", new JsonPrimitive(sum(thing)));
        return ext;
    }

    /** The sum of a thing's website fields: everything but its position and its game data. */
    static String sum(final JsonObject thing) {
        final JsonObject o = LiveJson.shallow(thing);
        o.remove("position");
        o.remove("gtnhplanner");
        return Canon.sum(o);
    }
}
