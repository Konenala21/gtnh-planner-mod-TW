package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * One leftover and the places that could bear it, as the overview's Spare section and a card's spare popup
 * ({@link SparePopup}) show it: a header saying what it is ("Surplus", or "Imported" for a shortfall) with Split and
 * Auto keys at its end, then a line per place, most upstream first: its icon and name, how much it bears (in the rate
 * unit the section's header names, as the overview's other rows), and a small bar of its share. The caller lays the
 * output's or source's tint under it ({@link #tint}), as under the overview's outputs and inputs. A click on a line
 * puts all of it there; a click or a drag on a
 * line's bar sets that place's share there (the others giving or taking in proportion); Split shares it evenly between
 * every place; Auto (greyed until it is split by hand) puts it back where the solver puts it. A leftover with one place
 * is a single line, under the section's "No other place".
 *
 * <p>
 * Drawn in the caller's coordinates; {@link #hitAt} answers against what was drawn last.
 */
final class SpareBar {

    static final int HEAD = 13, ROW = 13, MINI_W = 30, KEY_W = 28, KEY_H = 11;
    private static final int DIM = 0xFF9A9DA6, FAINT = 0xFF8A8D96;
    /** A share below this reads as none. */
    static final double SHOWN = 0.004;

    enum Kind {
        /** The header: hover it for what the leftover is. */
        HEAD,
        /** A place's line: a click puts all of it there. */
        ROW,
        /** A line's small bar: a click or a drag sets its share. */
        FILL,
        AUTO,
        SPLIT
    }

    record Hit(Kind kind, int index) {}

    private record Rect(Kind kind, int index, float x0, float y0, float x1, float y1) {}

    private final List<Rect> rects = new ArrayList<>();
    /** How wide each line's bar is: small in a popup, the drawer controls' width in the overview. */
    private int barW = MINI_W;

    /** Sets how wide each line's bar is. */
    void barWidth(final int w) {
        barW = w;
    }

    /** The line whose bar is in hand, the shares while dragging, those it began with, and what was stored before. */
    private BoardSession.SpareLine dragLine;
    private int dragRow = -1;
    private double[] dragShares, dragStart;
    private Map<String, Double> dragBefore;

    /** The height {@code line} takes: its header and a line per place, or the one line. */
    static int height(final BoardSession.SpareLine line) {
        final int n = line.bearers()
            .size();
        return n < 2 ? ROW : HEAD + n * ROW;
    }

    /** Draws {@code line} at {@code x, y}, {@code w} wide, with {@code hover} lit. Returns the height used. */
    int draw(final BoardSession session, final BoardSession.SpareLine line, final float x, final float y, final int w,
        final float z, final Hit hover) {
        return draw(session, line, x, y, w, z, hover, null);
    }

    /** As above, the place keyed {@code here} (the one a card's row was clicked on) marked so. */
    int draw(final BoardSession session, final BoardSession.SpareLine line, final float x, final float y, final int w,
        final float z, final Hit hover, final String here) {
        rects.clear();
        final int n = line.bearers()
            .size();
        final int ink = ink(line);
        final Fmt.RateUnit unit = session.rateUnit();
        if (n < 2) {
            row(line, 0, 1, x, y, w, z, hover, here, ink, unit, false);
            return ROW;
        }
        final double[] shares = held(line) && dragShares != null ? dragShares : session.spareShares(line);
        // The header: what it is, then Split and Auto.
        final float autoX = x + w - KEY_W, splitX = autoX - 2 - KEY_W;
        final boolean headHot = hover != null && hover.kind() == Kind.HEAD;
        Hyb.text(UiText.tr("sparebar.forced"), x, y + 3, headHot ? Hyb.INK : ink);
        rects.add(new Rect(Kind.HEAD, -1, x, y, splitX - 2, y + HEAD - 1));
        key(UiText.tr("sparebar.split"), splitX, y + 1, hover != null && hover.kind() == Kind.SPLIT, true);
        rects.add(new Rect(Kind.SPLIT, -1, splitX, y + 1, splitX + KEY_W, y + 1 + KEY_H));
        final boolean custom = session.spareCustom(line);
        key(UiText.tr("sparebar.auto"), autoX, y + 1, custom && hover != null && hover.kind() == Kind.AUTO, custom);
        if (custom) rects.add(new Rect(Kind.AUTO, -1, autoX, y + 1, autoX + KEY_W, y + 1 + KEY_H));
        // A line per place.
        for (int i = 0; i < n; i++) row(line, i, shares[i], x, y + HEAD + i * ROW, w, z, hover, here, ink, unit, true);
        return HEAD + n * ROW;
    }

    /** A key at {@code x, y}, as the overview's Avg key: lit under the mouse, greyed when it would do nothing. */
    private static void key(final String label, final float x, final float y, final boolean hot, final boolean live) {
        Hyb.bevel(x, y, KEY_W, KEY_H, hot ? Hyb.KEY_HOVER : 0xFF202329, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.textCentered(label, x + KEY_W / 2f, y + 1.5f, live ? Hyb.INK : FAINT);
    }

    /** The colour of a leftover: a source's for one brought in, an output's for a surplus. */
    static int ink(final BoardSession.SpareLine line) {
        return line.importing() ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
    }

    /** The faint tint laid under a leftover's lines, as under the overview's inputs and outputs. */
    static int tint(final BoardSession.SpareLine line) {
        return ink(line) & 0x00FFFFFF | 0x0D000000;
    }

    /**
     * Place {@code i}'s line at {@code y}: its icon and name, how much it bears a second, and (when there is a choice)
     * the small bar of its share.
     */
    private void row(final BoardSession.SpareLine line, final int i, final double share, final float x, final float y,
        final int w, final float z, final Hit hover, final String here, final int ink, final Fmt.RateUnit unit,
        final boolean bar) {
        final BoardSession.SpareBearer b = line.bearers()
            .get(i);
        final boolean on = share > SHOWN;
        final boolean hot = place(hover) == i;
        final float right = x + w, mini = right - barW;
        if (hot) Hyb.rect(x - 2, y, w + 4, ROW, 0x1AE8B34A);
        icon(b, x, y + 1.5f, 10, z);
        final float end = bar ? mini - 3 : right;
        // The amount as the overview's other rows say it: the time unit once, in the section's header, litres going
        // without saying; EU/t after the number, quieter.
        final String number = on ? (line.importing() ? "-" : "+") + amount(b, share, unit) : "",
            suffix = on ? suffix(b) : "";
        final float aw = Hyb.width(number) + Hyb.width(suffix) + (suffix.isEmpty() ? 0 : 1);
        if (on) {
            Hyb.text(number, end - aw, y + 3, hot ? Hyb.GOLD : ink);
            Hyb.text(suffix, end - Hyb.width(suffix), y + 3, Hyb.MUTED);
        }
        final String name = labelOf(line, b, here);
        Hyb.text(
            Hyb.fit(name, (int) Math.max(0, end - aw - 4 - (x + 13))),
            x + 13,
            y + 3,
            hot ? Hyb.GOLD : on ? Hyb.INK : DIM);
        if (bar) {
            Hyb.rect(mini, y + 2, barW, ROW - 4, 0xFF15171C);
            if (on) {
                final float fill = Math.max(2, (float) (share * barW));
                Hyb.rect(mini, y + 2, fill, ROW - 4, Hyb.mix(ink, CardPaint.SURFACE, 0.45f));
                Hyb.rect(mini, y + 2, fill, 1, Hyb.mix(ink, CardPaint.SURFACE, 0.7f));
            }
            rects.add(new Rect(Kind.FILL, i, mini - 2, y, right + 1, y + ROW));
        }
        rects.add(new Rect(Kind.ROW, i, x - 2, y, right + 1, y + ROW));
    }

    /** What a place is called on its line: its machine where another place has the same name. */
    private static String labelOf(final BoardSession.SpareLine line, final BoardSession.SpareBearer b,
        final String here) {
        int same = 0;
        for (final BoardSession.SpareBearer other : line.bearers()) if (other.name()
            .equals(b.name())) same++;
        return (same > 1 ? b.machine() : b.name()) + (b.key()
            .equals(here) ? UiText.tr("sparebar.here") : "");
    }

    /** Where a part was last drawn, {x0, y0, x1, y1} in the coordinates it was drawn in; null when it was not. */
    float[] rectOf(final Kind kind, final int index) {
        for (final Rect r : rects)
            if (r.kind() == kind && r.index() == index) return new float[] { r.x0(), r.y0(), r.x1(), r.y1() };
        return null;
    }

    /** What is under a point, in the coordinates it was last drawn in; null for nothing. */
    Hit hitAt(final float x, final float y) {
        for (final Rect r : rects)
            if (x >= r.x0() && x < r.x1() && y >= r.y0() && y < r.y1()) return new Hit(r.kind(), r.index());
        return null;
    }

    /** The place a hit names (a line or its bar), or -1. */
    static int place(final Hit hit) {
        return hit != null && (hit.kind() == Kind.ROW || hit.kind() == Kind.FILL) ? hit.index() : -1;
    }

    // region Handling

    /** A click: on a line, all of it there; Split, evenly everywhere; Auto, the solver's own. True when it acted. */
    boolean click(final BoardSession session, final BoardSession.SpareLine line, final Hit hit) {
        final int n = line.bearers()
            .size();
        if (hit == null || n < 2) return false;
        switch (hit.kind()) {
            case AUTO -> session.clearSpare(line);
            case SPLIT -> {
                final double[] even = new double[n];
                java.util.Arrays.fill(even, 1.0 / n);
                session.setSpareShares(line, even);
            }
            case ROW -> {
                final double[] all = new double[n];
                all[hit.index()] = 1;
                session.setSpareShares(line, all);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * A press on a line's bar takes it: its share goes to where the mouse is along it at once, and follows the mouse
     * until let go. {@code x, w} are where the lines were drawn. True when it took it.
     */
    boolean press(final BoardSession session, final BoardSession.SpareLine line, final Hit hit, final float mouseX,
        final float x, final int w) {
        if (hit == null || hit.kind() != Kind.FILL
            || line.bearers()
                .size() < 2)
            return false;
        dragLine = line;
        dragRow = hit.index();
        dragShares = session.spareShares(line);
        dragStart = dragShares.clone();
        final Map<String, Double> stored = session.graph().spareShares.get(line.key());
        dragBefore = stored == null ? null : new java.util.TreeMap<>(stored);
        drag(session, mouseX, x, w);
        return true;
    }

    boolean dragging() {
        return dragLine != null;
    }

    /** Whether {@code line} is the one whose bar is in hand (by key: every solve brings new line objects). */
    private boolean held(final BoardSession.SpareLine line) {
        return dragLine != null && dragLine.key()
            .equals(line.key());
    }

    /**
     * The bar in hand follows the mouse: its share is where the mouse is along it, in fiftieths, the others giving or
     * taking in proportion. The plan re-solves as it moves (no undo step until it is let go).
     */
    void drag(final BoardSession session, final float mouseX, final float x, final int w) {
        if (dragLine == null) return;
        final float mini = x + w - barW;
        final double share = Math.max(0, Math.min(1, Math.round((mouseX - mini) / barW * 50) / 50.0));
        if (Math.abs(share - dragShares[dragRow]) < 1e-9) return;
        dragShares = nudged(dragShares, dragRow, share - dragShares[dragRow]);
        session.previewSpareShares(dragLine, dragShares, dragBefore);
    }

    /** Lets go of the bar: the split it ends on is one undoable edit, made from what was stored before. */
    void release(final BoardSession session) {
        if (dragLine == null) return;
        final BoardSession.SpareLine line = dragLine;
        final double[] shares = dragShares;
        session.previewSpareShares(line, null, dragBefore);
        final boolean moved = !java.util.Arrays.equals(shares, dragStart);
        dragLine = null;
        dragRow = -1;
        dragShares = null;
        // Let go where it was taken: nothing changed, so nothing to undo either.
        if (moved) session.setSpareShares(line, shares);
    }

    /**
     * {@code shares} with place {@code i} given {@code step} more of the leftover, the others giving it in proportion.
     */
    static double[] nudged(final double[] shares, final int i, final double step) {
        final double[] out = shares.clone();
        final double target = Math.max(0, Math.min(1, out[i] + step));
        final double rest = 1 - out[i], newRest = 1 - target;
        for (int j = 0; j < out.length; j++) {
            if (j == i) continue;
            out[j] = rest > 1e-9 ? out[j] * newRest / rest : newRest / (out.length - 1);
        }
        out[i] = target;
        return out;
    }

    // endregion

    /** What a place carries at a share, a second (or in the plan's unit), litres for fluids: for tooltips. */
    static String rate(final BoardSession.SpareBearer b, final double share, final Fmt.RateUnit unit) {
        return amount(b, share, unit)
            + (Resources.isPower(b.resourceKey()) ? " EU/t" : (b.isFluid() ? " L" : "") + unit.suffix);
    }

    /** The number a place carries at a share: in the plan's rate unit, or EU/t for power. */
    static String amount(final BoardSession.SpareBearer b, final double share, final Fmt.RateUnit unit) {
        return Fmt
            .compact(Resources.isPower(b.resourceKey()) ? share * b.max() / 20 : share * b.max() * unit.perSecond);
    }

    /** What follows the number on a line: " EU/t" for power; nothing else (litres go without saying). */
    static String suffix(final BoardSession.SpareBearer b) {
        return Resources.isPower(b.resourceKey()) ? " EU/t" : "";
    }

    static void icon(final BoardSession.SpareBearer b, final float x, final float y, final int size, final float z) {
        if (Resources.isPower(b.resourceKey())) com.gtnhplanner.ui.card.RecipeCard.euIcon(x, y, size);
        else Hyb.icon(b.item(), b.fluid(), (int) x, (int) y, size, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
}
