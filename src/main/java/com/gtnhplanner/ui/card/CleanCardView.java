package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A plan card drawn from a {@link PlanSnapshot} card, for the cards over the world and on the minimap: the board's
 * clean card ({@link CardLayout}, {@link CardPaint}) with nothing to press. The name large on the left of the header
 * with the amps and tier chips on the right; inputs against the left edge and outputs against the right, each a tile;
 * power one more row after the inputs; the machine in its well in the middle (the circuit in its corner) with what runs
 * and what is set under it; the settings strip along the bottom, then the bar along the foot of an Up to card, its
 * frame in the bar's colour when the card has a problem. In card units, at the origin.
 */
public final class CleanCardView {

    private CleanCardView() {}

    private static final int W = CardLayout.W, ROW = CardLayout.ROW;
    private static final int CHIP_W = 48, SINGLE_TIER_W = 42;

    /** The input side's rows: the flows, then the power drawn. */
    private static List<PlanSnapshot.Flow> inputs(final PlanSnapshot.Card c) {
        final List<PlanSnapshot.Flow> rows = new ArrayList<>(c.inputs());
        boolean power = false;
        for (final PlanSnapshot.Flow f : c.inputs()) power |= f.power();
        if (!power && c.euPerTick() > 0)
            rows.add(new PlanSnapshot.Flow("Power", null, null, true, c.euPerTick() * 20, "power:eu"));
        return rows;
    }

    private static int body(final PlanSnapshot.Card c) {
        return Math.max(
            CardLayout.PICTURE_MIN,
            Math.max(
                inputs(c).size(),
                c.outputs()
                    .size())
                * ROW);
    }

    /** The settings strip's chips, each {x, y, w}. */
    private static List<int[]> strip(final PlanSnapshot.Card c, final int top) {
        final List<int[]> at = new ArrayList<>();
        int x = CardLayout.PAD, y = top + 4;
        for (final PlanSnapshot.Setting s : c.settings()) {
            final int w = CardLayout.chipW(s.label(), s.value(), s.icon() != null);
            if (x > CardLayout.PAD && x + w > W - CardLayout.PAD) {
                x = CardLayout.PAD;
                y += CardLayout.CHIP_ROW;
            }
            at.add(new int[] { x, y, w });
            x += w + 4;
        }
        return at;
    }

    /** Where the strip ends (or the rails, with no strip): the bar along the foot comes after. */
    private static int stripEnd(final PlanSnapshot.Card c) {
        final int stripY = CardLayout.RAILS_Y + body(c) + 6;
        final List<int[]> chips = strip(c, stripY);
        return chips.isEmpty() ? CardLayout.RAILS_Y + body(c) + 2
            : chips.get(chips.size() - 1)[1] + CardLayout.CHIP + 4;
    }

    public static int height(final PlanSnapshot.Card c) {
        final int end = stripEnd(c);
        return c.foot() != null ? end + 3 + CardLayout.FOOT_H + 3 : end + 6;
    }

    public static int width() {
        return W;
    }

