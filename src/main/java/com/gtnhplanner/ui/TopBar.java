package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Unit;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The planner's top bar, for the account's things only: the plan tabs (with their live status and faces), then the
 * keys in their groups (plans, share and the account; feedback, settings and help). What edits or views the board is
 * in {@link BoardToolbar}, along the top of the board.
 *
 * <p>
 * It is always one row and hides no key, as the website's bar ({@link TopBarFit}). As the planner narrows the tabs give
 * way first (they scroll, the open plan's tab kept in view; their end folds the faces to two and a count, in
 * {@link PlanTabs}), then Plans, Share and Sign in show only their pictures, as feedback, settings and help do, then
 * the keys sit closer. The open plan's tab and "+" always stay.
 */
final class TopBar extends ParentWidget<TopBar> {

    /** The bar's height, and the keys' on it. */
    static final int ROW_H = 20, KEY_H = 16;
    /** A key with only its picture. */
    static final int PICTURE_W = 18;
    private static final int PAD = 4, GAP = 3, GROUP_GAP = 9, TIGHT_GAP = 1, TIGHT_GROUP_GAP = 4;
    private static final int BG = 0xFF0E0F12, LINE = 0xFF2A2C31;

    /** A key: its group, and its width with its label and with only its picture (the same for a picture key). */
    private record Key(Widget<?> widget, int group, IntSupplier labelled, IntSupplier picture) {

        int width(final int step) {
            return (step == TopBarFit.ROOMY ? labelled : picture).getAsInt();
        }
    }

    /** Where everything goes: the dress, the tabs' width, and each key's place and width. */
    private record Layout(int step, int tabsW, int[] x, int[] w) {}

    private final PlanTabs tabs;
    private final List<Key> keys = new ArrayList<>();
    private Layout laid;
    private int laidWidth = -1, laidTabs = -1, laidKeys = -1;

    TopBar(final PlanTabs tabs) {
        this.tabs = tabs;
        widthRel(1f);
        height(ROW_H);
        background((IDrawable) (ctx, x, y, w, h, theme) -> {
            Hyb.rect(x, y, w, h, BG);
            Hyb.rect(x, y + h - 1, w, 1, LINE);
        });
        tabs.left(PAD)
            .top(2)
            .height(KEY_H)
            .width(() -> layout().tabsW(), Unit.Measure.PIXEL);
        child(tabs);
    }

    /** Adds a key to {@code group} (groups in order, from 0), as wide with its label as with only its picture. */
    <W extends Widget<?>> W key(final int group, final W widget, final int width) {
        return key(group, widget, () -> width, () -> width);
    }

    /**
     * Adds a key to {@code group}, {@code labelled} wide with its label and {@code picture} wide with only its picture
     * (a key may change its width: the account's is "Sign in", then a face).
     */
    <W extends Widget<?>> W key(final int group, final W widget, final IntSupplier labelled,
        final IntSupplier picture) {
        final int i = keys.size();
        keys.add(new Key(widget, group, labelled, picture));
        widget.left(() -> layout().x()[i], Unit.Measure.PIXEL)
            .top(2)
            .width(() -> layout().w()[i], Unit.Measure.PIXEL)
            .height(KEY_H);
        child(widget);
        return widget;
    }

    /** The bar's height: always one row. */
    int barHeight() {
        return ROW_H;
    }

    /** The dress the keys are in now ({@link TopBarFit}). */
    int step() {
        return layout().step();
    }

    /** The planner's width, which the bar spans. */
    private int barWidth() {
        final ModularPanel panel = isValid() ? getPanel() : null;
        final int w = panel != null ? panel.getArea().width : getArea().width;
        return w > 0 ? w : getArea().width;
    }

    /** The keys' widths added up: it moves when one of them changes its width. */
    private int keysSig() {
        int sig = 0;
        for (final Key k : keys) sig = sig * 31 + k.labelled()
            .getAsInt() * 7
            + k.picture()
                .getAsInt();
        return sig;
    }

    /** What the tabs ask for: it moves when a plan opens, closes or is renamed, or someone comes or goes. */
    private int tabsSig() {
        return tabs.want() * 31 + tabs.endLeast();
    }

    private Layout layout() {
        final int width = barWidth(), tabsSig = tabsSig(), sig = keysSig();
        if (laid == null || width != laidWidth || tabsSig != laidTabs || sig != laidKeys) {
            laid = lay(width);
            laidWidth = width;
            laidTabs = tabsSig;
            laidKeys = sig;
        }
        return laid;
    }

    /** The planner was resized, or the tabs or keys changed: lay the bar out again. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (barWidth() != laidWidth || tabsSig() != laidTabs || keysSig() != laidKeys) {
            layout();
            final ModularPanel panel = getPanel();
            if (panel != null) panel.scheduleResize();
        }
    }

    // region The layout

    /** The keys at the bar's right end in the widest dress that leaves the tabs their room; the tabs take the rest. */
    private Layout lay(final int width) {
        final int room = Math.max(0, width - 2 * PAD), n = keys.size();
        final int[] spans = new int[TopBarFit.TIGHT + 1];
        for (int step = 0; step <= TopBarFit.TIGHT; step++) spans[step] = span(step) + groupGap(step);
        final int step = TopBarFit.step(room, spans, tabs.want(), tabs.endLeast());
        final int[] x = new int[n], w = new int[n];
        int at = PAD + room - span(step);
        for (int i = 0; i < n; i++) {
            if (i > 0) at += gapBefore(i, step);
            x[i] = at;
            w[i] = keys.get(i)
                .width(step);
            at += w[i];
        }
        return new Layout(step, Math.max(0, room - spans[step]), x, w);
    }

    private static int groupGap(final int step) {
        return step == TopBarFit.TIGHT ? TIGHT_GROUP_GAP : GROUP_GAP;
    }

    /** The gap before key {@code i}: a group's between groups. */
    private int gapBefore(final int i, final int step) {
        final boolean newGroup = keys.get(i)
            .group()
            != keys.get(i - 1)
                .group();
        if (step == TopBarFit.TIGHT) return newGroup ? TIGHT_GROUP_GAP : TIGHT_GAP;
        return newGroup ? GROUP_GAP : GAP;
    }

    /** The keys' width side by side in a dress. */
    private int span(final int step) {
        int w = 0;
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) w += gapBefore(i, step);
            w += keys.get(i)
                .width(step);
        }
        return w;
    }

    // endregion
}
