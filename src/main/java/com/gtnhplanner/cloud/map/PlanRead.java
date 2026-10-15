package com.gtnhplanner.cloud.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.GraphData;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.importer.FfCards;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.FfDrawers;
import com.gtnhplanner.importer.FfHandle;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSection;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfStorage;
import com.gtnhplanner.importer.FfPlanParser;
import com.gtnhplanner.importer.FfSettings;
import com.gtnhplanner.importer.ImportReport;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.library.PlanExport;

/**
 * A shared plan read onto a board ({@link CloudPlans#read}). Each thing whose game data still matches its website
 * fields (its {@code sum}) is the game's own again, exactly, at the place the plan gives it; anything the website made
 * or changed beyond its place is built the importer's way ({@link FfCards}) under its own id, keeping from its game
 * data what the website has no place for. What the game has no place for at all is left out of the board and kept
 * in the plan untouched.
 */
final class PlanRead {

    /** One recipe of a card on the board: what its wires find their ports by. */
    private record Section(Node node, @Nullable FfRecipe recipe, Map<Integer, FfSlot> overrides) {}

    private final JsonObject plan;
    private final CloudGame game;
    private final FfPlanParser.Things things;
    private final ImportReport report = new ImportReport();
    private final FfCards.Context ctx;
    private final FfCards.Ports ports;
    private final Graph graph;
    private final Basis basis = new Basis();
    private final Map<String, FfRecipe> recipes = new HashMap<>();
    /** The cards on the board by plan id, each its recipes by section. */
    private final Map<String, Map<Integer, Section>> cards = new HashMap<>();
    private final Map<String, Drawer> drawers = new HashMap<>();
    private final Map<String, FfStorage> storages = new HashMap<>();
    /** Drawers the website made, by plan id: their resource key and name come from the first port they hold. */
    private final Map<String, Drawer> unkeyed = new LinkedHashMap<>();

    PlanRead(final JsonObject plan, final CloudGame game) {
        this.plan = plan;
        this.game = game;
        this.things = new FfPlanParser.Things(plan);
        this.ctx = new FfCards.Context(game.recipes(), game.maker(), things.solveMode, report);
        this.ports = new FfCards.Ports(game.maker());
        final String name = str(plan, "name");
        this.graph = new Graph(name == null ? "" : name);
    }

    CloudPlans.Read run() {
        final JsonObject data = meta();
        for (final JsonObject r : objects(plan, "recipes")) {
            final FfRecipe recipe = things.recipe(r);
            if (recipe != null) recipes.putIfAbsent(recipe.id(), recipe);
        }
        for (final JsonObject n : objects(plan, "nodes")) node(n);
        final List<FfEdge> wires = new ArrayList<>();
        final List<JsonObject> wireJson = new ArrayList<>();
        for (final JsonObject e : objects(plan, "edges")) {
            final FfEdge edge = things.edge(e);
            if (edge == null) continue;
            wires.add(edge);
            wireJson.add(e);
        }
        final List<FfStorage> all = new ArrayList<>();
        for (final JsonObject s : objects(plan, "storages")) {
            final FfStorage storage = things.storage(s);
            if (storage != null) all.add(storage);
        }
        final Map<String, FfDrawers.Role> roles = FfDrawers.roles(
            new FfPlan(
                graph.getName(),
                things.solveMode,
                things.poolMode,
                List.of(),
                List.of(),
                wires,
                all,
                List.of(),
                null,
                List.of()));
        for (final JsonObject s : objects(plan, "storages")) storage(s, roles);
        for (int i = 0; i < wires.size(); i++) edge(wireJson.get(i), wires.get(i));
        // A drawer the website made that none of its wires reached is not on the board, as the importer leaves it.
        for (final Map.Entry<String, Drawer> d : unkeyed.entrySet()) {
            graph.removeDrawer(
                d.getValue()
                    .getId());
            drawers.remove(d.getKey());
            basis.byGame.remove(
                "drawer:" + d.getValue()
                    .getId());
            report.add(Kind.DROPPED, d.getKey(), "a drawer with no wires left in GTNH Planner; left out");
        }
        for (final JsonObject a : objects(plan, "annotations")) note(a);
        groups(data);
        tasks();
        basis.settle(Fresh.of(graph, game, basis));
        final Fresh.Meta meta = Fresh.meta(graph, game, basis);
        basis.metaState = Canon.of(meta.game());
        basis.metaSite = meta.site();
        return new CloudPlans.Read(graph, basis, report);
    }

