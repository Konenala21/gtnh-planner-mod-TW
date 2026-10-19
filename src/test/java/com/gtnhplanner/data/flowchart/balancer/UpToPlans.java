package com.gtnhplanner.data.flowchart.balancer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * The hand-built Up to plans of the website's {@code __fixtures__/up-to/plans.ts} (docs/design/up-to.md), in Java.
 * Every
 * machine runs a craft a second per machine unless it says otherwise. A plan's ids are named ({@code "a"}, {@code "b"})
 * and become UUIDs as the website's parity export makes them ({@link #id}), so a read-out here and one written there
 * name the same cards.
 */
final class UpToPlans {

    private UpToPlans() {}

    /** A named id as a UUID: {@code upto:<name>} hashed as the parity export hashes it. */
    static UUID id(final String name) {
        return UUID.nameUUIDFromBytes(("upto:" + name).getBytes(StandardCharsets.UTF_8));
    }

    static SolveInput.PortIn port(final int resource, final String name) {
        return port(resource, name, 1);
    }

    static SolveInput.PortIn port(final int resource, final String name, final double amount) {
        return new SolveInput.PortIn(amount, 1f, 1, resource, name, false);
    }

    /** {@code exact} > 0 pins the count exactly; an Up to count is a pool ({@link #upTo}). */
    static SolveInput.Machine machine(final String name, final List<SolveInput.PortIn> inputs,
        final List<SolveInput.PortIn> outputs) {
        return machine(name, inputs, outputs, 0, 20);
    }

    static SolveInput.Machine machine(final String name, final List<SolveInput.PortIn> inputs,
        final List<SolveInput.PortIn> outputs, final int exact) {
        return machine(name, inputs, outputs, exact, 20);
    }

    static SolveInput.Machine machine(final String name, final List<SolveInput.PortIn> inputs,
        final List<SolveInput.PortIn> outputs, final int exact, final double durationTicks) {
        return new SolveInput.Machine(
            id(name),
            name,
            durationTicks,
            0,
            1,
            inputs,
            outputs,
            exact > 0,
            Math.max(1, exact),
            Map.of(),
            Map.of(),
            Map.of());
    }

    static SolveInput.EdgeIn edge(final String name, final String source, final int sourceOutput, final String target,
        final int targetInput) {
        return new SolveInput.EdgeIn(id(name), id(source), sourceOutput, id(target), targetInput);
    }

    static SolveInput.PoolIn upTo(final List<String> machines, final double capacity) {
        return new SolveInput.PoolIn(
            machines.stream()
                .map(UpToPlans::id)
                .toList(),
            capacity,
            false,
            true);
    }

    static Drawer.Link link(final String node, final int port) {
        return new Drawer.Link(id(node), port);
    }

    static SolveInput.DrawerIn drawer(final String name, final String label, final Drawer.Kind kind,
        final Drawer.Rule rule, final double rate, final List<Drawer.Link> links) {
        return new SolveInput.DrawerIn(id(name), label, kind, rule, rate, links);
    }

    static SolveInput plan(final List<SolveInput.Machine> machines, final List<SolveInput.EdgeIn> edges,
        final List<SolveInput.PoolIn> pools) {
        return plan(machines, edges, pools, List.of(), null);
    }

    static SolveInput plan(final List<SolveInput.Machine> machines, final List<SolveInput.EdgeIn> edges,
        final List<SolveInput.PoolIn> pools, final List<SolveInput.DrawerIn> drawers, final ChoiceKey choice) {
        return new SolveInput(BalanceMode.AUTO, choice, machines, edges, pools, drawers, Map.of());
    }

    /** The plan with its pools Exactly: no Up to pool left. */
    static SolveInput exactly(final SolveInput in) {
        final List<SolveInput.PoolIn> pools = new ArrayList<>();
        for (final SolveInput.PoolIn p : in.pools()) pools.add(new SolveInput.PoolIn(p.machines(), p.capacity(), true));
        return new SolveInput(in.mode(), in.choice(), in.machines(), in.edges(), pools, in.drawers(), in.extentPins());
    }

    /** The plan with {@code choice} stored. */
    static SolveInput choosing(final SolveInput in, final ChoiceKey choice) {
        return new SolveInput(in.mode(), choice, in.machines(), in.edges(), in.pools(), in.drawers(), in.extentPins());
    }

    // ── The plans ──

