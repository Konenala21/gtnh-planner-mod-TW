package com.gtnhplanner.ui.card;

import com.gtnhplanner.ui.drawer.DrawerPaint;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A plan's drawer as the board draws it zoomed in, for the minimap: the port row it is ({@link CardPaint#drawerRow}),
 * with nothing to press. Cards are {@link CleanCardView}.
 */
public final class PlanCardView {

    private PlanCardView() {}

    /**
     * A drawer as the board draws it zoomed in, without its keys: the port row it is, its resource, what the plan moves
     * through it and its name. {@code lit} rings it in the highlight.
     */
    public static void drawer(final PlanSnapshot.Box b, final Fmt.RateUnit unit, final boolean lit) {
        final com.gtnhplanner.data.flowchart.Drawer.Kind kind = b.kind();
        final int w = DrawerPaint.W, h = DrawerPaint.H;
        if (lit) DrawerPaint.shape(kind, 0, 0, w, h, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF, DrawerPaint.SCALE);
        final double shown = b.power() ? b.rate() / 20 : b.rate() * unit.perSecond;
        final String suffix = b.power() ? " EU/t" : (b.fluid() != null ? " L" : "") + unit.suffix;
        final boolean output = kind != com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        final int ink = b.rate() <= 0 ? DrawerPaint.IDLE_INK
            : kind == com.gtnhplanner.data.flowchart.Drawer.Kind.TRASH ? DrawerPaint.TRASH_INK
                : Hyb.mix(DrawerPaint.tint(kind), Hyb.INK, 0.75f);
        DrawerPaint.frame(kind, false);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(2 - CardLayout.railX(output), DrawerPaint.ROW_Y, 0);
        CardPaint
            .drawerRow(kind, b.item(), b.fluid(), b.power(), Fmt.compact(shown), suffix, b.label(), ink, false, 0, 0);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }
}
