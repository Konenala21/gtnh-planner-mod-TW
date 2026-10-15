package com.gtnhplanner.cloud.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.dev.SolveInputJson;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfImport;

/**
 * Boards to map, headless: the solver corpus's real plans (their solve inputs, rebuilt as boards with every port named
 * as {@link FakeGame} names them) and the website's plans as the importer brings them in, each dressed with what a
 * player adds that the website has no place for.
 */
final class Boards {

    /** The plans written from the game, under {@code /solver-corpus/}. */
    static final String[] CORPUS = { "alumina-line-shared-reactor", "benzene", "full-platline", "glass", "hv-diesel",
        "mega-iv-nitrobenzene-power-plant", "mv", "oxygen-silicon-loop", "pa-cell-loop", "platline", "single-card-v2",
        "sulfuric-acid", "titanium-line-chembath", "titanium-line-tour" };

    /** The website's plans, under {@code /factory-flow/}. */
    static final String[] WEBSITE = { "alumina-line-shared-reactor", "biodiesel-demo", "high-amperage-reactor",
        "pa-cell-loop-plan", "titanium-line-chembath.slim", "titanium-line-posted-alpha5" };

    private Boards() {}

    static JsonObject json(final String resource) {
        try (InputStream in = Boards.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException(resource);
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject website(final String name) {
        return json("/factory-flow/" + name + ".json");
    }

    /** A website plan as the importer brings it in. */
    static Graph imported(final String name, final FakeGame game) {
        return FfImport.importFromText(FakeGame.fixture(name + ".json"), game, game)
            .graph();
    }

    /** A corpus plan as a board: its cards, wires, drawers, shared machines and spare shares, laid out in rows. */
    static Graph corpus(final String name) {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final SolveInput in = SolveInputJson.read(json("/solver-corpus/" + name + ".json"));
        final Graph g = new Graph(name);
        g.setBalanceMode(in.mode());
        int i = 0;
        for (final SolveInput.Machine m : in.machines()) {
            // Places off the board's grid too: the mapping must keep every pixel.
            final Node n = new Node(m.id(), 37 + i % 6 * 361, 41 + i / 6 * 263);
            n.machineName = m.name();
            for (final SolveInput.PortIn p : m.inputs()) n.inputs.add(port(p));
            for (final SolveInput.PortIn p : m.outputs()) n.outputs.add(port(p));
            n.properties.put(FakeGame.DURATION, (int) Math.round(m.durationTicks()));
            n.properties.put(FakeGame.EU_PER_TICK, Math.round(m.energyPerTick()));
            n.machineConfig.setMachineCount(m.machineCount());
            n.setMachineCountFixed(m.countFixed());
            n.targetOutputRates.putAll(m.targetOutputRates());
            n.targetInputRates.putAll(m.targetInputRates());
            g.addNode(n);
            i++;
        }
        final List<Edge> edges = new ArrayList<>();
        for (final SolveInput.EdgeIn e : in.edges())
            edges.add(new Edge(e.id(), e.source(), e.target(), e.sourceOutput(), e.targetInput()));
        g.addEdgesLoaded(edges);
        int j = 0;
        for (final SolveInput.DrawerIn d : in.drawers()) {
            String key = "item:" + d.label() + ":0";
            if (!d.links()
                .isEmpty()) {
                final Drawer.Link l = d.links()
                    .getFirst();
                final Node n = g.nodes.get(l.nodeId());
                final List<Port<?>> side = d.kind()
                    .linksInputs() ? n.inputs : n.outputs;
                if (l.portIndex() < side.size()) key = new FakeGame().describe(side.get(l.portIndex()))
                    .key();
            }
            final Drawer drawer = new Drawer(d.id(), d.kind(), key);
            drawer.setLabel(d.label());
            drawer.setTarget(d.rule(), d.rate());
            drawer.setX(-303);
            drawer.setY(13 + j++ * 97);
            for (final Drawer.Link l : d.links()) drawer.addLink(l);
            g.addDrawer(drawer);
        }
        for (final SolveInput.PoolIn p : in.pools()) {
            final MachineGroup group = new MachineGroup(
                UUID.nameUUIDFromBytes(
                    (name + ":pool:"
                        + p.machines()
                            .getFirst()).getBytes(StandardCharsets.UTF_8)));
            for (final UUID member : p.machines()) group.addSection(member);
            group.setMachineCapacity(p.capacity());
            group.setPinned(p.exact());
            g.groups.put(group.getId(), group);
        }
        for (final Map.Entry<String, Map<String, Double>> s : in.spares()
            .entrySet()) g.setSpareSplit(s.getKey(), s.getValue());
        return g;
    }

    private static Port<?> port(final SolveInput.PortIn p) {
        return TestGame.port("item", p.name(), p.amount() * p.chance());
    }

    /**
     * What a player adds that the website has no place for: a note, a plain group, setting pins, a place in the world,
     * an input's target rate, a setting only the game has, a pin list, and a task board; a description and an icon.
     */
    static Graph dress(final Graph g) {
        final List<Node> nodes = new ArrayList<>(g.getNodes());
        final Node first = nodes.getFirst();
        first.worldLinks.add(new int[] { 0, 100, 64, -200, 2 });
        first.machineConfig.settings.put("tick_modifier", 150);
        first.machineConfig.settings.put("machine:circuit", "3");
        if (!first.inputs.isEmpty()) first.targetInputRates.put(0, 2.5);
        final Node last = nodes.getLast();
        last.x = 1234;
        last.y = -567;
        g.pinList.add(first.id);
        final Note note = new Note(UUID.nameUUIDFromBytes((g.getName() + ":note").getBytes(StandardCharsets.UTF_8)));
        note.setX(-101);
        note.setY(-77);
        note.setWidth(213);
        note.setHeight(131);
        note.setColor("lime");
        note.setFontSize(18);
        note.setJoined("Feed it\nfrom the ME system");
        g.notes.put(note.getId(), note);
        final Group frame = Serializer.GSON.fromJson(
            "{\"id\":\"" + UUID.nameUUIDFromBytes((g.getName() + ":frame").getBytes(StandardCharsets.UTF_8))
                + "\",\"type\":\"group\",\"x\":5,\"y\":6,\"width\":700,\"height\":400,\"header\":\"Ore line\"}",
            Group.class);
        frame.getNodeIds()
            .add(first.id);
        g.groups.put(frame.getId(), frame);
        g.settingPins.put("item:gregtech:gt.blockmachines:1000", new ArrayList<>(List.of("voltage", "amp")));
        g.setDescription("Made in the game");
        g.setIcon("item:minecraft:iron_ingot:0");
        g.setPrimaryView(Tasks.PLAN);
        g.tasks.add(
            new Tasks.Task(
                "t1",
                "Build " + g.getName(),
                "Two lines first.",
                Tasks.Status.DOING,
                "a0",
                List.of("11111111-1111-4111-8111-111111111111"),
                List.of(first.id.toString()),
                "11111111-1111-4111-8111-111111111111",
                "2026-10-14T10:00:00.000Z",
                null));
        g.taskItems.add(new Tasks.Item("i1", "t1", "Place the machines", false, "a0", first.id.toString()));
        g.taskComments.add(
            new Tasks.Comment(
                "c1",
                "t1",
                "11111111-1111-4111-8111-111111111111",
                "ann",
                "2026-10-14T10:05:00Z",
                "On it"));
        return g;
    }

    /**
     * Everything a board saves that a shared plan carries: its save, the camera and the pin list (each player's own)
     * left out, and its description and icon.
     */
    static JsonObject persisted(final Graph g) {
        final JsonObject o = Serializer.toJson(g);
        o.remove("zoom");
        o.remove("panX");
        o.remove("panY");
        o.remove("pinList");
        o.addProperty("description", g.getDescription());
        if (g.getIcon() != null) o.addProperty("icon", g.getIcon());
        return o;
    }
}
