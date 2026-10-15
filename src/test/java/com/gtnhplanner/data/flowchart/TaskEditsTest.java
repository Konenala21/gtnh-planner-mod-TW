package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The task board's edits: columns in order, moves that write one key, checklists, and the cards' status dots. */
class TaskEditsTest {

    private static final String AT = "2026-10-14T12:00:00.000Z";

    private static List<String> ids(final List<Tasks.Task> tasks) {
        return tasks.stream()
            .map(Tasks.Task::id)
            .toList();
    }

    @Test
    void tasksAddToTheBottomAndMoveBetweenTheirNeighbours() {
        final Graph g = new Graph("Board");
        TaskEdits.add(g, "a", "A", Tasks.Status.TODO, List.of(), null, AT);
        TaskEdits.add(g, "b", "B", Tasks.Status.TODO, List.of(), null, AT);
        TaskEdits.add(g, "c", "C", Tasks.Status.TODO, List.of(), null, AT);
        assertEquals(List.of("a", "b", "c"), ids(TaskEdits.column(g, Tasks.Status.TODO)));

        // c to the top: only c changes.
        final String bOrder = TaskEdits.task(g, "b")
            .order();
        TaskEdits.move(g, "c", Tasks.Status.TODO, 0, AT);
        assertEquals(List.of("c", "a", "b"), ids(TaskEdits.column(g, Tasks.Status.TODO)));
        assertEquals(
            bOrder,
            TaskEdits.task(g, "b")
                .order());

        // a to done, then back to doing: done stamps when, leaving clears it.
        TaskEdits.move(g, "a", Tasks.Status.DONE, 0, AT);
        assertEquals(
            AT,
            TaskEdits.task(g, "a")
                .doneAt());
        assertEquals(List.of("c", "b"), ids(TaskEdits.column(g, Tasks.Status.TODO)));
        TaskEdits.move(g, "a", Tasks.Status.DOING, 5, AT);
        assertNull(
            TaskEdits.task(g, "a")
                .doneAt());
        assertEquals(List.of("a"), ids(TaskEdits.column(g, Tasks.Status.DOING)));
    }

    @Test
    void aColumnWhoseKeysClashIsNumberedAfresh() {
        final Graph g = new Graph("Board");
        for (final String id : List.of("x", "y", "z"))
            g.tasks.add(new Tasks.Task(id, id, null, Tasks.Status.TODO, "a1", List.of(), List.of(), null, null, null));
        // Between x and y, both "a1": the column is numbered again with z in its place.
        TaskEdits.move(g, "z", Tasks.Status.TODO, 1, AT);
        assertEquals(List.of("x", "z", "y"), ids(TaskEdits.column(g, Tasks.Status.TODO)));
        for (final Tasks.Task t : g.tasks) assertTrue(OrderKeys.valid(t.order()), t.order());

        // A column of keys from somewhere else (none at all) takes new ones too.
        final Graph h = new Graph("Old");
        h.tasks.add(new Tasks.Task("p", "P", null, Tasks.Status.DOING, "", List.of(), List.of(), null, null, null));
        TaskEdits.add(h, "q", "Q", Tasks.Status.DOING, List.of(), null, AT);
        assertEquals(List.of("p", "q"), ids(TaskEdits.column(h, Tasks.Status.DOING)));
    }

    @Test
    void checklistsAndCommentsGoWithTheirTask() {
        final Graph g = new Graph("Board");
        TaskEdits.add(g, "t", "T", Tasks.Status.TODO, List.of("n1"), "me", AT);
        TaskEdits.addItem(g, "i1", "t", "One", null);
        TaskEdits.addItem(g, "i2", "t", "Two", "n1");
        TaskEdits.addItem(g, "i3", "t", "Three", null);
        TaskEdits.moveItem(g, "i3", 0);
        assertEquals(
            List.of("i3", "i1", "i2"),
            TaskEdits.items(g, "t")
                .stream()
                .map(Tasks.Item::id)
                .toList());
        TaskEdits.putItem(g, TaskEdits.withDone(TaskEdits.item(g, "i1"), true));
        assertTrue(
            TaskEdits.item(g, "i1")
                .done());
        TaskEdits.addComment(g, "c2", "t", "me", "Me", "2026-10-14T12:05:00.000Z", "later");
        TaskEdits.addComment(g, "c1", "t", "me", "Me", AT, "first");
        assertEquals(
            List.of("c1", "c2"),
            TaskEdits.comments(g, "t")
                .stream()
                .map(Tasks.Comment::id)
                .toList());

        // Through a save and back, as undo takes it.
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(g.taskItems, back.taskItems);
        assertEquals(g.taskComments, back.taskComments);

        TaskEdits.remove(g, "t");
        assertTrue(g.tasks.isEmpty() && g.taskItems.isEmpty() && g.taskComments.isEmpty());
    }

    @Test
    void aCardShowsItsMostPressingTask() {
        final Graph g = new Graph("Board");
        TaskEdits.add(g, "a", "A", Tasks.Status.DONE, List.of("n1", "n2"), null, AT);
        TaskEdits.add(g, "b", "B", Tasks.Status.BLOCKED, List.of("n2"), null, AT);
        TaskEdits.add(g, "c", "C", Tasks.Status.DOING, List.of("n1"), null, AT);
        assertEquals(Map.of("n1", Tasks.Status.DOING, "n2", Tasks.Status.BLOCKED), TaskEdits.statusByNode(g));
    }

    @Test
    void textKeepsToThePlansLimits() {
        assertEquals("abc", TaskEdits.clip("  abc  ", 10));
        assertEquals(
            TaskEdits.TITLE_MAX,
            TaskEdits.clip("x".repeat(500), TaskEdits.TITLE_MAX)
                .length());
        final Tasks.Task t = new Tasks.Task(
            "t",
            "T",
            "words",
            Tasks.Status.TODO,
            "a0",
            List.of(),
            List.of(),
            null,
            null,
            null);
        assertNull(
            TaskEdits.withText(t, "   ")
                .text());
    }
}
