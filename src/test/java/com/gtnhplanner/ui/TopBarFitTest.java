package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The top bar on one row: the tabs give way first, then the keys turn to pictures, then their gaps tighten. */
class TopBarFitTest {

    /**
     * The game's keys, the gap before them counted: Plans, Share and Sign in labelled (38, 41, 43), with feedback,
     * settings and help (18 each) and their gaps (3 in a group, 9 between); all six as pictures; the same closer (1 and
     * 4).
     */
    private static final int[] KEYS = { 197 + 9, 129 + 9, 116 + 4 };

    /** Five plans open, each at its widest: the tabs want more than any bar gives. */
    private static final int MANY = 5 * (120 + 2) + 20;

    @Test
    void aRoomyBarKeepsItsLabels() {
        assertEquals(TopBarFit.ROOMY, TopBarFit.step(900, KEYS, MANY, 0));
    }

    @Test
    void theTabsGiveWayToTwoWholeTabsBeforeTheKeysTurnToPictures() {
        // Room for the labelled keys and two whole tabs: still labels.
        assertEquals(TopBarFit.ROOMY, TopBarFit.step(KEYS[0] + TopBarFit.ROOM_TWO, KEYS, MANY, 0));
        // A pixel less: pictures.
        assertEquals(TopBarFit.PICTURES, TopBarFit.step(KEYS[0] + TopBarFit.ROOM_TWO - 1, KEYS, MANY, 0));
        // Then down to one whole tab, and the gaps tighten.
        assertEquals(TopBarFit.PICTURES, TopBarFit.step(KEYS[1] + TopBarFit.ROOM_ONE, KEYS, MANY, 0));
        assertEquals(TopBarFit.TIGHT, TopBarFit.step(KEYS[1] + TopBarFit.ROOM_ONE - 1, KEYS, MANY, 0));
    }

    @Test
    void gtnhDefaultsAtNormalAndLarge() {
        // 440 and 330 board px, less the bar's padding: pictures at both, the tabs with a whole tab beside them.
        assertEquals(TopBarFit.PICTURES, TopBarFit.step(432, KEYS, MANY, 12));
        assertEquals(TopBarFit.PICTURES, TopBarFit.step(322, KEYS, MANY, 12));
        // One plan with a short name fits beside the labels at Normal.
        assertEquals(TopBarFit.ROOMY, TopBarFit.step(432, KEYS, 70 + 2 + 20, 12));
    }

    @Test
    void theEndOfTheStripCountsAgainstTheTabs() {
        final int room = KEYS[0] + TopBarFit.ROOM_TWO;
        assertEquals(TopBarFit.ROOMY, TopBarFit.step(room, KEYS, MANY, 0));
        assertEquals(TopBarFit.PICTURES, TopBarFit.step(room, KEYS, MANY, 30));
    }

    @Test
    void tooNarrowForEvenThatTheKeysStayAndTheTabsTakeWhatIsLeft() {
        assertEquals(TopBarFit.TIGHT, TopBarFit.step(150, KEYS, MANY, 0));
    }
}
