package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.tutorial.Targets.Target;

/**
 * The tour's only chrome: a box beside what a beat shows, pointing at it, saying what it is (the words that matter
 * most in gold: written between asterisks in the script), with three small keys
 * along its bottom: leave on the left, back and next on the right (next turns gold once the beat is done). It sits
 * below the thing when there is room, else above, to the right or to the left, off the tour's cursor, and follows it
 * as the board moves. It is drawn a size up from the game's text ({@link #scale}): one more screen pixel to each pixel
 * of the font, so it stays crisp.
 */
final class Callout {

    /** How long a note waits for its target to show before it shows in the middle without it. */
    private static final long NO_TARGET_MS = 2500;
    private static final int TEXT_W = 176, PAD = 7, LINE = 11, KEY = 12, GAP = 7, TIP = 5, EDGE = 6;
    private static final int FILL = 0xFF222327, GOLD = 0xFFFFD257, HOT = 0xFF3C3E45, OFF = 0xFF55575F,
        ON_GOLD = 0xFF1A1B1F;

    private enum Side {
        BELOW,
        ABOVE,
        RIGHT,
        LEFT,
        /** On a target too big to sit beside: inside it, along its bottom. */
        INSIDE,
        /** Nothing to point at: the middle of the screen. */
        MIDDLE
    }

    private record Hit(Rect r, Glyph glyph, Runnable action, boolean enabled) {}

    private final Director d;
    private final Runnable leave;
    private final List<Hit> hits = new ArrayList<>();
    private Side side;
    private Rect box;
    /** Where the box is drawn from and at what size, while it draws: its keys' hits are kept in screen pixels. */
    private float ox, oy, os = 1;

    /**
     * How much bigger than the game's text the note is: one more screen pixel to each pixel of the font (a half again
     * at
     * GUI scale 2, a third at 3), so it reads easily and stays crisp.
     */
    private static float scale() {
        final int gs = Math.max(
            1,
            Targets.resolution()
                .getScaleFactor());
        return (gs + 1f) / gs;
    }

    Callout(final Director d, final Runnable leave) {
        this.d = d;
        this.leave = leave;
    }

    /** A click with the player's mouse: true when it landed on the callout. */
    boolean click(final float x, final float y) {
        for (final Hit h : hits) {
            if (!h.r.contains(x, y)) continue;
            if (h.enabled) {
                (h.glyph == Glyph.LEAVE ? com.gtnhplanner.ui.sound.Sfx.CLOSE : com.gtnhplanner.ui.sound.Sfx.PAGE)
                    .play();
                h.action.run();
            }
            return true;
        }
        return box != null && box.contains(x, y);
    }

