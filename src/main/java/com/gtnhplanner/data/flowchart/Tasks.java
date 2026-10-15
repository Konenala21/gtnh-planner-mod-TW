package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A plan's task board (docs/design/cloud-live.md, "Plan document additions"): tasks, their checklist lines and their
 * comments, each its own thing so two people can change two of them at once. Any plan can hold tasks; one whose
 * {@link Graph#getPrimaryView() primary view} is {@link #TASKS} opens on them. Saved and written exactly as the shared
 * plan spells them, so a plan's tasks are the same JSON in a save, a plan code and a cloud plan.
 */
public final class Tasks {

    /** The primary views: the board, or the task board. */
    public static final String PLAN = "plan", TASKS = "tasks";

    /** A task's column. */
    public enum Status {

        TODO("todo"),
        DOING("doing"),
        BLOCKED("blocked"),
        DONE("done");

        public final String wire;

        Status(final String wire) {
            this.wire = wire;
        }

        /** The status a plan spells so; null for one it does not know. */
        @Nullable
        public static Status of(@Nullable final String wire) {
            for (final Status s : values()) if (s.wire.equals(wire)) return s;
            return null;
        }
    }

    /**
     * A task. {@code order} is a fractional index: tasks sort by it inside a column, and a move writes one new key
     * between two others.
     *
     * @param assignees account ids of the plan's members working on it
     * @param links     the cards it is about, each in the plan it is on (a task board is a plan of its own, so its
     *                  cards are on other plans)
     */
    public record Task(String id, String title, @Nullable String text, Status status, String order,
        List<String> assignees, List<Link> links, @Nullable String createdBy, @Nullable String createdAt,
        @Nullable String doneAt) {

        public Task {
            assignees = List.copyOf(assignees);
            links = List.copyOf(links);
        }
    }

    /**
     * A card on a plan: {@code plan} is the plan's id (its cloud doc key, or the local id of a plan on this computer,
     * {@link Graph#planId()}) and {@code node} the card's id in that plan (the plan's own spelling of it, which
     * {@code cloud/map/PlanIds.uuid} turns into the game's). An empty {@code plan} is the plan holding the task: how an
     * older save spelled it ({@code nodeIds}, {@code nodeId}).
     */
    public record Link(String plan, String node) {}

    /** One checklist line of a task. */
    public record Item(String id, String taskId, String text, boolean done, String order, @Nullable Link link) {}

    /** A comment on a task; {@code at} is the server's time stamp. */
    public record Comment(String id, String taskId, String by, String byName, String at, String text) {}

    private Tasks() {}

    // region JSON, as the plan spells it

    public static JsonObject toJson(final Task t) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("title", t.title());
        if (t.text() != null) o.addProperty("text", t.text());
        o.addProperty("status", t.status().wire);
        o.addProperty("order", t.order());
        if (!t.assignees()
            .isEmpty()) o.add("assignees", strings(t.assignees()));
        if (!t.links()
            .isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Link l : t.links()) a.add(toJson(l));
            o.add("links", a);
        }
        if (t.createdBy() != null) o.addProperty("createdBy", t.createdBy());
        if (t.createdAt() != null) o.addProperty("createdAt", t.createdAt());
        if (t.doneAt() != null) o.addProperty("doneAt", t.doneAt());
        return o;
    }

    public static JsonObject toJson(final Item i) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", i.id());
        o.addProperty("taskId", i.taskId());
        o.addProperty("text", i.text());
        o.addProperty("done", i.done());
        o.addProperty("order", i.order());
        if (i.link() != null) o.add("link", toJson(i.link()));
        return o;
    }

    public static JsonObject toJson(final Link l) {
        final JsonObject o = new JsonObject();
        o.addProperty("plan", l.plan());
        o.addProperty("node", l.node());
        return o;
    }

    /** A link as a plan spells it; null when it lacks its card. */
    @Nullable
    public static Link link(final JsonObject o) {
        final String node = str(o, "node");
        return node == null ? null : new Link(orEmpty(str(o, "plan")), node);
    }

    /** The task with the links that left their plan empty naming {@code plan}, the plan holding the task. */
    public static Task claimed(final Task t, final String plan) {
        boolean any = false;
        for (final Link l : t.links()) any |= l.plan()
            .isEmpty();
        if (!any) return t;
        final List<Link> links = new ArrayList<>();
        for (final Link l : t.links()) links.add(
            l.plan()
                .isEmpty() ? new Link(plan, l.node()) : l);
        return new Task(
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

    public static Item claimed(final Item i, final String plan) {
        final Link l = i.link();
        if (l == null || !l.plan()
            .isEmpty()) return i;
        return new Item(i.id(), i.taskId(), i.text(), i.done(), i.order(), new Link(plan, l.node()));
    }

    public static JsonObject toJson(final Comment c) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", c.id());
        o.addProperty("taskId", c.taskId());
        o.addProperty("by", c.by());
        o.addProperty("byName", c.byName());
        o.addProperty("at", c.at());
        o.addProperty("text", c.text());
        return o;
    }

    /** A task as a plan spells it; null when it lacks what every task has (an id, a title, a known status). */
    @Nullable
    public static Task task(final JsonObject o) {
        final String id = str(o, "id"), title = str(o, "title");
        final Status status = Status.of(str(o, "status"));
        if (id == null || title == null || status == null) return null;
        return new Task(
            id,
            title,
            str(o, "text"),
            status,
            orEmpty(str(o, "order")),
            strings(o, "assignees"),
            links(o),
            str(o, "createdBy"),
            str(o, "createdAt"),
            str(o, "doneAt"));
    }

    @Nullable
    public static Item item(final JsonObject o) {
        final String id = str(o, "id"), taskId = str(o, "taskId");
        if (id == null || taskId == null) return null;
        final JsonElement done = o.get("done");
        return new Item(
            id,
            taskId,
            orEmpty(str(o, "text")),
            done != null && done.isJsonPrimitive()
                && done.getAsJsonPrimitive()
                    .isBoolean()
                && done.getAsBoolean(),
            orEmpty(str(o, "order")),
            itemLink(o));
    }

    /** A task's links; the older {@code nodeIds} (cards of the plan holding the task) read as links with no plan. */
    private static List<Link> links(final JsonObject o) {
        final List<Link> out = new ArrayList<>();
        for (final JsonObject l : objects(o, "links")) {
            final Link link = link(l);
            if (link != null && !out.contains(link)) out.add(link);
        }
        for (final String node : strings(o, "nodeIds")) {
            final Link link = new Link("", node);
            if (!out.contains(link)) out.add(link);
        }
        return out;
    }

    @Nullable
    private static Link itemLink(final JsonObject o) {
        final JsonElement e = o.get("link");
        if (e != null && e.isJsonObject()) {
            final Link l = link(e.getAsJsonObject());
            if (l != null) return l;
        }
        final String node = str(o, "nodeId");
        return node == null ? null : new Link("", node);
    }

    @Nullable
    public static Comment comment(final JsonObject o) {
        final String id = str(o, "id"), taskId = str(o, "taskId");
        if (id == null || taskId == null) return null;
        return new Comment(
            id,
            taskId,
            orEmpty(str(o, "by")),
            orEmpty(str(o, "byName")),
            orEmpty(str(o, "at")),
            orEmpty(str(o, "text")));
    }

    // endregion

    // region On a graph

    /** Writes the graph's task board into {@code root} as the plan spells it; nothing for a plan without one. */
    public static void write(final Graph g, final JsonObject root) {
        if (g.getPrimaryView() != null) root.addProperty("primaryView", g.getPrimaryView());
        if (!g.tasks.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Task t : g.tasks) a.add(toJson(claimed(t, g.planId())));
            root.add("tasks", a);
        }
        if (!g.taskItems.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Item i : g.taskItems) a.add(toJson(claimed(i, g.planId())));
            root.add("taskItems", a);
        }
        if (!g.taskComments.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Comment c : g.taskComments) a.add(toJson(c));
            root.add("taskComments", a);
        }
    }

    /** Reads a task board {@link #write} wrote (or a plan holds) onto the graph; what does not read is skipped. */
    public static void read(final JsonObject root, final Graph g) {
        final String view = str(root, "primaryView");
        g.setPrimaryView(PLAN.equals(view) || TASKS.equals(view) ? view : null);
        for (final JsonObject o : objects(root, "tasks")) {
            final Task t = task(o);
            if (t != null) g.tasks.add(t);
        }
        for (final JsonObject o : objects(root, "taskItems")) {
            final Item i = item(o);
            if (i != null) g.taskItems.add(i);
        }
        for (final JsonObject o : objects(root, "taskComments")) {
            final Comment c = comment(o);
            if (c != null) g.taskComments.add(c);
        }
    }

    // endregion

    private static List<JsonObject> objects(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    @Nullable
    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isString() ? e.getAsString() : null;
    }

    private static String orEmpty(@Nullable final String s) {
        return s == null ? "" : s;
    }

    private static List<String> strings(final JsonObject o, final String key) {
        final List<String> out = new ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    private static JsonArray strings(final List<String> values) {
        final JsonArray a = new JsonArray();
        for (final String v : values) a.add(new JsonPrimitive(v));
        return a;
    }

}
