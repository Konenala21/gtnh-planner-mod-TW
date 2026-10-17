package com.gtnhplanner.data.flowchart.balancer;

import static com.gtnhplanner.data.flowchart.balancer.UpToPlans.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternative;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Enumerator;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Rank;

/**
 * The Up to rule in the solver (docs/design/up-to.md): the guard, the push, the drawers and buffers as the player's own
 * end, the drawer relaxation naming the card, and the read-out ({@link SolutionView#upTo()}). The website's
 * {@code upto.test.ts}, case for case, on the same plans ({@link UpToPlans}) with the same expected answers; the whole
 * answers are also compared with the website's by {@code UpToParityTest}.
 */
class UpToTest {

    private static final double EPS = 1e-9;

    private static SolutionView solved(final SolveInput in) {
        final Balancer.Answer answer = Balancer.solveWithAlternatives(in);
        assertInstanceOf(Balancer.Answer.Solved.class, answer, () -> "failed: " + answer);
        return ((Balancer.Answer.Solved) answer).solution();
    }

    private static Balancer.Answer.Solved answer(final SolveInput in) {
        return (Balancer.Answer.Solved) Balancer.solveWithAlternatives(in);
    }

    private static UpToReadout readout(final SolutionView s, final String node) {
        for (final UpToReadout r : s.upTo()) if (r.node()
            .equals(id(node))) return r;
        throw new AssertionError("no read-out for " + node + ": " + s.upTo());
    }

    private static double count(final SolutionView s, final String node) {
        return s.machineCounts.get(id(node));
    }

    private static PortRef ref(final String node, final int port, final boolean input) {
        return new PortRef(id(node), port, input);
    }

    private static SolveContext context(final SolveInput in) {
        return new SolveContext(
            in,
            BalanceMode.AUTO.heuristics(),
            () -> false,
            in.extentPins(),
            BalanceMode.AUTO.pins(),
            true,
            Profiler.disabled());
    }

    private static void assertReadout(final UpToReadout r, final UpToReason reason, final double reached,
        final PortRef port) {
        assertEquals(reason, r.reason(), () -> "reason of " + r);
        assertEquals(reached, r.reached(), EPS, () -> "reached of " + r);
        assertEquals(port, r.port(), () -> "port of " + r);
    }

    // ── The push and the reasons ──

    @Test
    void aChainWhoseSecondStepIsSlower() {
        final SolutionView s = solved(UpToPlans.chain(2, 1));
        assertEquals(1, count(s, "a"), EPS);
        assertEquals(1, count(s, "b"), EPS);
        final UpToReadout a = readout(s, "a");
        assertEquals(List.of(id("a")), a.machines());
        assertEquals(2, a.capacity());
        assertReadout(a, UpToReason.CLOGGED, 1, ref("a", 0, false));
        assertTrue(
            a.tied()
                .isEmpty());
        assertNull(a.wants());
        assertTrue(
            a.upstream()
                .isEmpty());
        assertReadout(readout(s, "b"), UpToReason.FULL, 1, null);
        assertTrue(s.gatedSources.isEmpty());
        assertTrue(s.gatedSinks.isEmpty());
    }

    @Test
    void theMirroredChainStarvesTheSecondAndTheFirstIsABottleneck() {
        final SolutionView s = solved(UpToPlans.chain(2, 3));
        final UpToReadout a = readout(s, "a");
        assertReadout(a, UpToReason.BOTTLENECK, 2, null);
        assertEquals(3, a.wants(), EPS);
        final UpToReadout b = readout(s, "b");
        assertReadout(b, UpToReason.STARVED, 2, ref("b", 0, true));
        assertEquals(List.of(id("a")), b.upstream());
        assertTrue(
            b.tied()
                .isEmpty());
    }