    void draw(final int sw, final int sh, final float mx, final float my) {
        hits.clear();
        final String text = d.noteText();
        if (text == null) {
            box = null;
            side = null;
            return;
        }
        final List<List<Word>> lay = layout(text);
        final int lines = lay.size();
        int tw = 0;
        for (int i = 0; i < lines; i++) tw = Math.max(tw, width(lay.get(i)) + indent(i));
        final int gaps = Math.max(0, paraIcons.size() - 1) * PARA_GAP;
        final int w = Math.max(tw, 3 * KEY + 30) + 2 * PAD, h = PAD + lines * LINE + gaps + 4 + KEY + PAD - 2;
        final float s = scale(), bw = w * s, bh = h * s;
        final Target t = d.noteTarget();
        final Rect r = t == null ? null : t.rect();
        if (t != null && r == null && System.currentTimeMillis() - d.noteAt() < NO_TARGET_MS) {
            // Its target not on screen (yet): nothing to point at, so wait; one that never shows, the note says its
            // piece in the middle of the screen rather than leave the tour waiting on nothing.
            box = null;
            return;
        }
        final float[] at = place(r, bw, bh, sw, sh);
        // In over 170 ms: fading up, and sliding the last few pixels toward what it points at.
        final float age = Math.min(1, (System.currentTimeMillis() - d.noteAt()) / 170f);
        final float e = 1 - (1 - age) * (1 - age), slide = (1 - e) * 5;
        float x = at[0], y = at[1];
        switch (side) {
            case BELOW -> y += slide;
            case ABOVE -> y -= slide;
            case RIGHT -> x += slide;
            case LEFT -> x -= slide;
            default -> y += slide;
        }
        box = new Rect(x, y, bw, bh);
        // Drawn in the note's own pixels from its corner, a size up.
        ox = x;
        oy = y;
        os = s;
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(x, y, 0);
        org.lwjgl.opengl.GL11.glScalef(s, s, 1);
        Hyb.beginBatch();
        Hyb.rect(2, 3, w, h, fade(0x70000000, e));
        Hyb.rect(-1, -1, w + 2, h + 2, fade(0xFF0B0C0E, e));
        Hyb.rect(0, 0, w, h, fade(GOLD, e));
        Hyb.rect(1, 1, w - 2, h - 2, fade(FILL, e));
        if (r != null) tip(new Rect((r.x() - x) / s, (r.y() - y) / s, r.w() / s, r.h() / s), 0, 0, w, h, e);
        Hyb.endBatch();
        final int shown = d.noteLine();
        for (int i = 0; i < lines; i++) {
            final int para = paragraphs.get(i);
            final float ly = PAD + i * LINE + para * PARA_GAP;
            float wx = PAD + indent(i);
            final boolean lit = shown >= 0 && para == shown;
            // The line being shown: a gold bar beside it; the others quieter while one is.
            if (lit) Hyb
                .rect(2, ly - 1, 2, LINE - 1 + (i + 1 < lines && paragraphs.get(i + 1) == para ? 1 : 0), fade(GOLD, e));
            final boolean quiet = shown >= 0 && !lit;
            // A paragraph's mouse, beside its first line.
            final char icon = paraIcons.get(para);
            if (icon != 0 && (i == 0 || paragraphs.get(i - 1) != para)) mouseIcon(PAD, ly - 2, icon, e);
            for (final Word word : lay.get(i)) {
                for (final Bit bit : word.bits()) {
                    final int ink = bit.colour() != 0 ? bit.colour() : bit.gold() ? GOLD : Hyb.INK;
                    Hyb.text(bit.text(), wx, ly, 1f, fade(quiet ? Hyb.mix(ink, FILL, 0.45f) : ink, e), false);
                    wx += Hyb.width(bit.text());
                }
                wx += SPACE;
            }
        }
        // The keys: leave on the left, back and next on the right.
        final float ky = h - PAD - KEY + 2;
        key(Glyph.LEAVE, PAD - 2, ky, mx, my, e, false, true, leave);
        final boolean done = d.waiting();
        key(Glyph.BACK, w - PAD + 2 - 2 * KEY - 3, ky, mx, my, e, false, !d.first() && !d.catchingUp(), d::back);
        key(
            d.last() ? Glyph.DONE : Glyph.NEXT,
            w - PAD + 2 - KEY,
            ky,
            mx,
            my,
            e,
            done,
            true,
            () -> { if (!d.next()) leave.run(); });
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    /**
     * Where the box goes: beside its target, on the side and at the place along it that covers least of what is on the
     * board (cards, drawers, notes, popups, the overview); below first, then above, right, left; and off the tour's
     * cursor. It stays where it was while that is nearly as good, so it does not flick between two places.
     */
    private float[] place(final Rect r, final float w, final float h, final int sw, final int sh) {
        if (r == null) {
            side = Side.MIDDLE;
            final float[] spot = d.noteSpot();
            if (spot != null) return new float[] { clamp(spot[0] * sw - w / 2f, EDGE, sw - w - EDGE),
                clamp(spot[1] * sh - h / 2f, EDGE, sh - h - EDGE), 0 };
            return new float[] { (sw - w) / 2f, sh * 0.42f - h / 2f };
        }
        final List<Rect> content = Targets.content();
        float[] best = null;
        Side bestSide = null;
        float bestScore = Float.MAX_VALUE, keptScore = Float.MAX_VALUE;
        float[] kept = null;
        final Side[] order = { Side.BELOW, Side.ABOVE, Side.RIGHT, Side.LEFT };
        for (int si = 0; si < order.length; si++) {
            final Side s = order[si];
            for (int k = 0; k < SLOTS; k++) {
                final float[] p = at(s, k, r, w, h, sw, sh);
                if (p == null) continue;
                final Rect b = new Rect(p[0], p[1], w, h);
                float score = si * 300 + p[2] * 2;
                for (final Rect c : content) score += overlap(b, c);
                if (score < bestScore) {
                    bestScore = score;
                    best = p;
                    bestSide = s;
                }
                if (s == side && box != null && Math.abs(p[0] - lastX) < 1.5f && Math.abs(p[1] - lastY) < 1.5f) {
                    kept = p;
                    keptScore = score;
                }
            }
        }
        if (kept != null && keptScore <= bestScore + 400) {
            best = kept;
            bestSide = side;
        }
        if (best == null) {
            side = Side.INSIDE;
            best = new float[] { clamp(r.cx() - w / 2f, EDGE, sw - w - EDGE),
                clamp(r.bottom() - h - 12, EDGE, sh - h - EDGE), 0 };
        } else side = bestSide;
        lastX = best[0];
        lastY = best[1];
        return best;
    }

    /** Places tried along each side: the middle, then spread from one end of the target to the other. */
    private static final int SLOTS = 9;

    private float lastX = Float.NaN, lastY = Float.NaN;

    /**
     * The box's corner on one side of its target at place {@code k} along it, and how far that is from the middle; null
     * when it does not fit there. Along the side, the box always keeps 16 px of its edge against the target, for the
     * tip.
     */
    private float[] at(final Side s, final int k, final Rect r, final float w, final float h, final int sw,
        final int sh) {
        final float off = GAP + TIP;
        final boolean across = s == Side.BELOW || s == Side.ABOVE;
        // Along the side: the middle first, then from one end to the other.
        final float mid = across ? r.cx() - w / 2f : r.cy() - h / 2f;
        final float lo = across ? r.x() + 16 - w : r.y() + 16 - h, hi = across ? r.right() - 16 : r.bottom() - 16;
        final float want = k == 0 ? mid : lo + (hi - lo) * (k - 1) / (SLOTS - 2f);
        final float along = across ? clamp(want, EDGE, sw - w - EDGE) : clamp(want, EDGE, sh - h - EDGE);
        final float x, y;
        switch (s) {
            case BELOW -> {
                x = along;
                y = r.bottom() + off;
                if (y + h > sh - EDGE) return null;
            }
            case ABOVE -> {
                x = along;
                y = r.y() - off - h;
                if (y < EDGE) return null;
            }
            case RIGHT -> {
                x = r.right() + off;
                y = along;
                if (x + w > sw - EDGE) return null;
            }
            default -> {
                x = r.x() - off - w;
                y = along;
                if (x < EDGE) return null;
            }
        }
        // Off the tour's cursor, which usually rests by what is shown.
        if (Pointer.driving() && new Rect(x - 4, y - 4, w + 8, h + 8).contains(d.px(), d.py())) return null;
        return new float[] { x, y, Math.abs(along - mid) };
    }

    private static float overlap(final Rect a, final Rect b) {
        final float w = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x()),
            h = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
        return w <= 0 || h <= 0 ? 0 : w * h;
    }

