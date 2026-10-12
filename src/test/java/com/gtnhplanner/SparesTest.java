package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.Balancer.Answer;
import com.gtnhplanner.data.flowchart.balancer.External;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.Spares;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/**
 * Leftovers and who bears them ({@link Spares}), on the two charts made for it.
 *
 * <p>
 * symmetric_choice: two furnaces pinned at one make 10 ingots/s each, two benders turn them into plates and a pinned
 * assembler takes 15 plates/s, so 5/s is spare at alpha ingot, beta ingot or plate (machines 0 alpha furnace, 1 beta
 * furnace, 2 alpha bender, 3 beta bender, 4 assembler). excess_choice: a pinned centrifuge makes 780 nitrogen/s, coke
 * oven A (free) turns 1000 nitrogen into 20 charcoal a craft and a pinned oven B takes 5 charcoal/s, so the spare is
 * nitrogen (oven A at 0.25) or charcoal (oven A at 0.78).
 */
class SparesTest {

    private static final double EPS = 1e-6;

    private static SolutionView solve(final Graph graph) {
        final Answer answer = Balancer.solveWithAlternatives(SolveInput.of(graph, BalanceMode.AUTO, null));
        assertTrue(answer instanceof Answer.Solved, "solved: " + answer);
        return ((Answer.Solved) answer).solution();
    }

    private static Spares.Leftover only(final SolutionView view) {
        assertEquals(
            1,
            view.spares()
                .size(),
            "one leftover");
        return view.spares()
            .get(0);
    }

    private static Spares.Bearer bearerOn(final Spares.Leftover leftover, final UUID node) {
        for (final Spares.Bearer b : leftover.bearers()) if (b.anchor()
            .nodeId()
            .equals(node)) return b;
        return null;
    }

    private static double sinkAt(final SolutionView view, final UUID node) {
        double total = 0;
        for (final External e : view.gatedSinks) if (e.port()
            .nodeId()
            .equals(node)) total += e.ratePerSecond();
        return total;
    }

    @Test
    void theSpareHasThreePlacesAndTheSolverPicksOne() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Spares.Leftover leftover = only(solve(chart.graph()));