    @Test
    void benzeneTheOvensAreTheBottleneck() {
        final SolutionView s = solved(UpToPlans.benzene());
        assertEquals(2, count(s, "oven"), EPS);
        assertEquals(2 / 3.0, count(s, "tower"), EPS);
        final UpToReadout oven = readout(s, "oven");
        assertEquals(UpToReason.BOTTLENECK, oven.reason());
        assertEquals(3, oven.wants(), EPS);
        final UpToReadout tower = readout(s, "tower");
        assertEquals(UpToReason.STARVED, tower.reason());
        assertEquals(ref("tower", 0, true), tower.port());
        assertEquals(List.of(id("oven")), tower.upstream());
    }

    @Test
    void twoUpToConsumersAnExactlyProducerCannotBothFeed() {
        final SolutionView s = solved(UpToPlans.sharedProducer(1));
        assertEquals(0.5, count(s, "c1"), EPS);
        assertEquals(0.5, count(s, "c2"), EPS);
        for (final String c : List.of("c1", "c2")) {
            final UpToReadout r = readout(s, c);
            assertEquals(UpToReason.STARVED, r.reason());
            assertEquals(ref(c, 0, true), r.port());
            assertTrue(
                r.upstream()
                    .isEmpty(),
                "the producer is Exactly: no Up to card upstream to act on");
        }
        assertTrue(s.gatedSinks.isEmpty());
    }

    @Test
    void stageARunsFirst() {
        final SolutionView s = solved(UpToPlans.sharedProducer(2));
        assertEquals(1, count(s, "c1"), EPS);
        assertEquals(0, count(s, "c2"), EPS);
        assertEquals(UpToReason.FULL, readout(s, "c1").reason());
        assertReadout(readout(s, "c2"), UpToReason.STARVED, 0, ref("c2", 0, true));
        assertTrue(s.unneeded.isEmpty());
    }

    @Test
    void aDrawerRateTheUpToProducerCannotReachFallsShortAndNamesTheCard() {
        final SolutionView s = solved(UpToPlans.unreachableDrawer());
        assertEquals(2, count(s, "p"), 1e-6);
        assertEquals(2, count(s, "c"), 1e-6);
        assertTrue(s.gatedSources.isEmpty());
        assertTrue(s.gatedSinks.isEmpty());
        assertEquals(Set.of(id("d")), s.drawers.unmet());
        assertEquals(
            1,
            s.drawers.shortfalls()
                .size());
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfalls()
            .get(0);
        assertEquals(id("d"), shortfall.drawer());
        assertEquals(Drawer.Rule.AT_LEAST, shortfall.rule());
        assertEquals(5, shortfall.target());
        assertEquals(2, shortfall.reachable(), 1e-6);
        assertEquals(List.of(id("p")), shortfall.limitingNodes());
        assertTrue(
            shortfall.limitingDrawers()
                .isEmpty());
        assertEquals(0, shortfall.limitingGroups());
        final List<Note> limited = new ArrayList<>();
        for (final Note n : s.notes)
            if (n.message() == SolverMessage.DRAWER_LIMITED_BY) limited.add((Note) n.args()[1]);
        assertEquals(List.of(new Note(SolverMessage.LIMIT_PIN, "p", SolverMessage.PIN_UP_TO.toNote())), limited);
        final UpToReadout p = readout(s, "p");
        assertEquals(UpToReason.BOTTLENECK, p.reason());
        assertEquals(5, p.wants(), 1e-6);
    }

    @Test
    void anExactlyConsumerDownstreamOfAnUpToProducer() {
        final SolutionView s = solved(UpToPlans.exactConsumer());
        assertEquals(2, count(s, "p"), EPS);
        assertEquals(1, s.gatedSources.size());
        assertEquals(
            ref("c", 0, true),
            s.gatedSources.get(0)
                .port());
        assertEquals(
            1,
            s.gatedSources.get(0)
                .ratePerSecond(),
            EPS);
        final UpToReadout p = readout(s, "p");
        assertReadout(p, UpToReason.BOTTLENECK, 2, null);
        assertEquals(3, p.wants(), EPS);
    }

    @Test
    void anExactlyProducerUpstreamOfAnUpToConsumer() {
        final SolutionView s = solved(UpToPlans.exactProducer());
        assertEquals(2, count(s, "u"), EPS);
        assertEquals(1, s.gatedSinks.size());
        assertEquals(
            ref("e", 0, false),
            s.gatedSinks.get(0)
                .port());
        assertEquals(
            1,
            s.gatedSinks.get(0)
                .ratePerSecond(),
            EPS);
        assertEquals(UpToReason.FULL, readout(s, "u").reason());
    }

