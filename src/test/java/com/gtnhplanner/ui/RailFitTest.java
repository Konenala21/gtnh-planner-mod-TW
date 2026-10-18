package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The overview's width beside the board: the player's, given way to a narrow planner, folded at the narrowest. */
class RailFitTest {

    /** The board toolbar's tight row in the game (BoardToolbar.leastOneRow): what the board keeps. */
    private static final int TOOLBAR = 229;

    @Test
    void gtnhDefaultsKeepTheOverviewAtNormalAndFoldItAtLarge() {
        // 1080p at GUI scale 4: the planner is 440 board px at Normal size, 330 at Large.
        assertEquals(new RailFit.Fit(true, RailFit.DEFAULT, false), RailFit.fit(440, TOOLBAR, true, false, 198));
        assertEquals(new RailFit.Fit(false, RailFit.STRIP, true), RailFit.fit(330, TOOLBAR, true, false, 198));
    }

    @Test
    void aWideOverviewShrinksTowardItsLeastBeforeItFolds() {
        assertEquals(new RailFit.Fit(true, 280, false), RailFit.fit(800, TOOLBAR, true, false, 280));
        assertEquals(new RailFit.Fit(true, 211, false), RailFit.fit(440, TOOLBAR, true, false, 280));
        assertEquals(new RailFit.Fit(true, RailFit.LEAST, false), RailFit.fit(389, TOOLBAR, true, false, 280));
        assertEquals(new RailFit.Fit(false, RailFit.STRIP, true), RailFit.fit(388, TOOLBAR, true, false, 280));
    }

    @Test
    void thePlayersFoldIsNeverThePlannersAndTheOverviewComesBack() {
        // Folded by the player: a strip at any width, never called the planner's.
        assertEquals(new RailFit.Fit(false, RailFit.STRIP, false), RailFit.fit(1200, TOOLBAR, false, false, 198));
        // Folded by the planner, then the planner widens: it comes back at the width the player set.
        assertEquals(
            true,
            RailFit.fit(330, TOOLBAR, true, false, 240)
                .autoFolded());
        assertEquals(new RailFit.Fit(true, 240, false), RailFit.fit(600, TOOLBAR, true, false, 240));
    }

    @Test
    void openedAgainstThePlannerItStaysAndTheBoardGivesWay() {
        assertEquals(new RailFit.Fit(true, RailFit.LEAST, false), RailFit.fit(330, TOOLBAR, true, true, 198));
    }

    @Test
    void theEdgeDragsBetweenTheLeastAndWhatLeavesTheBoardItsRow() {
        assertArrayEquals(new int[] { RailFit.LEAST, RailFit.MOST }, RailFit.dragLimits(1000, TOOLBAR));
        assertArrayEquals(new int[] { RailFit.LEAST, 211 }, RailFit.dragLimits(440, TOOLBAR));
        assertArrayEquals(new int[] { RailFit.LEAST, RailFit.LEAST }, RailFit.dragLimits(330, TOOLBAR));
    }

    @Test
    void aSavedWidthIsKeptInRange() {
        assertEquals(RailFit.LEAST, RailFit.clamp(20));
        assertEquals(RailFit.MOST, RailFit.clamp(5000));
        assertEquals(RailFit.DEFAULT, RailFit.clamp(0));
        assertEquals(250, RailFit.clamp(250));
    }
}
