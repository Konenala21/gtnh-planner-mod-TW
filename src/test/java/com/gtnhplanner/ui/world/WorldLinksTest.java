package com.gtnhplanner.ui.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.util.Vec3;

import org.junit.jupiter.api.Test;

/** Placing builds against placed machines' ghosts: where a ray enters one's box, and through which face. */
class WorldLinksTest {

    private static final double[] TWO_HIGH = { 0, 0, 0, 1, 2, 1 };

    private static double[] enter(final double ox, final double oy, final double oz, final double dx, final double dy,
        final double dz) {
        return WorldLinks.enterFace(Vec3.createVectorHelper(ox, oy, oz), Vec3.createVectorHelper(dx, dy, dz), TWO_HIGH);
    }

    @Test
    void lookingDownMeetsTheTop() {
        assertArrayEquals(new double[] { 1, 1 }, enter(0.5, 3, 0.5, 0, -1, 0), 1e-9);
    }

    @Test
    void eachSideIsTheFaceTheRayCrosses() {
        assertArrayEquals(new double[] { 2, 4 }, enter(-2, 0.5, 0.5, 1, 0, 0), 1e-9);
        assertArrayEquals(new double[] { 2, 5 }, enter(3, 0.5, 0.5, -1, 0, 0), 1e-9);
        assertArrayEquals(new double[] { 1, 2 }, enter(0.5, 0.5, -1, 0, 0, 1), 1e-9);
        assertArrayEquals(new double[] { 1, 3 }, enter(0.5, 0.5, 2, 0, 0, -1), 1e-9);
        assertArrayEquals(new double[] { 1, 0 }, enter(0.5, -1, 0.5, 0, 1, 0), 1e-9);
    }

    @Test
    void aSlantedRayEntersThroughTheFaceItReachesLast() {
        // Down and along: it crosses the top's plane after the west face's, so it comes in through the top.
        final double[] hit = enter(-0.25, 3, 0.5, 0.5, -1, 0);
        assertArrayEquals(new double[] { 1, 1 }, hit, 1e-9);
    }

    @Test
    void aMissOrAStartInsideIsNoHit() {
        assertNull(enter(2, 0.5, 0.5, 0, 0, 1));
        assertNull(enter(0.5, 0.5, 0.5, 1, 0, 0));
        assertNull(enter(0.5, 3, 0.5, 0, 1, 0));
    }
}
