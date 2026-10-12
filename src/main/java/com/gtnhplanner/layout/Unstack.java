package com.gtnhplanner.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pulls apart boxes that lie on each other (an imported plan's cards, which are sized differently here than where
 * the plan was made). Top to bottom, then left to right, each box touching one already settled moves to the clear
 * grid spot nearest it, nearest first on rings of growing size, as a card carried on the board lands
 * (BoardCanvas.nearestClear, the website's search). Boxes that touch nothing stay put.
 */
public final class Unstack {

    /** The grid the boxes move on, and how far (in steps) one looks for a clear spot. */
    private static final int STEP = 10, SEARCH = 60;

    private Unstack() {}

    /**
     * The offset {dx, dy} to move each box by, in the order given.
     *
     * @param boxes {x, y, w, h} each
     */
    public static int[][] offsets(final List<int[]> boxes) {
        final Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(
            order,
            Comparator.comparingInt((Integer i) -> boxes.get(i)[1])
                .thenComparingInt(i -> boxes.get(i)[0]));
        final int[][] out = new int[boxes.size()][];
        final List<int[]> settled = new ArrayList<>();
        for (final int i : order) {
            final int[] b = boxes.get(i), at = clearNear(b, settled);
            out[i] = at;
            settled.add(new int[] { b[0] + at[0], b[1] + at[1], b[2], b[3] });
        }
        return out;
    }

    private static int[] clearNear(final int[] b, final List<int[]> settled) {
        if (clear(b, 0, 0, settled)) return new int[] { 0, 0 };
        for (int r = 1; r <= SEARCH; r++) for (final int[] o : ring(r)) {
            final int dx = o[0] * STEP, dy = o[1] * STEP;
            if (clear(b, dx, dy, settled)) return new int[] { dx, dy };
        }
        return new int[] { 0, 0 };
    }

    /** The grid steps {dx, dy} at ring {@code r}, nearest first, then top left. */
    private static List<int[]> ring(final int r) {
        final List<int[]> out = new ArrayList<>();
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++)
            if (Math.max(Math.abs(dx), Math.abs(dy)) == r) out.add(new int[] { dx, dy });
        out.sort(
            Comparator.comparingInt((int[] o) -> o[0] * o[0] + o[1] * o[1])
                .thenComparingInt(o -> o[1])
                .thenComparingInt(o -> o[0]));
        return out;
    }

    private static boolean clear(final int[] b, final int dx, final int dy, final List<int[]> settled) {
        for (final int[] o : settled) if (b[0] + dx < o[0] + o[2] && o[0] < b[0] + dx + b[2]
            && b[1] + dy < o[1] + o[3]
            && o[1] < b[1] + dy + b[3]) return false;
        return true;
    }
}
