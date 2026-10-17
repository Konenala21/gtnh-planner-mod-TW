package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.Notice;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.gt.GtCoils;

/**
 * The open board as data for scripted tests: the view, and per card its state and the GUI rectangle of every control,
 * so a test clicks "the tier chip of card 2" instead of guessing pixels. Client thread only.
 */
final class DevBoard {

    private DevBoard() {}

    static BoardScreen screen() {
        return Planner.screenOf(Minecraft.getMinecraft().currentScreen) instanceof final BoardScreen b ? b : null;
    }

    static Map<String, Object> board() {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final BoardCanvas canvas = screen.canvas();
        final Graph graph = screen.session()
            .graph();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("zoom", graph.getZoom());
        m.put("panX", graph.getPanX());
        m.put("panY", graph.getPanY());
        m.put("canvas", rect(canvas.getArea().x, canvas.getArea().y, canvas.getArea().width, canvas.getArea().height));
        final List<Object> cards = new ArrayList<>();
        int i = 0;
        for (final CardModel model : screen.session()
            .models()
            .values()) {
            final RecipeCard card = canvas.cards()
                .get(model.node.id);
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", i++);
            c.put("id", model.node.id.toString());
            c.put("machine", model.machineName);
            // The card on the board, in world px (a shared machine: its card, under each of its recipes).
            c.put("x", model.node.x);
            c.put("y", model.node.y);
            c.put("w", com.gtnhplanner.ui.card.CardLayout.W);
            c.put("h", card == null || card.layout() == null ? 100 : card.layout().height);
            c.put("card", card == null ? null : card.nodeId.toString());
            c.put("tier", model.tier);
            c.put("amps", model.amps);
            c.put("multiblock", model.multiblock);
            final GtCoils.Coil coil = model.coilHeat > 0 ? GtCoils.forHeat(model.coilHeat) : null;
            c.put("coil", coil == null ? null : coil.name());
            c.put("coilHeat", model.coilHeat);
            c.put("recipeHeat", model.recipeHeat);
            c.put("machines", model.machines);
            c.put("pinned", model.pinned);
            c.put(
                "rule",
                screen.session()
                    .rule(model.node)
                    .name());
            if (card != null && card.model() != null) {
                // What the card says under its machine and along its foot (docs/design/up-to.md).
                final com.gtnhplanner.ui.card.CardFace.Count face = card.face();
                c.put(
                    "count",
                    face.run() + (face.pinned() ? " " + face.ruleWord() + " " + face.setText() : " " + face.note()));
                final com.gtnhplanner.ui.card.CardFace.Foot foot = card.foot();
                c.put("foot", foot == null ? null : foot.word() + " | " + foot.say() + " | " + foot.frac());
                // The rows its reason lights, its ring, and the frame it wears.
                final List<String> lit = new ArrayList<>();
                for (final boolean output : new boolean[] { false, true })
                    for (final CardModel.PortView p : output ? model.outputs : model.inputs) {
                        final com.gtnhplanner.ui.card.CardFace.Tone why = card.why(model.node.id, output, p.index());
                        if (why != null) lit.add((output ? "out" : "in") + p.index() + ":" + why.name());
                    }
                c.put("lit", lit);
                final com.gtnhplanner.data.flowchart.balancer.UpToLoop.Kind loop = screen.session()
                    .loopOf(card.nodeId);
                c.put("loop", loop == null ? null : loop.name());
                c.put(
                    "edge",
                    Integer.toHexString(
                        com.gtnhplanner.ui.card.CardFace
                            .edge(foot, com.gtnhplanner.ui.PlannerSettings.problemOutlines())));
            }
            // Where it is placed in the world: {dim, x, y, z}, then a facing and a structure size when set.
            c.put(
                "worldLinks",
                model.node.worldLinks.stream()
                    .map(
                        l -> java.util.Arrays.stream(l)
                            .boxed()
                            .toList())
                    .toList());
            c.put(
                "selected",
                screen.session()
                    .isSelected(model.node.id));
            c.put("euPerTick", model.euPerTick);
            c.put("durationTicks", model.durationTicks);
            final List<Object> ports = new ArrayList<>();
            for (final CardModel.PortView p : model.inputs) ports.add(port(p));
            for (final CardModel.PortView p : model.outputs) ports.add(port(p));
            c.put("ports", ports);
            if (card != null) {
                final RecipeCard.Part under = card.partUnderMouse();
                c.put("underMouse", under == null ? null : under.name());
                final Map<String, Object> parts = new LinkedHashMap<>();
                for (final RecipeCard.Part part : RecipeCard.Part.values()) {
                    final int[] r = card.partRect(part);
                    if (r == null) continue;
                    final int x0 = canvas.screenX(model.node.x + r[0]), y0 = canvas.screenY(model.node.y + r[1]);
                    final int x1 = canvas.screenX(model.node.x + r[0] + r[2]),
                        y1 = canvas.screenY(model.node.y + r[1] + r[3]);
                    parts.put(part.name(), rect(x0, y0, x1 - x0, y1 - y0));
                }
                for (final CardModel.PortView p : model.inputs) parts
                    .put("IN" + p.index(), slot(canvas, card.layout(), model.node.x, model.node.y, false, p.index()));
                for (final CardModel.PortView p : model.outputs) parts
                    .put("OUT" + p.index(), slot(canvas, card.layout(), model.node.x, model.node.y, true, p.index()));
                c.put("parts", parts);
            }
            cards.add(c);
        }
        m.put("cards", cards);
        final List<Object> drawers = new ArrayList<>();
        int d = 0;
        for (final DrawerModel model : screen.session()
            .drawerModels()
            .values()) {
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("index", d++);
            o.put(
                "id",
                model.drawer.getId()
                    .toString());
            o.put("kind", model.kind.name());
            o.put("label", model.label);
            o.put("x", model.drawer.getX());
            o.put("y", model.drawer.getY());
            o.put("w", com.gtnhplanner.ui.drawer.DrawerCard.W);
            o.put("h", com.gtnhplanner.ui.drawer.DrawerCard.H);
            o.put("rule", model.rule.name());
            o.put("target", model.target);
            o.put("rate", model.rate);
            o.put("unmet", model.unmet);
            o.put(
                "selected",
                screen.session()
                    .isSelected(model.drawer.getId()));
            o.put(
                "links",
                model.drawer.getLinks()
                    .size());
            final DrawerCard widget = canvas.drawers()
                .get(model.drawer.getId());
            if (widget != null) {
                final Map<String, Object> parts = new LinkedHashMap<>();
                for (final DrawerCard.Part part : DrawerCard.Part.values()) {
                    final int[] r = widget.partRect(part);
                    if (r == null) continue;
                    final int x0 = canvas.screenX(model.drawer.getX() + r[0]),
                        y0 = canvas.screenY(model.drawer.getY() + r[1]);
                    final int x1 = canvas.screenX(model.drawer.getX() + r[0] + r[2]),
                        y1 = canvas.screenY(model.drawer.getY() + r[1] + r[3]);
                    parts.put(part.name(), rect(x0, y0, x1 - x0, y1 - y0));
                }
                o.put("parts", parts);
            }
            drawers.add(o);
        }
        m.put("drawers", drawers);
        m.put(
            "edges",
            graph.getEdges()
                .size());
        m.put("wires", canvas.wirePaths());
        m.put(
            "solving",
            screen.session()
                .solving());
        final List<String> notices = new ArrayList<>();
        for (final Notice n : screen.session()
            .notices()) notices.add(n.severity() + ": " + n.text());
        m.put("notices", notices);
        // The leftovers and the places that could bear them, with their shares now.
        final List<Map<String, Object>> spares = new ArrayList<>();
        for (final com.gtnhplanner.ui.BoardSession.SpareLine line : screen.session()
            .spares()) {
            final Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", line.key());
            s.put("importing", line.importing());
            s.put(
                "custom",
                screen.session()
                    .spareCustom(line));
            final double[] shares = screen.session()
                .spareShares(line);
            final List<String> bearers = new ArrayList<>();
            for (int k = 0; k < shares.length; k++) {
                final com.gtnhplanner.ui.BoardSession.SpareBearer b = line.bearers()
                    .get(k);
                bearers.add(
                    b.name() + " at "
                        + b.machine()
                        + ": "
                        + Math.round(shares[k] * 1000) / 1000.0
                        + " of "
                        + b.max()
                        + (b.solverPick() ? " (solver)" : ""));
            }
            s.put("bearers", bearers);
            spares.add(s);
        }
        m.put("spares", spares);
        return m;
    }