    /** The point on the box's edge facing its target: a small gold-edged triangle. */
    private void tip(final Rect r, final float x, final float y, final int w, final int h, final float e) {
        final float ax, ay, dx, dy;
        switch (side) {
            case BELOW -> {
                ax = clamp(r.cx(), x + 9, x + w - 9);
                ay = y;
                dx = 0;
                dy = -1;
            }
            case ABOVE -> {
                ax = clamp(r.cx(), x + 9, x + w - 9);
                ay = y + h;
                dx = 0;
                dy = 1;
            }
            case RIGHT -> {
                ax = x;
                ay = clamp(r.cy(), y + 9, y + h - 9);
                dx = -1;
                dy = 0;
            }
            case LEFT -> {
                ax = x + w;
                ay = clamp(r.cy(), y + 9, y + h - 9);
                dx = 1;
                dy = 0;
            }
            default -> {
                return;
            }
        }
        triangle(ax, ay, dx, dy, TIP + 1.5f, TIP + 1.5f, 0, fade(GOLD, e));
        triangle(ax, ay, dx, dy, TIP, TIP, 1, fade(FILL, e));
    }

    /** A triangle with its base along an edge at (ax, ay), set {@code in} back inside, its point out along (dx, dy). */
    private static void triangle(final float ax, final float ay, final float dx, final float dy, final float half,
        final float len, final float in, final int argb) {
        final float bx = ax - dx * in, by = ay - dy * in, px = -dy, py = dx;
        Hyb.triangle(
            bx + px * half,
            by + py * half,
            bx - px * half,
            by - py * half,
            ax + dx * len,
            ay + dy * len,
            argb);
        Hyb.triangle(
            bx - px * half,
            by - py * half,
            bx + px * half,
            by + py * half,
            ax + dx * len,
            ay + dy * len,
            argb);
    }

