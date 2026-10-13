package com.gtnhplanner.data.flowchart.balancer;

/**
 * A wall-clock ceiling shared by every model in one solve, so separate entry points can budget
 * independently. Passed around rather than held statically; optional stages skip themselves once it
 * is spent, and the non-optional ones get a workable slice even if that overshoots.
 */
public final class Budget {

    private final long deadlineMillis;

    /**
     * Tests only: no wall-clock ceilings anywhere in a solve (budgets never run out, models get no time limit), so a
     * solve answers the same however busy the machine is. The counted limits (swaps tried, branch-and-bound nodes)
     * still hold.
     */
    private static volatile boolean timeless;

    public static void timeless(final boolean on) {
        timeless = on;
    }

    public static boolean timeless() {
        return timeless;
    }

    private Budget(final long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    /** A budget that expires {@code millis} from now. */
    public static Budget of(final long millis) {
        return new Budget(System.currentTimeMillis() + millis);
    }

    public long remaining() {
        if (timeless) return Long.MAX_VALUE / 4;
        return Math.max(0, deadlineMillis - System.currentTimeMillis());
    }

    public boolean expired() {
        return remaining() <= 0;
    }
}
