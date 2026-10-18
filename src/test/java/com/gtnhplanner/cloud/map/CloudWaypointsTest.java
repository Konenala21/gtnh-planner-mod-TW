package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;

/**
 * A wire's steering dots in a shared plan (the website's edge waypoints): the website's come onto the board on the
 * router's lines and go back as they were; the game's go out on the website's grid and come back to the pixel while
 * the website leaves the wire alone; a dot the website moved comes in where it put it.
 */
class CloudWaypointsTest {

    /** Every dot on the board's wires, edges then drawer links, as "x,y". */
    private static List<String> dots(final Graph g) {
        final List<String> out = new ArrayList<>();
        for (final Edge e : g.getEdges()) for (final int[] p : e.waypoints) out.add(p[0] + "," + p[1]);
        for (final Drawer d : g.getDrawers()) for (final Drawer.Held h : d.held()) for (final int[] p : h.link()
            .waypoints()) out.add(p[0] + "," + p[1]);
        return out;
    }

    /** The plan's wires that are steered. */
    private static List<JsonObject> steered(final JsonObject plan) {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonElement e : plan.getAsJsonArray("edges")) if (e.getAsJsonObject()
            .has("waypoints")) out.add(e.getAsJsonObject());
        return out;
    }

    @Test
    void aWebsitePlansDotsComeOnAndGoBackAsTheyWere() {
        final JsonObject plan = Boards.website("pa-cell-loop-plan");
        final TestGame game = new TestGame();
        final CloudPlans.Read r = CloudPlans.read(plan, game);
        // The website's (100, -220), on the line through its cell.
        assertEquals(List.of("105,-215"), dots(r.graph()));
        assertEquals(
            List.of(),
            CloudPlans.diff(
                plan,
                CloudPlans.write(r.graph(), game, plan, r.basis())
                    .plan()),
            "written straight back, the plan is as it was");
    }

    @Test
    void theGamesDotsGoOutOnTheGridAndComeBackToThePixel() {
        final Graph g = Boards.dress(Boards.corpus("platline"));
        final TestGame game = new TestGame();
        final Edge e = g.getEdges()
            .iterator()
            .next();
        e.waypoints = List.of(new int[] { 315, 225 }, new int[] { 455, 225 });
        final JsonObject plan = CloudPlans.write(g, game, null, null)
            .plan();
        final List<JsonObject> wires = steered(plan);
        assertEquals(1, wires.size());
        assertEquals(
            "[{\"x\":320,\"y\":220},{\"x\":460,\"y\":220}]",
            wires.getFirst()
                .get("waypoints")
                .toString(),
            "the website's own, on its grid");
        final CloudPlans.Read back = CloudPlans.read(plan, game);
        assertEquals(List.of("315,225", "455,225"), dots(back.graph()), "to the pixel while the website left it");

        // The website drags the first dot (a new plan, as a batch from it makes): it comes in where it was put.
        final JsonObject dragged = com.gtnhplanner.cloud.sync.LiveJson.copy(plan);
        steered(dragged).getFirst()
            .add(
                "waypoints",
                new JsonParser().parse("[{\"x\":400,\"y\":400},{\"x\":460,\"y\":220}]")
                    .getAsJsonArray());
        final CloudPlans.Read edited = CloudPlans.read(dragged, game, back);
        assertEquals(List.of("405,405", "465,225"), dots(edited.graph()));
        // And takes them all off: none.
        final JsonObject cleared = com.gtnhplanner.cloud.sync.LiveJson.copy(dragged);
        steered(cleared).getFirst()
            .remove("waypoints");
        assertEquals(
            List.of(),
            dots(
                CloudPlans.read(cleared, game, edited)
                    .graph()));
        assertNotNull(back.basis());
    }
}
