package com.gtnhplanner.ui.card;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The clean card's pieces, drawn the same by the board's cards ({@link RecipeCard}) and the cards over the world and on
 * the minimap ({@link CleanCardView}), in card units at the origin, with {@link CardLayout}'s geometry.
 */
public final class CardPaint {

    /** The card's one surface, its edge, and the hairlines that part the header and the strip. */
    public static final int SURFACE = 0xFF23252A, EDGE = 0xFF34363C, HAIR = 0xFF2C2E34;
    /** The outline of what would take the wire in hand (the website's green), and of the one it would go to now. */
    public static final int ACCEPT_FAINT = 0xB34ADE80, ACCEPT = 0xFF4ADE80;
    /** The machine's name: as large as fits, down to plain. */
    private static final float[] NAME_SIZES = { 1.5f, 1.25f, 1f };
    /** How small a port's name may go before it is cut, and how wide the fade is where it is cut. */
    private static final float NAME_SMALL = 0.75f;
    private static final int NAME_FADE = 10;

    private CardPaint() {}

    /** The surface with its edge, {@code w} by {@code h}. */
    public static void surface(final int w, final int h) {
        surface(w, h, 0);
    }

    /**
     * The surface with its edge in {@code edge} ({@link CardFace#edge}: a problem card's frame in its bar's colour),
     * or the plain edge for 0.
     */
    public static void surface(final int w, final int h, final int edge) {
        Hyb.rect(0, 0, w, h, edge != 0 ? edge : EDGE);
        Hyb.rect(1, 1, w - 2, h - 2, SURFACE);
    }

    /** A hairline across the card. */
    public static void hair(final int y) {
        Hyb.rect(1, y, CardLayout.W - 2, 1, HAIR);
    }

    /**
     * The machine's name from {@code x} to {@code right}, level with the header's chips: large, smaller when long, then
     * cut. Returns where it ends.
     */
    public static float name(final String name, final float x, final float right, final int colour) {
        final float room = right - x;
        for (final float size : NAME_SIZES) {
            if (Hyb.width(name) * size <= room || size == NAME_SIZES[NAME_SIZES.length - 1]) {
                final String shown = Hyb.width(name) * size <= room ? name : Hyb.fit(name, (int) (room / size));
                // Level with the chips' labels: the game's capitals are seven of its eight rows.
                Hyb.text(
                    shown,
                    x,
                    RecipeCard.crisp(CardLayout.HEAD_Y + (CardLayout.HEAD - 7 * size) / 2f),
                    size,
                    colour);
                return x + Hyb.width(shown) * size;
            }
        }
        return x;
    }

