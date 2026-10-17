package com.gtnhplanner.ui.popup;

import java.util.function.DoubleConsumer;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.utils.MathUtils;
import com.cleanroommc.modularui.utils.ParseResult;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A number typed straight into its box, where it is shown (a port's or a drawer's rate, an overview row's rate or
 * machine count): no popup. It starts with the number there selected, so typing replaces it; ModularUI's maths reads it
 * ("2.5k", "1M", "64/3"); Enter or a click elsewhere sets it, Escape leaves it as it was, and an empty box clears it
 * (0). The widget that owns the box holds the editor, takes the keyboard while it is editing (it is the focused
 * widget), and draws the box through {@link #drawBox}.
 */
public final class InlineNumber {

    /** The editor typed into last while it is still in use, and how many times any has started (for the tour). */
    private static InlineNumber current;
    private static int starts;

    /** What is being edited (the owner's key for the box), or null. */
    private String key;
    private String draft = "";
    private int caret, anchor;
    private long since;
    private DoubleConsumer commit;

    /** Starts editing the box {@code key}, showing {@code current} selected; {@code commit} takes what is set. */
    public void start(final String key, final double current, final DoubleConsumer commit) {
        this.key = key;
        this.commit = commit;
        draft = NumberPopup.format(current);
        anchor = 0;
        caret = draft.length();
        since = System.currentTimeMillis();
        InlineNumber.current = this;
        starts++;
    }

    /** The box being typed into now, anywhere; null for none (the tour types into it as a player would). */
    public static InlineNumber current() {
        return current != null && current.active() ? current : null;
    }

    /** How many times a box has been started, so a step can tell a new one from one still open. */
    public static int starts() {
        return starts;
    }

    /** Puts {@code text} in the box, the caret at its end, as if typed over everything. */
    public void setText(final String text) {
        draft = text;
        caret = anchor = text.length();
        since = System.currentTimeMillis();
    }

    public boolean active() {
        return key != null;
    }

    public boolean editing(final String key) {
        return this.key != null && this.key.equals(key);
    }

    /** Sets what was typed (empty: 0; what does not read as a number changes nothing) and stops editing. */
    public void commit() {
        if (key == null) return;
        key = null;
        final String t = draft.trim();
        if (t.isEmpty()) {
            commit.accept(0);
            return;
        }
        final ParseResult r = MathUtils.parseExpression(t, Double.NaN);
        if (!r.isSuccess() || !r.hasValue()) return;
        final double v = r.getResult()
            .getNumberValue()
            .doubleValue();
        if (!Double.isNaN(v) && !Double.isInfinite(v)) commit.accept(Math.max(0, v));
    }

    /** Stops editing, leaving the number as it was. */
    public void cancel() {
        key = null;
    }

    /** A key while editing; always taken, so nothing else on the board acts on it. */
    public void keyPressed(final char typed, final int code) {
        if (key == null) return;
        since = System.currentTimeMillis();
        final boolean ctrl = GuiScreen.isCtrlKeyDown(), shift = GuiScreen.isShiftKeyDown();
        switch (code) {
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> commit();
            case Keyboard.KEY_ESCAPE -> cancel();
            case Keyboard.KEY_BACK -> {
                if (anchor == caret) anchor = Math.max(0, caret - 1);
                insert("");
            }
            case Keyboard.KEY_DELETE -> {
                if (anchor == caret) anchor = Math.min(draft.length(), caret + 1);
                insert("");
            }
            case Keyboard.KEY_LEFT -> move(anchor != caret && !shift ? Math.min(caret, anchor) : caret - 1, shift);
            case Keyboard.KEY_RIGHT -> move(anchor != caret && !shift ? Math.max(caret, anchor) : caret + 1, shift);
            case Keyboard.KEY_HOME -> move(0, shift);
            case Keyboard.KEY_END -> move(draft.length(), shift);
            default -> {
                if (ctrl && code == Keyboard.KEY_A) {
                    anchor = 0;
                    caret = draft.length();
                } else if (ctrl && code == Keyboard.KEY_V) insert(clean(GuiScreen.getClipboardString()));
                else if (!ctrl && allowed(typed)) insert(String.valueOf(typed));
            }
        }
    }

    /** What a number may be typed with: digits, a point, SI prefixes, and the maths between them. */
    private static boolean allowed(final char c) {
        return c >= '0' && c <= '9' || "., kKmMgGtTpPuUnNeE/*+-()".indexOf(c) >= 0;
    }

    private static String clean(final String s) {
        if (s == null) return "";
        final StringBuilder b = new StringBuilder();
        for (final char c : s.toCharArray()) if (allowed(c)) b.append(c);
        return b.toString();
    }

    private void insert(final String s) {
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        draft = draft.substring(0, a) + s + draft.substring(b);
        caret = anchor = a + s.length();
    }

    private void move(final int to, final boolean extend) {
        caret = Math.max(0, Math.min(draft.length(), to));
        if (!extend) anchor = caret;
    }

    /**
     * The box being edited at {@code x, y} ({@code w} by {@code h}): a gold edge, what has been typed centred in it
     * (the selection behind it, the caret blinking), the text's top at {@code textY}.
     */
    public void drawBox(final float x, final float y, final float w, final float h, final float textY) {
        Hyb.rect(x, y, w, h, Hyb.GOLD);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, 0xFF15171C);
        final String shown = draft;
        final float tw = Hyb.width(shown), tx = x + Math.max(2, (w - tw) / 2f);
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        if (a != b) {
            final float sx = tx + Hyb.width(shown.substring(0, a)), ex = tx + Hyb.width(shown.substring(0, b));
            Hyb.rect(sx, y + 2, ex - sx, h - 4, 0xFF35507A);
        }
        Hyb.text(shown, tx, textY, Hyb.INK);
        if ((System.currentTimeMillis() - since) % 1000 < 600) {
            final float cx = tx + Hyb.width(shown.substring(0, caret));
            Hyb.rect(cx, y + 2, 1, h - 4, Hyb.INK);
        }
    }

    /**
     * What has been typed, in place of a label that keeps the size it had (a chip's): no box of its own, centred in
     * {@code x, y, w, h} in {@code ink}, as large as the chip's label (1.5) where it fits, else plain, its tail kept
     * when even that is too long; the selection behind it and the caret blinking.
     */
    public void drawText(final float x, final float w, final float y, final float h, final int ink) {
        final float s = Hyb.width(draft) * 1.5f <= w ? 1.5f : 1f;
        int from = 0;
        while (from < draft.length() && Hyb.width(draft.substring(from)) * s > w) from++;
        final String shown = draft.substring(from);
        final float tw = Hyb.width(shown) * s, tx = x + (w - tw) / 2f, ty = y + (h - 7 * s) / 2f;
        final int a = Math.max(0, Math.min(caret, anchor) - from), b = Math.max(0, Math.max(caret, anchor) - from);
        if (a != b) {
            final float sx = tx + Hyb.width(shown.substring(0, a)) * s, ex = tx + Hyb.width(shown.substring(0, b)) * s;
            Hyb.rect(sx, y, ex - sx, h, 0x8035507A);
        }
        Hyb.text(shown, tx, ty, s, ink, false);
        if ((System.currentTimeMillis() - since) % 1000 < 600) {
            final float cx = tx + Hyb.width(shown.substring(0, Math.max(0, caret - from))) * s;
            Hyb.rect(cx, y, 1, h, ink);
        }
    }
}
