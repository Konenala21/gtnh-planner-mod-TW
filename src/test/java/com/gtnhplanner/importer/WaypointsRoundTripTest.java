package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Waypoints;
import com.gtnhplanner.library.PlanExport;

/**
 * A wire's steering dots (the website's edge waypoints) come in with a website plan, on the router's lines and moved
 * as the cards were, and go out with a posted one on the website's grid, where they were.
 */
class WaypointsRoundTripTest {

    /** A website plan whose cell wire into its buffer is steered through one dot, at (100, -220). */
    private static final String STEERED = "pa-cell-loop-plan.json";

    private static FakeGame game() {
        final FakeGame game = new FakeGame();
        game.routed = true;
        game.generators = true;
        return game;
    }

    /** Every steered wire of a board: its dots, and the card at its source end (a drawer's link: the card). */
    private record Steered(Node card, List<int[]> dots) {}

    private static List<Steered> steered(final Graph g) {
        final List<Steered> out = new ArrayList<>();
        for (final Edge e : g.getEdges())
            if (!e.waypoints.isEmpty()) out.add(new Steered(g.nodes.get(e.sourceNodeId), e.waypoints));
        for (final Drawer d : g.getDrawers()) for (final Drawer.Held h : d.held()) {
            if (!h.link()
                .waypoints()
                .isEmpty())
                out.add(
                    new Steered(
                        g.nodes.get(
                            h.link()
                                .nodeId()),
                        h.link()
                            .waypoints()));
        }
        return out;
    }

    /** The plan's least x and y over its cards, drawers and notes: what the import measures the board from. */
    private static double[] origin(final FfPlan plan) {
        double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
        for (final FfPlan.FfNode n : plan.nodes()) {
            x = Math.min(x, n.x());
            y = Math.min(y, n.y());
        }
        for (final FfPlan.FfStorage s : plan.storages()) {
            x = Math.min(x, s.x());
            y = Math.min(y, s.y());
        }
        for (final FfPlan.FfNote n : plan.textNotes()) {
            x = Math.min(x, n.x());
            y = Math.min(y, n.y());
        }
        return new double[] { x, y };
    }

    @Test
    void aWebsitePlansDotsComeInAndGoBackOut() {
        final FakeGame game = game();
        final String json = FakeGame.fixture(STEERED);
        final FfPlan plan = FfPlanParser.parse(json);
        final Graph g = FfImport.importFromText(json, game, game)
            .graph();

        // In: on the wire into the cell buffer, on the router's line through the dot's cell, moved as the cards were.
        final List<Steered> in = steered(g);
        assertEquals(1, in.size(), "one steered wire");
        final double[] o = origin(plan);
        final int[] dot = in.getFirst()
            .dots()
            .getFirst();
        assertEquals(Waypoints.onLine((int) Math.round(100 - o[0]) + FfConverter.MARGIN), dot[0]);
        assertEquals(Waypoints.onLine((int) Math.round(-220 - o[1]) + FfConverter.MARGIN), dot[1]);
        final Drawer cells = g.getDrawers()
            .stream()
            .filter(Drawer::isBuffer)
            .findFirst()
            .orElseThrow();
        assertEquals(
            1,
            cells.getLinks()
                .stream()
                .filter(
                    l -> !l.waypoints()
                        .isEmpty())
                .count(),
            "a maker's link into the buffer carries it");

        // Out: the posted plan's wire has it on the website's grid, where it stood against its card.
        final JsonObject posted = PlanExport.project(g, g.getName(), LibraryRoundTripTest.world(game, g));
        JsonObject edge = null;
        for (final JsonElement e : posted.getAsJsonArray("edges")) if (e.getAsJsonObject()
            .has("waypoints")) {
                assertEquals(null, edge, "one steered wire posted");
                edge = e.getAsJsonObject();
            }
        assertNotNull(edge);
        final JsonObject point = edge.getAsJsonArray("waypoints")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            Waypoints.toSite(dot[0]),
            point.get("x")
                .getAsInt());
        assertEquals(
            Waypoints.toSite(dot[1]),
            point.get("y")
                .getAsInt());

        // And opened again, the dot stands where it did against its card.
        final Graph again = FfImport.importFromText(posted.toString(), game, game)
            .graph();
        final List<Steered> back = steered(again);
        assertEquals(1, back.size());
        final Node card = in.getFirst()
            .card(),
            cardAgain = back.getFirst()
                .card();
        final int[] dotAgain = back.getFirst()
            .dots()
            .getFirst();
        assertEquals(dot[0] - card.x, dotAgain[0] - cardAgain.x);
        assertEquals(dot[1] - card.y, dotAgain[1] - cardAgain.y);
    }
}
