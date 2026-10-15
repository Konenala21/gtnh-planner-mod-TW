package com.gtnhplanner.ui.task;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How the task board looks and who is writing: each status's name and colour, people's faces (their initials on a
 * colour of their own, as the website draws them), times as the plan writes them and as people read them.
 */
public final class TaskLook {

    private TaskLook() {}

    /** A column's name. */
    public static String name(final Tasks.Status s) {
        return switch (s) {
            case TODO -> "To do";
            case DOING -> "Doing";
            case BLOCKED -> "Blocked";
            case DONE -> "Done";
        };
    }

    /** A status's colour: grey to do, blue doing, red blocked, green done. */
    public static int colour(final Tasks.Status s) {
        return switch (s) {
            case TODO -> 0xFFA1A4AD;
            case DOING -> 0xFF60A5FA;
            case BLOCKED -> 0xFFE5484D;
            case DONE -> Hyb.OK_INK;
        };
    }

    /** A disc {@code d} across, in whole steps of the space it is drawn in. */
    public static void dot(final float x, final float y, final float d, final int colour) {
        Hyb.roundRect(x, y, d, d, d / 2, colour);
    }

    // region People

    /** What a signed-out player writes as, and is called. */
    public static final String LOCAL = "local", YOU = "You";

    /** The signed-in account's id, or {@link #LOCAL} signed out. */
    public static String me() {
        final String id = Account.userId();
        return id == null ? LOCAL : id;
    }

    /** The signed-in account's name, or "You" signed out. */
    public static String myName() {
        final String name = Account.username();
        return name == null ? YOU : name;
    }

    /** Whether a comment or task is the player's own: their account's, or written on this computer signed out. */
    public static boolean mine(@Nullable final String by) {
        return by != null && (by.equals(LOCAL) || by.equals(Account.userId()));
    }

    /** Someone's name by account id: the player's own, else the name their comments on the plan carry; null if none. */
    @Nullable
    public static String nameOf(final Graph g, final String id) {
        if (id.equals(Account.userId())) return Account.username();
        if (id.equals(LOCAL)) return YOU;
        for (final Tasks.Comment c : g.taskComments) if (c.by()
            .equals(id)
            && !c.byName()
                .isEmpty())
            return c.byName();
        return null;
    }

    /** Up to two initials: a name's first letter, or the first letters of its first two words. */
    public static String initials(@Nullable final String name) {
        if (name == null || name.isBlank()) return "?";
        final String[] words = name.trim()
            .split("[\\s_.\\-]+");
        final StringBuilder out = new StringBuilder();
        for (final String w : words) {
            if (w.isEmpty()) continue;
            out.appendCodePoint(w.codePointAt(0));
            if (out.length() >= 2 || words.length < 2) break;
        }
        return out.toString()
            .toUpperCase(Locale.ROOT);
    }

    /** Faces' colours, picked by account id so one person is the same colour everywhere. */
    private static final int[] FACES = { 0xFFF59E0B, 0xFF10B981, 0xFF3B82F6, 0xFFEC4899, 0xFF8B5CF6, 0xFFEF4444,
        0xFF14B8A6, 0xFF84CC16 };

    public static int faceColour(final String id) {
        return FACES[Math.floorMod(id.hashCode(), FACES.length)];
    }

    public static final int FACE = 12;

    /** A face's width: room for its initials. */
    public static int faceWidth(final String initials) {
        return Math.max(FACE, Hyb.width(initials) + 3);
    }

    /** Someone's face, flat: their initials on their colour. Returns its width. */
    public static int face(final Graph g, final String id, final float x, final float y) {
        final String in = initials(nameOf(g, id));
        final int w = faceWidth(in), bg = faceColour(id);
        Hyb.rect(x + 1, y, w - 2, FACE, bg);
        Hyb.rect(x, y + 1, w, FACE - 2, bg);
        Hyb.text(in, x + (w - Hyb.width(in) + 1) / 2f, y + 2, 1f, Hyb.contrastInk(bg), false);
        return w;
    }

    /** Faces side by side, each overlapping the last a little; returns how wide they run. */
    public static int faces(final Graph g, final List<String> ids, final float x, final float y) {
        float at = x;
        for (final String id : ids) at += face(g, id, at, y) - 2;
        return ids.isEmpty() ? 0 : Math.round(at - x + 2);
    }

    // endregion

    // region Time

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    /** Now, as the plan writes a time: ISO in UTC to the millisecond, as the website's {@code toISOString}. */
    public static String now() {
        return ISO.format(Instant.now());
    }

    /** "just now", "5m ago", "3h ago", "12d ago" from a time the plan wrote; "" when it does not read. */
    public static String ago(@Nullable final String iso) {
        if (iso == null) return "";
        try {
            final long m = Math.max(
                0,
                Duration.between(Instant.parse(iso), Instant.now())
                    .toMinutes());
            if (m < 1) return "just now";
            if (m < 60) return m + "m ago";
            if (m < 60 * 24) return m / 60 + "h ago";
            if (m < 60 * 24 * 60) return m / (60 * 24) + "d ago";
            return m / (60 * 24 * 30) + "mo ago";
        } catch (final RuntimeException e) {
            return "";
        }
    }

    // endregion
}
