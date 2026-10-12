package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * Geometry of a card in GUI pixels, shared by drawing, the child widgets and wire anchors: the clean card
 * ({@code docs/design/card-redesign.md}). One surface, no boxes inside it: a header band (keys, the machine's name,
 * the amps and tier chips), inputs against the left edge and outputs against the right where their wires meet the card,
 * the machine's picture in the middle, and a strip along the bottom of the settings pinned to it. A port row is its
 * icon at the
 * edge, how much in large type and its name small under it. Power drawn, and the circuit when set so, are one more row
 * after a recipe's inputs.
 */
public final class CardLayout {

    public static final int W = 320;
    /** Where the content starts inside the frame. */
    public static final int PAD = 6;
    /** The header band: its chips (and keys) {@link #HEAD} high, as far from its top as from the card's right edge. */
    public static final int HEAD_Y = 6;
    public static final int HEAD = 20;
    public static final int HEADER = HEAD_Y * 2 + HEAD;
    /**
     * A port row: its icon spanning it, 32 px (twice the game's, so its pixels stay whole), at the card's edge; its
     * rate, its name and, under them, its rule key and rate box (as a drawer's).
     */
    public static final int ROW = 42;
    public static final int ICON = 32;
    public static final int ICON_X = 3;
    public static final int ICON_Y = (ROW - ICON) / 2;
    /** A side's column, and where a port's words start (from the side's outer edge) and how wide they may be. */
    public static final int RAIL_W = 112;
    public static final int TEXT_X = ICON_X + ICON + 4;
    public static final int TEXT_W = RAIL_W - TEXT_X - 2;
    /** A port's rule key and rate box: their height, the key's width, the gap between them, and the box's width. */
    public static final int CTRL_H = 10, CTRL_KEY = 10, CTRL_GAP = 2, CTRL_BOX = TEXT_W - CTRL_KEY - CTRL_GAP;
    /** A striped row's box: the row less a pixel above and below, so rows on a card keep a hairline apart. */
    public static final int BOX_Y = 1, BOX_H = ROW - 2;
    /** The machine's picture: square, in the middle column. */
    public static final int PICTURE = 88;
    /**
     * A single block's item in the picture: four times the game's 16, so its pixels stay whole, near the top of the
     * picture so the machine count under it stays clear.
     */
    public static final int ITEM = 64;
    public static final int PICTURE_MIN = PICTURE;

    public static final int RAILS_Y = HEADER + 6;
    public static final int IN_RAIL_X = 4;
    public static final int OUT_RAIL_X = W - 4 - RAIL_W;
    public static final int PICTURE_X = IN_RAIL_X + RAIL_W;
    public static final int PICTURE_W = OUT_RAIL_X - PICTURE_X;

    /** The settings strip: a chip's height, a row's, and the room inside a chip. */
    public static final int CHIP = 18, CHIP_ROW = 22, CHIP_PAD = 5;

    /** On a shared machine, the rule row over each recipe's rails, and the gap before every recipe after the first. */
    public static final int SECTION_RULE = 16, SECTION_GAP = 8;

    public final int railsH;
    /** Where the settings strip starts (its hairline), the pinned settings under it as chips, each {x, y, w}. */
    public final int stripY;
    public final List<SettingControls.Control> chips;
    /** The pins the strip was laid out by ({@link SettingPins#version()}). */
    public final int pinsVersion;
    private final int[][] chipAt;
    /** A power card's warnings, wrapped to the card, under the strip. */
    public final List<String> warningLines;
    public final int warningsY;
    public final int height;
    /** The power row (what the machine draws) and the circuit's row among the inputs, card-local y; -1 for none. */
    public final int powerRowY, circuitRowY;
    /** Whether the circuit is a row of its own (the setting), so the layout is redone when it changes. */
    public final boolean circuitAsInput;

    /** Each recipe on the card: one, or several on a shared machine. */
    private final int sections;
    /** Rows per side (ports and their extra rows), and per port the row it starts on and whether an extra follows. */
    private final int[] inRows, outRows, ruleY, railsY;
    private final int[][] inSlot, outSlot;
    private final boolean[][] inExtra, outExtra;
    /** Where the rails end: below the last recipe's (and the power row). */
    public final int railsEnd;

    public CardLayout(final CardModel model, final com.gtnhplanner.data.flowchart.Graph plan) {
        this(List.of(model), plan);
    }

