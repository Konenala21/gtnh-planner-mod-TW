package com.gtnhplanner.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Boxes lying on each other pulled apart, the rest left where they are. */
class UnstackTest {

    @Test
    void whatTouchesNothingStaysPut() {
        final int[][] by = Unstack.offsets(List.of(new int[] { 0, 0, 320, 140 }, new int[] { 0, 200, 320, 140 }));
        assertArrayEquals(new int[] { 0, 0 }, by[0]);
        assertArrayEquals(new int[] { 0, 0 }, by[1]);
    }

    @Test
    void theLowerOfTwoMovesToTheNearestClearSpot() {
        // A tall card 240 px above another: the lower one's top 24 px lie under the upper's bottom.
        final List<int[]> boxes = List.of(new int[] { 0, 240, 320, 140 }, new int[] { 0, 0, 320, 264 });
        final int[][] by = Unstack.offsets(boxes);
        assertArrayEquals(new int[] { 0, 0 }, by[1], "the upper one settles first");
        assertArrayEquals(new int[] { 0, 30 }, by[0], "the nearest grid spot clear of it: 30 px down");
    }

    @Test
    void noneOverlapAfter() {
        final List<int[]> boxes = new ArrayList<>();
        for (int i = 0; i < 12; i++) boxes.add(new int[] { (i % 3) * 200, (i / 3) * 90, 320, 140 });
        final int[][] by = Unstack.offsets(boxes);
        for (int i = 0; i < boxes.size(); i++) for (int j = i + 1; j < boxes.size(); j++) {
            final int[] a = boxes.get(i), b = boxes.get(j);
            final int ax = a[0] + by[i][0], ay = a[1] + by[i][1], bx = b[0] + by[j][0], by2 = b[1] + by[j][1];
            assertFalse(ax < bx + b[2] && bx < ax + a[2] && ay < by2 + b[3] && by2 < ay + a[3], i + " and " + j);
        }
    }
}
