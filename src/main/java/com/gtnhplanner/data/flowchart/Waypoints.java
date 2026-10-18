package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The dots a player pins on a wire to steer it, the website's edge waypoints: board points, in order, that the wire's
 * route passes through ({@code layout/WireRouter}). An edge keeps its own ({@link Edge#waypoints}), a drawer's link its
 * own ({@link Drawer.Link#waypoints()}). Each point is {x, y} in board px on the router's lines, the middles of its
 * 10 px cells ({@link #onLine}); the website keeps them on the corners of its 20 px grid, so they are snapped each way
 * as they cross ({@link #toSite}, {@link #fromSite}).
 */
public final class Waypoints {

    /** The router's cell, and the website's grid, in board px. */
    public static final int CELL = 10, SITE_GRID = 20;

    private Waypoints() {}

    /** The line through the cell {@code v} (a board x or y) lies in: where the router runs a wire through it. */
    public static int onLine(final int v) {
        return Math.floorDiv(v, CELL) * CELL + CELL / 2;
    }

    /** The points as plain {x, y} pairs, a malformed one left out; unmodifiable, empty for none. */
    public static List<int[]> clean(@Nullable final List<int[]> points) {
        if (points == null || points.isEmpty()) return List.of();
        final List<int[]> out = new ArrayList<>(points.size());
        for (final int[] p : points) if (p != null && p.length >= 2) out.add(new int[] { p[0], p[1] });
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }

    /** Whether two lists are the same points in the same order. */
    public static boolean same(final List<int[]> a, final List<int[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /** The points moved by (dx, dy). */
    public static List<int[]> shifted(final List<int[]> points, final int dx, final int dy) {
        if (points.isEmpty() || dx == 0 && dy == 0) return points;
        final List<int[]> out = new ArrayList<>(points.size());
        for (final int[] p : points) out.add(new int[] { p[0] + dx, p[1] + dy });
        return Collections.unmodifiableList(out);
    }

    /** The points with {@code point} put in at {@code index} (clamped to the list). */
    public static List<int[]> inserted(final List<int[]> points, final int index, final int[] point) {
        final List<int[]> out = new ArrayList<>(points);
        out.add(Math.max(0, Math.min(out.size(), index)), new int[] { point[0], point[1] });
        return Collections.unmodifiableList(out);
    }

    /** The points with the one at {@code index} moved to {@code point}. */
    public static List<int[]> moved(final List<int[]> points, final int index, final int[] point) {
        final List<int[]> out = new ArrayList<>(points);
        if (index >= 0 && index < out.size()) out.set(index, new int[] { point[0], point[1] });
        return Collections.unmodifiableList(out);
    }

    /** The points without the one at {@code index}. */
    public static List<int[]> removed(final List<int[]> points, final int index) {
        final List<int[]> out = new ArrayList<>(points);
        if (index >= 0 && index < out.size()) out.remove(index);
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }

    // region The plan's wires

    /**
     * After an arrange, as on the website: every wire it laid out again loses its dots, which aimed at a layout that is
     * gone. A wire wholly inside one group keeps them, moved as far as the group moved ({@code groupMoves}: each moved
     * group's {dx, dy}; Arrange moves a group as one block, its insides untouched).
     */
    public static void afterArrange(final Graph g, final java.util.Map<java.util.UUID, int[]> groupMoves) {
        for (final Edge e : g.getEdges()) {
            if (!e.waypoints.isEmpty())
                e.waypoints = arranged(g, groupMoves, e.sourceNodeId, e.targetNodeId, e.waypoints);
        }
        for (final Drawer d : g.getDrawers()) for (final Drawer.Held h : d.held()) {
            final List<int[]> stops = h.link()
                .waypoints();
            if (!stops.isEmpty()) d.setWaypoints(
                h.link(),
                h.input(),
                arranged(
                    g,
                    groupMoves,
                    d.getId(),
                    h.link()
                        .nodeId(),
                    stops));
        }
    }

    /** Takes the dots off every wire with an end at one of {@code ids} (cards and drawers). */
    public static void dropAt(final Graph g, final java.util.Set<java.util.UUID> ids) {
        for (final Edge e : g.getEdges())
            if (ids.contains(e.sourceNodeId) || ids.contains(e.targetNodeId)) e.waypoints = List.of();
        for (final Drawer d : g.getDrawers()) for (final Drawer.Held h : d.held()) {
            if (h.link()
                .waypoints()
                .isEmpty()
                || !ids.contains(d.getId()) && !ids.contains(
                    h.link()
                        .nodeId()))
                continue;
            d.setWaypoints(h.link(), h.input(), List.of());
        }
    }

    /** A wire's dots after an arrange: moved with the outermost group both its ends are in, else gone. */
    private static List<int[]> arranged(final Graph g, final java.util.Map<java.util.UUID, int[]> groupMoves,
        final java.util.UUID a, final java.util.UUID b, final List<int[]> stops) {
        final List<java.util.UUID> upA = Groups.ancestors(g, a), upB = Groups.ancestors(g, b);
        if (upA.isEmpty() || upB.isEmpty()
            || !upA.getLast()
                .equals(upB.getLast()))
            return List.of();
        final int[] move = groupMoves.get(upA.getLast());
        return move == null ? stops : shifted(stops, move[0], move[1]);
    }

    // endregion

    // region JSON

    /** As the game's save writes them: [[x, y], ...]. */
    public static JsonArray toJson(final List<int[]> points) {
        final JsonArray out = new JsonArray();
        for (final int[] p : points) {
            final JsonArray xy = new JsonArray();
            xy.add(new JsonPrimitive(p[0]));
            xy.add(new JsonPrimitive(p[1]));
            out.add(xy);
        }
        return out;
    }

    /** As the game's save wrote them ({@link #toJson}); empty for none or anything malformed. */
    public static List<int[]> fromJson(@Nullable final JsonElement saved) {
        return read(saved, false);
    }

    /** As the website keeps them: [{x, y}, ...], each snapped to its grid. */
    public static JsonArray toSite(final List<int[]> points) {
        final JsonArray out = new JsonArray();
        for (final int[] p : points) {
            final JsonObject xy = new JsonObject();
            xy.addProperty("x", toSite(p[0]));
            xy.addProperty("y", toSite(p[1]));
            out.add(xy);
        }
        return out;
    }

    /** A board x or y on the website's grid: the corner nearest it. */
    public static int toSite(final int v) {
        return Math.round(v / (float) SITE_GRID) * SITE_GRID;
    }

    /** The website's waypoints ([{x, y}, ...]) on the router's lines; empty for none or anything malformed. */
    public static List<int[]> fromSite(@Nullable final JsonElement site) {
        return read(site, true);
    }

    private static List<int[]> read(@Nullable final JsonElement json, final boolean snap) {
        if (json == null || !json.isJsonArray()) return List.of();
        final List<int[]> out = new ArrayList<>();
        for (final JsonElement e : json.getAsJsonArray()) {
            final double[] xy = xy(e);
            if (xy == null) continue;
            final int x = (int) Math.round(xy[0]), y = (int) Math.round(xy[1]);
            out.add(snap ? new int[] { onLine(x), onLine(y) } : new int[] { x, y });
        }
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }

    /** One point, as {x, y} or [x, y]; null when it is neither or not finite. */
    @Nullable
    private static double[] xy(final JsonElement e) {
        try {
            final double x, y;
            if (e.isJsonObject()) {
                final JsonObject o = e.getAsJsonObject();
                if (!o.has("x") || !o.has("y")) return null;
                x = o.get("x")
                    .getAsDouble();
                y = o.get("y")
                    .getAsDouble();
            } else if (e.isJsonArray() && e.getAsJsonArray()
                .size() >= 2) {
                    x = e.getAsJsonArray()
                        .get(0)
                        .getAsDouble();
                    y = e.getAsJsonArray()
                        .get(1)
                        .getAsDouble();
                } else return null;
            return Double.isFinite(x) && Double.isFinite(y) ? new double[] { x, y } : null;
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    // endregion
}