    /** The card; {@code ringed} for the one looked at. */
    public static void draw(final PlanSnapshot.Card c, final Fmt.RateUnit unit, final boolean ringed) {
        final int h = height(c);
        Hyb.dropShadow(0, 0, W, h);
        if (ringed) Hyb.ring(-2, -2, W + 4, h + 4, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        CardPaint.surface(W, h, CardFace.edge(c.foot()));
        head(c);
        CardPaint.hair(CardLayout.HEADER);
        final int top = CardLayout.RAILS_Y;
        final List<PlanSnapshot.Flow> ins = inputs(c);
        for (int i = 0; i < ins.size(); i++) {
            final int y = top + i * ROW;
            CardPaint.portTile(false, y, false);
            flow(ins.get(i), false, y, unit);
        }
        for (int i = 0; i < c.outputs()
            .size(); i++) {
            final int y = top + i * ROW;
            CardPaint.portTile(true, y, false);
            flow(
                c.outputs()
                    .get(i),
                true,
                y,
                unit);
        }
        final int[] well = CardLayout.well(body(c));
        CardPaint.well(well);
        final int[] picture = CardLayout.picture(well);
        art(c, picture);
        if (c.circuit() != null) CardPaint.circuitBadge(c.circuit(), well, 0);
        // As the board's card: what runs, and under it what is set (a crop card's count is the crops planted, said
        // under
        // it, no ×); a fluid tank has none.
        if (!c.countless()) CardPaint.count(count(c), picture, Hyb.MUTED, false, false);
        final int stripY = top + body(c) + 6;
        final List<int[]> chips = strip(c, stripY);
        if (!chips.isEmpty()) CardPaint.hair(stripY);
        for (int i = 0; i < chips.size(); i++) {
            final PlanSnapshot.Setting s = c.settings()
                .get(i);
            final int[] at = chips.get(i);
            CardPaint.chip(at[0], at[1], at[2], s.label(), s.value(), s.icon(), s.warn(), s.reading(), false, 0);
        }
        if (c.foot() != null) CardPaint.foot(
            c.foot(),
            new int[] { CardLayout.IN_RAIL_X, stripEnd(c) + 3, W - 2 * CardLayout.IN_RAIL_X, CardLayout.FOOT_H });
    }

    /** The board's count, or one made from the card's number when the board gave none. */
    private static CardFace.Count count(final PlanSnapshot.Card c) {
        if (c.count() != null) return c.count();
        return CardFace
            .count(com.gtnhplanner.data.flowchart.PinRule.ANY, 0, c.machines(), null, false, c.crop(), false);
    }

    private static void flow(final PlanSnapshot.Flow f, final boolean output, final int y, final Fmt.RateUnit unit) {
        CardPaint.icon(f.item(), f.fluid(), f.power(), output, y, 0);
        CardPaint.flowWords(f.perSecond(), f.power(), f.fluid() != null, f.name(), unit, output, y);
    }

    /** The name large, left; the amps and tier chips, as the board's, right. */
    private static void head(final PlanSnapshot.Card c) {
        final int y = CardLayout.HEAD_Y;
        int right = W - CardLayout.HEAD_Y;
        if (!c.tier()
            .isEmpty()) {
            final Hyb.Tier tier = Hyb.tier(c.tier());
            final int tw = c.amps() > 0 ? CHIP_W : SINGLE_TIER_W;
            right -= tw;
            RecipeCard.chip(right, y, tw, CardLayout.HEAD, tier, tier.name(), tier.underline(), false, true);
            if (c.amps() > 0) {
                right -= CHIP_W + 2;
                RecipeCard.chip(right, y, CHIP_W, CardLayout.HEAD, tier, c.amps() + "A", false, false, true);
            }
            right -= 8;
        }
        CardPaint.name(c.name(), 8, right, Hyb.INK);
    }

    /** The machine's structure picture fitted in its box, or its item. */
    private static void art(final PlanSnapshot.Card c, final int[] box) {
        final float x = box[0], y = box[1], side = box[2];
        final StructureArt.Art art = c.art();
        if (art != null) {
            final float scale = Math.min(side / art.width(), side / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            final float px = x + (side - pw) / 2f, py = y + (side - ph) / 2f;
            final float s = Math.min(pw, ph), pad = StructureArt.SHADOW_PAD * scale;
            Hyb.texture(
                art.shadow(),
                px + s * 0.045f - pad,
                py + s * 0.06f - pad,
                pw + 2 * pad,
                ph + 2 * pad,
                0x7A000000);
            Hyb.texture(art.location(), px, py, pw, ph);
        } else if (c.machine() != null) {
            final float s = Math.min(CardLayout.ITEM, side);
            final float ix = x + (side - s) / 2f, iy = y + (side - s) / 2f;
            Hyb.iconShadow(c.machine(), null, ix, iy, s);
            Hyb.item(c.machine(), ix, iy, s, 0);
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
}