    // region The plan

    /** The plan's own fields and its game data (returned, empty when it has none). */
    private JsonObject meta() {
        final String description = str(plan, "description");
        graph.setDescription(description == null ? "" : description);
        final String view = str(plan, "primaryView");
        graph.setPrimaryView(Tasks.PLAN.equals(view) || Tasks.TASKS.equals(view) ? view : null);
        final Ext ext = Ext.of(plan);
        final JsonObject data = ext == null ? new JsonObject() : ext.game();
        if (data.has("balanceMode")) {
            try {
                graph.setBalanceMode(BalanceMode.valueOf(str(data, "balanceMode")));
            } catch (final RuntimeException ignored) {}
        }
        Serializer.readSettingPins(data.get("settingPins"), graph);
        Serializer.readSpareShares(data.get("spareShares"), graph);
        // The icon by the key the game wrote, while the website's icon is still the one written with it.
        final JsonElement icon = plan.get("icon");
        if (ext != null && str(data, "icon") != null
            && ext.sum()
                .equals(metaSum(plan)))
            graph.setIcon(str(data, "icon"));
        else if (icon != null && icon.isJsonObject()) {
            final String kind = str(icon.getAsJsonObject(), "kind"), id = str(icon.getAsJsonObject(), "resourceId");
            if (kind != null && id != null) graph.setIcon(game.key(kind, id));
        }
        return data;
    }

    /** The website fields the plan's game data is summed over: its icon, the one plan field the game keeps richer. */
    static String metaSum(final JsonObject plan) {
        final JsonObject o = new JsonObject();
        if (plan.has("icon")) o.add("icon", plan.get("icon"));
        return Canon.sum(o);
    }

    /**
     * The plan's groups and shared machines, as a load reads them: one that names a card no longer on the board is
     * put right by the board, as after a load.
     */
    private void groups(final JsonObject data) {
        final JsonElement saved = data.get("groups");
        if (saved == null || !saved.isJsonArray()) return;
        for (final JsonElement e : saved.getAsJsonArray()) {
            final Group group;
            try {
                group = (Group) Serializer.GSON.fromJson(e, GraphData.class);
            } catch (final RuntimeException x) {
                continue;
            }
            if (group == null || group.getId() == null) continue;
            graph.groups.put(group.getId(), group);
        }
    }

    private void tasks() {
        for (final JsonObject o : objects(plan, "tasks")) {
            final Tasks.Task t = Tasks.task(o);
            if (t == null) continue;
            graph.tasks.add(t);
            basis.map("tasks", t.id(), "task:" + t.id());
        }
        for (final JsonObject o : objects(plan, "taskItems")) {
            final Tasks.Item i = Tasks.item(o);
            if (i == null) continue;
            graph.taskItems.add(i);
            basis.map("taskItems", i.id(), "item:" + i.id());
        }
        for (final JsonObject o : objects(plan, "taskComments")) {
            final Tasks.Comment c = Tasks.comment(o);
            if (c == null) continue;
            graph.taskComments.add(c);
            basis.map("taskComments", c.id(), "comment:" + c.id());
        }
    }

    // endregion

    // region Cards

