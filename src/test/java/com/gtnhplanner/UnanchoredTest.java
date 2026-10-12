package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Drawer.Kind;
import com.gtnhplanner.data.flowchart.Drawer.Link;
import com.gtnhplanner.data.flowchart.Drawer.Rule;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.balancer.Unanchored;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/**
 * Which parts of a plan nothing sets, on light_fuel: the chemical reactor (0) wired to the distillery (1, pinned) and
 * the electrolyzer (2), whose hydrogen feeds the reactor.
 */
class UnanchoredTest {

    @Test
    void aPinSetsEverythingWiredToIt() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        assertTrue(
            Unanchored.of(chart.graph())
                .isEmpty());
    }

    @Test
    void nothingSetLeavesEverythingUnset() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        chart.machine(1)
            .setMachineCountFixed(false);
        assertEquals(Set.of(id(chart, 0), id(chart, 1), id(chart, 2)), Unanchored.of(chart.graph()));
    }

    @Test
    void aPartWiredToNoPinStaysUnset() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        unwire(chart.graph(), id(chart, 2));
        assertEquals(Set.of(id(chart, 2)), Unanchored.of(chart.graph()));
    }

    @Test
    void aDrawerAskingForFlowSetsItsPart() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        unwire(chart.graph(), id(chart, 2));
        final Drawer any = drawer(chart.graph(), Rule.ANY, 0, id(chart, 2), 1);
        assertEquals(Set.of(id(chart, 2), any.getId()), Unanchored.of(chart.graph()));

        any.setTarget(Rule.AT_LEAST, 10);
        assertTrue(
            Unanchored.of(chart.graph())
                .isEmpty());
    }

    private static UUID id(final LoadedChart chart, final int machine) {
        return chart.machine(machine).id;
    }

    private static void unwire(final Graph graph, final UUID node) {
        for (final Edge e : graph.getEdges()
            .toArray(new Edge[0]))
            if (e.sourceNodeId.equals(node) || e.targetNodeId.equals(node)) graph.removeEdge(e.id);
    }

    private static Drawer drawer(final Graph graph, final Rule rule, final double rate, final UUID node,
        final int port) {
        final Drawer drawer = new Drawer(Kind.PRODUCT, "test:" + node + "/" + port);
        drawer.setTarget(rule, rate);
        graph.addDrawer(drawer);
        graph.linkDrawer(drawer.getId(), new Link(node, port));
        return drawer;
    }
}