    /**
     * A card for its recipes, top first. A shared machine gives each recipe a rule row (its share on the left, its
     * circuit and keys on the right) over its rails, which are as tall as its longer side so its inputs and outputs
     * face each other; its power is one row after them all.
     */
    public CardLayout(final List<CardModel> models, final com.gtnhplanner.data.flowchart.Graph plan) {
        sections = models.size();
        final boolean shared = sections > 1;
        final CardModel first = models.get(0);
        circuitAsInput = com.gtnhplanner.ui.PlannerSettings.circuitAsInput();
        final boolean circuitRow = !shared && !first.isPower() && first.circuit != null && circuitAsInput;
        // A steam machine's row bills its steam where an electric one's bills EU.
        final boolean powerRow = !first.isPower() && (first.euPerTick > 0 || first.steamPerTick > 0
            || com.gtnhplanner.machines.game.CropCards.isCrop(first.node));
        inRows = new int[sections];
        outRows = new int[sections];
        ruleY = new int[sections];
        railsY = new int[sections];
        inSlot = new int[sections][];
        outSlot = new int[sections][];
        inExtra = new boolean[sections][];
        outExtra = new boolean[sections][];
        int y = RAILS_Y, circuitY = -1, powerY = -1;
        for (int s = 0; s < sections; s++) {
            final CardModel model = models.get(s);
            inExtra[s] = extras(model.inputs);
            outExtra[s] = extras(model.outputs);
            inSlot[s] = slots(inExtra[s]);
            outSlot[s] = slots(outExtra[s]);
            inRows[s] = rowsOf(inExtra[s]);
            outRows[s] = rowsOf(outExtra[s]);
            if (shared) {
                if (s > 0) y += SECTION_GAP;
                ruleY[s] = y;
                y += SECTION_RULE;
            }
            railsY[s] = y;
            int extra = 0;
            if (!shared) {
                if (circuitRow) circuitY = y + (inRows[s] + extra++) * ROW;
                if (powerRow) powerY = y + (inRows[s] + extra++) * ROW;
            }
            y += Math.max(shared ? 1 : 0, Math.max(inRows[s] + extra, outRows[s])) * ROW;
        }
        if (shared && powerRow) {
            powerY = y + 4;
            y += 4 + ROW;
        }
        railsEnd = y;
        circuitRowY = circuitY;
        powerRowY = powerY;
        railsH = Math.max(railsEnd - RAILS_Y, PICTURE_MIN);
        // The strip: chips left to right, a new row when one would pass the edge.
        chips = SettingControls.pinned(plan, first);
        pinsVersion = SettingPins.version();
        chipAt = new int[chips.size()][];
        stripY = RAILS_Y + railsH + 6;
        int cx = PAD, cy = stripY + 4;
        for (int i = 0; i < chips.size(); i++) {
            final int w = chipW(chips.get(i));
            if (cx > PAD && cx + w > W - PAD) {
                cx = PAD;
                cy += CHIP_ROW;
            }
            chipAt[i] = new int[] { cx, cy, w };
            cx += w + 4;
        }
        final int stripEnd = chips.isEmpty() ? RAILS_Y + railsH + 2 : cy + CHIP + 4;
        warningLines = new ArrayList<>();
        if (first.isPower() && first.power.model() != null) {
            for (final String warning : first.power.model()
                .warnings())
                warningLines.addAll(
                    Hyb.font()
                        .listFormattedStringToWidth(warning, W - 2 * PAD - 4));
        }
        warningsY = stripEnd + 2;
        height = warningsY + (warningLines.isEmpty() ? 0 : warningLines.size() * 9 + 4) + 4;
    }

    private static boolean[] extras(final List<CardModel.PortView> ports) {
        final boolean[] out = new boolean[ports.size()];
        for (int i = 0; i < out.length; i++) out[i] = ports.get(i)
            .hasExtra();
        return out;
    }

    /** Each port's first row: after the ports before it and their extra rows. */
    private static int[] slots(final boolean[] extra) {
        final int[] out = new int[extra.length];
        int row = 0;
        for (int i = 0; i < extra.length; i++) {
            out[i] = row;
            row += extra[i] ? 2 : 1;
        }
        return out;
    }

    private static int rowsOf(final boolean[] extra) {
        int rows = extra.length;
        for (final boolean e : extra) if (e) rows++;
        return rows;
    }

