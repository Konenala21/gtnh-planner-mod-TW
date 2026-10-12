package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * The tour's cursor, drawn over everything: the arrow the game's own looks like, in the planner's gold so it never
 * passes for the player's, with a soft shadow. A click dips it and leaves a ring spreading from its tip; a key the tour
 * presses shows as a key cap beside it; a wheel turn shows as a small arrow.
 */
final class Ghost {

    /** The arrow, a row a string: '#' its outline, 'o' its fill. Its tip is the top-left pixel. */
    private static final String[] ARROW = { "#", "##", "#o#", "#oo#", "#ooo#", "#oooo#", "#ooooo#", "#oooooo#",
        "#ooooooo#", "#oooooooo#", "#ooooo#####", "#oo#oo#", "#o# #oo#", "##  #oo#", "#    #oo#", "     #oo#",
        "      ##" };

    private static final int FILL = 0xFFFFE7A0, OUTLINE = 0xFF17181C;
    /** The small mouse's wheel, for {@link #mouse}. */
    static final int WHEEL = -2;
    /** The button the small mouse shows lit, or -1 when it does not show; when it went, to fade it. */
    private int mouseButton = -1;
    private long mouseAt = -1, mouseGone = -1;

    private record Ring(float x, float y, long at) {}

    private record Cap(String label, long at, long until) {}

    private final List<Ring> rings = new ArrayList<>();
    private Cap cap;
    /** When the button went down, or -1 while it is up. */
    private long downAt = -1;
    private float wheelDir;
    private long wheelAt = -1;
    /** How visible it is, 0 to 1, eased toward shown or hidden. */
    private float alpha;
    private boolean shown;

    void show(final boolean on) {
        shown = on;
    }

    void down() {
        downAt = System.currentTimeMillis();
    }

    void up(final float x, final float y) {
        downAt = -1;
        rings.add(new Ring(x, y, System.currentTimeMillis()));
    }

    void key(final String label, final long ms) {
        final long now = System.currentTimeMillis();
        cap = new Cap(label, now, now + ms);
    }

    void wheel(final int notches) {
        wheelDir = Math.signum(notches);
        wheelAt = System.currentTimeMillis();
    }

    /** Shows a small mouse beside the pointer, {@code button} lit (0 left, 1 right, 2 middle, {@link #WHEEL}). */
    void mouse(final int button) {
        mouseButton = button;
        mouseAt = System.currentTimeMillis();
        mouseGone = -1;
    }

    /** The small mouse fades out. */
    void mouseOff() {
        if (mouseButton != -1 && mouseGone < 0) mouseGone = System.currentTimeMillis();
    }

    void clear() {
        rings.clear();
        cap = null;
        downAt = -1;
        wheelAt = -1;
        mouseButton = -1;
    }

    void draw(final float x, final float y, final float dt) {
        alpha += ((shown ? 1 : 0) - alpha) * Math.min(1, dt / 120f);
        final long now = System.currentTimeMillis();
        for (final Iterator<Ring> it = rings.iterator(); it.hasNext();) {
            final Ring r = it.next();
            final float t = (now - r.at) / 420f;
            if (t >= 1) {
                it.remove();
                continue;
            }
            final float e = 1 - (1 - t) * (1 - t);
            final int a = Math.round(200 * (1 - t) * alpha);
            ring(r.x, r.y, 3 + 13 * e, 1.5f, a << 24 | 0xFFD257);
        }
        if (alpha < 0.02f) return;
        if (wheelAt >= 0 && now - wheelAt < 500) {
            // A small arrow beside the tip, up or down, as the wheel turns.
            final float t = (now - wheelAt) / 500f;
            final int a = Math.round(230 * (1 - t) * alpha);
            final float wx = x + 14, wy = y + 6 - wheelDir * 4 * t;
            final int c = a << 24 | 0xFFD257;
            if (wheelDir > 0) Hyb.triangle(wx, wy - 4, wx - 4, wy + 2, wx + 4, wy + 2, c);
            else Hyb.triangle(wx, wy + 4, wx - 4, wy - 2, wx + 4, wy - 2, c);
        }
        final float press = downAt < 0 ? 0 : Math.min(1, (now - downAt) / 70f);
        final float scale = 1 - 0.12f * press;
        final int fa = Math.round(255 * alpha);
        Hyb.beginBatch();
        // Shadow, then the arrow, shrunk a little toward its tip while the button is down.
        for (int row = 0; row < ARROW.length; row++) {
            final String s = ARROW[row];
            for (int col = 0; col < s.length(); col++) {
                if (s.charAt(col) == ' ') continue;
                Hyb.rect(x + 1.5f + col * scale, y + 2 + row * scale, scale, scale, Math.round(70 * alpha) << 24);
            }
        }
        for (int row = 0; row < ARROW.length; row++) {
            final String s = ARROW[row];
            for (int col = 0; col < s.length(); col++) {
                final char ch = s.charAt(col);
                if (ch == ' ') continue;
                final int c = ch == '#' ? OUTLINE : FILL;
                Hyb.rect(x + col * scale, y + row * scale, scale, scale, fa << 24 | c & 0xFFFFFF);
            }
        }
        Hyb.endBatch();
        if (cap != null) {
            if (now > cap.until) cap = null;
            else drawCap(x + 16, y + 14, now);
        }
        if (mouseButton != -1) drawMouse(x + 13, y + 15, now);
    }

