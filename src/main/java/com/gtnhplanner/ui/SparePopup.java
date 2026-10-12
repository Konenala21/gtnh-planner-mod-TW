package com.gtnhplanner.ui;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * What a card's spare row opens (a click on it) when its leftover has somewhere else to go: the same slider with labels
 * as the overview's Spare section ({@link SpareBar}), the place clicked marked "here", and under it a small link for a
 * drawer to take it at that port instead. Nothing else: no title, no list. Hovering a place rings its card on the
 * board;
 * the slider is handled as in the overview.
 */
public final class SparePopup extends Widget<SparePopup> implements Interactable {

    private static final int SLIDER_W = 190, PAD = 6, LINK_H = 9;
    private static final float SMALL = 0.75f;
    private static final String DRAWER = "or put a drawer here";

    private final BoardSession session;
    /** The leftover, by key: the solve gives it a new object each time, so it is looked up on every draw. */
    private final String key;
    private final String here;
    private final Runnable drawer;
    private final SpareBar bar = new SpareBar();
    private BoardSession.SpareLine line;
    private float linkY;

    private SparePopup(final BoardSession session, final BoardSession.SpareLine line, final String here,
        final Runnable drawer) {
        this.session = session;
        this.key = line.key();
        this.here = here;
        this.drawer = drawer;
        this.line = line;
    }

    /**
     * Opens the popup for {@code line} at a screen point, {@code here} being the key of the place whose row was
     * clicked, {@code drawer} what the drawer link does.
     */
    public static void open(final com.cleanroommc.modularui.screen.ModularPanel parent, final BoardSession session,
        final BoardSession.SpareLine line, final String here, final Runnable drawer, final int x, final int y) {
        final int h = PAD + SpareBar.height(line) + 3 + LINK_H + PAD;
        final Popup popup = new Popup("gtnhplanner_spare", SLIDER_W + 2 * PAD, h);
        popup.child(
            new SparePopup(session, line, here, drawer).pos(PAD, PAD)
                .size(SLIDER_W, h - 2 * PAD));
        Popup.open(parent, popup, x, y);
    }

    @Override
    public boolean canHover() {
        return true;
    }

    private float mx() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private float my() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    private SpareBar.Hit hover() {
        return isHovering() ? bar.hitAt(mx(), my()) : null;
    }

    private boolean overLink() {
        final float w = Hyb.width(DRAWER) * SMALL;
        return isHovering() && my() >= linkY && my() < linkY + LINK_H && mx() >= SLIDER_W - w;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        for (final BoardSession.SpareLine l : session.spares()) if (l.key()
            .equals(key)) line = l;
        final SpareBar.Hit hover = hover();
        final int place = SpareBar.place(hover);
        session.setSpareMenuHover(
            place < 0 ? null : line,
            place < 0 ? null
                : line.bearers()
                    .get(place));
        Hyb.rect(-PAD + 1, -2, SLIDER_W + 2 * PAD - 2, SpareBar.height(line) + 4, SpareBar.tint(line));
        final int h = bar.draw(session, line, 0, 0, SLIDER_W, context.getCurrentDrawingZ(), hover, here);
        linkY = h + 3;
        // The labels take more rows or fewer as the spare moves: the popup grows or shrinks to fit them.
        final int panelH = PAD + h + 3 + LINK_H + PAD;
        if (getPanel().getArea().height != panelH) {
            getPanel().size(SLIDER_W + 2 * PAD, panelH);
            getPanel().scheduleResize();
            size(SLIDER_W, panelH - 2 * PAD);
            scheduleResize();
        }
        final float w = Hyb.width(DRAWER) * SMALL;
        Hyb.text(DRAWER, SLIDER_W - w, linkY + 1, SMALL, overLink() ? Hyb.GOLD : 0xFF9A9DA6);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        if (overLink()) {
            Hyb.click();
            getPanel().closeIfOpen();
            drawer.run();
            return Result.SUCCESS;
        }
        final SpareBar.Hit hit = bar.hitAt(mx(), my());
        if (!bar.press(session, line, hit, mx(), 0, SLIDER_W) && bar.click(session, line, hit)) Hyb.click();
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (bar.dragging()) bar.drag(session, mx(), 0, SLIDER_W);
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (!bar.dragging()) return false;
        bar.release(session);
        return true;
    }

}