    /** Pins card {@code index} (in the report's order) at {@code count} machines, or unpins it at 0. */
    static void pin(final int index, final double count) {
        screen().session()
            .pin(card(index).node, count);
    }

    /** Card {@code index} in the report's order. */
    private static CardModel card(final int index) {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        int i = 0;
        for (final CardModel model : screen.session()
            .models()
            .values()) if (i++ == index) return model;
        throw new IllegalArgumentException("no card " + index);
    }

    /** The stand-in read-outs and shortfalls {@link #upTo} has put on the board, kept so calls add up. */
    private static final Map<java.util.UUID, com.gtnhplanner.data.flowchart.balancer.UpToReadout> UP_TO = new LinkedHashMap<>();
    private static final List<com.gtnhplanner.data.flowchart.balancer.DrawerReadout.Shortfall> SHORT = new ArrayList<>();
    private static final List<com.gtnhplanner.data.flowchart.balancer.UpToLoop> LOOPS = new ArrayList<>();

    /**
     * The Up to rule set up to look at before the solver reads cards out (docs/design/up-to.md): {@code card=N} (the
     * report's order) with {@code rule=any|upto|exact} and {@code count=C} sets its rule through the board; with
     * {@code reason=FULL|BOTTLENECK|STARVED|CLOGGED|UNUSED|LIMITED|none}, {@code reached=R}, {@code capacity=C},
     * {@code port=in0|out1}, {@code tied=in1,out0}, {@code wants=W} and {@code upstream=M,K} (cards) it shows that
     * read-out on the card in place of the solver's. {@code shortfall=D} (a drawer, the report's order) with
     * {@code target}, {@code reach}, {@code limit=N,M} (cards) and {@code limitDrawer=K} adds a drawer shortfall.
     * {@code loop=dead|clog} with {@code cards=N,M,K} and {@code ports=N:in0,M:out1} (each card's blaming ports) adds
     * a dead loop or a clog lock in place of the solver's. {@code clear=1} takes every stand-in away.
     */
    static Map<String, Object> upTo(final Map<String, String> q) {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final com.gtnhplanner.ui.BoardSession session = screen.session();
        if (q.containsKey("clear")) {
            UP_TO.clear();
            SHORT.clear();
            LOOPS.clear();
            session.devUpTo(null, null);
            session.devLoops(null);
            return Map.of("cleared", true);
        }
        if (q.containsKey("loop")) {
            final List<java.util.UUID> members = new ArrayList<>();
            for (final String s : q.getOrDefault("cards", "")
                .split(",")) if (!s.isBlank()) members.add(card(Integer.parseInt(s.trim())).node.id);
            final List<com.gtnhplanner.data.flowchart.balancer.PortRef> ports = new ArrayList<>();
            for (final String s : q.getOrDefault("ports", "")
                .split(",")) {
                if (s.isBlank()) continue;
                final String[] at = s.trim()
                    .split(":");
                ports.add(port(card(Integer.parseInt(at[0])), at[1]));
            }
            LOOPS.add(
                new com.gtnhplanner.data.flowchart.balancer.UpToLoop(
                    q.get("loop")
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("dead") ? com.gtnhplanner.data.flowchart.balancer.UpToLoop.Kind.DEAD_LOOP
                            : com.gtnhplanner.data.flowchart.balancer.UpToLoop.Kind.CLOG_LOCK,
                    members,
                    ports));
            session.devLoops(LOOPS);
        }
        if (q.containsKey("combine")) {
            // Cards N,M on one machine, as the selection bar's Combine key does.
            final List<java.util.UUID> ids = new ArrayList<>();
            for (final String s : q.get("combine")
                .split(",")) ids.add(card(Integer.parseInt(s.trim())).node.id);
            session.combine(ids);
        }
        if (q.containsKey("card") && q.containsKey("rule")) {
            final CardModel m = card(Integer.parseInt(q.get("card")));
            final com.gtnhplanner.data.flowchart.PinRule rule = switch (q.get("rule")
                .toLowerCase(java.util.Locale.ROOT)) {
                case "exact", "exactly" -> com.gtnhplanner.data.flowchart.PinRule.EXACT;
                case "upto", "up_to" -> com.gtnhplanner.data.flowchart.PinRule.UP_TO;
                default -> com.gtnhplanner.data.flowchart.PinRule.ANY;
            };
            session.setRule(m.node, rule, Double.parseDouble(q.getOrDefault("count", "1")));
        }
        if (q.containsKey("card") && q.containsKey("reason")) {
            final CardModel m = card(Integer.parseInt(q.get("card")));
            final List<java.util.UUID> machines = session.sectionsOf(m.node.id);
            final String why = q.get("reason")
                .toUpperCase(java.util.Locale.ROOT);
            final List<java.util.UUID> upstream = new ArrayList<>();
            for (final String s : q.getOrDefault("upstream", "")
                .split(",")) if (!s.isBlank()) upstream.add(card(Integer.parseInt(s.trim())).node.id);
            final List<com.gtnhplanner.data.flowchart.balancer.PortRef> tied = new ArrayList<>();
            for (final String s : q.getOrDefault("tied", "")
                .split(",")) if (!s.isBlank()) tied.add(port(m, s.trim()));
            final com.gtnhplanner.data.flowchart.balancer.UpToReadout r = new com.gtnhplanner.data.flowchart.balancer.UpToReadout(
                machines.get(0),
                machines,
                Double.parseDouble(q.getOrDefault("capacity", String.valueOf(session.setCount(m.node)))),
                Double.parseDouble(q.getOrDefault("reached", "0")),
                "NONE".equals(why) ? null : com.gtnhplanner.data.flowchart.balancer.UpToReason.valueOf(why),
                q.containsKey("port") ? port(m, q.get("port")) : null,
                tied,
                q.containsKey("wants") ? Double.valueOf(q.get("wants")) : null,
                upstream);
            for (final java.util.UUID id : machines) UP_TO.put(id, r);
        }
        if (q.containsKey("shortfall")) {
            final List<DrawerModel> drawers = new ArrayList<>(
                session.drawerModels()
                    .values());
            final DrawerModel d = drawers.get(Integer.parseInt(q.get("shortfall")));
            final List<java.util.UUID> limits = new ArrayList<>(), limitDrawers = new ArrayList<>();
            for (final String s : q.getOrDefault("limit", "")
                .split(",")) if (!s.isBlank()) limits.add(card(Integer.parseInt(s.trim())).node.id);
            for (final String s : q.getOrDefault("limitDrawer", "")
                .split(",")) if (!s.isBlank()) limitDrawers.add(drawers.get(Integer.parseInt(s.trim())).drawer.getId());
            SHORT.add(
                new com.gtnhplanner.data.flowchart.balancer.DrawerReadout.Shortfall(
                    d.drawer.getId(),
                    d.rule,
                    Double.parseDouble(q.getOrDefault("target", "2")),
                    Double.parseDouble(q.getOrDefault("reach", "1")),
                    limits,
                    limitDrawers,
                    0));
        }
        session.devUpTo(UP_TO.isEmpty() ? null : UP_TO, SHORT.isEmpty() ? null : SHORT);
        return Map.of("readouts", UP_TO.size(), "shortfalls", SHORT.size(), "loops", LOOPS.size());
    }

