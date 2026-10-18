package com.gtnhplanner.ui;

/**
 * How wide the overview is beside the board (the website's {@code fitPanels} and {@code panelDragLimits} in
 * {@code workspace-columns.ts}, for the one column the planner owns: NEI's list is NEI's own). Board pixels.
 *
 * <p>
 * The player sets a width by dragging the overview's edge ({@link #DEFAULT} until they do). The board keeps room for
 * its toolbar on one row while the overview can still give way: the overview shrinks toward {@link #LEAST}, then folds
 * to its strip. That fold is the planner's, never written over the player's own: the overview comes back as the
 * planner widens. One the player opened while the planner had folded it ({@code forced}) stays open, and the board
 * takes what is left.
 *
 * <p>
 * At GTNH's defaults (1080p, GUI scale 4) the planner is 440 wide at Normal size, where the overview keeps its default
 * beside the toolbar's tight row, and 330 at Large, where it folds.
 */
public final class RailFit {

    /** The overview's width unless the player drags it. */
    public static final int DEFAULT = 198;
    /** Its least: a machine line's chips, count, rule key and count box still sit side by side. */
    public static final int LEAST = 160;
    /** Its most: about the website's 480 against its 336. */
    public static final int MOST = 300;
    /** Folded to a strip. */
    public static final int STRIP = 14;

    private RailFit() {}

    /** A width the player set, kept in range ({@link #DEFAULT} for nonsense). */
    public static int clamp(final int width) {
        return width <= 0 ? DEFAULT : Math.max(LEAST, Math.min(MOST, width));
    }

    /**
     * What the overview shows: whether it is open, its width, and whether the planner (not the player) folded it.
     */
    public record Fit(boolean open, int width, boolean autoFolded) {}

    /**
     * The overview in a planner {@code planner} wide whose board keeps at least {@code boardLeast} (its toolbar on one
     * row), as the player has it: {@code open} unless they folded it, {@code forced} open against the planner, and the
     * {@code width} they set.
     */
    public static Fit fit(final int planner, final int boardLeast, final boolean open, final boolean forced,
        final int width) {
        if (!open) return new Fit(false, STRIP, false);
        if (!forced && planner - LEAST < boardLeast) return new Fit(false, STRIP, true);
        return new Fit(true, Math.max(LEAST, Math.min(clamp(width), planner - boardLeast)), false);
    }

    /**
     * What its edge can be dragged to: its least, and its most or what leaves the board its least, never under its
     * least.
     */
    public static int[] dragLimits(final int planner, final int boardLeast) {
        return new int[] { LEAST, Math.max(LEAST, Math.min(MOST, planner - boardLeast)) };
    }
}