    private void node(final JsonObject json) {
        final String id = str(json, "id");
        final FfNode card = id == null ? null : things.node(json);
        if (card == null) return;
        final UUID uuid = PlanIds.uuid(id);
        final int x = PlanExport.gameX(card.x()), y = PlanExport.gameY(card.y());
        final FfRecipe recipe = recipes.get(card.recipeId());
        final Ext ext = Ext.of(json);
        // A shared machine the website made is always its recipes the importer's way: its game data is its first's.
        final Node own = ext != null && ext.matches(json)
            && card.extraRecipes()
                .isEmpty() ? fromGame(ext.game(), uuid, x, y) : null;
        // A recipe this game's NEI does not know by its id (another pack version) reads with no ports: the importer
        // may still find it by what the plan says of it.
        final boolean portless = own != null && !own.isPower() && own.inputs.isEmpty() && own.outputs.isEmpty();
        final Map<Integer, Section> made = own == null || portless ? importCard(card, recipe, id, uuid, x, y)
            : Map.of();
        if (own != null && made.isEmpty()) {
            graph.addNode(own);
            basis.map("nodes", id, "node:" + uuid);
            final Map<Integer, Section> sections = new HashMap<>();
            sections.put(0, new Section(own, recipe, card.overrides()));
            cards.put(id, sections);
            return;
        }
        if (made.isEmpty()) return;
        final Node first = made.get(0)
            .node();
        if (ext != null) keepGameOnly(first, ext.game());
        for (final Section s : made.values()) graph.addNode(s.node());
        basis.map("nodes", id, "node:" + uuid);
        cards.put(id, made);
        if (made.size() > 1) {
            // A shared machine the website made: its later recipes and their group come with the card.
            final List<Node> nodes = new ArrayList<>();
            for (int i = 0; made.containsKey(i); i++) nodes.add(
                made.get(i)
                    .node());
            final MachineGroup g = FfCards.share(nodes, new MachineGroup(PlanIds.part(id, "shared")));
            graph.groups.put(g.getId(), g);
            basis.carry("group:" + g.getId(), "nodes", id);
            for (final Section s : made.values())
                if (s.node() != first) basis.carry("node:" + s.node().id, "nodes", id);
        }
    }

