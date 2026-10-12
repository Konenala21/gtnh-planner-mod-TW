package com.gtnhplanner.ui.popup;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.card.CleanCardView;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.MachineLabel;
import com.gtnhplanner.ui.world.Minimap;
import com.gtnhplanner.ui.world.PlanSnapshot;
import com.gtnhplanner.ui.world.PlannerKeys;

/**
 * The settings' pictures of what they change, beside the In the world and Minimap sections, drawn from the open plan
 * and the settings as they are now, so a switch shows its effect at once. The world: up to three of the plan's machines
 * on muted grass, wired as in the plan, the middle one under the crosshair; with its machines as set, its labels, its
 * wire
 * labels, the card of the machine looked at and the keys list. The minimap: the map itself, large, its shape, zoom
 * and see-through. Labels fit inside the machines, as in the
 * world.
 */
final class SettingsPreview {

    private SettingsPreview() {}

    // region The world

    /** Up to three of the plan's machines, each wired to the next where the plan has a wire between them. */
    private record Scene(List<PlanSnapshot.Card> machines, List<Link> links) {}

    /** A wire from machine {@code from} to the next, its line and what it carries. */
    private record Link(int from, PlanSnapshot.Line line, @Nullable PlanSnapshot.Flow flow) {}

    @Nullable
    private static PlanSnapshot sceneOf;
    @Nullable
    private static Scene scene;

    /** The plan's longest run of up to three machines wired one to the next, kept for the snapshot it was found in. */
    @Nullable
    private static Scene scene(@Nullable final PlanSnapshot snap) {
        if (snap == null) return null;
        if (snap == sceneOf) return scene;
        sceneOf = snap;
        scene = find(snap);
        return scene;
    }

    @Nullable
    private static Scene find(final PlanSnapshot snap) {
        List<PlanSnapshot.Card> best = List.of();
        List<Link> bestLinks = List.of();
        for (final PlanSnapshot.Card b : snap.cards()) {
            if (b.machine() == null) continue;
            final List<PlanSnapshot.Card> run = new ArrayList<>(List.of(b));
            final List<Link> links = new ArrayList<>();
            // Something feeding it, and something it feeds.
            for (final PlanSnapshot.Line l : snap.wires()) {
                final PlanSnapshot.Card a = l.from() == null || l.to() == null
                    || !b.nodeIds()
                        .contains(l.to()) ? null : snap.cardOf(l.from());
                if (a == null || a == b || a.machine() == null) continue;
                run.add(0, a);
                links.add(new Link(0, l, flow(a, l)));
                break;
            }
            for (final PlanSnapshot.Line l : snap.wires()) {
                final PlanSnapshot.Card c = l.from() == null || l.to() == null
                    || !b.nodeIds()
                        .contains(l.from()) ? null : snap.cardOf(l.to());
                if (c == null || run.contains(c) || c.machine() == null) continue;
                links.add(new Link(run.size() - 1, l, flow(b, l)));
                run.add(c);
                break;
            }
            if (run.size() > best.size()) {
                best = run;
                bestLinks = links;
            }
            if (best.size() == 3) break;
        }
        return best.isEmpty() ? null : new Scene(best, bestLinks);
    }

    @Nullable
    private static PlanSnapshot.Flow flow(final PlanSnapshot.Card from, final PlanSnapshot.Line l) {
        for (final PlanSnapshot.Flow f : from.outputs()) if (f.key()
            .equals(l.resource())) return f;
        return null;
    }