    /** A card's port as the solver names it: {@code in0}, {@code out1} (on the card's own recipe). */
    private static com.gtnhplanner.data.flowchart.balancer.PortRef port(final CardModel m, final String s) {
        final boolean input = s.startsWith("in");
        return new com.gtnhplanner.data.flowchart.balancer.PortRef(
            m.node.id,
            Integer.parseInt(s.substring(input ? 2 : 3)),
            input);
    }

    /**
     * The plan over the world, set up for a look: {@code card=N} (the report's order) placed at x, y, z (facing 0
     * south, 1 west, 2 north, 3 east; {@code add=1} as one more of its machines), {@code pick=N} starts placing card N
     * as its place key does (the planner closes), {@code clear=1} takes every card of the open plan out of the world.
     */
    static Map<String, Object> worldPlace(final Map<String, String> q) {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final Graph graph = screen.session()
            .graph();
        if (q.containsKey("clear")) {
            for (final com.gtnhplanner.data.flowchart.Node n : graph.nodes.values())
                com.gtnhplanner.ui.world.WorldLinks.clear(graph, n);
            return Map.of("cleared", true);
        }
        final boolean pick = q.containsKey("pick");
        final int index = Integer.parseInt(pick ? q.get("pick") : q.get("card"));
        int i = 0;
        for (final CardModel model : screen.session()
            .models()
            .values()) {
            if (i++ != index) continue;
            if (pick) {
                final RecipeCard card = screen.canvas()
                    .cards()
                    .get(model.node.id);
                if (card == null) throw new IllegalArgumentException("card " + index + " is not on the board");
                card.placeInWorld();
                return Map.of("placing", String.valueOf(model.machineName));
            }
            com.gtnhplanner.ui.world.WorldLinks.assign(
                graph,
                model.node,
                Minecraft.getMinecraft().theWorld.provider.dimensionId,
                Integer.parseInt(q.get("x")),
                Integer.parseInt(q.get("y")),
                Integer.parseInt(q.get("z")),
                Integer.parseInt(q.getOrDefault("facing", "0")),
                0,
                q.containsKey("add"));
            return Map.of("placed", String.valueOf(model.machineName), "links", model.node.worldLinks.size());
        }
        throw new IllegalArgumentException("no card " + index);
    }

