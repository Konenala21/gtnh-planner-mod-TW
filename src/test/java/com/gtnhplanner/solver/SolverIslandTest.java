package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * Islands answer as if alone: every copy of a plan tiled into one (copies nothing joins) gets exactly the plan's own
 * answer, its machines, its flows, its leftovers and its choices, however many copies sit beside it.
 */
class SolverIslandTest {

    @BeforeAll
    static void setUp() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @AfterAll
    static void tearDown() {}

    @Test
    void everyCopyAnswersAsThePlanAlone() {
        for (final String plan : new String[] { "platline", "mega-iv-nitrobenzene-power-plant", "hv-diesel" }) {
            final SolveInput one = SolverCorpus.plan(plan);
            final SolutionView alone = solved(Balancer.solveWithAlternatives(one));
            final int copies = 4;
            final Balancer.Answer tiledAnswer = Balancer.solveWithAlternatives(SolverCorpus.tile(one, copies));
            final SolutionView tiled = solved(tiledAnswer);
            for (int c = 0; c < copies; c++) {
                same(plan, c, alone.machineCounts, tiled.machineCounts);
                same(plan, c, alone.edgeFlowsPerSecond, tiled.edgeFlowsPerSecond);
            }
            assertEquals(copies * alone.openGates, tiled.openGates, plan + ": open gates");
            assertEquals(
                copies * alone.spares()
                    .size(),
                tiled.spares()
                    .size(),
                plan + ": leftovers");
            assertEquals(
                copies * ((Balancer.Answer.Solved) Balancer.solveWithAlternatives(one)).alternatives()
                    .options()
                    .size(),
                ((Balancer.Answer.Solved) tiledAnswer).alternatives()
                    .options()
                    .size(),
                plan + ": choices");
        }
    }

    /**
     * The same with every count Up to (docs/design/up-to.md): each island pushes and reads out its own Up to cards, so
     * every copy reads as the plan alone.
     */
    @Test
    void everyCopyOfAnUpToPlanAnswersAsThePlanAlone() {
        for (final String plan : new String[] { "platline", "hv-diesel", "oxygen-silicon-loop" }) {
            final SolveInput one = SolverCorpus.everyCountUpTo(SolverCorpus.plan(plan));
            final SolutionView alone = solved(Balancer.solveWithAlternatives(one));
            final int copies = 3;
            final SolutionView tiled = solved(Balancer.solveWithAlternatives(SolverCorpus.tile(one, copies)));
            for (int c = 0; c < copies; c++) same(plan, c, alone.machineCounts, tiled.machineCounts);
            assertEquals(
                copies * alone.upTo()
                    .size(),
                tiled.upTo()
                    .size(),
                plan + ": read-outs");
            final Map<UUID, UpToReadout> byNode = new java.util.HashMap<>();
            for (final UpToReadout r : tiled.upTo()) byNode.put(r.node(), r);
            for (final UpToReadout r : alone.upTo()) {
                for (int c = 0; c < copies; c++) {
                    final UUID node = c == 0 ? r.node()
                        : UUID.nameUUIDFromBytes((c + ":" + r.node()).getBytes(StandardCharsets.UTF_8));
                    final UpToReadout copy = byNode.get(node);
                    assertTrue(
                        copy != null && copy.reason() == r.reason(),
                        plan + " copy " + c + ": " + r + " / " + copy);
                    assertEquals(r.reached(), copy.reached(), 1e-9 * Math.max(1, r.reached()), plan + " copy " + c);
                }
            }
        }
    }

    /**
     * The island cache never answers for an island that changed: after one copy's pin moves, a solve through the
     * cache answers as a solve without it.
     */
    @Test
    void theCacheOnlyKeepsWhatDidNotChange() {
        final SolveInput tiled = SolverCorpus.tile(SolverCorpus.plan("platline"), 3);
        final Balancer.Cache cache = new Balancer.Cache();
        Balancer.solveWithAlternatives(tiled, cache, () -> false);
        final java.util.List<SolveInput.Machine> machines = new java.util.ArrayList<>(tiled.machines());
        int edited = -1;
        for (int m = machines.size() - 1; m >= 0 && edited < 0; m--) if (machines.get(m)
            .countFixed()) edited = m;
        final SolveInput.Machine pinned = machines.get(edited);
        machines.set(
            edited,
            new SolveInput.Machine(
                pinned.id(),
                pinned.name(),
                pinned.durationTicks(),
                pinned.energyPerTick(),
                pinned.throughputFactor(),
                pinned.inputs(),
                pinned.outputs(),
                true,
                pinned.machineCount() + 3,
                pinned.targetOutputRates(),
                pinned.properties(),
                pinned.targetInputRates()));
        final SolveInput edit = new SolveInput(
            tiled.mode(),
            tiled.choice(),
            machines,
            tiled.edges(),
            tiled.pools(),
            tiled.drawers(),
            tiled.extentPins(),
            tiled.spares());
        final SolutionView cached = solved(Balancer.solveWithAlternatives(edit, cache, () -> false));
        final SolutionView fresh = solved(Balancer.solveWithAlternatives(edit));
        assertEquals(fresh.machineCounts, cached.machineCounts);
        assertEquals(fresh.edgeFlowsPerSecond, cached.edgeFlowsPerSecond);
    }

    private static SolutionView solved(final Balancer.Answer answer) {
        return assertInstanceOf(Balancer.Answer.Solved.class, answer).solution();
    }

    private static void same(final String plan, final int copy, final Map<UUID, Double> alone,
        final Map<UUID, Double> tiled) {
        for (final Map.Entry<UUID, Double> e : alone.entrySet()) {
            final UUID id = copy == 0 ? e.getKey()
                : UUID.nameUUIDFromBytes((copy + ":" + e.getKey()).getBytes(StandardCharsets.UTF_8));
            final Double got = tiled.get(id);
            assertTrue(
                got != null && Math.abs(got - e.getValue()) <= 1e-9 * Math.max(1, Math.abs(e.getValue())),
                plan + " copy " + copy + ": " + e.getKey() + " " + e.getValue() + " alone, " + got + " tiled");
        }
    }
}
