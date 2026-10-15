package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The task board's edits on a graph, pure: what the task view and its panel do to {@link Graph#tasks},
 * {@link Graph#taskItems} and {@link Graph#taskComments}. Callers wrap them in one undoable edit. Lists come back in
 * the board's order: a column's tasks and a checklist by their {@link OrderKeys order keys} (then id, where two keys
 * clash), comments oldest first. A move writes one new key between its neighbours'; only a list whose keys cannot take
 * one (clashing, or not keys at all) is numbered afresh.
 */
public final class TaskEdits {

    /** The plan's limits on lengths (docs/design/cloud-live.md). */
    public static final int TITLE_MAX = 120, TEXT_MAX = 4000, ITEM_MAX = 200, COMMENT_MAX = 2000;

    private TaskEdits() {}

    // region Reading

    @Nullable
    public static Tasks.Task task(final Graph g, final String id) {
        for (final Tasks.Task t : g.tasks) if (t.id()
            .equals(id)) return t;
        return null;
    }

    @Nullable
    public static Tasks.Item item(final Graph g, final String id) {
        for (final Tasks.Item i : g.taskItems) if (i.id()
            .equals(id)) return i;
        return null;
    }

    /** A column's tasks, top first. */
    public static List<Tasks.Task> column(final Graph g, final Tasks.Status status) {
        final List<Tasks.Task> out = new ArrayList<>();
        for (final Tasks.Task t : g.tasks) if (t.status() == status) out.add(t);
        out.sort(
            Comparator.comparing(Tasks.Task::order)
                .thenComparing(Tasks.Task::id));
        return out;
    }

    /** A task's checklist, top first. */
    public static List<Tasks.Item> items(final Graph g, final String taskId) {
        final List<Tasks.Item> out = new ArrayList<>();
        for (final Tasks.Item i : g.taskItems) if (i.taskId()
            .equals(taskId)) out.add(i);
        out.sort(
            Comparator.comparing(Tasks.Item::order)
                .thenComparing(Tasks.Item::id));
        return out;
    }

    /** A task's comments, oldest first. */
    public static List<Tasks.Comment> comments(final Graph g, final String taskId) {
        final List<Tasks.Comment> out = new ArrayList<>();
        for (final Tasks.Comment c : g.taskComments) if (c.taskId()
            .equals(taskId)) out.add(c);
        out.sort(
            Comparator.comparing(Tasks.Comment::at)
                .thenComparing(Tasks.Comment::id));
        return out;
    }

    /**
     * Adds to {@code out} each linked card's most pressing task of this board, by link: blocked first, then doing, to
     * do and done. A link with no plan is one to a card of the board itself.
     */
    public static void statusByLink(final Graph g, final Map<Tasks.Link, Tasks.Status> out) {
        final Map<String, Tasks.Status> status = new java.util.HashMap<>();
        for (final Tasks.Task t : g.tasks) {
            status.put(t.id(), t.status());
            for (final Tasks.Link l : t.links()) mark(g, l, t.status(), out);
        }
        // A task's machines are its cards too.
        for (final Tasks.Item i : g.taskItems) {
            final Tasks.Status s = status.get(i.taskId());
            if (s != null && i.link() != null) mark(g, i.link(), s, out);
        }
    }

    private static void mark(final Graph g, final Tasks.Link l, final Tasks.Status s,
        final Map<Tasks.Link, Tasks.Status> out) {
        out.merge(
            l.plan()
                .isEmpty() ? new Tasks.Link(g.planId(), l.node()) : l,
            s,
            (a, b) -> urgency(a) >= urgency(b) ? a : b);
    }

    /**
     * The cards a task links that none of its things names: an older task's cards, made before every card was a thing
     * of its own. Each once, in its order.
     */
    public static List<Tasks.Link> looseLinks(final Graph g, final String taskId) {
        final Tasks.Task t = task(g, taskId);
        if (t == null) return List.of();
        final Set<Tasks.Link> named = new HashSet<>();
        for (final Tasks.Item i : items(g, taskId)) if (i.link() != null) named.add(i.link());
        final List<Tasks.Link> out = new ArrayList<>();
        for (final Tasks.Link l : t.links()) if (named.add(l)) out.add(l);
        return out;
    }

    /**
     * Takes a thing off its task; a card it was also linked by (an older task's) goes with it, so it does not come
     * back as a loose card.
     */
    public static void removeThing(final Graph g, final String id) {
        final Tasks.Item item = item(g, id);
        if (item == null) return;
        removeItem(g, id);
        final Tasks.Task t = task(g, item.taskId());
        if (t == null || item.link() == null
            || !t.links()
                .contains(item.link()))
            return;
        final List<Tasks.Link> links = new ArrayList<>(t.links());
        links.remove(item.link());
        put(g, withLinks(t, links));
    }

    /** How much a status asks for attention: blocked most, done least. */
    public static int urgency(final Tasks.Status s) {
        return switch (s) {
            case BLOCKED -> 3;
            case DOING -> 2;
            case TODO -> 1;
            case DONE -> 0;
        };
    }

    // endregion

    // region Tasks

    /** Puts a task in place of the one with its id, or adds it. */
    public static void put(final Graph g, final Tasks.Task t) {
        for (int i = 0; i < g.tasks.size(); i++) if (g.tasks.get(i)
            .id()
            .equals(t.id())) {
                g.tasks.set(i, t);
                return;
            }
        g.tasks.add(t);
    }

    /** A new task at the bottom of a column. */
    public static Tasks.Task add(final Graph g, final String id, final String title, final Tasks.Status status,
        final List<Tasks.Link> links, @Nullable final String by, final String at) {
        final List<Tasks.Task> column = column(g, status);
        String order;
        try {
            order = OrderKeys.between(
                column.isEmpty() ? null
                    : column.get(column.size() - 1)
                        .order(),
                null);
        } catch (final IllegalArgumentException e) {
            renumber(g, column);
            order = OrderKeys.between(
                column.isEmpty() ? null
                    : task(
                        g,
                        column.get(column.size() - 1)
                            .id()).order(),
                null);
        }
        final Tasks.Task t = new Tasks.Task(
            id,
            clip(title, TITLE_MAX),
            null,
            status,
            order,
            List.of(),
            links,
            by,
            at,
            status == Tasks.Status.DONE ? at : null);
        g.tasks.add(t);
        return t;
    }

    /**
     * Moves a task to {@code index} among the other tasks of column {@code to} (0 the top). Into done stamps when, at
     * {@code at}; out of done clears it.
     */
    public static void move(final Graph g, final String id, final Tasks.Status to, final int index, final String at) {
        final Tasks.Task t = task(g, id);
        if (t == null) return;
        final List<Tasks.Task> others = column(g, to);
        others.removeIf(
            o -> o.id()
                .equals(id));
        final int i = Math.max(0, Math.min(others.size(), index));
        String order;
        try {
            order = strictBetween(
                i > 0 ? others.get(i - 1)
                    .order() : null,
                i < others.size() ? others.get(i)
                    .order() : null);
        } catch (final IllegalArgumentException e) {
            // The column's keys clash or are not keys: number it afresh with the task in its place.
            final List<String> keys = OrderKeys.sequence(others.size() + 1);
            for (int k = 0; k < others.size(); k++) {
                final Tasks.Task o = others.get(k);
                final String key = keys.get(k < i ? k : k + 1);
                if (!key.equals(o.order())) put(g, withOrder(o, key));
            }
            order = keys.get(i);
        }
        final String doneAt = to == Tasks.Status.DONE ? t.status() == Tasks.Status.DONE ? t.doneAt() : at : null;
        put(
            g,
            new Tasks.Task(
                t.id(),
                t.title(),
                t.text(),
                to,
                order,
                t.assignees(),
                t.links(),
                t.createdBy(),
                t.createdAt(),
                doneAt));
    }

    /** Takes a task off the board, with its checklist and comments. */
    public static void remove(final Graph g, final String id) {
        g.tasks.removeIf(
            t -> t.id()
                .equals(id));
        g.taskItems.removeIf(
            i -> i.taskId()
                .equals(id));
        g.taskComments.removeIf(
            c -> c.taskId()
                .equals(id));
    }

    public static Tasks.Task withTitle(final Tasks.Task t, final String title) {
        return new Tasks.Task(
            t.id(),
            clip(title, TITLE_MAX),
            t.text(),
            t.status(),
            t.order(),
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt());
    }

    /** The task with its text; blank text is none. */
    public static Tasks.Task withText(final Tasks.Task t, @Nullable final String text) {
        final String s = text == null || text.isBlank() ? null : clip(text, TEXT_MAX);
        return new Tasks.Task(
            t.id(),
            t.title(),
            s,
            t.status(),
            t.order(),
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt());
    }

    public static Tasks.Task withAssignees(final Tasks.Task t, final List<String> assignees) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            t.order(),
            assignees,
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt());
    }

    public static Tasks.Task withLinks(final Tasks.Task t, final List<Tasks.Link> links) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            t.order(),
            t.assignees(),
            links,
            t.createdBy(),
            t.createdAt(),
            t.doneAt());
    }

    private static Tasks.Task withOrder(final Tasks.Task t, final String order) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            order,
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt());
    }

    /**
     * A key between two neighbours of a move; throws when they are not in order (equal or reversed keys), so the
     * caller numbers the list afresh instead of guessing a place.
     */
    private static String strictBetween(@Nullable final String a, @Nullable final String b) {
        if (a != null && b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException(a + " >= " + b);
        return OrderKeys.between(a, b);
    }

    /** Numbers a column afresh, in its order: for a column whose last key nothing can follow. */
    private static void renumber(final Graph g, final List<Tasks.Task> column) {
        final List<String> keys = OrderKeys.sequence(column.size());
        for (int k = 0; k < column.size(); k++) put(g, withOrder(column.get(k), keys.get(k)));
    }

    // endregion

    // region Things

    /** Puts a thing in place of the one with its id, or adds it. */
    public static void putItem(final Graph g, final Tasks.Item item) {
        for (int i = 0; i < g.taskItems.size(); i++) if (g.taskItems.get(i)
            .id()
            .equals(item.id())) {
                g.taskItems.set(i, item);
                return;
            }
        g.taskItems.add(item);
    }

    /** A new line of words at the bottom of a task's things (how an older checklist was made). */
    public static Tasks.Item addItem(final Graph g, final String id, final String taskId, final String text,
        @Nullable final Tasks.Link link) {
        return addThing(g, id, taskId, "", text, link, null, null);
    }

    /**
     * A new thing at the bottom of a task's things, not done: a machine ({@code link}), a location ({@code place}) or
     * a screenshot ({@code picture}); {@code text} is the thing in words.
     */
    public static Tasks.Item addThing(final Graph g, final String id, final String taskId, final String kind,
        final String text, @Nullable final Tasks.Link link, @Nullable final Tasks.Place place,
        @Nullable final Tasks.Picture picture) {
        final List<Tasks.Item> list = items(g, taskId);
        String order;
        try {
            order = OrderKeys.between(
                list.isEmpty() ? null
                    : list.get(list.size() - 1)
                        .order(),
                null);
        } catch (final IllegalArgumentException e) {
            final List<String> keys = OrderKeys.sequence(list.size() + 1);
            for (int k = 0; k < list.size(); k++) putItem(g, withOrder(list.get(k), keys.get(k)));
            order = keys.get(list.size());
        }
        final Tasks.Item item = new Tasks.Item(
            id,
            taskId,
            kind,
            clip(text, ITEM_MAX),
            false,
            order,
            link,
            place,
            picture);
        g.taskItems.add(item);
        return item;
    }

    /** A task's things done and all of them: {done, total}. */
    public static int[] progress(final Graph g, final String taskId) {
        int done = 0, all = 0;
        for (final Tasks.Item i : g.taskItems) if (i.taskId()
            .equals(taskId)) {
                all++;
                if (i.done()) done++;
            }
        return new int[] { done, all };
    }

    /** A task's first screenshot, in its order; null when it has none. */
    @Nullable
    public static Tasks.Item firstPicture(final Graph g, final String taskId) {
        for (final Tasks.Item i : items(g, taskId)) if (Tasks.PICTURE.equals(i.shownKind())) return i;
        return null;
    }

    /** Moves a line to {@code index} among the other lines of its checklist (0 the top). */
    public static void moveItem(final Graph g, final String id, final int index) {
        final Tasks.Item item = item(g, id);
        if (item == null) return;
        final List<Tasks.Item> others = items(g, item.taskId());
        others.removeIf(
            o -> o.id()
                .equals(id));
        final int i = Math.max(0, Math.min(others.size(), index));
        String order;
        try {
            order = strictBetween(
                i > 0 ? others.get(i - 1)
                    .order() : null,
                i < others.size() ? others.get(i)
                    .order() : null);
        } catch (final IllegalArgumentException e) {
            final List<String> keys = OrderKeys.sequence(others.size() + 1);
            for (int k = 0; k < others.size(); k++) {
                final Tasks.Item o = others.get(k);
                final String key = keys.get(k < i ? k : k + 1);
                if (!key.equals(o.order())) putItem(g, withOrder(o, key));
            }
            order = keys.get(i);
        }
        putItem(g, withOrder(item, order));
    }

    public static void removeItem(final Graph g, final String id) {
        g.taskItems.removeIf(
            i -> i.id()
                .equals(id));
    }

    public static Tasks.Item withDone(final Tasks.Item i, final boolean done) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            done,
            i.order(),
            i.link(),
            i.place(),
            i.picture());
    }

    public static Tasks.Item withText(final Tasks.Item i, final String text) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            clip(text, ITEM_MAX),
            i.done(),
            i.order(),
            i.link(),
            i.place(),
            i.picture());
    }

    public static Tasks.Item withLink(final Tasks.Item i, @Nullable final Tasks.Link link) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            i.order(),
            link,
            i.place(),
            i.picture());
    }

    /**
     * A location with its label (blank is none); its words follow it, as {@link #placeText} spells them.
     */
    public static Tasks.Item withLabel(final Tasks.Item i, @Nullable final String label) {
        final Tasks.Place p = i.place();
        if (p == null) return i;
        final String l = label == null || label.isBlank() ? null : clip(label, LABEL_MAX);
        final Tasks.Place next = new Tasks.Place(p.x(), p.y(), p.z(), p.dim(), p.dimName(), p.world(), l);
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            placeText(next),
            i.done(),
            i.order(),
            i.link(),
            next,
            i.picture());
    }

    private static Tasks.Item withOrder(final Tasks.Item i, final String order) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            order,
            i.link(),
            i.place(),
            i.picture());
    }

    /** The most a location's label holds. */
    public static final int LABEL_MAX = 60;

    /** A location in words: its label and its block, "Ore field at 120 64 -30 in the Overworld". */
    public static String placeText(final Tasks.Place p) {
        final String at = p.x() + " "
            + p.y()
            + " "
            + p.z()
            + (p.dimName()
                .isEmpty() ? "" : " in " + p.dimName());
        return p.label() == null ? at : p.label() + " at " + at;
    }

    // endregion

    // region Where a spot is

    /**
     * Which way a spot lies from someone, in eight points of the compass as the game draws them (north is toward -z,
     * east toward +x); "" when they are on it.
     */
    public static String compass(final double dx, final double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "";
        // 0 east, counter-clockwise in the game's x and -z.
        final double deg = Math.toDegrees(Math.atan2(-dz, dx));
        final int sector = (int) Math.floorMod(Math.round(deg / 45), 8);
        return switch (sector) {
            case 0 -> "east";
            case 1 -> "north-east";
            case 2 -> "north";
            case 3 -> "north-west";
            case 4 -> "west";
            case 5 -> "south-west";
            case 6 -> "south";
            default -> "south-east";
        };
    }

    /** "12 m north-east", "here": how far a spot is and which way, across the ground. */
    public static String howFar(final double dx, final double dz) {
        final long d = Math.round(Math.sqrt(dx * dx + dz * dz));
        final String way = compass(dx, dz);
        if (d < 1 || way.isEmpty()) return "here";
        return (d >= 10_000 ? Math.round(d / 1000.0) + " km " : d + " m ") + way;
    }

    // endregion

    // region Comments

    public static Tasks.Comment addComment(final Graph g, final String id, final String taskId, final String by,
        final String byName, final String at, final String text) {
        final Tasks.Comment c = new Tasks.Comment(id, taskId, by, byName, at, clip(text, COMMENT_MAX));
        g.taskComments.add(c);
        return c;
    }

    public static void removeComment(final Graph g, final String id) {
        g.taskComments.removeIf(
            c -> c.id()
                .equals(id));
    }

    // endregion

    /** Text trimmed and kept within the plan's limit for it. */
    public static String clip(final String text, final int max) {
        final String t = text.strip();
        return t.length() <= max ? t
            : t.substring(0, max)
                .strip();
    }
}
