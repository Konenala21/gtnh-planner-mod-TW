package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Tasks;

/**
 * What a shared plan links by id: a task's cards (the plan's ids in the plan, the board's on the board) and a card's
 * places in the world (kept by world: each game shows its own world's and carries the rest).
 */
class CloudLinksTest {

    private static JsonObject json(final String text) {
        return new JsonParser().parse(text)
            .getAsJsonObject();
    }

    /** The first card of a website plan whose id is the website's own (not a UUID) and that comes onto the board. */
    private static String websiteCard(final JsonObject plan, final Graph board) {
        for (final JsonElement e : plan.getAsJsonArray("nodes")) {
            final String id = e.getAsJsonObject()
                .get("id")
                .getAsString();
            if (board.nodes.containsKey(PlanIds.uuid(id)) && !PlanIds.uuid(id)
                .toString()
                .equals(id)) return id;
        }
        throw new AssertionError("no website card on the board");
    }

    @Test
    void aWebsiteTaskLinkingAWebsiteCardShowsLinkedInTheGame() {
        for (final String name : List.of("biodiesel-demo", "pa-cell-loop-plan")) {
            final JsonObject plan = Boards.website(name);
            final TestGame game = new TestGame();
            final String card = websiteCard(
                plan,
                CloudPlans.read(plan, game)
                    .graph());
            final JsonArray tasks = new JsonArray(), items = new JsonArray();
            tasks.add(
                json(
                    "{\"id\":\"t1\",\"title\":\"Build it\",\"status\":\"todo\",\"order\":\"a0\",\"nodeIds\":[\"" + card
                        + "\",\"node-gone\"]}"));
            items.add(
                json(
                    "{\"id\":\"i1\",\"taskId\":\"t1\",\"text\":\"Place it\",\"done\":false,\"order\":\"a0\","
                        + "\"nodeId\":\""
                        + card
                        + "\"}"));
            plan.add("tasks", tasks);
            plan.add("taskItems", items);

            final CloudPlans.Read r = CloudPlans.read(plan, game);
            final Tasks.Task t = r.graph().tasks.getFirst();
            final String onBoard = PlanIds.uuid(card)
                .toString();
            assertEquals(List.of(onBoard, "node-gone"), t.nodeIds(), name + ": the card by the board's id");
            assertTrue(
                r.graph().nodes.containsKey(UUID.fromString(onBoard)),
                name + ": the link finds the card on the board");
            assertEquals(
                onBoard,
                r.graph().taskItems.getFirst()
                    .nodeId(),
                name);
            // Written straight back, the plan is as it was: the website's ids, the unknown one too.
            assertEquals(
                List.of(),
                CloudPlans.diff(
                    plan,
                    CloudPlans.write(r.graph(), game, plan, r.basis())
                        .plan()),
                name + ": round trip");
        }
    }

    @Test
    void linkingAWebsiteCardInTheGameWritesTheWebsiteId() {
        final JsonObject plan = Boards.website("biodiesel-demo");
        final TestGame game = new TestGame();
        final CloudPlans.Read r = CloudPlans.read(plan, game);
        final String card = websiteCard(plan, r.graph());
        final Node node = r.graph().nodes.get(PlanIds.uuid(card));
        // The board's link action names the card by its id on the board.
        r.graph().tasks.add(
            new Tasks.Task(
                "t9",
                "Feed it",
                null,
                Tasks.Status.DOING,
                "b0",
                List.of(),
                List.of(node.id.toString()),
                null,
                null,
                null));
        r.graph().taskItems.add(new Tasks.Item("i9", "t9", "Pipe it", false, "a0", node.id.toString()));
        final JsonObject written = CloudPlans.write(r.graph(), game, plan, r.basis())
            .plan();
        JsonObject task = null, item = null;
        for (final JsonElement e : written.getAsJsonArray("tasks")) if ("t9".equals(
            e.getAsJsonObject()
                .get("id")
                .getAsString()))
            task = e.getAsJsonObject();
        for (final JsonElement e : written.getAsJsonArray("taskItems")) if ("i9".equals(
            e.getAsJsonObject()
                .get("id")
                .getAsString()))
            item = e.getAsJsonObject();
        assertNotNull(task);
        assertNotNull(item);
        assertEquals(
            card,
            task.getAsJsonArray("nodeIds")
                .get(0)
                .getAsString(),
            "the website's id");
        assertEquals(
            card,
            item.get("nodeId")
                .getAsString());
        // And read again, the board has its own id back.
        final CloudPlans.Read again = CloudPlans.read(written, game);
        assertEquals(
            List.of(node.id.toString()),
            again.graph().tasks.stream()
                .filter(
                    t -> t.id()
                        .equals("t9"))
                .findFirst()
                .orElseThrow()
                .nodeIds());
    }

    @Test
    void aGameCardsTaskLinksRoundTripExactly() {
        final Graph g = Boards.dress(Boards.corpus("platline"));
        final TestGame game = new TestGame().knowing(g);
        final JsonObject plan = CloudPlans.write(g, game, null, null)
            .plan();
        final String first = g.tasks.getFirst()
            .nodeIds()
            .getFirst();
        final CloudPlans.Read r = CloudPlans.read(plan, game);
        assertEquals(
            first,
            r.graph().tasks.getFirst()
                .nodeIds()
                .getFirst(),
            "a game card's id is its UUID in both");
        assertEquals(
            List.of(),
            CloudPlans.diff(
                plan,
                CloudPlans.write(r.graph(), game, plan, r.basis())
                    .plan()));
    }

    @Test
    void placementsAreKeptByWorld() {
        final Graph g = Boards.corpus("sulfuric-acid");
        final Node card = g.getNodes()
            .iterator()
            .next();
        card.worldLinks.add(new int[] { 0, 1, 64, 2, 1 });
        final TestGame inA = new TestGame().knowing(g), inB = new TestGame().knowing(g);
        inA.world = "world:a";
        inB.world = "server:b";
        final JsonObject plan = CloudPlans.write(g, inA, null, null)
            .plan();

        // In another world the card shows none of the first world's places, and keeps them.
        final CloudPlans.Read b = CloudPlans.read(plan, inB);
        final Node there = b.graph().nodes.get(card.id);
        assertTrue(there.worldLinks.isEmpty(), "another world's places are not this one's");
        assertArrayEquals(
            new int[] { 0, 1, 64, 2, 1 },
            there.elsewhere.get("world:a")
                .getFirst());
        assertEquals(
            List.of(),
            CloudPlans.diff(
                plan,
                CloudPlans.write(b.graph(), inB, plan, b.basis())
                    .plan()),
            "read in another world and written back, nothing changed");

        // Placed there too: both worlds' places in the plan.
        there.worldLinks.add(new int[] { -1, 5, 70, 5, 0 });
        final JsonObject both = CloudPlans.write(b.graph(), inB, plan, b.basis())
            .plan();
        final Node back = CloudPlans.read(both, inA)
            .graph().nodes.get(card.id);
        assertEquals(1, back.worldLinks.size(), "back in the first world: its own place only");
        assertArrayEquals(new int[] { 0, 1, 64, 2, 1 }, back.worldLinks.getFirst());
        assertArrayEquals(
            new int[] { -1, 5, 70, 5, 0 },
            back.elsewhere.get("server:b")
                .getFirst());
    }
}