    /** Sets zoom and pan so tests start from a known view. */
    static void view(final float zoom, final float panX, final float panY) {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final Graph graph = screen.session()
            .graph();
        graph.setZoom(zoom);
        graph.setPanX(panX);
        graph.setPanY(panY);
    }

    private static Map<String, Object> port(final CardModel.PortView p) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("side", p.output() ? "out" : "in");
        m.put("name", p.name());
        m.put("perSecond", p.perSecond());
        if (p.hasExtra()) m.put("extra", p.extra());
        if (p.chance() < 0.9999f) m.put("chance", p.chance());
        return m;
    }

    /** A port's icon on screen. */
    private static Map<String, Object> slot(final BoardCanvas canvas, final CardLayout layout, final int nx,
        final int ny, final boolean output, final int index) {
        final int lx = CardLayout.iconX(output), ly = layout.rowY(output, index) + CardLayout.ICON_Y;
        final int x0 = canvas.screenX(nx + lx), y0 = canvas.screenY(ny + ly);
        return rect(
            x0,
            y0,
            canvas.screenX(nx + lx + CardLayout.ICON) - x0,
            canvas.screenY(ny + ly + CardLayout.ICON) - y0);
    }

    /**
     * {x, y, w, h} plus the centre, which is what a click wants: in the game's GUI pixels, as the harness's input
     * takes them, though the board lays out in its own ({@link com.gtnhplanner.ui.PlannerScale}).
     */
    private static Map<String, Object> rect(final int bx, final int by, final int bw, final int bh) {
        final int x = com.gtnhplanner.ui.PlannerScale.toGame(bx), y = com.gtnhplanner.ui.PlannerScale.toGame(by);
        final int w = com.gtnhplanner.ui.PlannerScale.toGame(bx + bw) - x,
            h = com.gtnhplanner.ui.PlannerScale.toGame(by + bh) - y;
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        m.put("cx", x + w / 2);
        m.put("cy", y + h / 2);
        return m;
    }
}
