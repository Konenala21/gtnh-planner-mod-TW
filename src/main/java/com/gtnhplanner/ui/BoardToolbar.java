package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Unit;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The strip along the top of the board, in the middle column between the overview and NEI: the keys that edit or view
 * the board (the non-recipe machines; undo and redo; arrange and fit; the rate unit, EU/t and average or peak). The top
 * bar above holds the account's things only. A task board has none of these: it shows its own header here instead
 * ({@code ui/task/TaskBoardView}).
 *
 * <p>
 * It gives way as the board narrows the way {@link TopBar} does: the keys first sit closer (the non-recipe key keeps
 * only its bolt, arrange's progress only its number), then wrap onto more rows, a group at a time.
 */
final class BoardToolbar extends ParentWidget<BoardToolbar> {

    private static final int PAD = 4, GAP = 3, GROUP_GAP = 9, TIGHT_GAP = 1, TIGHT_GROUP_GAP = 4;
    private static final int BG = 0xFF0E0F12, LINE = 0xFF2A2C31;

    /** A key: its group, and its width with room to spare and when the strip is tight. */
    private record Key(Widget<?> widget, int group, int roomy, int tight) {}

    /** Where everything goes: the rows, and each key's place and width. */
    private record Layout(int rows, int[] x, int[] y, int[] w) {}

    private final List<Key> keys = new ArrayList<>();
    private Layout laid;
    private int laidWidth = -1;

    BoardToolbar() {
        height(() -> layout().rows() * TopBar.ROW_H, Unit.Measure.PIXEL);
        background((IDrawable) (ctx, x, y, w, h, theme) -> {
            Hyb.rect(x, y, w, h, BG);
            Hyb.rect(x, y + h - 1, w, 1, LINE);
        });
    }

    /**
     * Adds a key to {@code group} (groups in order, from 0), {@code roomy} wide with room and {@code tight} without.
     */
    <W extends Widget<?>> W key(final int group, final W widget, final int roomy, final int tight) {
        final int i = keys.size();
        keys.add(new Key(widget, group, roomy, tight));
        widget.left(() -> layout().x()[i], Unit.Measure.PIXEL)
            .top(() -> layout().y()[i], Unit.Measure.PIXEL)
            .width(() -> layout().w()[i], Unit.Measure.PIXEL)
            .height(TopBar.KEY_H);
        child(widget);
        return widget;
    }

    /** The strip's height now: a row, or more when the keys wrapped; nothing while it is hidden. */
    int barHeight() {
        return isEnabled() ? layout().rows() * TopBar.ROW_H : 0;
    }

    private int barWidth() {
        final int w = getArea().width;
        return w > 0 ? w : 100_000;
    }

    private Layout layout() {
        final int width = barWidth();
        if (laid == null || width != laidWidth) {
            laid = lay(width);
            laidWidth = width;
        }
        return laid;
    }

    /** The board was resized: lay the strip out again. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (barWidth() != laidWidth) {
            layout();
            final com.cleanroommc.modularui.screen.ModularPanel panel = getPanel();
            if (panel != null) panel.scheduleResize();
        }
    }

    private Layout lay(final int width) {
        final int room = Math.max(0, width - 2 * PAD), n = keys.size();
        final int[] x = new int[n], y = new int[n], w = new int[n];
        for (final boolean tight : new boolean[] { false, true }) {
            if (span(0, n, tight) > room) continue;
            place(0, n, PAD, 2, tight, x, y, w);
            return new Layout(1, x, y, w);
        }
        // Rows, wrapped a group at a time; only a group wider than a row breaks between its keys.
        final List<int[]> rows = new ArrayList<>();
        int from = 0;
        for (int i = 1; i < n; i++) {
            final boolean groupStart = keys.get(i)
                .group()
                != keys.get(i - 1)
                    .group();
            if (span(from, groupStart ? groupEnd(i) : i + 1, true) > room) {
                rows.add(new int[] { from, i });
                from = i;
            }
        }
        if (n > 0) rows.add(new int[] { from, n });
        for (int r = 0; r < rows.size(); r++)
            place(rows.get(r)[0], rows.get(r)[1], PAD, r * TopBar.ROW_H + 2, true, x, y, w);
        return new Layout(Math.max(1, rows.size()), x, y, w);
    }

    private int groupEnd(final int i) {
        int end = i;
        while (end < keys.size() && keys.get(end)
            .group()
            == keys.get(i)
                .group())
            end++;
        return end;
    }

    private int span(final int from, final int to, final boolean tight) {
        int w = 0;
        for (int i = from; i < to; i++) {
            final Key k = keys.get(i);
            if (i > from) w += k.group() != keys.get(i - 1)
                .group() ? tight ? TIGHT_GROUP_GAP : GROUP_GAP : tight ? TIGHT_GAP : GAP;
            w += tight ? k.tight() : k.roomy();
        }
        return w;
    }

    private void place(final int from, final int to, final int left, final int top, final boolean tight, final int[] x,
        final int[] y, final int[] w) {
        int at = left;
        for (int i = from; i < to; i++) {
            final Key k = keys.get(i);
            if (i > from) at += k.group() != keys.get(i - 1)
                .group() ? tight ? TIGHT_GROUP_GAP : GROUP_GAP : tight ? TIGHT_GAP : GAP;
            x[i] = at;
            y[i] = top;
            w[i] = tight ? k.tight() : k.roomy();
            at += w[i];
        }
    }
}