    /** a (Up to {@code first}, ore -> x) -> b (Up to {@code second}, x -> y); ore and y are free ends. */
    static SolveInput chain(final double first, final double second) {
        return plan(
            List.of(
                machine("a", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("b", List.of(port(1, "x")), List.of(port(2, "y")))),
            List.of(edge("e1", "a", 0, "b", 0)),
            List.of(upTo(List.of("a"), first), upTo(List.of("b"), second)));
    }

    /** a (Up to 2) -> b (Up to 2) -> c (Up to 1). */
    static SolveInput chain3() {
        return plan(
            List.of(
                machine("a", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("b", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("c", List.of(port(2, "y")), List.of(port(3, "z")))),
            List.of(edge("e1", "a", 0, "b", 0), edge("e2", "b", 0, "c", 0)),
            List.of(upTo(List.of("a"), 2), upTo(List.of("b"), 2), upTo(List.of("c"), 1)));
    }

    /**
     * m (Up to 2) draws its ore from a source giving at most 1/s; n (Up to 2) sends its gears to a product taking at
     * most 1/s.
     */
    static SolveInput cappedDrawers() {
        return plan(
            List.of(
                machine("m", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("n", List.of(port(8, "dust")), List.of(port(2, "gear")))),
            List.of(),
            List.of(upTo(List.of("m"), 2), upTo(List.of("n"), 2)),
            List.of(
                drawer("ore", "ore", Drawer.Kind.SOURCE, Drawer.Rule.AT_MOST, 1, List.of(link("m", 0))),
                drawer("gear", "gear", Drawer.Kind.PRODUCT, Drawer.Rule.AT_MOST, 1, List.of(link("n", 0)))),
            null);
    }

    /** Two pyrolyse ovens (32 s, 20 tar a craft) feed a distillation tower (1 s, 1.875 tar a craft). */
    static SolveInput benzene() {
        return plan(
            List.of(
                machine("oven", List.of(port(9, "logs", 16)), List.of(port(1, "wood tar", 20)), 0, 640),
                machine("tower", List.of(port(1, "wood tar", 1.875)), List.of(port(2, "benzene")))),
            List.of(edge("tar", "oven", 0, "tower", 0)),
            List.of(upTo(List.of("oven"), 2), upTo(List.of("tower"), 1)));
    }

    /** An Exactly producer of 1 x/s feeding two Up to 1 consumers; c2 takes {@code c2Takes} x a craft. */
    static SolveInput sharedProducer(final double c2Takes) {
        return plan(
            List.of(
                machine("c1", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("c2", List.of(port(1, "x", c2Takes)), List.of(port(3, "z"))),
                machine("e", List.of(), List.of(port(1, "x")), 1)),
            List.of(edge("e1", "e", 0, "c1", 0), edge("e2", "e", 0, "c2", 0)),
            List.of(upTo(List.of("c1"), 1), upTo(List.of("c2"), 1)));
    }

    /** p (Up to 2, ore -> x) -> c (any count, x -> y), with a product drawer asking at least 5 y/s. */
    static SolveInput unreachableDrawer() {
        return plan(
            List.of(
                machine("c", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("p", List.of(port(9, "ore")), List.of(port(1, "x")))),
            List.of(edge("e1", "p", 0, "c", 0)),
            List.of(upTo(List.of("p"), 2)),
            List.of(drawer("d", "y", Drawer.Kind.PRODUCT, Drawer.Rule.AT_LEAST, 5, List.of(link("c", 0)))),
            null);
    }

    /** p (Up to 2) -> c (Exactly 3). */
    static SolveInput exactConsumer() {
        return plan(
            List.of(
                machine("c", List.of(port(1, "x")), List.of(port(2, "y")), 3),
                machine("p", List.of(port(9, "ore")), List.of(port(1, "x")))),
            List.of(edge("e1", "p", 0, "c", 0)),
            List.of(upTo(List.of("p"), 2)));
    }

    /** e (Exactly 3) -> u (Up to 2). */
    static SolveInput exactProducer() {
        return plan(
            List.of(
                machine("e", List.of(port(9, "ore")), List.of(port(1, "x")), 3),
                machine("u", List.of(port(1, "x")), List.of(port(2, "y")))),
            List.of(edge("e1", "e", 0, "u", 0)),
            List.of(upTo(List.of("u"), 2)));
    }

    /** p (Up to 2) -> q (any count). */
    static SolveInput onlyUpTo() {
        return plan(
            List.of(
                machine("p", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("q", List.of(port(1, "x")), List.of(port(2, "y")))),
            List.of(edge("e1", "p", 0, "q", 0)),
            List.of(upTo(List.of("p"), 2)));
    }

    static SolveInput unwired() {
        return plan(
            List.of(machine("m", List.of(port(9, "ore")), List.of(port(1, "gear")))),
            List.of(),
            List.of(upTo(List.of("m"), 3)));
    }

    /** c (Exactly 1) takes all it needs from e (Exactly 1); m (Up to 2) makes x for c too. */
    static SolveInput nothingTakes() {
        return plan(
            List.of(
                machine("c", List.of(port(1, "x")), List.of(port(2, "y")), 1),
                machine("e", List.of(), List.of(port(1, "x")), 1),
                machine("m", List.of(port(9, "ore")), List.of(port(1, "x")))),
            List.of(edge("e1", "m", 0, "c", 0), edge("e2", "e", 0, "c", 0)),
            List.of(upTo(List.of("m"), 2)));
    }

    /** A shared machine: a lathe (ore -> 2 gears) and an assembler (gear -> kit) sharing up to 4 machines. */
    static SolveInput sharedPool() {
        return plan(
            List.of(
                machine("assembler", List.of(port(1, "gear")), List.of(port(2, "kit"))),
                machine("lathe", List.of(port(0, "ore")), List.of(port(1, "gear", 2)))),
            List.of(edge("gear", "lathe", 0, "assembler", 0)),
            List.of(upTo(List.of("lathe", "assembler"), 4)));
    }

    /**
     * e (Exactly 1) makes 2 x/s; a (any count) turns x into y; b (Exactly 1) takes 1 y/s. The spare leaves at a's
     * output
     * (the default) or at e's (the alternative). u (Up to 3, free ends) shares e's seed drawer.
     */
    static SolveInput withAlternatives(final ChoiceKey choice) {
        return plan(
            List.of(
                machine("a", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("b", List.of(port(2, "y")), List.of(port(3, "z")), 1),
                machine("e", List.of(port(8, "seed")), List.of(port(1, "x", 2)), 1),
                machine("u", List.of(port(8, "seed")), List.of(port(4, "w")))),
            List.of(edge("e1", "e", 0, "a", 0), edge("e2", "a", 0, "b", 0)),
            List.of(upTo(List.of("u"), 3)),
            List.of(
                drawer("seed", "seed", Drawer.Kind.SOURCE, Drawer.Rule.ANY, 0, List.of(link("e", 0), link("u", 0)))),
            choice);
    }

    /** chain(2, 1) and chain(1, 3) side by side, ids interleaved. */
    static SolveInput twoIslands() {
        return plan(
            List.of(
                machine("a1", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("a2", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("b1", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("b2", List.of(port(1, "x")), List.of(port(2, "y")))),
            List.of(edge("e1", "a1", 0, "b1", 0), edge("e2", "a2", 0, "b2", 0)),
            List.of(upTo(List.of("a1"), 2), upTo(List.of("a2"), 1), upTo(List.of("b1"), 1), upTo(List.of("b2"), 3)));
    }

    /**
     * Old Build's loop top-up: m (Up to 2, x -> y) and r (Up to 3, y -> half an x) in a loop on the x wire, a source
     * drawer ({@code rule}, {@code rate}) on m's x input and a byproduct drawer on r's x output, both on wired ports.
     */
    static SolveInput toppedLoop(final Drawer.Rule rule, final double rate) {
        return plan(
            List.of(
                machine("m", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("r", List.of(port(2, "y")), List.of(port(1, "x", 0.5)))),
            List.of(edge("y", "m", 0, "r", 0), edge("x", "r", 0, "m", 0)),
            List.of(upTo(List.of("m"), 2), upTo(List.of("r"), 3)),
            List.of(
                drawer("top-up", "x", Drawer.Kind.SOURCE, rule, rate, List.of(link("m", 0))),
                drawer("spare", "x", Drawer.Kind.BYPRODUCT, Drawer.Rule.ANY, 0, List.of(link("r", 0)))),
            null);
    }

    /**
     * The topped loop on an island that must also trade: e (Exactly 1) makes 1 z/s for k (Exactly 1, half a z a craft).
     */
    static SolveInput toppedLoopTrading() {
        final SolveInput loop = toppedLoop(Drawer.Rule.ANY, 0);
        final SolveInput.Machine r = loop.machines()
            .get(1);
        final List<SolveInput.PortIn> rInputs = new ArrayList<>(r.inputs());
        rInputs.add(port(8, "seed"));
        final List<SolveInput.EdgeIn> edges = new ArrayList<>(loop.edges());
        edges.add(edge("z", "e", 0, "k", 0));
        final List<SolveInput.DrawerIn> drawers = new ArrayList<>(loop.drawers());
        drawers
            .add(drawer("seed", "seed", Drawer.Kind.SOURCE, Drawer.Rule.ANY, 0, List.of(link("e", 0), link("r", 1))));
        return plan(
            List.of(
                machine("e", List.of(port(8, "seed")), List.of(port(5, "z")), 1),
                machine("k", List.of(port(5, "z", 0.5)), List.of(), 1),
                loop.machines()
                    .get(0),
                machine("r", rInputs, r.outputs())),
            edges,
            loop.pools(),
            drawers,
            null);
    }

    /** p (Up to 2, ore -> x) -> q (x -> y; any count or Up to 1), with a byproduct drawer on p's wired x output. */
    static SolveInput banked(final boolean upToTaker) {
        return plan(
            List.of(
                machine("p", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("q", List.of(port(1, "x")), List.of(port(2, "y")))),
            List.of(edge("e1", "p", 0, "q", 0)),
            upToTaker ? List.of(upTo(List.of("p"), 2), upTo(List.of("q"), 1)) : List.of(upTo(List.of("p"), 2)),
            List.of(drawer("spare", "x", Drawer.Kind.BYPRODUCT, Drawer.Rule.ANY, 0, List.of(link("p", 0)))),
            null);
    }

    /**
     * m (Up to 2, ore -> x) puts its x into a buffer ({@code rule}, {@code rate}) that t draws from (Up to 1 or any).
     */
    static SolveInput buffered(final boolean upToTaker, final Drawer.Rule rule, final double rate) {
        return plan(
            List.of(
                machine("m", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("t", List.of(port(1, "x")), List.of(port(2, "y")))),
            List.of(),
            upToTaker ? List.of(upTo(List.of("m"), 2), upTo(List.of("t"), 1)) : List.of(upTo(List.of("m"), 2)),
            List.of(
                new SolveInput.DrawerIn(
                    id("buf"),
                    "x",
                    Drawer.Kind.BUFFER,
                    rule,
                    rate,
                    List.of(link("m", 0)),
                    List.of(link("t", 0)))),
            null);
    }

    /**
     * s (Up to {@code feeder}, ore -> x) feeds m (Up to 2, x -> y), whose y goes to a product drawer taking at most
     * 1/s.
     */
    static SolveInput twoSided(final double feeder) {
        return plan(
            List.of(
                machine("m", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("s", List.of(port(9, "ore")), List.of(port(1, "x")))),
            List.of(edge("x", "s", 0, "m", 0)),
            List.of(upTo(List.of("m"), 2), upTo(List.of("s"), feeder)),
            List.of(drawer("out", "y", Drawer.Kind.PRODUCT, Drawer.Rule.AT_MOST, 1, List.of(link("m", 0)))),
            null);
    }

    /** p1 and p2 (Up to 1, 1 x/s each) feed c (Up to 1, 3 x a craft). */
    static SolveInput twoProducers() {
        return plan(
            List.of(
                machine("c", List.of(port(1, "x", 3)), List.of(port(2, "y"))),
                machine("p1", List.of(port(9, "ore")), List.of(port(1, "x"))),
                machine("p2", List.of(port(9, "ore")), List.of(port(1, "x")))),
            List.of(edge("x1", "p1", 0, "c", 0), edge("x2", "p2", 0, "c", 0)),
            List.of(upTo(List.of("c"), 1), upTo(List.of("p1"), 1), upTo(List.of("p2"), 1)));
    }

    /** m (Up to 2) takes x and w, 1 a craft each, from e1 and e2 (Exactly 1, 1/s each). */
    static SolveInput twoShortInputs() {
        return plan(
            List.of(
                machine("e1", List.of(), List.of(port(1, "x")), 1),
                machine("e2", List.of(), List.of(port(2, "w")), 1),
                machine("m", List.of(port(1, "x"), port(2, "w")), List.of(port(3, "y")))),
            List.of(edge("x", "e1", 0, "m", 0), edge("w", "e2", 0, "m", 1)),
            List.of(upTo(List.of("m"), 2)));
    }

    /** n (Up to 2) makes y and v, 1 a craft each, for c1 and c2 (Exactly 1, 1/s each). */
    static SolveInput twoFullOutputs() {
        return plan(
            List.of(
                machine("c1", List.of(port(2, "y")), List.of(), 1),
                machine("c2", List.of(port(3, "v")), List.of(), 1),
                machine("n", List.of(port(9, "ore")), List.of(port(2, "y"), port(3, "v")))),
            List.of(edge("y", "n", 0, "c1", 0), edge("v", "n", 1, "c2", 0)),
            List.of(upTo(List.of("n"), 2)));
    }

    /** The ids of a dead ring of {@code n}: {@code prefix0} to {@code prefix(n-1)}. */
    static List<String> ringIds(final int n, final String prefix) {
        final List<String> ids = new ArrayList<>();
        for (int k = 0; k < n; k++) ids.add(prefix + k);
        return ids;
    }

    /**
     * A recycling ring of {@code n} Up to 1 machines nothing feeds ({@code prefix0}: x0 -> x1, ..., the last back to x0
     * at half a craft's worth): every member starved by the one before it, a dead loop (with {@code n} 1, a machine fed
     * by its own output).
     */
    static SolveInput deadRing(final int n, final String prefix) {
        final List<String> ids = ringIds(n, prefix);
        final List<SolveInput.Machine> machines = new ArrayList<>();
        final List<SolveInput.EdgeIn> edges = new ArrayList<>();
        final List<SolveInput.PoolIn> pools = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            final int after = (k + 1) % n;
            machines.add(
                machine(
                    ids.get(k),
                    List.of(port(k, "x" + k)),
                    List.of(port(after, "x" + after, k == n - 1 ? 0.5 : 1))));
            edges.add(edge(prefix + "w" + k, ids.get(k), 0, ids.get(after), 0));
            pools.add(upTo(List.of(ids.get(k)), 1));
        }
        return plan(machines, edges, pools);
    }

    static SolveInput deadRing(final int n) {
        return deadRing(n, "r");
    }

    /**
     * The dead ring of two fed from outside: e (Exactly 1) puts {@code feed} x0 a second into r0. With 0.25 the ring
     * runs at half its counts, both starved, held back by the feed: it is no dead loop.
     */
    static SolveInput fedRing(final double feed) {
        final SolveInput ring = deadRing(2);
        final List<SolveInput.Machine> machines = new ArrayList<>(ring.machines());
        machines.add(machine("e", List.of(), List.of(port(0, "x0", feed)), 1));
        final List<SolveInput.EdgeIn> edges = new ArrayList<>(ring.edges());
        edges.add(edge("rfeed", "e", 0, "r0", 0));
        return plan(machines, edges, ring.pools());
    }

    /**
     * a (x and free ore -> y) and b (y -> 2 x), both Up to 1, in a ring: b makes twice the x a takes, with nowhere for
     * the rest to go, so neither runs: a clog lock (the fix is a drawer on b's x).
     */
    static SolveInput clogRing() {
        return plan(
            List.of(
                machine("a", List.of(port(1, "x"), port(9, "ore")), List.of(port(2, "y"))),
                machine("b", List.of(port(2, "y")), List.of(port(1, "x", 2)))),
            List.of(edge("y", "a", 0, "b", 0), edge("x", "b", 0, "a", 0)),
            List.of(upTo(List.of("a"), 1), upTo(List.of("b"), 1)));
    }

    /** e (Exactly 1) -> a (Up to 2) -> b (Up to 2): both starved, b by a, a by e; no ring. */
    static SolveInput starvedChain() {
        return plan(
            List.of(
                machine("a", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("b", List.of(port(2, "y")), List.of(port(3, "z"))),
                machine("e", List.of(), List.of(port(1, "x")), 1)),
            List.of(edge("x", "e", 0, "a", 0), edge("y", "a", 0, "b", 0)),
            List.of(upTo(List.of("a"), 2), upTo(List.of("b"), 2)));
    }

    /** A dead ring of two (a0, a1) and one of three (b0, b1, b2), two islands. */
    static SolveInput twoRings() {
        final SolveInput one = deadRing(2, "a"), two = deadRing(3, "b");
        final List<SolveInput.Machine> machines = new ArrayList<>(one.machines());
        machines.addAll(two.machines());
        final List<SolveInput.EdgeIn> edges = new ArrayList<>(one.edges());
        edges.addAll(two.edges());
        final List<SolveInput.PoolIn> pools = new ArrayList<>(one.pools());
        pools.addAll(two.pools());
        return plan(machines, edges, pools);
    }

    /**
     * e (Exactly 1) and p (Up to 2) feed c; a drawer asks 5 of c's output; f (Exactly 1) is a second pin on the island.
     */
    static SolveInput pinDiagnosis() {
        return plan(
            List.of(
                machine("c", List.of(port(1, "x")), List.of(port(2, "y"))),
                machine("e", List.of(port(8, "seed")), List.of(port(1, "x")), 1),
                machine("f", List.of(port(8, "seed")), List.of(port(5, "v")), 1),
                machine("p", List.of(port(9, "ore")), List.of(port(1, "x")))),
            List.of(edge("e1", "e", 0, "c", 0), edge("e2", "p", 0, "c", 0)),
            List.of(upTo(List.of("p"), 2)),
            List.of(
                drawer("d", "y", Drawer.Kind.PRODUCT, Drawer.Rule.AT_LEAST, 5, List.of(link("c", 0))),
                drawer("seed", "seed", Drawer.Kind.SOURCE, Drawer.Rule.ANY, 0, List.of(link("e", 0), link("f", 0)))),
            null);
    }

    /** The budget plan's consumers, in pool order. */
    static List<String> budgetConsumers() {
        final List<String> ids = new ArrayList<>();
        for (int k = 0; k < 10; k++) ids.add(String.format("c%02d", k));
        return ids;
    }

    /**
     * Ten consumers (Up to 1) each take one of twelve goods a craft from twelve Exactly producers making five a second.
     */
    static SolveInput budgetPlan() {
        final List<String> ids = budgetConsumers();
        final List<SolveInput.Machine> machines = new ArrayList<>();
        final List<SolveInput.EdgeIn> edges = new ArrayList<>();
        final List<SolveInput.PortIn> goods = new ArrayList<>();
        for (int g = 1; g <= 12; g++) goods.add(port(g, "g" + g));
        for (final String id : ids) machines.add(machine(id, goods, List.of(port(99, "y"))));
        for (int g = 1; g <= 12; g++)
            machines.add(machine(String.format("p%02d", g), List.of(), List.of(port(g, "g" + g)), 5));
        for (final String id : ids)
            for (int g = 1; g <= 12; g++) edges.add(edge("w" + id + "-" + g, String.format("p%02d", g), 0, id, g - 1));
        final List<SolveInput.PoolIn> pools = new ArrayList<>();
        for (final String id : ids) pools.add(upTo(List.of(id), 1));
        return plan(machines, edges, pools);
    }

    /** Every plan the website's FIXTURES lists, by its name there. */
    static Map<String, SolveInput> fixtures() {
        final Map<String, SolveInput> out = new LinkedHashMap<>();
        out.put("chain, second slower", chain(2, 1));
        out.put("chain, first slower", chain(2, 3));
        out.put("chain of three", chain3());
        out.put("capped drawers", cappedDrawers());
        out.put("benzene", benzene());
        out.put("shared producer", sharedProducer(1));
        out.put("stage A", sharedProducer(2));
        out.put("unreachable drawer", unreachableDrawer());
        out.put("exact consumer", exactConsumer());
        out.put("exact producer", exactProducer());
        out.put("only Up to", onlyUpTo());
        out.put("unwired", unwired());
        out.put("nothing takes", nothingTakes());
        out.put("shared pool", sharedPool());
        out.put("alternatives", withAlternatives(null));
        out.put("two islands", twoIslands());
        out.put("topped loop", toppedLoop(Drawer.Rule.ANY, 0));
        out.put("topped loop, capped", toppedLoop(Drawer.Rule.AT_MOST, 0.5));
        out.put("topped loop, trading", toppedLoopTrading());
        out.put("banked, any taker", banked(false));
        out.put("banked, Up to taker", banked(true));
        out.put("buffer, Up to taker", buffered(true, Drawer.Rule.ANY, 0));
        out.put("buffer, any taker", buffered(false, Drawer.Rule.ANY, 0));
        out.put("buffer with a rule", buffered(true, Drawer.Rule.AT_MOST, 0.5));
        out.put("two-sided, tied", twoSided(1));
        out.put("two-sided, output tighter", twoSided(1.5));
        out.put("two producers", twoProducers());
        out.put("two short inputs", twoShortInputs());
        out.put("two full outputs", twoFullOutputs());
        out.put("dead ring of one", deadRing(1));
        out.put("dead ring of two", deadRing(2));
        out.put("dead ring of three", deadRing(3));
        out.put("clog ring", clogRing());
        out.put("starved chain", starvedChain());
        out.put("two rings", twoRings());
        return out;
    }
}