    @Test
    void onlyUpToCountsAnchorThePlan() {
        assertTrue(context(UpToPlans.onlyUpTo()).anchored);
        final SolutionView s = solved(UpToPlans.onlyUpTo());
        assertEquals(2, count(s, "p"), EPS);
        assertEquals(2, count(s, "q"), EPS);
        assertTrue(s.unneeded.isEmpty());
        assertEquals(UpToReason.FULL, readout(s, "p").reason());
    }

    @Test
    void anUpToMachineWithNoWiresRunsAtItsCount() {
        final SolutionView s = solved(UpToPlans.unwired());
        assertEquals(
            1,
            s.upTo()
                .size());
        final UpToReadout m = s.upTo()
            .get(0);
        assertEquals(id("m"), m.node());
        assertEquals(List.of(id("m")), m.machines());
        assertEquals(3, m.capacity());
        assertReadout(m, UpToReason.FULL, 3, null);
    }

    @Test
    void aMachineNothingTakesFromIsUnusedAndNeverUnneeded() {
        final SolutionView s = solved(UpToPlans.nothingTakes());
        assertEquals(0, count(s, "m"));
        assertTrue(s.unneeded.isEmpty());
        assertReadout(readout(s, "m"), UpToReason.UNUSED, 0, ref("m", 0, false));
    }

    @Test
    void aSharedUpToMachineRunsAtItsCapacityAcrossItsRecipes() {
        final SolutionView s = solved(UpToPlans.sharedPool());
        assertEquals(4 / 3.0, count(s, "lathe"), EPS);
        assertEquals(8 / 3.0, count(s, "assembler"), EPS);
        assertEquals(
            1,
            s.upTo()
                .size());
        final UpToReadout r = s.upTo()
            .get(0);
        assertEquals(id("lathe"), r.node());
        assertEquals(List.of(id("lathe"), id("assembler")), r.machines());
        assertEquals(4, r.capacity());
        assertReadout(r, UpToReason.FULL, 4, null);
    }

    @Test
    void aThreeStepChainHeldByItsLastStep() {
        final SolutionView s = solved(UpToPlans.chain3());
        for (final String m : List.of("a", "b")) {
            assertEquals(1, count(s, m), EPS);
            final UpToReadout r = readout(s, m);
            assertEquals(UpToReason.CLOGGED, r.reason(), m);
            assertEquals(ref(m, 0, false), r.port());
        }
        assertEquals(UpToReason.FULL, readout(s, "c").reason());
    }

    @Test
    void cappedDrawersOnUnwiredPorts() {
        final SolutionView s = solved(UpToPlans.cappedDrawers());
        assertEquals(1, count(s, "m"), EPS);
        assertEquals(1, count(s, "n"), EPS);
        assertEquals(UpToReason.STARVED, readout(s, "m").reason());
        assertEquals(ref("m", 0, true), readout(s, "m").port());
        assertEquals(UpToReason.CLOGGED, readout(s, "n").reason());
        assertEquals(ref("n", 0, false), readout(s, "n").port());
    }

    @Test
    void aPlanWithoutUpToPoolsHasNoReadOut() {
        assertTrue(
            solved(UpToPlans.exactly(UpToPlans.chain(2, 1))).upTo()
                .isEmpty());
    }

    // ── A drawer on a wired port is the player's own end ──

    @Test
    void aSourceDrawerOfAnyRateTopsUpALoop() {
        final SolutionView s = solved(UpToPlans.toppedLoop(Drawer.Rule.ANY, 0));
        assertEquals(2, count(s, "m"), EPS);
        assertEquals(2, count(s, "r"), EPS);
        assertEquals(1, s.drawers.rate(id("top-up")), EPS);
        assertEquals(0, s.drawers.rate(id("spare")), EPS);
        assertEquals(1, s.edgeFlowsPerSecond.get(id("x")), EPS);
        final UpToReadout m = readout(s, "m");
        assertEquals(UpToReason.BOTTLENECK, m.reason());
        assertEquals(3, m.wants(), EPS);
        final UpToReadout r = readout(s, "r");
        assertEquals(UpToReason.STARVED, r.reason());
        assertEquals(ref("r", 0, true), r.port());
        assertEquals(List.of(id("m")), r.upstream());
    }