    /**
     * A port's words: how much in large type with its unit small beside it, and the name small under it on one line
     * (smaller when long, then cut and faded into the card), from the edge {@link CardLayout#textEdge} (right aligned
     * for an output), in a row at {@code y}. The number drops to the name's size when it would not fit.
     */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final boolean output, final int y) {
        words(number, suffix, name, colour, Hyb.MUTED, output, y);
    }

    /** As {@link #words(String, String, String, int, boolean, int)}, the name in {@code nameColour}. */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final int nameColour, final boolean output, final int y) {
        words(number, suffix, name, colour, nameColour, output, y, false);
    }

    /** As above; {@code controls}, the words sit higher, leaving room under them for the port's rule key and box. */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final int nameColour, final boolean output, final int y, final boolean controls) {
        final int edge = CardLayout.textEdge(output), room = CardLayout.TEXT_W;
        final float rateW = Hyb.width(number) * Hyb.FIGURE + 2 + Hyb.width(suffix);
        final boolean big = rateW <= room;
        final float rateH = big ? 8 * Hyb.FIGURE : 8;
        final String label = name == null ? "" : name;
        final float size = Hyb.width(label) <= room ? 1 : NAME_SMALL;
        final boolean cut = Hyb.width(label) * size > room;
        final String shown = cut ? Hyb.font()
            .trimStringToWidth(label, (int) (room / size)) : label;
        final float block = rateH + 2 + 8 * size + (controls ? 2 + CardLayout.CTRL_H : 0);
        final float top = RecipeCard.crisp(y + (CardLayout.ROW - block) / 2f);
        if (big) {
            final float x = output ? edge - rateW : edge;
            Hyb.text(number, x, top, Hyb.FIGURE, colour);
            Hyb.text(suffix, x + Hyb.width(number) * Hyb.FIGURE + 2, top + 3.5f, Hyb.MUTED);
        } else {
            final String both = Hyb.fit(number + suffix, room);
            Hyb.text(both, output ? edge - Hyb.width(both) : edge, top, colour);
        }
        final float nameW = Hyb.width(shown) * size, nameY = top + rateH + 2;
        final float nx = output ? edge - nameW : edge;
        Hyb.text(shown, nx, nameY, size, nameColour);
        if (!cut) return;
        // The cut end fades into the card.
        for (int k = 0; k < NAME_FADE; k++) {
            final int a = (int) (255f * (k + 1) / NAME_FADE);
            final float px = nx + nameW - NAME_FADE + k;
            Hyb.rect(px, nameY - 1, 1, 8 * size + 2, a << 24 | SURFACE & 0xFFFFFF);
        }
    }

    /** A flow's words: its rate in the plan's unit (EU/t for power) and its name. */
    public static void flowWords(final double perSecond, final boolean power, final boolean fluid, final String name,
        final Fmt.RateUnit unit, final boolean output, final int y) {
        flowWords(perSecond, power, fluid, name, unit, output, y, false, false);
    }

    /**
     * As above; {@code set}, the rate is the player's (the port's own rate), in gold as a pinned count is;
     * {@code controls}, room is left under the words for the port's rule key and box.
     */
    public static void flowWords(final double perSecond, final boolean power, final boolean fluid, final String name,
        final Fmt.RateUnit unit, final boolean output, final int y, final boolean set, final boolean controls) {
        flowWords(perSecond, power, fluid, name, unit, output, y, set, controls, Hyb.MUTED);
    }

    /** As above, the name in {@code nameColour} (a lit row's in its reason's tone). */
    public static void flowWords(final double perSecond, final boolean power, final boolean fluid, final String name,
        final Fmt.RateUnit unit, final boolean output, final int y, final boolean set, final boolean controls,
        final int nameColour) {
        final String number = power ? Fmt.power(perSecond / 20) : Fmt.compact(perSecond * unit.perSecond);
        final String suffix = power ? " EU/t" : (fluid ? " L" : "") + unit.suffix;
        words(
            number,
            suffix,
            name,
            set ? Hyb.GOLD : perSecond <= 0 ? 0xFFA8AFBB : Hyb.INK,
            nameColour,
            output,
            y,
            controls);
    }

    /**
     * A port's rule key and rate box under its words in the row at {@code y}: the key's mark ({@code ~} greyed while
     * the plan decides, {@code =} in gold once set) in the middle of a small dark key, and the box with "Set", or the
     * rate set in gold with its unit, centred. {@code hotKey}/{@code hotBox}: the one under the mouse; {@code prompt}:
     * "Set" pulses gold, with every other thing waiting to be set, while the plan has nothing to solve for.
     */
    public static void portControls(final boolean output, final int y, @Nullable final String number,
        final String suffix, final boolean hotKey, final boolean hotBox, final boolean prompt,
        @Nullable final com.gtnhplanner.ui.popup.InlineNumber edit) {
        ruleControls(
            output,
            y,
            number != null ? com.gtnhplanner.data.flowchart.Drawer.Rule.EXACTLY
                : com.gtnhplanner.data.flowchart.Drawer.Rule.ANY,
            null,
            number,
            suffix,
            null,
            hotKey,
            hotBox,
            false,
            prompt,
            edit);
    }

    /**
     * A row's rule key and rate box, a port's or a drawer's: the key's mark ({@code keyMark}, else the rule's: greyed
     * for Any, gold for the rest) in the middle of a small dark key, and the box with the rate (gold, red when it
     * cannot be reached: {@code unmet}) and its unit centred, or {@code fixed} in grey, or "Set" (gold and pulsing
     * while the plan waits for something to be set: {@code prompt}).
     */
    public static void ruleControls(final boolean output, final int y,
        final com.gtnhplanner.data.flowchart.Drawer.Rule rule, @Nullable final String keyMark,
        @Nullable final String number, final String suffix, @Nullable final String fixed, final boolean hotKey,
        final boolean hotBox, final boolean unmet, final boolean prompt,
        @Nullable final com.gtnhplanner.ui.popup.InlineNumber edit) {
        final int cy = CardLayout.ctrlY(y), kx = CardLayout.keyX(output), bx = CardLayout.boxX(output);
        final int h = CardLayout.CTRL_H, kw = CardLayout.CTRL_KEY, bw = CardLayout.CTRL_BOX;
        // The key: its mark in the middle.
        Hyb.rect(kx, cy, kw, h, 0xFF111317);
        Hyb.rect(kx + 1, cy + 1, kw - 2, h - 2, hotKey ? 0xFF454952 : 0xFF34373E);
        Hyb.rect(kx + 1, cy + 1, kw - 2, 1, 0x1FFFFFFF);
        if (keyMark != null) Hyb.text(keyMark, kx + (kw - Hyb.width(keyMark) + 1) / 2f, cy + 1, Hyb.MUTED);
        else com.gtnhplanner.ui.drawer.DrawerCard.drawRuleMark(
            rule,
            kx + (kw - Hyb.width(com.gtnhplanner.ui.drawer.DrawerCard.ruleMark(rule)) + 1) / 2f,
            cy + 1,
            rule == com.gtnhplanner.data.flowchart.Drawer.Rule.ANY ? Hyb.MUTED : Hyb.GOLD);
        // The box: being typed into; or the rate with its unit, centred; or what stands there instead.
        if (edit != null) {
            edit.drawBox(bx, cy, bw, h, cy + 1);
            return;
        }
        Hyb.rect(bx, cy, bw, h, unmet ? 0xBFF87171 : hotBox ? Hyb.GOLD : 0xFF5A5E68);
        Hyb.rect(bx + 1, cy + 1, bw - 2, h - 2, hotBox ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(bx + 1, cy + 1, bw - 2, 1, 0xB3000000);
        if (fixed != null || number == null) {
            Hyb.textCentered(
                fixed != null ? fixed : "Set",
                bx + bw / 2f,
                cy + 1,
                fixed == null && prompt ? prompt() : 0xFF6F737C);
            return;
        }
        final String shown = Hyb.fit(number, bw - 4 - Hyb.width(suffix));
        final float tw = Hyb.width(shown) + Hyb.width(suffix), tx = bx + (bw - tw) / 2f;
        Hyb.text(shown, tx, cy + 1, unmet ? 0xFFF87171 : Hyb.GOLD);
        Hyb.text(suffix, tx + Hyb.width(shown), cy + 1, 0xFF8A8E97);
    }

    /**
     * What is on a drawer's row, a port row lifted off a card ({@code y} its top; the drawer's frame,
     * {@link com.gtnhplanner.ui.drawer.DrawerPaint#frame}, is its striped box): the resource at the edge (a source's on
     * the left, as an input's; a product's or trash's on the right, as an output's), what the plan moves through it and
     * its name, with room under them for its rule key and box when {@code controls}. Drawn in a card's coordinates: the
     * caller moves it onto the drawer.
     */
    public static void drawerRow(final com.gtnhplanner.data.flowchart.Drawer.Kind kind, @Nullable final ItemStack item,
        @Nullable final net.minecraftforge.fluids.FluidStack fluid, final boolean power, final String number,
        final String suffix, final String name, final int colour, final boolean controls, final int y, final float z) {
        final boolean output = kind != com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        icon(item, fluid, power, output, y, z);
        words(number, suffix, name, colour, Hyb.mix(tint, Hyb.MUTED, 0.45f), output, y, controls);
    }

    /**
     * A port's extra row ({@link CardModel.PortView#extra}), at {@code y} under the port's own: what the plan is forced
     * to send out at the port with no drawer to take it, or bring in with no source. A tile of every port's shape,
     * striped in a product's orange going out or a source's blue coming in, the resource, the rate in the drawer's ink,
     * and the word under it.
     */
    public static void extraRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z) {
        extraRow(p, unit, y, z, 0);
    }

    /**
     * As {@link #extraRow(CardModel.PortView, Fmt.RateUnit, int, float)}, with {@code carried} of it picked up and in
     * hand: the row shows the rest, or, all of it carried, lies empty (a dashed outline where it was).
     */
    public static void extraRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final double carried) {
        extraRow(p, unit, y, z, carried, null);
    }

    /**
     * As above; {@code why}, the port is the row an Up to card's reason names: the forced tile wears the reason's
     * stripes and edge in place of its own (its words keep theirs).
     */
    public static void extraRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final double carried, @Nullable final CardFace.Tone why) {
        final com.gtnhplanner.data.flowchart.Drawer.Kind kind = p.output()
            ? com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT
            : com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        final double left = Math.max(0, p.extra() - carried);
        final int bx = CardLayout.railX(p.output()), bw = CardLayout.RAIL_W;
        if (carried > 0 && left < 1e-6) {
            Hyb.dashed(bx, y + CardLayout.BOX_Y, bw, CardLayout.BOX_H, Hyb.mix(tint, EDGE, 0.35f), 3, 3);
            final String gone = "picked up";
            final float x = p.output() ? CardLayout.textEdge(true) - Hyb.width(gone) : CardLayout.textEdge(false);
            Hyb.text(gone, x, y + (CardLayout.ROW - 8) / 2f, Hyb.mix(tint, Hyb.MUTED, 0.3f));
            return;
        }
        spareRow(p, unit, y, z, left, p.output() ? EXTRA_OUT : EXTRA_IN, false, false, why, false);
    }

    /**
     * A port with no wire and no drawer that the plan runs through anyway (a pinned machine runs whatever is wired to
     * it): what it takes comes in from outside, what it makes leaves, as a spare's does. So it is drawn as one, in a
     * source's blue or a product's orange, striped, with its own name.
     */
    public static void openRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final boolean set) {
        openRow(p, unit, y, z, set, null);
    }

    /**
     * As above; {@code why}, the row an Up to card's reason names: striped in the reason's tone in place of the free
     * end's, its edge and its name in that tone.
     */
    public static void openRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final boolean set, @Nullable final CardFace.Tone why) {
        spareRow(p, unit, y, z, p.perSecond(), p.name(), set, true, why, true);
    }

    /**
     * The striped row of a flow leaving or coming into the plan at a port: {@code amount} a second, {@code label}; the
     * number in gold when it is the player's (the port's own rate, {@code set}). {@code why}: striped in an Up to
     * reason's tone instead, and with {@code whyName} its name in that tone too.
     */
    private static void spareRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final double amount, final String label, final boolean set, final boolean controls,
        @Nullable final CardFace.Tone why, final boolean whyName) {
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(
            p.output() ? com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT
                : com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE);
        freeTile(p.output(), y, why);
        icon(p.item(), p.fluid(), p.isPower(), p.output(), y, z);
        final String number = p.isPower() ? Fmt.power(amount / 20) : Fmt.compact(amount * unit.perSecond);
        final String suffix = p.isPower() ? " EU/t" : (p.isFluid() ? " L" : "") + unit.suffix;
        words(
            number,
            suffix,
            label,
            set ? Hyb.GOLD : Hyb.mix(tint, Hyb.INK, 0.75f),
            why != null && whyName ? why.colour : Hyb.mix(tint, Hyb.MUTED, 0.45f),
            p.output(),
            y,
            controls);
    }

    public static final String EXTRA_OUT = "Forced", EXTRA_IN = "Forced";

    /** A port's icon at the card's edge (a bolt for power). */
    public static void icon(@Nullable final ItemStack item, @Nullable final net.minecraftforge.fluids.FluidStack fluid,
        final boolean power, final boolean output, final int y, final float z) {
        final int x = CardLayout.iconX(output), iy = y + CardLayout.ICON_Y;
        if (power) RecipeCard.euIcon(x, iy, CardLayout.ICON);
        else Hyb.icon(item, fluid, x, iy, CardLayout.ICON, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * The small amber square on the bottom left of an input's icon that can be set to another item (the website's
     * marker on a slot that takes several).
     */
    public static void choiceMark(final int y) {
        final int x = CardLayout.iconX(false) - 1, by = y + CardLayout.ICON_Y + CardLayout.ICON - 4;
        Hyb.rect(x, by, 5, 5, 0xFF000000);
        Hyb.rect(x + 1, by + 1, 3, 3, 0xFFFFAA00);
    }

    /**
     * The recipe's programmed circuit as a badge in the well's top left corner: its own icon alone, which already
     * shows its number; no words beside it and never dimmed (it is a setting, not a flow). Its tip says "Circuit 24".
     */
    public static void circuitBadge(final ItemStack circuit, final int[] well, final float z) {
        final int[] r = CardLayout.circuit(well);
        com.gtnhplanner.ui.gt.CircuitIcons.draw(circuit, r[0], r[1], r[2], z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** Under a count the plan waits for (nothing set anywhere): what to do, pulsing gold with the drawers' Set rate. */
    public static final String SET_COUNT = "(set count)";

    // region Tiles and the well (the website's pass 3)

    /** A port's tile: its face (lighter under the mouse), its edge, and the bevel's light and dark lines. */
    private static final int TILE = 0xFF2B2D33, TILE_HOVER = 0xFF31343A, TILE_EDGE = 0xFF121418, TILE_HI = 0xFF3B3E45,
        TILE_LO = 0xFF222428;
    /** A free end's tile (an open port, a forced row): a source's blue coming in, a product's orange going out. */
    private static final int IN_BODY = 0xFF292F38, IN_STRIPE = 0xFF2E3844, IN_EDGE = 0xFF4F6986, IN_HI = 0xFF3A4757,
        IN_LO = 0xFF22272E, OUT_BODY = 0xFF322D2E, OUT_STRIPE = 0xFF3E3431, OUT_EDGE = 0xFF815E49, OUT_HI = 0xFF4B3F39,
        OUT_LO = 0xFF2A2425;

    /** A port's tile in the row at {@code y}: one shape for every port and the power row. */
    public static void portTile(final boolean output, final int y, final boolean hover) {
        tile(
            CardLayout.railX(output),
            y + CardLayout.BOX_Y,
            CardLayout.RAIL_W,
            CardLayout.BOX_H,
            hover ? TILE_HOVER : TILE,
            TILE_EDGE,
            TILE_HI,
            TILE_LO);
    }

    /**
     * A free end's tile (a port the plan runs through with no wire, a forced row): the same shape, striped in a
     * source's blue on an input or a product's orange on an output.
     */
    public static void freeTile(final boolean output, final int y) {
        freeTile(output, y, null);
    }

    /**
     * As above; {@code why}, the row an Up to card's reason names: the reason's stripes replace the free end's and its
     * edge is the reason's tone, the free end's bevel kept.
     */
    public static void freeTile(final boolean output, final int y, @Nullable final CardFace.Tone why) {
        final float x = CardLayout.railX(output), ty = y + CardLayout.BOX_Y;
        final int w = CardLayout.RAIL_W, h = CardLayout.BOX_H;
        final int[] lit = why == null ? null : CardFace.stripes(why);
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        tile(
            x,
            ty,
            w,
            h,
            lit != null ? lit[0] : output ? OUT_BODY : IN_BODY,
            why != null ? why.colour : output ? OUT_EDGE : IN_EDGE,
            output ? OUT_HI : IN_HI,
            output ? OUT_LO : IN_LO);
        stripes(x + 2, ty + 2, w - 4, h - 4, lit != null ? lit[1] : output ? OUT_STRIPE : IN_STRIPE, 8, 3);
        if (own) Hyb.endBatch();
    }

    /**
     * The tile of the row an Up to card's reason names (STARVED's short input, CLOGGED's or UNUSED's output, and the
     * rows tied with it; the website's lit row): striped in the reason's tone as a free end is in its own, its edge in
     * that tone, the plain tile's bevel kept. Its name goes in the tone too ({@link #flowWords}).
     */
    public static void whyTile(final boolean output, final int y, final CardFace.Tone why) {
        final float x = CardLayout.railX(output), ty = y + CardLayout.BOX_Y;
        final int w = CardLayout.RAIL_W, h = CardLayout.BOX_H;
        final int[] lit = CardFace.stripes(why);
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        tile(x, ty, w, h, lit[0], why.colour, TILE_HI, TILE_LO);
        stripes(x + 2, ty + 2, w - 4, h - 4, lit[1], 8, 3);
        if (own) Hyb.endBatch();
    }

    /**
     * A raised tile: its edge round a pixel short of each corner, the light line top and left, the dark bottom right.
     */
    private static void tile(final float x, final float y, final float w, final float h, final int face, final int edge,
        final int hi, final int lo) {
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        Hyb.rect(x + 1, y, w - 2, 1, edge);
        Hyb.rect(x + 1, y + h - 1, w - 2, 1, edge);
        Hyb.rect(x, y + 1, 1, h - 2, edge);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, edge);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, face);
        Hyb.rect(x + 1, y + 1, w - 2, 1, hi);
        Hyb.rect(x + 1, y + 2, 1, h - 3, hi);
        Hyb.rect(x + 2, y + h - 2, w - 3, 1, lo);
        Hyb.rect(x + w - 2, y + 2, 1, h - 4, lo);
        if (own) Hyb.endBatch();
    }

    /**
     * Diagonal stripes rising to the right inside a box: {@code width} pixels of {@code colour} every {@code period},
     * as rows of rectangles.
     */
    public static void stripes(final float x, final float y, final float w, final float h, final int colour,
        final int period, final int width) {
        final int rows = Math.round(h);
        if (w <= 0 || rows <= 0) return;
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        for (int j = 0; j < rows; j++) {
            for (float sx = x - Math.floorMod(j + Math.round(x + y), period); sx < x + w; sx += period) {
                final float a = Math.max(sx, x), b = Math.min(sx + width, x + w);
                if (b > a) Hyb.rect(a, y + j, b - a, 1, colour);
            }
        }
        if (own) Hyb.endBatch();
    }

    /** The machine's well, {x, y, w, h}: sunk, dark at its top and left, a light line at its bottom and right. */
    public static void well(final int[] well) {
        final int x = well[0], y = well[1], w = well[2], h = well[3];
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        Hyb.rect(x + 1, y, w - 2, 1, 0xFF0E0F12);
        Hyb.rect(x + 1, y + h - 1, w - 2, 1, 0xFF0E0F12);
        Hyb.rect(x, y + 1, 1, h - 2, 0xFF0E0F12);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, 0xFF0E0F12);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, 0xFF1A1C20);
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0xFF141518);
        Hyb.rect(x + 1, y + 2, 1, h - 3, 0xFF141518);
        Hyb.rect(x + 2, y + h - 2, w - 3, 1, 0xFF2F3138);
        Hyb.rect(x + w - 2, y + 2, 1, h - 4, 0xFF2F3138);
        if (own) Hyb.endBatch();
    }

    // endregion

    // region The count under the machine (docs/design/up-to.md)

    /** The gap between the rule word and the set number. */
    private static final int RULE_GAP = 3;

    /**
     * Card-local rectangles {x, y, w, h} of the count's parts under the picture: [0] the whole block (a click types a
     * count), [1] the rule word (a click opens its menu; null for Any), [2] what a click types into (the set number,
     * else what runs).
     */
    public static int[][] countParts(final CardFace.Count c, final int[] picture) {
        final int top = CardLayout.countY(picture), lineY = top + CardLayout.RUN_H + CardLayout.LINE_GAP;
        final float cx = picture[0] + picture[2] / 2f;
        final int[] block = { CardLayout.WELL_X + 2, top - 1, CardLayout.WELL_W - 4, CardLayout.COUNT_H + 2 };
        if (!c.pinned()) {
            final int rw = Math.round(Hyb.width(c.run()) * Hyb.FIGURE);
            return new int[][] { block, null, { Math.round(cx - rw / 2f), top, rw, CardLayout.RUN_H } };
        }
        final int ww = Hyb.width(c.ruleWord()), nw = Hyb.width(c.setText());
        final int x0 = Math.round(cx - (ww + RULE_GAP + nw) / 2f);
        return new int[][] { block, { x0, lineY - 1, ww, CardLayout.LINE_H + 1 },
            { x0 + ww + RULE_GAP, lineY - 1, nw, CardLayout.LINE_H + 1 } };
    }

    /**
     * The count under the picture: what runs, big and white (grey at none), centred; under it the rule word (a dotted
     * line under it while it opens a menu) and the set number in gold, or for Any its note ("(set count)" pulsing in
     * {@code noteColour}). {@code hoverNumber}: a dotted line under what a click types into; {@code hoverRule}: the
     * rule word lit.
     */
    public static void count(final CardFace.Count c, final int[] picture, final int noteColour,
        final boolean hoverNumber, final boolean hoverRule) {
        final int top = CardLayout.countY(picture), lineY = top + CardLayout.RUN_H + CardLayout.LINE_GAP;
        final float cx = picture[0] + picture[2] / 2f, s = Hyb.FIGURE;
        final float rw = Hyb.width(c.run()) * s, rx = RecipeCard.crisp(cx - rw / 2f);
        Hyb.text(c.run(), rx, top, s, c.zero() ? Hyb.MUTED : Hyb.INK);
        if (!c.pinned()) {
            if (hoverNumber) for (int dx = 0; dx < rw; dx += 3) Hyb.rect(rx + dx, top + 8 * s, 1, 1, Hyb.MUTED);
            if (!c.note()
                .isEmpty()) Hyb.text(c.note(), RecipeCard.crisp(cx - Hyb.width(c.note()) / 2f), lineY, noteColour);
            return;
        }
        final int[][] parts = countParts(c, picture);
        final int[] word = parts[1], number = parts[2];
        Hyb.text(c.ruleWord(), word[0], lineY, hoverRule ? Hyb.INK : Hyb.MUTED);
        // The rule word opens its menu: a dotted line under it says so (a custom rate card's cannot change).
        if (!c.ruleFixed())
            for (int dx = 0; dx < word[2] - 1; dx += 2) Hyb.rect(word[0] + dx, lineY + 8, 1, 1, 0xFF5A5E68);
        Hyb.text(c.setText(), number[0], lineY, Hyb.GOLD);
        if (hoverNumber)
            for (int dx = 0; dx < number[2] - 1; dx += 3) Hyb.rect(number[0] + dx, lineY + 8, 1, 1, Hyb.MUTED);
    }

    /**
     * The count being typed: the website's box where the set number is (after the rule word), or under what runs with
     * nothing set, as wide as three characters or what is typed and one more, and 4 px either side.
     */
    public static void countBox(final CardFace.Count c, final int[] picture,
        final com.gtnhplanner.ui.popup.InlineNumber edit) {
        final int top = CardLayout.countY(picture), lineY = top + CardLayout.RUN_H + CardLayout.LINE_GAP;
        final int w = Math.max(
            3,
            edit.text()
                .length() + 1)
            * Hyb.width("0") + 8, h = CardLayout.LINE_H + 3;
        final float x = c.pinned() ? countParts(c, picture)[2][0] - 4
            : RecipeCard.crisp(picture[0] + picture[2] / 2f - w / 2f);
        edit.drawBox(x, lineY - 2, w, h, lineY);
    }

    // endregion

    // region The bar along the foot

    /** The bar's ground and its sunk edge. */
    private static final int FOOT_BG = 0xFF17191D, FOOT_EDGE = 0xFF0D0E11, FOOT_SHADE = 0xFF111215,
        FOOT_SAY = 0xFFC8CAD0;

    /**
     * The bar along an Up to card's foot (docs/design/up-to.md), {x, y, w, h}: its fill is what runs of the count, a
     * bottleneck's bar runs on striped to what the line wants (a line in the reason's colour where your count ends);
     * over it the reason's word in its colour, the plain line, and the fraction on the right. FORCED IN has no fill.
     */
    public static void foot(final CardFace.Foot f, final int[] r) {
        final int x = r[0], y = r[1], w = r[2], h = r[3];
        final int tone = f.tone().colour;
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        Hyb.rect(x + 1, y, w - 2, 1, FOOT_EDGE);
        Hyb.rect(x + 1, y + h - 1, w - 2, 1, FOOT_EDGE);
        Hyb.rect(x, y + 1, 1, h - 2, FOOT_EDGE);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, FOOT_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, FOOT_BG);
        Hyb.rect(x + 1, y + 1, w - 2, 1, FOOT_SHADE);
        Hyb.rect(x + 1, y + 2, 1, h - 3, FOOT_SHADE);
        final int tx = x + 2, ty = y + 2, th = h - 4;
        if (f.fill() > 0) Hyb.rect(tx, ty, Math.min(f.fill(), w - 4), th, Hyb.mix(tone, FOOT_BG, 0.30f));
        if (f.ghost()) {
            stripes(tx + f.ghostLeft(), ty, f.ghostWidth(), th, Hyb.mix(tone, FOOT_BG, 0.34f), 7, 3);
            Hyb.rect(tx + f.ghostLeft(), ty, 1, th, tone);
        }
        if (own) Hyb.endBatch();
        final float textY = y + (h - 8) / 2f;
        float at = x + 7;
        if (!f.word()
            .isEmpty()) {
            Hyb.text(f.word(), at, textY, tone);
            at += Hyb.width(f.word()) + CardFace.FOOT_GAP;
        }
        if (!f.say()
            .isEmpty()) Hyb.text(f.say(), at, textY, FOOT_SAY);
        if (!f.frac()
            .isEmpty()) Hyb.text(f.frac(), x + w - 7 - Hyb.width(f.frac()), textY, Hyb.INK);
    }

    // endregion

    /** Gold on the beat the drawers' Set rate pulses on, grey off it: the next thing to do, while nothing is set. */
    public static int prompt() {
        // A picture of the plan holds still, at rest.
        if (com.gtnhplanner.ui.canvas.PlanPicture.drawing()) return 0xFF6F737C;
        return promptOn() ? Hyb.GOLD : 0xFF6F737C;
    }

    /**
     * The board's one prompt clock: true in its bright half (the prompts' gold, a ring's outline at full tone), false
     * in the grey half. Half a second each.
     */
    public static boolean promptOn() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }

    /**
     * The outline of a card in a dead loop or a clog lock ({@link CardFace#loopFrame}) on the prompt clock: dimmed in
     * its grey half, steady in a picture of the plan.
     */
    public static int loopFrame(final com.gtnhplanner.data.flowchart.balancer.UpToLoop.Kind kind) {
        return CardFace.loopFrame(kind, com.gtnhplanner.ui.canvas.PlanPicture.drawing() || promptOn());
    }

    /**
     * A chip in the settings strip, {@code w} wide: its icon, its label quiet and its value plain; red edged when it
     * stops the recipe. A reading has no edge, only its words.
     */
    public static void chip(final int x, final int y, final int w, final String label, final String value,
        @Nullable final ItemStack icon, final boolean warn, final boolean reading, final boolean hover, final float z) {
        if (!reading) {
            Hyb.rect(x, y, w, CardLayout.CHIP, warn ? 0xFF6B2A2A : hover ? 0xFF4A4D55 : EDGE);
            Hyb.rect(x + 1, y + 1, w - 2, CardLayout.CHIP - 2, hover ? 0xFF33363C : 0xFF2A2C31);
        }
        int tx = x + CardLayout.CHIP_PAD;
        if (icon != null) {
            Hyb.item(icon, tx, y + 2, 14, z);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            tx += 14 + 3;
        }
        Hyb.text(label, tx, y + 5, Hyb.MUTED);
        Hyb.text(value, tx + Hyb.width(label) + 4, y + 5, warn ? Hyb.RED_INK : Hyb.INK);
    }
}