        assertFalse(leftover.importing());
        assertFalse(leftover.custom());
        assertEquals(
            3,
            leftover.bearers()
                .size());
        assertEquals(
            1,
            leftover.bearers()
                .stream()
                .filter(Spares.Bearer::solverPick)
                .count());
        double shares = 0;
        for (final Spares.Bearer b : leftover.bearers()) {
            assertEquals(5.0, b.max(), EPS, "each could bear all 5/s");
            shares += b.share();
        }
        assertEquals(1.0, shares, EPS);
    }

    @Test
    void anEvenSplitRunsBothBendersAtThreeQuarters() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Spares.Leftover leftover = only(solve(chart.graph()));
        final Spares.Bearer alpha = bearerOn(leftover, chart.machine(0).id),
            beta = bearerOn(leftover, chart.machine(1).id);
        assertNotNull(alpha);
        assertNotNull(beta);

        chart.graph()
            .setSpareSplit(leftover.key(), Map.of(alpha.key(), 0.5, beta.key(), 0.5));
        final SolutionView view = solve(chart.graph());

        assertEquals(0.75, view.machineCounts.get(chart.machine(2).id), EPS, "alpha bender");
        assertEquals(0.75, view.machineCounts.get(chart.machine(3).id), EPS, "beta bender");
        assertEquals(2.5, sinkAt(view, chart.machine(0).id), EPS, "alpha ingot spare");
        assertEquals(2.5, sinkAt(view, chart.machine(1).id), EPS, "beta ingot spare");
        final Spares.Leftover now = only(view);
        assertTrue(now.custom());
        assertEquals(0.5, bearerOn(now, chart.machine(0).id).share(), EPS);
        assertEquals(2.5, bearerOn(now, chart.machine(0).id).amount(), EPS);
    }

    @Test
    void sharesAreFractionsWhateverTheyAddUpTo() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Spares.Leftover leftover = only(solve(chart.graph()));
        final Spares.Bearer alpha = bearerOn(leftover, chart.machine(0).id);
        Spares.Bearer plate = null;
        for (final Spares.Bearer b : leftover.bearers()) if (b != alpha && !b.anchor()
            .nodeId()
            .equals(chart.machine(1).id)) plate = b;
        assertNotNull(plate);

        // 1 : 4, written as 2 and 8: a fifth on alpha ingot, the rest as plates.
        chart.graph()
            .setSpareSplit(leftover.key(), Map.of(alpha.key(), 2.0, plate.key(), 8.0));
        final SolutionView view = solve(chart.graph());

        assertEquals(1.0, sinkAt(view, chart.machine(0).id), EPS, "alpha ingot spare");
        assertEquals(0.0, sinkAt(view, chart.machine(1).id), EPS, "no beta ingot spare");
        assertEquals(4.0, sinkAt(view, chart.machine(2).id) + sinkAt(view, chart.machine(3).id), EPS, "plates spare");
        assertEquals(0.9, view.machineCounts.get(chart.machine(2).id), EPS, "alpha bender");
        assertEquals(1.0, view.machineCounts.get(chart.machine(3).id), EPS, "beta bender");
    }

    @Test
    void mixedUnitsSplitByTheLeftoverNotByTheAmount() {
        final LoadedChart chart = GtnhFlowLoader.load("excess_choice");
        final Spares.Leftover leftover = only(solve(chart.graph()));
        assertEquals(
            2,
            leftover.bearers()
                .size());
        final Spares.Bearer nitrogen = bearerOn(leftover, chart.machine(0).id),
            charcoal = bearerOn(leftover, chart.machine(1).id);
        assertNotNull(nitrogen);
        assertNotNull(charcoal);
        assertEquals(530, nitrogen.max(), 1e-4);
        assertEquals(10.6, charcoal.max(), 1e-4);

        chart.graph()
            .setSpareSplit(leftover.key(), Map.of(nitrogen.key(), 0.5, charcoal.key(), 0.5));
        final SolutionView view = solve(chart.graph());

        assertEquals(265, sinkAt(view, chart.machine(0).id), 1e-4, "half the nitrogen");
        assertEquals(5.3, sinkAt(view, chart.machine(1).id), 1e-4, "half the charcoal");
    }

    @Test
    void aSplitIsSavedWithThePlan() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Spares.Leftover leftover = only(solve(chart.graph()));
        final Spares.Bearer alpha = bearerOn(leftover, chart.machine(0).id),
            beta = bearerOn(leftover, chart.machine(1).id);
        chart.graph()
            .setSpareSplit(leftover.key(), Map.of(alpha.key(), 0.3, beta.key(), 0.7));

        // Only the split is compared: the harness's ingredients cannot be rebuilt by the Serializer, so a reloaded
        // corpus chart has no ports to solve against (as in AlternativesTest). Shares are keyed by node ids and port
        // indices, which a save keeps.
        final Graph reloaded = Serializer.decode(Serializer.encode(chart.graph()));

        assertEquals(chart.graph().spareShares, reloaded.spareShares);
    }

    @Test
    void clearingTheSplitGivesTheSolversOwnBack() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final SolutionView before = solve(chart.graph());
        final Spares.Leftover leftover = only(before);
        final Spares.Bearer alpha = bearerOn(leftover, chart.machine(0).id),
            beta = bearerOn(leftover, chart.machine(1).id);
        chart.graph()
            .setSpareSplit(leftover.key(), Map.of(alpha.key(), 0.5, beta.key(), 0.5));
        solve(chart.graph());

        chart.graph()
            .setSpareSplit(leftover.key(), null);
        final SolutionView after = solve(chart.graph());

        assertFalse(only(after).custom());
        assertEquals(before.machineCounts, after.machineCounts);
    }
}
