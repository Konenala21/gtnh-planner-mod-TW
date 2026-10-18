package com.gtnhplanner.ui;

/**
 * How the top bar dresses its keys for the room it has (the website's {@code top-bar-fold.ts}). It is always one row
 * and hides no key in a menu: as the planner narrows the plan tabs give way first (they scroll; the open plan's tab and
 * "+" always stay), then Plans, Share and Sign in become small picture keys like feedback, settings and help, then the
 * gaps between the keys tighten.
 *
 * <p>
 * The tabs keep room for two whole tabs and "+" before the keys turn to pictures, and for one before the gaps tighten,
 * or less when that is all their plans take: one short tab never turns the keys to pictures. The website measures the
 * strip after each step and steps back with some slack against flicker; here every width is known before the bar is
 * laid out, so the step follows from them directly and never flickers.
 */
public final class TopBarFit {

    /** The dresses, widest first: the keys' labels, their pictures, their pictures with tighter gaps. */
    public static final int ROOMY = 0, PICTURES = 1, TIGHT = 2;

    /** A tab at its widest, the gap between tabs, and "+" with its margin ({@code PlanTabs}). */
    public static final int TAB_MOST = 120, TAB_GAP = 2, PLUS = 20;
    /** Two whole tabs and "+"; one. */
    public static final int ROOM_TWO = 2 * TAB_MOST + TAB_GAP + PLUS, ROOM_ONE = TAB_MOST + PLUS;

    private TopBarFit() {}

    /**
     * The dress for a bar whose keys leave the tabs {@code room} less their width at each dress ({@code keys}, the gap
     * before them counted), where the tabs and "+" take {@code want} at their own widths and the strip's end (who is
     * here, the account's status) takes {@code end} at its least.
     */
    public static int step(final int room, final int[] keys, final int want, final int end) {
        for (int step = ROOMY; step < TIGHT; step++) {
            final int keep = Math.min(want, step == ROOMY ? ROOM_TWO : ROOM_ONE);
            if (room - keys[step] - end >= keep) return step;
        }
        return TIGHT;
    }
}