    // region The words

    /** A run of a word in one colour, or ({@code icon} not 0) a small mouse: 'L', 'M', 'W' or 'R' lit. */
    /** A run of a word in one colour: gold, or {@code colour} when it is not 0. */
    private record Bit(String text, boolean gold, int colour) {

        Bit(final String text, final boolean gold) {
            this(text, gold, 0);
        }
    }

    private record Word(List<Bit> bits) {

        int width() {
            int w = 0;
            for (final Bit b : bits) w += Hyb.width(b.text());
            return w;
        }
    }

    private static final int SPACE = 4;
    /**
     * A paragraph's mouse sits in a column of its own left of its text, this wide; paragraphs (lines of the text) are
     * this far apart.
     */
    private static final int ICON_COL = 17, PARA_GAP = 5;

    /** Each paragraph's mouse ('L', 'M', 'W', 'R'), or 0 for none, as laid out last. */
    private final List<Character> paraIcons = new ArrayList<>();

    /** How far a line's text starts in: past its paragraph's mouse when it has one. */
    private int indent(final int line) {
        return paraIcons.get(paragraphs.get(line)) != 0 ? ICON_COL : 0;
    }

    /** Which paragraph (of the text split by new lines) each line laid out last belongs to. */
    private final List<Integer> paragraphs = new ArrayList<>();

    /**
     * The text in lines no wider than the box allows, each word in its runs of colour (gold between asterisks); a new
     * line starts a paragraph, and {M}, {W}, {L} or {R} is a small mouse with that button lit.
     */
    private List<List<Word>> layout(final String text) {
        final List<List<Word>> lines = new ArrayList<>();
        paragraphs.clear();
        paraIcons.clear();
        boolean gold = false;
        final String[] paras = text.split("\n");
        for (int p = 0; p < paras.length; p++) {
            String para = paras[p].trim();
            char icon = 0;
            if (para.length() >= 3 && para.charAt(0) == '{' && para.charAt(2) == '}') {
                icon = para.charAt(1);
                para = para.substring(3);
            }
            paraIcons.add(icon);
            final int room = TEXT_W - (icon != 0 ? ICON_COL : 0);
            List<Word> line = new ArrayList<>();
            int lineW = 0;
            for (final String raw : para.split(" ")) {
                if (raw.isEmpty()) continue;
                final List<Bit> bits = new ArrayList<>();
                // A word in the inputs' blue or the outputs' orange: {b:Blue}, {o:Orange}, punctuation after it kept.
                if (raw.length() > 4 && raw.charAt(0) == '{' && raw.charAt(2) == ':' && raw.indexOf('}') > 3) {
                    final int end = raw.indexOf('}');
                    final int colour = raw.charAt(1) == 'b' ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
                    bits.add(new Bit(raw.substring(3, end), false, colour));
                    if (end + 1 < raw.length()) bits.add(new Bit(raw.substring(end + 1), gold));
                    final Word word = new Word(bits);
                    final int w = word.width();
                    if (!line.isEmpty() && lineW + SPACE + w > room) {
                        lines.add(line);
                        paragraphs.add(p);
                        line = new ArrayList<>();
                        lineW = 0;
                    }
                    lineW += (line.isEmpty() ? 0 : SPACE) + w;
                    line.add(word);
                    continue;
                }
                final StringBuilder run = new StringBuilder();
                for (final char ch : raw.toCharArray()) {
                    if (ch != '*') {
                        run.append(ch);
                        continue;
                    }
                    if (run.length() > 0) bits.add(new Bit(run.toString(), gold));
                    run.setLength(0);
                    gold = !gold;
                }
                if (run.length() > 0) bits.add(new Bit(run.toString(), gold));
                if (bits.isEmpty()) continue;
                final Word word = new Word(bits);
                final int w = word.width();
                if (!line.isEmpty() && lineW + SPACE + w > room) {
                    lines.add(line);
                    paragraphs.add(p);
                    line = new ArrayList<>();
                    lineW = 0;
                }
                lineW += (line.isEmpty() ? 0 : SPACE) + w;
                line.add(word);
            }
            if (!line.isEmpty()) {
                lines.add(line);
                paragraphs.add(p);
            }
        }
        return lines;
    }