    /** Whether the port rows sit where another layout's do, so the port widgets need no new place. */
    public boolean sameRows(final CardLayout other) {
        return other != null && java.util.Arrays.deepEquals(inSlot, other.inSlot)
            && java.util.Arrays.deepEquals(outSlot, other.outSlot)
            && java.util.Arrays.equals(railsY, other.railsY)
            && java.util.Arrays.deepEquals(inExtra, other.inExtra)
            && java.util.Arrays.deepEquals(outExtra, other.outExtra);
    }

    /** A chip's width: its icon, label and value. */
    public static int chipW(final SettingControls.Control c) {
        return chipW(c.label(), c.value(), c.icon() != null);
    }

    public static int chipW(final String label, final String value, final boolean icon) {
        return CHIP_PAD + (icon ? 14 + 3 : 0) + Hyb.width(label) + 4 + Hyb.width(value) + CHIP_PAD;
    }

    /** Card-local {x, y, w, h} of a chip in the strip. */
    public int[] chipRect(final int index) {
        final int[] at = chipAt[index];
        return new int[] { at[0], at[1], at[2], CHIP };
    }

    /** The chip under a card-local point, or -1. */
    public int chipAt(final float x, final float y) {
        for (int i = 0; i < chipAt.length; i++) {
            final int[] at = chipAt[i];
            if (x >= at[0] && x < at[0] + at[2] && y >= at[1] && y < at[1] + CHIP) return i;
        }
        return -1;
    }

    /** The picture's box in the middle column, card-local {x, y, side}. */
    public int[] picture() {
        return new int[] { PICTURE_X + (PICTURE_W - PICTURE) / 2, RAILS_Y, PICTURE };
    }

    public int sections() {
        return sections;
    }

    /** Top of a shared machine's rule row over a recipe. */
    public int ruleY(final int section) {
        return ruleY[clampSection(section)];
    }

    /** Top of a recipe's rails. */
    public int railsY(final int section) {
        return railsY[clampSection(section)];
    }

    public int rows(final int section, final boolean output) {
        return (output ? outRows : inRows)[clampSection(section)];
    }

    private int clampSection(final int section) {
        return Math.max(0, Math.min(section, sections - 1));
    }

    /** Top of a port's row, card-local. */
    public int rowY(final int section, final boolean output, final int index) {
        final int s = clampSection(section);
        final int[] slots = (output ? outSlot : inSlot)[s];
        if (slots.length == 0) return railsY[s];
        return railsY[s] + slots[Math.max(0, Math.min(index, slots.length - 1))] * ROW;
    }

    public int rowY(final boolean output, final int index) {
        return rowY(0, output, index);
    }

    /** Whether a port has its extra row under it ({@link CardModel.PortView#extra}). */
    public boolean hasExtra(final int section, final boolean output, final int index) {
        final boolean[] extra = (output ? outExtra : inExtra)[clampSection(section)];
        return index >= 0 && index < extra.length && extra[index];
    }

    /** A port's height: its row, and its extra row when it has one. */
    public int rowH(final int section, final boolean output, final int index) {
        return hasExtra(section, output, index) ? 2 * ROW : ROW;
    }

    /** Where a wire meets the card: the card edge, level with the middle of the port's row. */
    public int anchorY(final int section, final boolean output, final int index) {
        return rowY(section, output, index) + ROW / 2;
    }

    public int anchorY(final boolean output, final int index) {
        return anchorY(0, output, index);
    }

    public static int railX(final boolean output) {
        return output ? OUT_RAIL_X : IN_RAIL_X;
    }

    /** Where a port's icon is: at the card's edge, on the left for an input and the right for an output. */
    public static int iconX(final boolean output) {
        return output ? OUT_RAIL_X + RAIL_W - ICON_X - ICON : IN_RAIL_X + ICON_X;
    }

    /** Where a port's words start (an input) or end (an output, right aligned). */
    public static int textEdge(final boolean output) {
        return output ? OUT_RAIL_X + RAIL_W - TEXT_X : IN_RAIL_X + TEXT_X;
    }

    /** The top of a port's rule key and rate box, in the row starting at {@code rowY}. */
    public static int ctrlY(final int rowY) {
        return rowY + ROW - 4 - CTRL_H;
    }

    /** Where a port's rule key starts: at the words' edge, the box after it (right aligned on an output). */
    public static int keyX(final boolean output) {
        return output ? textEdge(true) - CTRL_BOX - CTRL_GAP - CTRL_KEY : textEdge(false);
    }

    public static int boxX(final boolean output) {
        return keyX(output) + CTRL_KEY + CTRL_GAP;
    }

    public static int anchorX(final boolean output) {
        return output ? W : 0;
    }
}
