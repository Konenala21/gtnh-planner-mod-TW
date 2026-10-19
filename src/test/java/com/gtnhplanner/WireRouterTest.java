package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.layout.WireRouter;
import com.gtnhplanner.layout.WireRouter.Box;
import com.gtnhplanner.layout.WireRouter.Wire;

/**
 * The wire router: straight where boxes face, eight directions only, clear of every box, on the boxes' edges, the same
 * answer every time; and routing again after a change touches only what the change did.
 */
class WireRouterTest {

    @Test
    void facingBoxesJoinWithOneStraightLine() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200), new Box(500, 30, 320, 200));
        final Wire w = wire("a", 0, 1);
        final List<int[]> path = new WireRouter().route(boxes, List.of(w))
            .get(w.key());
        assertEquals(2, path.size(), () -> describe(path));
        assertEquals(320, path.getFirst()[0]);
        assertEquals(500, path.getLast()[0]);
        assertEquals(path.getFirst()[1], path.getLast()[1]);
    }

    @Test
    void aWireGoesRoundABoxInTheWayInTheEightDirections() {
        final List<Box> boxes = List
            .of(new Box(0, 0, 320, 200), new Box(900, 0, 320, 200), new Box(420, -60, 380, 320));
        final Wire w = wire("round", 0, 1);
        final Set<UUID> fellBack = new HashSet<>();
        final List<int[]> path = new WireRouter().route(boxes, List.of(w), fellBack, true)
            .get(w.key());
        assertTrue(fellBack.isEmpty());
        assertTrue(octilinear(path), () -> describe(path));
        assertTrue(clear(path, boxes), () -> "through a box: " + describe(path));
        assertTrue(onEdge(path.getFirst(), boxes.get(0)) && onEdge(path.getLast(), boxes.get(1)), () -> describe(path));
    }

    @Test
    void aLoopLeavesItsRightSideAndComesBackIntoItsLeft() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200));
        final Wire w = new Wire(id("loop"), 0, 0, WireRouter.RIGHT, WireRouter.LEFT, 1);
        final List<int[]> path = new WireRouter().route(boxes, List.of(w))
            .get(w.key());
        assertEquals(320, path.getFirst()[0]);
        assertEquals(0, path.getLast()[0]);
        assertTrue(clear(path, boxes), () -> describe(path));
    }

    @Test
    void aBoxWalledInStillGetsItsWire() {
        // A drawer dropped on a card: walled in, routed through as lightly as it can, never left out.
        final List<Box> boxes = List.of(new Box(0, 0, 320, 300), new Box(80, 100, 136, 68), new Box(700, 0, 320, 200));
        final Wire w = wire("sealed", 1, 2);
        final Map<UUID, List<int[]>> routes = new WireRouter().route(boxes, List.of(w));
        assertNotNull(routes.get(w.key()));
        assertTrue(
            routes.get(w.key())
                .size() >= 2);
    }

    @Test
    void theSameBoardRoutesTheSameEveryTime() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final Map<UUID, List<int[]>> a = new WireRouter().route(boxes, wires), b = new WireRouter().route(boxes, wires);
        for (final Wire w : wires) assertEquals(describe(a.get(w.key())), describe(b.get(w.key())));
    }

    @Test
    void movingABoxRoutesOnlyItsWiresAndThoseItCrosses() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final WireRouter router = new WireRouter();
        final Map<UUID, List<int[]>> before = router.route(boxes, wires);
        // The last box down a little: its own wires route again, wires far from it keep theirs exactly.
        final List<Box> moved = new ArrayList<>(boxes);
        final Box last = boxes.getLast();
        moved.set(boxes.size() - 1, new Box(last.x(), last.y() + 40, last.w(), last.h()));
        final Map<UUID, List<int[]>> after = router.reroute(moved, wires, null, false);
        final Wire far = wires.getFirst();
        assertSame(before.get(far.key()), after.get(far.key()));
        assertTrue(router.statRouted < wires.size() / 2, () -> "routed " + router.statRouted);
        for (final Wire w : wires) {
            assertTrue(clear(after.get(w.key()), moved), () -> describe(after.get(w.key())));
            if (w.to() == boxes.size() - 1) assertTrue(
                onEdge(
                    after.get(w.key())
                        .getLast(),
                    moved.getLast()),
                () -> describe(after.get(w.key())));
        }
    }

    @Test
    void aNewWireRoutesAloneAndAGoneBoxTakesItsWiresWithIt() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) keys.add(id("box" + i));
        final WireRouter router = new WireRouter();
        final Map<UUID, List<int[]>> before = router.route(boxes, keys, wires, null, true);

        final List<Wire> more = new ArrayList<>(wires);
        more.add(wire("new", 0, boxes.size() - 1));
        final Map<UUID, List<int[]>> added = router.reroute(boxes, keys, more, null, true);
        assertEquals(1, router.statRouted);
        for (final Wire w : wires) assertSame(before.get(w.key()), added.get(w.key()));
        assertNotNull(
            added.get(
                more.getLast()
                    .key()));

        // Box 4 goes, with every wire at it; the rest renumber, and keep their routes.
        final List<Box> fewer = new ArrayList<>(boxes);
        final List<UUID> fewerKeys = new ArrayList<>(keys);
        fewer.remove(4);
        fewerKeys.remove(4);
        final List<Wire> left = new ArrayList<>();
        for (final Wire w : more) {
            if (w.from() == 4 || w.to() == 4) continue;
            left.add(new Wire(w.key(), w.from() > 4 ? w.from() - 1 : w.from(), w.to() > 4 ? w.to() - 1 : w.to(), 1));
        }
        final Map<UUID, List<int[]>> removed = router.reroute(fewer, fewerKeys, left, null, true);
        assertEquals(left.size(), removed.size());
        for (final Wire w : left) assertTrue(clear(removed.get(w.key()), fewer), () -> describe(removed.get(w.key())));
        assertFalse(router.statRouted == left.size(), "everything routed again");
    }

    // region Steering dots (the website's waypoints)

    @Test
    void aSteeredWirePassesThroughItsDot() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200), new Box(800, 0, 320, 200));
        final int[] dot = { 555, 405 };
        final Wire w = new Wire(id("steered"), 0, 1, WireRouter.ANY, WireRouter.ANY, 1, List.of(dot));
        final Set<UUID> fellBack = new HashSet<>();
        final List<int[]> path = new WireRouter().route(boxes, List.of(w), fellBack, true)
            .get(w.key());
        assertTrue(fellBack.isEmpty());
        assertTrue(along(path, dot) >= 0, () -> "misses the dot: " + describe(path));
        assertTrue(octilinear(path), () -> describe(path));
        assertTrue(clear(path, boxes), () -> describe(path));
        assertTrue(onEdge(path.getFirst(), boxes.get(0)) && onEdge(path.getLast(), boxes.get(1)), () -> describe(path));
    }

    @Test
    void dotsAreVisitedInTheirOrderWhereverTheyLie() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200), new Box(800, 0, 320, 200));
        // The first below the target, the second above the source: the wire goes there and back, in that order.
        final int[] first = { 955, 405 }, second = { 155, -195 };
        final Wire w = new Wire(id("order"), 0, 1, WireRouter.ANY, WireRouter.ANY, 1, List.of(first, second));
        final List<int[]> path = new WireRouter().route(boxes, List.of(w))
            .get(w.key());
        final double a = along(path, first), b = along(path, second);
        assertTrue(a >= 0 && b >= 0, () -> describe(path));
        assertTrue(a < b, () -> "out of order: " + describe(path));
        assertTrue(clear(path, boxes), () -> describe(path));
    }

    @Test
    void aWireSentBackTheWayItCameDoesNotRunOnItself() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200), new Box(800, 0, 320, 200));
        // Straight between the two, and a dot out past the target's far side: out to it and back.
        final int[] dot = { 1405, 105 };
        final Wire w = new Wire(id("spur"), 0, 1, WireRouter.ANY, WireRouter.ANY, 1, List.<int[]>of(dot));
        final List<int[]> path = new WireRouter().route(boxes, List.of(w))
            .get(w.key());
        assertTrue(along(path, dot) >= 0, () -> describe(path));
        assertEquals(0, overlaps(path), () -> "runs on itself: " + describe(path));
        assertTrue(clear(path, boxes), () -> describe(path));
    }

    @Test
    void aDotInsideABoxIsLeftOut() {
        final List<Box> boxes = List
            .of(new Box(0, 0, 320, 200), new Box(800, 0, 320, 200), new Box(400, 300, 200, 200));
        final Wire plain = wire("plain", 0, 1);
        final Wire inBox = new Wire(
            plain.key(),
            0,
            1,
            WireRouter.ANY,
            WireRouter.ANY,
            1,
            List.<int[]>of(new int[] { 505, 405 }));
        final List<int[]> a = new WireRouter().route(boxes, List.of(plain))
            .get(plain.key()),
            b = new WireRouter().route(boxes, List.of(inBox))
                .get(plain.key());
        assertEquals(describe(a), describe(b));
    }

    @Test
    void movingADotRoutesOnlyItsWire() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final WireRouter router = new WireRouter();
        router.route(boxes, wires);
        // The first wire steered below its row, then the dot moved: only that wire routes again, through the dot.
        final Wire first = wires.getFirst();
        final List<Wire> steered = new ArrayList<>(wires);
        steered.set(0, withStops(first, new int[] { 425, 255 }));
        Map<UUID, List<int[]>> routes = router.reroute(boxes, steered, null, true);
        assertEquals(1, router.statRouted);
        final List<Wire> moved = new ArrayList<>(wires);
        final int[] dot = { 425, 295 };
        moved.set(0, withStops(first, dot));
        final Map<UUID, List<int[]>> before = routes;
        routes = router.reroute(boxes, moved, null, true);
        assertEquals(1, router.statRouted);
        final List<int[]> path = routes.get(first.key());
        assertTrue(along(path, dot) >= 0, () -> describe(path));
        for (final Wire w : wires.subList(1, wires.size())) {
            if (!w.key()
                .equals(first.key())) assertSame(before.get(w.key()), routes.get(w.key()));
        }
        // And taken off again, it routes as it did unsteered.
        final Map<UUID, List<int[]>> off = router.reroute(boxes, wires, null, true);
        assertEquals(
            describe(
                new WireRouter().route(boxes, wires)
                    .get(first.key())),
            describe(off.get(first.key())));
    }

    @Test
    void aDotPastTheBoardStillRoutes() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200), new Box(800, 0, 320, 200));
        final WireRouter router = new WireRouter();
        final Wire plain = wire("far", 0, 1);
        router.route(boxes, List.of(plain));
        // Far past the grid's slack: the grid grows to take it.
        final int[] dot = { 555, 2005 };
        final Map<UUID, List<int[]>> routes = router.reroute(boxes, List.of(withStops(plain, dot)), null, true);
        assertTrue(along(routes.get(plain.key()), dot) >= 0, () -> describe(routes.get(plain.key())));
    }

    private static Wire withStops(final Wire w, final int[]... stops) {
        return new Wire(w.key(), w.from(), w.to(), w.fromSides(), w.toSides(), w.weight(), List.of(stops));
    }

    /** How far along the path the point is, or -1 when the path does not pass through it. */
    private static double along(final List<int[]> path, final int[] p) {
        double at = 0;
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            final long cross = (long) (b[0] - a[0]) * (p[1] - a[1]) - (long) (b[1] - a[1]) * (p[0] - a[0]);
            final boolean within = Math.min(a[0], b[0]) <= p[0] && p[0] <= Math.max(a[0], b[0])
                && Math.min(a[1], b[1]) <= p[1]
                && p[1] <= Math.max(a[1], b[1]);
            if (cross == 0 && within) return at + Math.hypot(p[0] - a[0], p[1] - a[1]);
            at += len;
        }
        return -1;
    }

    /** Ten-pixel steps the path runs along twice (either way). */
    private static int overlaps(final List<int[]> path) {
        final Set<String> seen = new HashSet<>();
        int twice = 0;
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) / 10;
            final int sx = Integer.signum(b[0] - a[0]) * 10, sy = Integer.signum(b[1] - a[1]) * 10;
            for (int k = 0; k < steps; k++) {
                final int x0 = a[0] + k * sx, y0 = a[1] + k * sy, x1 = x0 + sx, y1 = y0 + sy;
                final String step = Math.min(
                    x0,
                    x1) + "," + Math.min(y0, y1) + "," + Math.max(x0, x1) + "," + Math.max(y0, y1) + "," + (sx * sy);
                if (!seen.add(step)) twice++;
            }
        }
        return twice;
    }

    // endregion

    // region Helpers

    /** Three rows of four cards with room between. */
    private static List<Box> grid() {
        final List<Box> out = new ArrayList<>();
        for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) out.add(new Box(c * 520, r * 380, 320, 200));
        return out;
    }

    /** Each card to the next in its row, and down each column. */
    private static List<Wire> wiresOf(final int n) {
        final List<Wire> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % 4 != 3) out.add(wire("r" + i, i, i + 1));
            if (i + 4 < n) out.add(wire("c" + i, i, i + 4));
        }
        return out;
    }

    private static Wire wire(final String name, final int from, final int to) {
        return new Wire(id(name), from, to, 1);
    }

    private static UUID id(final String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean octilinear(final List<int[]> path) {
        for (int i = 1; i < path.size(); i++) {
            final int dx = path.get(i)[0] - path.get(i - 1)[0], dy = path.get(i)[1] - path.get(i - 1)[1];
            if (dx != 0 && dy != 0 && Math.abs(dx) != Math.abs(dy)) return false;
        }
        return true;
    }

    /** Whether no point along the path lies strictly inside a box. */
    private static boolean clear(final List<int[]> path, final List<Box> boxes) {
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int k = 0; k <= steps; k++) {
                final double x = a[0] + (b[0] - a[0]) * (double) k / Math.max(1, steps),
                    y = a[1] + (b[1] - a[1]) * (double) k / Math.max(1, steps);
                for (final Box box : boxes)
                    if (x > box.x() && x < box.x() + box.w() && y > box.y() && y < box.y() + box.h()) return false;
            }
        }
        return true;
    }

    private static boolean onEdge(final int[] p, final Box b) {
        final boolean inX = p[0] >= b.x() && p[0] <= b.x() + b.w(), inY = p[1] >= b.y() && p[1] <= b.y() + b.h();
        return inX && (p[1] == b.y() || p[1] == b.y() + b.h()) || inY && (p[0] == b.x() || p[0] == b.x() + b.w());
    }

    private static String describe(final List<int[]> path) {
        final StringBuilder s = new StringBuilder();
        for (final int[] p : path) s.append('(')
            .append(p[0])
            .append(',')
            .append(p[1])
            .append(") ");
        return s.toString();
    }

    // endregion
}