    @Test
    void theSameWhereTheTradesSettleIt() {
        final SolutionView s = solved(UpToPlans.toppedLoopTrading());
        assertEquals(1, s.gatedSinks.size());
        assertEquals(
            ref("e", 0, false),
            s.gatedSinks.get(0)
                .port());
        assertEquals(
            0.5,
            s.gatedSinks.get(0)
                .ratePerSecond(),
            EPS);
        assertEquals(2, count(s, "m"), EPS);
        assertEquals(2, count(s, "r"), EPS);
        assertEquals(1, s.drawers.rate(id("top-up")), EPS);
        assertEquals(0, s.drawers.rate(id("spare")), EPS);
    }

    @Test
    void aCappedSourceDrawerStarvesTheMachineAtItsPort() {
        final SolutionView s = solved(UpToPlans.toppedLoop(Drawer.Rule.AT_MOST, 0.5));
        assertEquals(1, count(s, "m"), EPS);
        assertEquals(1, count(s, "r"), EPS);
        assertEquals(0.5, s.drawers.rate(id("top-up")), EPS);
        assertEquals(0, s.drawers.rate(id("spare")), EPS);
        assertEquals(UpToReason.STARVED, readout(s, "m").reason());
        assertEquals(ref("m", 0, true), readout(s, "m").port());
    }

    @Test
    void aByproductDrawerIsPermissionToRun() {
        final SolutionView any = solved(UpToPlans.banked(false));
        assertEquals(2, count(any, "p"), EPS);
        assertEquals(2, count(any, "q"), EPS);
        assertEquals(0, any.drawers.rate(id("spare")), EPS);
        final SolutionView full = solved(UpToPlans.banked(true));
        assertEquals(2, count(full, "p"), EPS);
        assertEquals(1, count(full, "q"), EPS);
        assertEquals(1, full.drawers.rate(id("spare")), EPS);
        assertEquals(UpToReason.FULL, readout(full, "p").reason());
        assertEquals(UpToReason.FULL, readout(full, "q").reason());
    }

    @Test
    void theTradeLevelsLeaveADrawersPortsOutOnlyWithUpToPools() {
        final SolveContext up = context(UpToPlans.toppedLoop(Drawer.Rule.ANY, 0));
        assertEquals(2, up.drawnPorts.size());
        final Set<Integer> traded = new java.util.HashSet<>();
        for (final Solver.Level l : Solver.levels(up)) for (final int p : l.ports()) traded.add(p);
        for (final int p : up.drawnPorts) assertFalse(traded.contains(p));
        final SolveContext plain = context(UpToPlans.exactly(UpToPlans.toppedLoop(Drawer.Rule.ANY, 0)));
        final Set<Integer> all = new java.util.HashSet<>();
        for (final Solver.Level l : Solver.levels(plain)) for (final int p : l.ports()) all.add(p);
        for (final int p : plain.drawnPorts) assertTrue(all.contains(p));
    }

    // ── A buffer of any rule banks what its takers do not take ──

    @Test
    void aSlowerUpToTakerLetsTheBufferBankTheRest() {
        final SolutionView s = solved(UpToPlans.buffered(true, Drawer.Rule.ANY, 0));
        assertEquals(2, count(s, "m"), EPS);
        assertEquals(1, count(s, "t"), EPS);
        assertEquals(
            Set.of(id("buf")),
            s.drawers.banked()
                .keySet());
        assertEquals(1, s.drawers.bankedRate(id("buf")), EPS);
        assertEquals(1, s.drawers.rate(id("buf")), EPS);
        assertEquals(1, s.drawers.portRate(id("buf"), ref("m", 0, false)), EPS);
        assertEquals(1, s.drawers.portRate(id("buf"), ref("t", 0, true)), EPS);
        assertTrue(s.gatedSinks.isEmpty());
        assertTrue(s.gatedSources.isEmpty());
        assertEquals(0, s.openGates);
        assertEquals(UpToReason.FULL, readout(s, "m").reason());
        assertEquals(UpToReason.FULL, readout(s, "t").reason());
    }