    /** The world as the settings now show it, in the box at (x, y), w by h. */
    static void world(final int x, final int y, final int w, final int h) {
        backdrop(x, y, w, h);
        final PlanSnapshot snap = PlanSnapshot.latest();
        final Scene s = scene(snap);
        final int size = 42;
        // You look at the middle of the screen: the machine looked at stands there, under the crosshair.
        final float cx = x + w / 2f, gy = y + h * 0.5f, apart = w * 0.34f;
        if (s != null && PlannerSettings.arLens()) {
            final int n = s.machines()
                .size(), looked = n == 1 ? 0 : 1;
            final float[] xs = new float[n];
            for (int i = 0; i < n; i++) xs[i] = cx + (i - looked) * apart;
            // The wires, from one machine to the next, under them.
            final List<Link> links = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                final Link l = linkInto(s, i);
                if (l == null) continue;
                links.add(l);
                wire(xs[i - 1] + size / 2f - 4, gy + 6, xs[i] - size / 2f + 4, gy + 6, l.line());
            }
            // The machines, as set: see-through, solid or not at all; the one looked at outlined.
            final int look = PlannerSettings.arGhosts();
            for (int i = 0; i < n; i++) {
                final float ix = xs[i] - size / 2f, iy = gy - size / 2f;
                if (look != PlannerSettings.GHOSTS_HIDDEN) {
                    Hyb.item(
                        s.machines()
                            .get(i)
                            .machine(),
                        ix,
                        iy,
                        size,
                        0);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    if (look == PlannerSettings.GHOSTS_SEE_THROUGH) Hyb.rect(ix, iy, size, size, 0x88000000 | FLOOR);
                }
                if (i == looked) Hyb.ring(ix - 1, iy - 1, size + 2, size + 2, 1, 0xA0000000 | Hyb.LIT & 0xFFFFFF);
            }
            // What each wire carries, between its machines: every wire touches the one looked at here.
            if (PlannerSettings.arWireLabels() != PlannerSettings.WIRE_LABELS_OFF)
                for (final Link l : links) tag((xs[l.from()] + xs[l.from() + 1]) / 2, gy + 6, l.flow(), snap);
            // Each machine's label, inside it.
            if (PlannerSettings.arLabels()) for (int i = 0; i < n; i++) MachineLabel.draw(
                s.machines()
                    .get(i),
                0,
                xs[i],
                gy,
                size * 0.95f,
                size * 0.8f,
                0.35f,
                null);
            // The card of the machine looked at, above it.
            if (PlannerSettings.arFocus()) card(
                s.machines()
                    .get(looked),
                cx,
                gy - size / 2f - 10,
                x,
                y,
                w,
                snap);
        }
        crosshair(cx, gy);
        if (PlannerSettings.arLens() && PlannerSettings.arKeys()) keys(x + w - 4, y + h - 4);
        frame(x, y, w, h);
    }

    /** The wire of the run into machine {@code to}, or null. */
    @Nullable
    private static Link linkInto(final Scene s, final int to) {
        for (final Link l : s.links()) if (l.from() + 1 == to) return l;
        return null;
    }

    /** The view: the game's sky and grass, toned down, each shading only slightly toward the horizon. */
    private static final int SKY_TOP = 0xFF5F7898, SKY_LOW = 0xFF6A84A3, FLOOR = 0x4C6A3B, FLOOR_FAR = 0xFF51703F,
        FLOOR_NEAR = 0xFF496638;

    private static void backdrop(final int x, final int y, final int w, final int h) {
        final float horizon = y + h * 0.36f;
        gradient(x, y, w, horizon - y, SKY_TOP, SKY_LOW);
        gradient(x, horizon, w, y + h - horizon, FLOOR_FAR, FLOOR_NEAR);
    }

    private static void frame(final int x, final int y, final int w, final int h) {
        Hyb.ring(x - 1, y - 1, w + 2, h + 2, 1, CardPaint.EDGE);
    }

    /** A box shading from one colour at its top to another at its bottom, in strips. */
    private static void gradient(final float x, final float y, final float w, final float h, final int top,
        final int bottom) {
        final int strips = 16;
        for (int i = 0; i < strips; i++)
            Hyb.rect(x, y + h * i / strips, w, h / strips + 0.5f, Hyb.mix(top, bottom, (i + 0.5f) / strips));
    }

    /** A wire as the plan over the world draws one: its colour on a dark edge, two arrowheads lighter than it. */
    private static void wire(final float ax, final float ay, final float bx, final float by,
        final PlanSnapshot.Line l) {
        final int colour = 0xFF000000 | l.color() & 0xFFFFFF, tip = Hyb.mix(colour, 0xFFFFFFFF, 0.6f);
        band(ax, ay, bx, by, 5, 0xB0000000);
        band(ax, ay, bx, by, 3, colour);
        final float len = (float) Math.hypot(bx - ax, by - ay), ux = (bx - ax) / len, uy = (by - ay) / len;
        for (final float t : new float[] { 0.3f, 0.7f }) {
            final float px = ax + (bx - ax) * t, py = ay + (by - ay) * t, head = 6, half = 3.2f;
            final float fx = px + ux * head * 0.6f, fy = py + uy * head * 0.6f, qx = px - ux * head * 0.4f,
                qy = py - uy * head * 0.4f;
            Hyb.triangle(
                fx + ux,
                fy + uy,
                qx - uy * (half + 1),
                qy + ux * (half + 1),
                qx + uy * (half + 1),
                qy - ux * (half + 1),
                0xB0000000);
            Hyb.triangle(fx, fy, qx - uy * half, qy + ux * half, qx + uy * half, qy - ux * half, tip);
        }
    }

    private static void band(final float ax, final float ay, final float bx, final float by, final float w,
        final int colour) {
        final float len = (float) Math.hypot(bx - ax, by - ay), half = w / 2;
        final float nx = -(by - ay) / len * half, ny = (bx - ax) / len * half;
        Hyb.triangle(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, colour);
        Hyb.triangle(ax + nx, ay + ny, bx - nx, by - ny, ax - nx, ay - ny, colour);
    }

    /** A wire's tag above its middle: what it carries and how much, small. */
    private static void tag(final float cx, final float wy, @Nullable final PlanSnapshot.Flow f,
        @Nullable final PlanSnapshot snap) {
        if (f == null || snap == null) return;
        final String rate = f.power() ? Fmt.power(f.perSecond() / 20) + " EU/t"
            : Fmt.rate(f.perSecond(), snap.rateUnit(), f.fluid() != null);
        final float scale = 0.6f;
        final int w = 4 + 12 + 3 + Hyb.width(rate) + 4, h = 16;
        GL11.glPushMatrix();
        GL11.glTranslatef(cx - w * scale / 2, wy - 6 - h * scale, 0);
        GL11.glScalef(scale, scale, 1);
        Hyb.roundRect(0, 0, w, h, 3, 0xC8141518);
        if (f.power()) com.gtnhplanner.ui.card.RecipeCard.euIcon(4, 2, 12);
        else Hyb.icon(f.item(), f.fluid(), 4, 2, 12, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        Hyb.text(rate, 4 + 12 + 3, 4, Hyb.INK);
        GL11.glPopMatrix();
    }

    /** The card of the machine looked at, centred over it with its bottom at {@code bottom}, kept in the box. */
    private static void card(final PlanSnapshot.Card c, final float cx, final float bottom, final int x, final int y,
        final int w, @Nullable final PlanSnapshot snap) {
        if (snap == null) return;
        final float scale = Math.min(0.3f, w * 0.58f / CleanCardView.width());
        final float cw = CleanCardView.width() * scale, ch = CleanCardView.height(c) * scale;
        final float left = Math.max(x + 4, Math.min(x + w - 4 - cw, cx - cw / 2)), top = Math.max(y + 4, bottom - ch);
        Hyb.rect(cx - 0.5f, top + ch, 1, Math.max(0, bottom - top - ch + 6), 0x90A4A8B0);
        GL11.glPushMatrix();
        GL11.glTranslatef(left, top, 0);
        GL11.glScalef(scale, scale, 1);
        Hyb.fadeOut = 0.15f;
        CleanCardView.draw(c, snap.rateUnit(), true);
        Hyb.fadeOut = 0;
        GL11.glPopMatrix();
    }

    /** The crosshair, white on a dark edge so it shows on anything. */
    private static void crosshair(final float cx, final float cy) {
        Hyb.rect(cx - 7, cy - 1.5f, 15, 3, 0xB0000000);
        Hyb.rect(cx - 1.5f, cy - 7, 3, 15, 0xB0000000);
        Hyb.rect(cx - 6, cy - 0.5f, 13, 1, 0xFFFFFFFF);
        Hyb.rect(cx - 0.5f, cy - 6, 1, 13, 0xFFFFFFFF);
    }

    /** The keys list, small, its bottom right at (right, bottom). */
    private static void keys(final float right, final float bottom) {
        final String[][] lines = { { PlannerKeys.held(PlannerKeys.ROTATE), "Rotate" },
            { PlannerKeys.held(PlannerKeys.FOCUS), "Focus" }, { PlannerKeys.held(PlannerKeys.LABELS), "Labels" } };
        final float scale = 0.5f;
        int keysW = 0, whatW = 0;
        for (final String[] l : lines) {
            keysW = Math.max(keysW, Hyb.width(l[0]));
            whatW = Math.max(whatW, Hyb.width(l[1]));
        }
        final int w = 5 + keysW + 8 + whatW + 5, h = lines.length * 10 + 5;
        GL11.glPushMatrix();
        GL11.glTranslatef(right - w * scale, bottom - h * scale, 0);
        GL11.glScalef(scale, scale, 1);
        Hyb.rect(0, 0, w, h, 0xB0141414);
        for (int i = 0; i < lines.length; i++) {
            Hyb.text(lines[i][0], 5, 3 + 10 * i, 0xFFC8CAD0);
            Hyb.text(lines[i][1], 5 + keysW + 8, 3 + 10 * i, Hyb.MUTED);
        }
        GL11.glPopMatrix();
    }

    // endregion

    // region The minimap

    /**
     * The minimap as set, as large as the box holds, drawn by the minimap itself, over the view so its see-through
     * shows.
     */
    static void minimap(final int x, final int y, final int w, final int h) {
        backdrop(x, y, w, h);
        if (PlannerSettings.minimap()) {
            final int size = Math.min(w, h) - 36;
            Hyb.fadeOut = PlannerSettings.minimapSeeThrough() / 100f;
            Hyb.keepItems = true;
            try {
                Minimap.preview(x + (w - size) / 2, y + (h - size) / 2 - 6, size);
            } finally {
                Hyb.fadeOut = 0;
                Hyb.keepItems = false;
            }
        }
        frame(x, y, w, h);
    }

    // endregion
}
