package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;

/**
 * One line along the top of the board: something about the plan worth fixing or knowing, and the cards and drawers
 * it is about, which "Show me" frames. A notice may carry a key of its own instead ({@code actionLabel}, which runs
 * {@code action}): "Open" on a plan someone shared. A ring's notice is an alarm ({@code loop}): a chip naming it before
 * the sentence, in the ring's tone, blinking with its cards' outlines.
 */
public record Notice(Severity severity, String text, List<UUID> focus, @Nullable String actionLabel,
    @Nullable Runnable action, @Nullable UpToLoop.Kind loop) {

    public Notice(final Severity severity, final String text, final List<UUID> focus) {
        this(severity, text, focus, null, null, null);
    }

    public Notice(final Severity severity, final String text, final List<UUID> focus,
        @Nullable final String actionLabel, @Nullable final Runnable action) {
        this(severity, text, focus, actionLabel, action, null);
    }

    /** A ring's alarm: DEAD LOOP or CLOG LOCK on its chip, its sentence, Show me on its cards. */
    public static Notice alarm(final UpToLoop.Kind loop, final String text, final List<UUID> cards) {
        return new Notice(Severity.ERROR, text, cards, null, null, loop);
    }

    /** Whether the row has a key at its end. */
    public boolean hasKey() {
        return action != null || !focus.isEmpty();
    }

    /** The key's words. */
    public String keyLabel() {
        return action != null && actionLabel != null ? actionLabel : "Show me";
    }

    /** An alarm's chip ("DEAD LOOP"); null for any other notice. */
    @Nullable
    public String tag() {
        return loop == null ? null : Loops.tag(loop);
    }

    /**
     * Where a notice goes in the list, lowest first (the website's noticeRank): a failed solve, then alarms, then
     * warnings, then the rest.
     */
    public int rank() {
        if (severity == Severity.ERROR) return loop != null ? 1 : 0;
        return severity == Severity.WARN ? 2 : 3;
    }

    /** Most pressing first; notices of one rank keep their order (the website's byUrgency). */
    public static List<Notice> byUrgency(final List<Notice> list) {
        final List<Notice> out = new ArrayList<>(list);
        out.sort(Comparator.comparingInt(Notice::rank));
        return out;
    }
}