    @Test
    void aTakerOfAnyCountTakesAllOfItFirst() {
        final SolutionView s = solved(UpToPlans.buffered(false, Drawer.Rule.ANY, 0));
        assertEquals(2, count(s, "m"), EPS);
        assertEquals(2, count(s, "t"), EPS);
        assertEquals(
            Set.of(id("buf")),
            s.drawers.banked()
                .keySet());
        assertEquals(0, s.drawers.bankedRate(id("buf")), EPS);
        assertTrue(s.gatedSinks.isEmpty());
    }

    @Test
    void aBufferWithARuleBanksNothing() {
        final SolutionView s = solved(UpToPlans.buffered(true, Drawer.Rule.AT_MOST, 0.5));
        assertEquals(0.5, count(s, "m"), EPS);
        assertEquals(0.5, count(s, "t"), EPS);
        assertNull(s.drawers.banked());
        assertTrue(s.gatedSinks.isEmpty());
        assertEquals(UpToReason.CLOGGED, readout(s, "m").reason());
    }

    @Test
    void withoutUpToPoolsABufferIsWiringOnly() {
        assertTrue(context(UpToPlans.exactly(UpToPlans.buffered(true, Drawer.Rule.ANY, 0))).bankPorts.isEmpty());
    }

    // ── The reasons weigh the inputs against the outputs ──

    @Test
    void heldAtBothEndsAtOnceTellsTheInputStoryFirst() {
        final SolutionView s = solved(UpToPlans.twoSided(1));
        assertEquals(1, count(s, "m"), EPS);
        final UpToReadout m = readout(s, "m");
        assertEquals(UpToReason.STARVED, m.reason());
        assertEquals(ref("m", 0, true), m.port());
        assertEquals(List.of(id("s")), m.upstream());
        final UpToReadout feeder = readout(s, "s");
        assertEquals(UpToReason.BOTTLENECK, feeder.reason());
        assertEquals(2, feeder.wants(), EPS);
    }

    @Test
    void theOutputStrictlyTighterIsClogged() {
        final SolutionView s = solved(UpToPlans.twoSided(1.5));
        assertEquals(1, count(s, "m"), EPS);
        assertEquals(1, count(s, "s"), EPS);
        assertEquals(UpToReason.CLOGGED, readout(s, "m").reason());
        assertEquals(ref("m", 0, false), readout(s, "m").port());
        assertEquals(UpToReason.CLOGGED, readout(s, "s").reason());
        assertEquals(ref("s", 0, false), readout(s, "s").port());
    }

    @Test
    void twoProducersShortOfOneConsumer() {
        final SolutionView s = solved(UpToPlans.twoProducers());
        assertEquals(2 / 3.0, count(s, "c"), EPS);
        final UpToReadout c = readout(s, "c");
        assertEquals(UpToReason.STARVED, c.reason());
        assertEquals(ref("c", 0, true), c.port());
        final List<UUID> upstream = new ArrayList<>(List.of(id("p1"), id("p2")));
        upstream.sort(UUID::compareTo);
        assertEquals(upstream, c.upstream());
        for (final String p : List.of("p1", "p2")) {
            assertEquals(UpToReason.BOTTLENECK, readout(s, p).reason());
            assertEquals(2, readout(s, p).wants(), EPS);
        }
    }

    // ── Rings that blame each other (dead loops and clog locks) ──

    private static List<UUID> ids(final List<String> names) {
        final List<UUID> out = new ArrayList<>();
        for (final String name : names) out.add(id(name));
        return out;
    }