    /**
     * A paragraph's mouse, 11 by 15 at {@code x, y}: a grey rounded body, its two buttons split by the wheel, the one
     * in
     * use ({@code which}: 'L', 'M', 'W' or 'R') bright gold.
     */
    private static void mouseIcon(final float x, final float y, final char which, final float e) {
        final int edge = fade(0xFF0B0C0E, e), body = fade(0xFF8A8E97, e), gold = fade(GOLD, e);
        if (which == 'K') {
            // A key cap: its rim, its face, a gold glint on top.
            Hyb.rect(x, y + 1, 12, 13, edge);
            Hyb.rect(x + 1, y + 2, 10, 11, fade(0xFF5A5C65, e));
            Hyb.rect(x + 2, y + 2, 8, 8, body);
            Hyb.rect(x + 3, y + 4, 6, 1, gold);
            return;
        }
        Hyb.rect(x + 1, y, 9, 15, edge);
        Hyb.rect(x, y + 1, 11, 13, edge);
        Hyb.rect(x + 1, y + 1, 9, 13, body);
        // The buttons either side of the wheel, the line under them, and the wheel in its slot.
        if (which == 'L') Hyb.rect(x + 1, y + 1, 4, 5, gold);
        if (which == 'R') Hyb.rect(x + 6, y + 1, 4, 5, gold);
        Hyb.rect(x + 1, y + 6, 9, 1, edge);
        Hyb.rect(x + 5, y + 1, 1, 5, edge);
        if (which == 'M' || which == 'W') Hyb.rect(x + 4, y + 2, 3, 3, gold);
    }

    private static int width(final List<Word> line) {
        int w = 0;
        for (final Word word : line) w += word.width();
        return w + Math.max(0, line.size() - 1) * SPACE;
    }

    // endregion

    // region Keys

    private enum Glyph {
        BACK,
        NEXT,
        DONE,
        LEAVE
    }

    /** A key at {@code x, y} in the note's own pixels; {@code mx, my} the mouse on the screen. */
    private void key(final Glyph g, final float x, final float y, final float mx, final float my, final float e,
        final boolean lit, final boolean enabled, final Runnable action) {
        final Rect r = new Rect(ox + x * os, oy + y * os, KEY * os, KEY * os);
        final boolean hot = enabled && r.contains(mx, my);
        if (lit) Hyb.rect(x, y, KEY, KEY, fade(hot ? 0xFFFFE08A : GOLD, e));
        else if (hot) Hyb.rect(x, y, KEY, KEY, fade(HOT, e));
        final int ink = fade(!enabled ? OFF : lit ? ON_GOLD : hot ? GOLD : Hyb.INK, e);
        glyph(g, x, y, ink);
        hits.add(new Hit(r.grow(os), g, action, enabled));
    }

    /** Drawn a pixel at a time: the game's font has no arrows or crosses at this size. */
    private static void glyph(final Glyph g, final float x, final float y, final int ink) {
        switch (g) {
            case NEXT -> {
                for (int i = 0; i < 4; i++) Hyb.rect(x + 4 + i, y + 2 + i, 1, 8 - 2 * i, ink);
            }
            case BACK -> {
                for (int i = 0; i < 4; i++) Hyb.rect(x + 7 - i, y + 2 + i, 1, 8 - 2 * i, ink);
            }
            case LEAVE -> {
                for (int i = 0; i < 6; i++) {
                    Hyb.rect(x + 3 + i, y + 3 + i, 1, 1, ink);
                    Hyb.rect(x + 8 - i, y + 3 + i, 1, 1, ink);
                }
            }
            case DONE -> {
                for (int i = 0; i < 3; i++) Hyb.rect(x + 2 + i, y + 5 + i, 1, 2, ink);
                for (int i = 0; i < 5; i++) Hyb.rect(x + 5 + i, y + 7 - i, 1, 2, ink);
            }
        }
    }

    // endregion

    private static float clamp(final float v, final float lo, final float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int fade(final int argb, final float a) {
        return Math.round((argb >>> 24) * a) << 24 | argb & 0xFFFFFF;
    }
}