    /** A card from its game data, as a load reads it; null when that does not read. */
    @Nullable
    private Node fromGame(final JsonObject data, final UUID uuid, final int x, final int y) {
        final JsonObject saved = LiveJson.copy(data);
        saved.addProperty("id", uuid.toString());
        saved.addProperty("x", x);
        saved.addProperty("y", y);
        try {
            final Node node = Serializer.jsonToNode(saved);
            game.rebuilt(node);
            return node;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A card the importer's way, its recipes by section (none when the game has no place for it): the first takes the
     * card's id, a shared machine's later ones ids made from it.
     */
    private Map<Integer, Section> importCard(final FfNode card, @Nullable final FfRecipe recipe, final String id,
        final UUID uuid, final int x, final int y) {
        final Map<Integer, Section> made = new HashMap<>();
        if (recipe == null) {
            report.add(Kind.UNMATCHED, card.recipeId(), "the plan does not carry this card's recipe");
            return made;
        }
        Node single = null;
        if (recipe.isCustomRate()) single = customRate(card, recipe, x, y);
        else if (!card.enabled()) report.add(Kind.DROPPED, recipe.name(), "a switched-off card; left out");
        else if (recipe.power()) single = FfCards.power(ctx, card, recipe, x, y);
        else if (recipe.isPlannerTank()) single = FfCards.tank(ctx, card, recipe, x, y);
        if (single != null) {
            single.id = uuid;
            made.put(0, new Section(single, recipe, card.overrides()));
            return made;
        }
        if (recipe.isCustomRate() || !card.enabled() || recipe.power()) return made;
        final List<FfSection> sections = new ArrayList<>();
        sections.add(new FfSection(card.recipeId(), card.overrides()));
        sections.addAll(card.extraRecipes());
        for (int i = 0; i < sections.size(); i++) {
            final FfRecipe r = recipes.get(
                sections.get(i)
                    .recipeId());
            if (r == null) {
                report.add(
                    Kind.UNMATCHED,
                    sections.get(i)
                        .recipeId(),
                    "the plan does not carry this recipe");
                continue;
            }
            final Node node = FfCards.section(
                ctx,
                ports,
                card,
                r,
                sections.get(i)
                    .overrides(),
                i,
                r.name(),
                x,
                y);
            // The card is its first recipe: without it the game has no place for the card.
            if (node == null && i == 0) return made;
            if (node == null) continue;
            node.id = i == 0 ? uuid : PlanIds.part(id, "r" + i);
            made.put(
                i,
                new Section(
                    node,
                    r,
                    sections.get(i)
                        .overrides()));
        }
        return made;
    }

    /** A custom rate card as one, holding the resource its recipe names; null when the game builds none. */
    @Nullable
    private Node customRate(final FfNode card, final FfRecipe recipe, final int x, final int y) {
        if (!card.enabled()) {
            report.add(Kind.DROPPED, "Custom rate card", "a switched-off card; left out");
            return null;
        }
        final boolean supply = !recipe.outputs()
            .isEmpty();
        final FfSlot slot = supply ? recipe.outputs()
            .getFirst()
            : recipe.inputs()
                .isEmpty() ? null
                    : recipe.inputs()
                        .getFirst();
        if (slot == null) return null;
        final String key = game.key(slot.kind(), slot.id());
        if (key == null) return null;
        final double perSecond = card.customRatePerSecond() != null ? card.customRatePerSecond() : slot.amount();
        final Node node = game.maker()
            .makeCustomRate(FfCards.customRateSettings(supply, perSecond, key));
        if (node == null) return null;
        node.x = x;
        node.y = y;
        final Double count = things.solveMode ? card.solvePin() : Double.valueOf(card.machineCount());
        if (count != null && count > 0) {
            node.machineConfig.setMachineCount((int) Math.max(1, Math.round(count)));
            node.setMachineCountFixed(true);
        }
        return node;
    }

    /**
     * What a card's game data has that the website has no place for, onto a card built from the website's fields:
     * its world links, its rates (input targets, and output targets where the website set none), an unpinned card's
     * count, and the machine settings the website does not keep.
     */
    private static void keepGameOnly(final Node node, final JsonObject data) {
        if (node.worldLinks.isEmpty()) Serializer.readWorldLinks(data.get("worldLinks"), node);
        if (node.targetInputRates.isEmpty()) Serializer.readRates(data.get("inputTargets"), node.targetInputRates);
        if (node.targetOutputRates.isEmpty()) Serializer.readRates(data.get("targets"), node.targetOutputRates);
        final JsonElement count = data.get("machineCount");
        if (!node.isMachineCountFixed() && !data.has("machineCountFixed")
            && count != null
            && count.isJsonPrimitive()
            && count.getAsJsonPrimitive()
                .isNumber())
            node.machineConfig.setMachineCount(Math.max(1, count.getAsInt()));
        final JsonElement cfg = data.get("machineConfig");
        if (cfg == null || !cfg.isJsonObject()
            || !cfg.getAsJsonObject()
                .has("settings"))
            return;
        final Map<String, Object> saved = new LinkedHashMap<>();
        Serializer.readSettings(
            cfg.getAsJsonObject()
                .getAsJsonObject("settings"),
            saved);
        // The website does not keep these, so it cannot have changed them: the game's stand. The count is the card's.
        for (final Map.Entry<String, Object> s : saved.entrySet()) {
            if (!FfSettings.carried(s.getKey()) && !Settings.MACHINES.key()
                .equals(s.getKey())) node.machineConfig.settings.put(s.getKey(), s.getValue());
        }
    }

    // endregion

    // region Drawers and wires

    private void storage(final JsonObject json, final Map<String, FfDrawers.Role> roles) {
        final String id = str(json, "id");
        final FfStorage s = id == null ? null : things.storage(json);
        if (s == null) return;
        final UUID uuid = PlanIds.uuid(id);
        final int x = PlanExport.gameX(s.x()), y = PlanExport.gameY(s.y());
        final Ext ext = Ext.of(json);
        final Drawer had = ext == null ? null : drawerFromGame(ext.game(), uuid, x, y);
        Drawer d = had != null && ext.matches(json) ? had : null;
        if (d == null) {
            FfDrawers.Role role = roles.getOrDefault(id, FfDrawers.Role.IDLE);
            Drawer.Kind kind = FfDrawers.kindOf(role);
            // A drawer of the game's with no wires is still the kind it was.
            if (kind == null && had != null && role == FfDrawers.Role.IDLE) {
                kind = had.getKind();
                role = kind == Drawer.Kind.SOURCE ? FfDrawers.Role.SOURCE
                    : kind == Drawer.Kind.TRASH ? FfDrawers.Role.TRASH : FfDrawers.Role.PRODUCT;
            }
            if (kind == null) {
                report.add(
                    Kind.DROPPED,
                    id,
                    role == FfDrawers.Role.BUFFER ? "a buffer drawer (GTNH Planner has none); left in the plan"
                        : "an unwired drawer; left in the plan");
                return;
            }
            // The game's own key while the website still names the same resource; else the first port it holds gives
            // it.
            final boolean same = had != null && sameResource(had, s);
            final String key = same ? had.getResourceKey() : game.key(s.kind(), s.resourceId());
            d = new Drawer(uuid, kind, key == null ? "" : key);
            d.setLabel(s.displayName() == null ? "" : s.displayName());
            if (had != null) d.setHeader(had.getHeader());
            d.setX(x);
            d.setY(y);
            final FfDrawers.Target target = FfDrawers.target(s, role, things.solveMode);
            d.setTarget(target.rule(), target.rate());
            if (!same) unkeyed.put(id, d);
        }
        graph.addDrawer(d);
        drawers.put(id, d);
        storages.put(id, s);
        basis.map("storages", id, "drawer:" + uuid);
    }

    /** Whether the website's storage names the resource the game's drawer holds. */
    private boolean sameResource(final Drawer d, final FfStorage s) {
        final PlanExport.Res r = Fresh.resource(d, game);
        return r.kind()
            .equals(s.kind()) && com.gtnhplanner.importer.FfIds.same(r.id(), s.resourceId());
    }

    @Nullable
    private static Drawer drawerFromGame(final JsonObject data, final UUID uuid, final int x, final int y) {
        final JsonObject saved = LiveJson.copy(data);
        saved.addProperty("id", uuid.toString());
        saved.addProperty("x", x);
        saved.addProperty("y", y);
        saved.add("links", new JsonArray());
        try {
            return Serializer.readDrawer(saved);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A wire: between two cards a wire on the board, between a drawer and a card one of the drawer's links. Its game
     * data gives its ports while it matches; else, as the importer does, the ports holding its resource.
     */
    private void edge(final JsonObject json, final FfEdge e) {
        final String id = str(json, "id");
        if (id == null) return;
        final Map<Integer, Section> from = cards.get(e.source()), to = cards.get(e.target());
        final Drawer feeds = drawers.get(e.source()), takes = drawers.get(e.target());
        final Ext ext = Ext.of(json);
        if (ext != null && ext.matches(json) && fromGame(id, e, ext.game(), from, to, feeds, takes)) return;
        if (e.crossForm()) {
            report
                .add(Kind.WIRE, e.resourceId(), "a loose cell wire left in the plan; GTNH Planner needs a tank there");
            return;
        }
        final Section a = from == null ? null : from.get(FfHandle.sectionOf(e.sourceHandle())),
            b = to == null ? null : to.get(FfHandle.sectionOf(e.targetHandle()));
        if (a != null && b != null) {
            final int out = port(a, true, e, null), in = port(b, false, e, null);
            if (out < 0 || in < 0) report.add(Kind.WIRE, e.resourceId(), "a wire that found no port; left in the plan");
            else wire(id, a.node(), b.node(), out, in);
            return;
        }
        // A drawer's link: a source feeds a card's input, any other drawer takes a card's output.
        final boolean feeding = feeds != null && b != null
            && feeds.getKind()
                .linksInputs();
        final boolean taking = takes != null && a != null
            && !takes.getKind()
                .linksInputs();
        if (!feeding && !taking) return;
        final String drawerId = feeding ? e.source() : e.target();
        final Section card = feeding ? b : a;
        final int port = port(
            card,
            !feeding,
            e,
            storages.get(drawerId)
                .resourceId());
        if (port >= 0) link(id, drawerId, feeding ? feeds : takes, card.node(), port);
    }

    /** The port of a card a wire's end names (its handle, its resource, a drawer's resource); -1 when none. */
    private int port(final Section s, final boolean output, final FfEdge e, @Nullable final String drawerResource) {
        return FfCards.port(
            ports,
            s.node(),
            s.recipe(),
            s.overrides(),
            output,
            output ? e.sourceHandle() : e.targetHandle(),
            e,
            drawerResource);
    }

    /** A wire by the ports its game data names; false when they are not on the board. */
    private boolean fromGame(final String id, final FfEdge e, final JsonObject data,
        @Nullable final Map<Integer, Section> from, @Nullable final Map<Integer, Section> to,
        @Nullable final Drawer feeds, @Nullable final Drawer takes) {
        final Node a = from == null || !from.containsKey(0) ? null
            : from.get(0)
                .node(),
            b = to == null || !to.containsKey(0) ? null
                : to.get(0)
                    .node();
        if (a != null && b != null) {
            final int out = integer(data, "srcOut"), in = integer(data, "dstIn");
            if (out < 0 || out >= a.outputs.size() || in < 0 || in >= b.inputs.size()) return false;
            wire(id, a, b, out, in);
            return true;
        }
        final int port = integer(data, "port");
        if (feeds != null && b != null
            && feeds.getKind()
                .linksInputs()
            && port >= 0
            && port < b.inputs.size()) {
            link(id, e.source(), feeds, b, port);
            return true;
        }
        if (takes != null && a != null
            && !takes.getKind()
                .linksInputs()
            && port >= 0
            && port < a.outputs.size()) {
            link(id, e.target(), takes, a, port);
            return true;
        }
        return false;
    }

    private void wire(final String id, final Node from, final Node to, final int out, final int in) {
        final UUID uuid = PlanIds.uuid(id);
        graph.addEdge(new Edge(uuid, from.id, to.id, out, in));
        basis.map("edges", id, "edge:" + uuid);
    }

    /** A drawer's link to a port; a drawer the website made takes its resource from the first port it holds. */
    private void link(final String id, @Nullable final String drawerId, final Drawer d, final Node node,
        final int port) {
        final Drawer.Link link = new Drawer.Link(node.id, port);
        graph.linkDrawer(d.getId(), link);
        basis.map("edges", id, Fresh.linkKey(d, link));
        if (drawerId != null && unkeyed.remove(drawerId) != null) {
            final NodeMaker.PortInfo info = ports.describe(
                node,
                !d.getKind()
                    .linksInputs())
                .get(port);
            d.setResourceKey(info.key());
            if (d.getLabel()
                .isEmpty()) d.setLabel(info.label());
        }
    }

    // endregion

    // region Notes

    /** A text annotation as a note; the website's other drawings have no place on the board. */
    private void note(final JsonObject json) {
        final String id = str(json, "id");
        if (id == null) return;
        final UUID uuid = PlanIds.uuid(id);
        final double[] at = at(json);
        final int x = PlanExport.gameX(at[0]), y = PlanExport.gameY(at[1]);
        final Ext ext = Ext.of(json);
        Note note = null;
        if (ext != null && ext.matches(json)) {
            final JsonObject saved = LiveJson.copy(ext.game());
            saved.addProperty("id", uuid.toString());
            saved.addProperty("x", x);
            saved.addProperty("y", y);
            try {
                note = Serializer.GSON.fromJson(saved, Note.class);
            } catch (final RuntimeException ignored) {}
        }
        if (note == null) {
            final FfPlan.FfNote n = things.note(json);
            if (n == null) return;
            note = FfConverter.note(n, uuid, x, y);
        }
        graph.notes.put(uuid, note);
        basis.map("annotations", id, "note:" + uuid);
    }

    /** A thing's place on the site's board, its board's offset added. */
    private double[] at(final JsonObject json) {
        final JsonElement p = json.get("position");
        double x = 0, y = 0;
        if (p != null && p.isJsonObject()) {
            x = number(p.getAsJsonObject(), "x");
            y = number(p.getAsJsonObject(), "y");
        }
        final double[] off = things.offset(str(json, "pocketId"));
        return new double[] { x + off[0], y + off[1] };
    }

    // endregion

    // region The game data a thing carries

    /**
     * A thing's {@code gtnhplanner}: the game's own data for it and the sum of the website fields it was written with.
     */
    record Ext(JsonObject game, String sum) {

        @Nullable
        static Ext of(final JsonObject thing) {
            final JsonElement e = thing.get("gtnhplanner");
            if (e == null || !e.isJsonObject()) return null;
            final JsonObject o = e.getAsJsonObject();
            final JsonElement v = o.get("v"), game = o.get("game"), sum = o.get("sum");
            if (v == null || !v.isJsonPrimitive()
                || !v.getAsJsonPrimitive()
                    .isNumber()
                || v.getAsInt() != 1) return null;
            if (game == null || !game.isJsonObject() || sum == null || !sum.isJsonPrimitive()) return null;
            return new Ext(game.getAsJsonObject(), sum.getAsString());
        }

        /** Whether the thing's website fields are still the ones its game data was written with. */
        boolean matches(final JsonObject thing) {
            return sum.equals(Fresh.sum(thing));
        }
    }

    // endregion

    // region Lenient JSON

    static List<JsonObject> objects(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x != null && x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    @Nullable
    static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isString() ? e.getAsString() : null;
    }

    private static int integer(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isNumber() ? e.getAsInt() : -1;
    }

    private static double number(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isNumber() ? e.getAsDouble() : 0;
    }

    // endregion
}