    @Test
    void aRecyclingRingNothingFeedsIsADeadLoopTwoAndThreeLongAndAMachineFedByItself() {
        for (final int n : new int[] { 1, 2, 3 }) {
            final SolutionView s = solved(UpToPlans.deadRing(n));
            final List<String> names = UpToPlans.ringIds(n, "r");
            for (final String name : names) assertEquals(UpToReason.STARVED, readout(s, name).reason(), name);
            final List<PortRef> ports = new ArrayList<>();
            for (final String name : names) ports.add(ref(name, 0, true));
            assertEquals(List.of(new UpToLoop(UpToLoop.Kind.DEAD_LOOP, ids(names), ports)), s.upToLoops(), "n " + n);
        }
    }

    @Test
    void aByproductRingThatFillsItsOwnWayOutIsAClogLockOfItsFullOutputs() {
        final SolutionView s = solved(UpToPlans.clogRing());
        assertEquals(UpToReason.UNUSED, readout(s, "a").reason());
        assertEquals(UpToReason.UNUSED, readout(s, "b").reason());
        assertEquals(
            List.of(
                new UpToLoop(
                    UpToLoop.Kind.CLOG_LOCK,
                    ids(List.of("a", "b")),
                    List.of(ref("a", 0, false), ref("b", 0, false)))),
            s.upToLoops());
    }

    @Test
    void aStarvedChainIsNoRing() {
        final SolutionView s = solved(UpToPlans.starvedChain());
        assertEquals(UpToReason.STARVED, readout(s, "a").reason());
        assertEquals(UpToReason.STARVED, readout(s, "b").reason());
        assertTrue(
            s.upToLoops()
                .isEmpty());
    }

    @Test
    void eachIslandsRingsMergedByTheirFirstMember() {
        final SolutionView s = solved(UpToPlans.twoRings());
        final List<List<UUID>> expected = new ArrayList<>(
            List.of(ids(UpToPlans.ringIds(2, "a")), ids(UpToPlans.ringIds(3, "b"))));
        expected.sort(Comparator.comparing(nodes -> nodes.get(0)));
        final List<List<UUID>> rings = new ArrayList<>();
        for (final UpToLoop l : s.upToLoops()) {
            assertEquals(UpToLoop.Kind.DEAD_LOOP, l.kind());
            rings.add(l.nodes());
        }
        assertEquals(expected, rings);
    }

    // ── Several ports short at once ──

    @Test
    void twoInputsShortTogether() {
        final SolutionView s = solved(UpToPlans.twoShortInputs());
        assertEquals(1, count(s, "m"), EPS);
        final UpToReadout m = readout(s, "m");
        assertEquals(UpToReason.STARVED, m.reason());
        assertEquals(ref("m", 0, true), m.port());
        assertEquals(List.of(ref("m", 1, true)), m.tied());
    }

    @Test
    void twoOutputsFullTogether() {
        final SolutionView s = solved(UpToPlans.twoFullOutputs());
        assertEquals(1, count(s, "n"), EPS);
        final UpToReadout n = readout(s, "n");
        assertEquals(UpToReason.CLOGGED, n.reason());
        assertEquals(ref("n", 0, false), n.port());
        assertEquals(List.of(ref("n", 1, false)), n.tied());
    }

    // ── Wherever a support is settled ──

    @Test
    void theAlternativesAndTheStoredChoicePush() {
        final Balancer.Answer.Solved first = answer(UpToPlans.withAlternatives(null));
        assertEquals(3, count(first.solution(), "u"), EPS);
        Alternative other = null;
        for (final Alternative o : first.alternatives()
            .options()) if (o.rank() != Rank.DEFAULT) {
                other = o;
                break;
            }
        assertNotNull(other);
        assertEquals(ref("e", 0, false), other.opens());

        // evaluateSupport pushes: without it least flow would leave u idle.
        final SolveContext ctx = Balancer.run(UpToPlans.withAlternatives(null), Profiler.disabled())
            .ctx();
        final Set<Integer> target = Enumerator.resolve(ctx, other.key());
        assertNotNull(target);
        final Enumerator.Settled settled = Enumerator.evaluateSupportWith(ctx, target);
        assertNotNull(settled);
        final int u = ctx.model.machineIndex.get(id("u"));
        assertEquals(3, ctx.countOf(u, settled.point().extents[u]), EPS);
        assertEquals(
            1,
            settled.trades()
                .floors()
                .size());

        // The stored choice commits that support, pushed, and the read-out reads it.
        final SolutionView chosen = solved(UpToPlans.withAlternatives(other.key()));
        assertEquals(other.key(), chosen.key);
        assertEquals(1, count(chosen, "a"), EPS);
        assertEquals(3, count(chosen, "u"), EPS);
        assertEquals(UpToReason.FULL, readout(chosen, "u").reason());
    }

