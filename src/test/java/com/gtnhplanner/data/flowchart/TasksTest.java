package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A plan's task board through a save, a plan code and an older save that has none. */
class TasksTest {

    static Graph board() {
        final Graph g = new Graph("Board");
        g.setPrimaryView(Tasks.TASKS);
        g.tasks.add(
            new Tasks.Task(
                "t1",
                "Build the line",
                "Two LV mixers first.",
                Tasks.Status.DOING,
                "a0",
                List.of("11111111-1111-4111-8111-111111111111"),
                List.of("n1"),
                "11111111-1111-4111-8111-111111111111",
                "2026-10-14T10:00:00.000Z",
                null));
        g.tasks.add(
            new Tasks.Task(
                "t2",
                "Done thing",
                null,
                Tasks.Status.DONE,
                "a1",
                List.of(),
                List.of(),
                null,
                null,
                "2026-10-14T11:00:00.000Z"));
        g.taskItems.add(new Tasks.Item("i1", "t1", "Place mixers", true, "a0", "n1"));
        g.taskItems.add(new Tasks.Item("i2", "t1", "Wire them", false, "a1", null));
        g.taskComments.add(
            new Tasks.Comment(
                "c1",
                "t1",
                "11111111-1111-4111-8111-111111111111",
                "ann",
                "2026-10-14T10:05:00.000Z",
                "On it"));
        return g;
    }

    @Test
    void aTaskBoardGoesThroughASaveAndAPlanCode() {
        final Graph g = board();
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(Tasks.TASKS, back.getPrimaryView());
        assertEquals(g.tasks, back.tasks);
        assertEquals(g.taskItems, back.taskItems);
        assertEquals(g.taskComments, back.taskComments);
    }

    @Test
    void theBoardIsWrittenAsThePlanSpellsIt() {
        final JsonObject root = new JsonObject();
        Tasks.write(board(), root);
        assertEquals(
            "{\"id\":\"t2\",\"title\":\"Done thing\",\"status\":\"done\",\"order\":\"a1\",\"doneAt\":\"2026-10-14T11:00:00.000Z\"}",
            root.getAsJsonArray("tasks")
                .get(1)
                .toString());
        assertEquals(
            "{\"id\":\"i2\",\"taskId\":\"t1\",\"text\":\"Wire them\",\"done\":false,\"order\":\"a1\"}",
            root.getAsJsonArray("taskItems")
                .get(1)
                .toString());
        assertEquals(
            "tasks",
            root.get("primaryView")
                .getAsString());
    }

    @Test
    void anOlderSaveLoadsWithNoBoard() {
        final Graph plain = Serializer.decode(Serializer.encode(new Graph("Plain")));
        assertNull(plain.getPrimaryView());
        assertTrue(plain.tasks.isEmpty() && plain.taskItems.isEmpty() && plain.taskComments.isEmpty());

        // A task the game cannot read (no title, an unknown status) is skipped, not the plan.
        final Graph g = new Graph("x");
        Tasks.read(
            new JsonParser()
                .parse(
                    "{\"primaryView\":\"sideways\",\"tasks\":[{\"id\":\"a\",\"status\":\"todo\",\"order\":\"a0\"},"
                        + "{\"id\":\"b\",\"title\":\"B\",\"status\":\"later\",\"order\":\"a1\"},"
                        + "{\"id\":\"c\",\"title\":\"C\",\"status\":\"blocked\",\"order\":\"a2\"}]}")
                .getAsJsonObject(),
            g);
        assertNull(g.getPrimaryView());
        assertEquals(1, g.tasks.size());
        assertEquals(
            Tasks.Status.BLOCKED,
            g.tasks.get(0)
                .status());
    }
}
