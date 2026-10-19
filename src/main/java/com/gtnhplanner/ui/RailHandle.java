package com.gtnhplanner.ui;

import java.util.function.BooleanSupplier;

import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The strip on the overview's edge that drags it wider or narrower (the website's PanelHandle): {@value #INSIDE} px
 * over the overview and {@value #OUTSIDE} over the board, easy to catch with the overview's scroll bar left clear
 * inside it. It starts below the board's toolbar row, so the fold key and the toolbar's first key keep their clicks. A
 * line on the overview's edge shows while the mouse is on it, gold while it is dragged; a double click puts the
 * default width back. The width is kept in the settings ({@link PlannerSettings#overviewWidth()}).
 *
 * <p>
 * It is dragged as the board's cards are, through ModularUI's draggables, so letting go anywhere ends it.
 */
final class RailHandle extends Widget<RailHandle> implements Interactable, IDraggable {

    /** How far it reaches into the overview and out over the board. */
    static final int INSIDE = 2, OUTSIDE = 7;
    /** Two presses this close are a double click. */
    private static final long DOUBLE_MS = 350;

    private final OverviewRail rail;
    /** Something covers the board (the non-recipe picker): the handle is not there. */
    private final BooleanSupplier covered;
    private boolean dragging, moving;
    private int grabX, grabWidth;
    private long pressedAt;

    RailHandle(final OverviewRail rail, final BooleanSupplier covered) {
        this.rail = rail;
        this.covered = covered;
    }

    /** Whether there is an edge to drag: the overview open beside the board. */
    boolean active() {
        return rail.isEnabled() && rail.isOpen() && !covered.getAsBoolean();
    }

    @Override
    public boolean canHover() {
        return active();
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (!active() || !dragging && !isHovering()) return;
        Hyb.rect(0, 0, INSIDE, getArea().height, dragging ? Hyb.GOLD : 0xFF5A5C65);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (!active() || mouseButton != 0) return Result.IGNORE;
        final long now = System.currentTimeMillis();
        if (now - pressedAt < DOUBLE_MS) {
            pressedAt = 0;
            Hyb.click();
            rail.resetWidth();
            return Result.SUCCESS;
        }
        pressedAt = now;
        // Accepted, not stopped: the drag starts from here.
        return Result.ACCEPT;
    }

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || !active()) return false;
        dragging = true;
        grabX = getContext().getAbsMouseX();
        grabWidth = rail.currentWidth();
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (dragging) rail.dragTo(grabWidth + getContext().getAbsMouseX() - grabX);
    }

    /** Let go: the width it reached is the player's. */
    @Override
    public void onDragEnd(final boolean successful) {
        if (!dragging) return;
        dragging = false;
        rail.endDrag();
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public Area getMovingArea() {
        return getArea();
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void setMoving(final boolean moving) {
        this.moving = moving;
    }

    /** Its tip, read by the board's tooltips. */
    Tip tip() {
        return Tip.of("Overview width")
            .action(Tip.Input.DRAG, "Resize")
            .action(Tip.Input.LEFT, "Default (double-click)");
    }
}