    @Test
    void eachIslandsReadOutIsItsOwn() {
        final SolutionView s = solved(UpToPlans.twoIslands());
        final List<UUID> nodes = new ArrayList<>();
        for (final UpToReadout r : s.upTo()) nodes.add(r.node());
        final List<UUID> sorted = new ArrayList<>(List.of(id("a1"), id("a2"), id("b1"), id("b2")));
        sorted.sort(UUID::compareTo);
        assertEquals(sorted, nodes);
        final SolutionView one = solved(UpToPlans.chain(2, 1)), two = solved(UpToPlans.chain(1, 3));
        sameReadout(readout(one, "a"), readout(s, "a1"), "1");
        sameReadout(readout(one, "b"), readout(s, "b1"), "1");
        sameReadout(readout(two, "a"), readout(s, "a2"), "2");
        sameReadout(readout(two, "b"), readout(s, "b2"), "2");
        assertEquals(UpToReason.BOTTLENECK, readout(s, "a2").reason());
        assertEquals(3, readout(s, "a2").wants(), EPS);
    }

    /** {@code island} is {@code alone} with every card renamed with {@code suffix}. */
    private static void sameReadout(final UpToReadout alone, final UpToReadout island, final String suffix) {
        assertEquals(alone.reason(), island.reason());
        assertEquals(alone.capacity(), island.capacity());
        assertEquals(alone.reached(), island.reached(), EPS);
        assertEquals(alone.wants() == null, island.wants() == null);
        if (alone.wants() != null) assertEquals(alone.wants(), island.wants(), EPS);
        assertEquals(alone.port() == null, island.port() == null);
        if (alone.port() != null) {
            assertEquals(
                alone.port()
                    .portIndex(),
                island.port()
                    .portIndex());
            assertEquals(
                alone.port()
                    .input(),
                island.port()
                    .input());
            assertEquals(
                island.node(),
                island.port()
                    .nodeId());
        }
        assertEquals(
            alone.upstream()
                .size(),
            island.upstream()
                .size());
    }

    @Test
    void aDrawerShortfallIsNeverAPinConflict() {
        final SolveInput in = UpToPlans.pinDiagnosis();
        final Settlement stalled = BalanceMode.AUTO.chain()
            .run(context(in));
        final Settlement.Stalled reason = assertInstanceOf(Settlement.Stalled.class, stalled);
        assertFalse(
            reason.reason()
                .containsMessage(SolverMessage.PIN_CONFLICT),
            "the pin diagnosis runs without the guard: " + reason);
        final SolutionView s = solved(in);
        assertEquals(3, count(s, "c"), 1e-6);
        assertTrue(s.gatedSources.isEmpty());
        assertEquals(
            List.of(id("e"), id("p")),
            s.drawers.shortfalls()
                .get(0)
                .limitingNodes());
    }

    @Test
    void theReadOutsWorkIsCounted() {
        assertEquals(UpToRead.MAX_READOUT_LPS, UpToRead.budget(10));
        final SolutionView s = solved(UpToPlans.budgetPlan());
        final List<UpToReason> reasons = new ArrayList<>();
        for (final String c : UpToPlans.budgetConsumers()) reasons.add(readout(s, c).reason());
        final List<UpToReason> expected = new ArrayList<>();
        for (int k = 0; k < 7; k++) expected.add(UpToReason.STARVED);
        for (int k = 0; k < 3; k++) expected.add(null);
        assertEquals(expected, reasons);
        for (final String c : UpToPlans.budgetConsumers()) assertEquals(0.5, count(s, c), EPS);
    }
}