    /**
     * The small mouse: a rounded body, its two buttons split by the wheel, the button in use gold (the wheel's arrow
     * shows it turning). It pops in and fades when let go.
     */
    private void drawMouse(final float x, final float y, final long now) {
        final float in = Math.min(1, (now - mouseAt) / 140f);
        final float out = mouseGone < 0 ? 1 : 1 - Math.min(1, (now - mouseGone) / 260f);
        if (out <= 0) {
            mouseButton = -1;
            return;
        }
        final float a = in * out * alpha;
        final int al = Math.round(255 * a);
        final int edge = al << 24 | 0x17181C, body = al << 24 | 0xD9DAE0, dark = al << 24 | 0x9A9CA4,
            gold = al << 24 | 0xFFD257;
        final int w = 13, h = 19, split = 8;
        Hyb.beginBatch();
        Hyb.rect(x + 1.5f, y + 2, w, h, Math.round(70 * a) << 24);
        // The body, its corners rounded a pixel.
        Hyb.rect(x + 1, y, w - 2, h, edge);
        Hyb.rect(x, y + 1, w, h - 2, edge);
        Hyb.rect(x + 2, y + 1, w - 4, h - 2, body);
        Hyb.rect(x + 1, y + 2, w - 2, h - 4, body);
        // The buttons: left and right halves of the top, split by the wheel's slot; the line under them.
        final int half = (w - 2) / 2;
        if (mouseButton == 0) Hyb.rect(x + 1, y + 1, half, split - 1, gold);
        if (mouseButton == 1) Hyb.rect(x + w - 1 - half, y + 1, half, split - 1, gold);
        Hyb.rect(x + 1, y + split, w - 2, 1, edge);
        Hyb.rect(x + w / 2f - 0.5f, y + 1, 1, split - 1, edge);
        // The wheel, in its slot.
        final boolean wheel = mouseButton == 2 || mouseButton == WHEEL;
        Hyb.rect(x + w / 2f - 1.5f, y + 2, 3, split - 3, edge);
        Hyb.rect(x + w / 2f - 0.5f, y + 2.5f, 1, split - 4, wheel ? gold : dark);
        Hyb.endBatch();
    }

    /** The key being pressed, as a key cap: it pops in, dips once, and fades. */
    private void drawCap(final float x, final float y, final long now) {
        final float in = Math.min(1, (now - cap.at) / 120f);
        final float out = Math.min(1, (cap.until - now) / 200f);
        final float a = Math.min(in, out) * alpha;
        final boolean dipped = now - cap.at > 160 && now - cap.at < 300;
        final int w = Math.max(16, Hyb.width(cap.label) + 10), h = 16;
        final float dy = dipped ? 1.5f : 0;
        final int al = Math.round(255 * a);
        Hyb.rect(x, y + 2, w, h, Math.round(120 * a) << 24);
        Hyb.rect(x, y + dy, w, h, al << 24 | 0x5A5C65);
        Hyb.rect(x + 1, y + dy + 1, w - 2, h - 3, al << 24 | 0x3C3E45);
        Hyb.rect(x + 1, y + dy + 1, w - 2, 1, al << 24 | 0x6E7079);
        if (al > 8)
            Hyb.text(cap.label, x + (w - Hyb.width(cap.label)) / 2f, y + dy + 4, 1f, al << 24 | 0xE8E9EE, false);
    }

    /** A ring of {@code thickness} around (cx, cy), in 32 slices. */
    static void ring(final float cx, final float cy, final float r, final float thickness, final int argb) {
        final int n = 32;
        final float r0 = Math.max(0, r - thickness);
        Hyb.beginBatch();
        for (int i = 0; i < n; i++) {
            final double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            final float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            final float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            Hyb.triangle(cx + c0 * r0, cy + s0 * r0, cx + c0 * r, cy + s0 * r, cx + c1 * r, cy + s1 * r, argb);
            Hyb.triangle(cx + c0 * r0, cy + s0 * r0, cx + c1 * r, cy + s1 * r, cx + c1 * r0, cy + s1 * r0, argb);
        }
        Hyb.endBatch();
    }
}
