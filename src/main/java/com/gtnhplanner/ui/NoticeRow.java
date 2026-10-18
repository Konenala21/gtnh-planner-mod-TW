package com.gtnhplanner.ui;

import java.util.function.ToIntFunction;

import com.gtnhplanner.ui.note.NoteText;

/**
 * How a notice's row lays out ({@link NoticeBar}): its width and height, its sentence in lines and where the first
 * starts, and an alarm's chip width (0 for none) and whether its sentence goes under it. Pure: the font's width comes
 * in as a function.
 */
record NoticeRow(int width, int height, NoteText text, int textX, int textY, int chipWidth, boolean under) {

    static final int ROW = 14;
    static final int LINE = 10;
    /** Where the words start, an alarm chip's padding either side of its word, and the gap after the chip. */
    static final int LEFT = 7, CHIP_PAD = 3, CHIP_GAP = 5;

    /**
     * A notice's row at most {@code max} wide: as wide as its words and its key, so the board stays visible beside
     * it, the sentence wrapped to the room left (whole words). An alarm puts its chip first and the sentence beside it
     * on one line where that fits, else on the lines under it.
     */
    static NoticeRow of(final Notice n, final int max, final ToIntFunction<String> width) {
        final int show = n.hasKey() ? width.applyAsInt(n.keyLabel()) + 10 : 0;
        final int words = width.applyAsInt(n.text());
        final String tag = n.tag();
        if (tag == null) {
            final int rw = Math.min(max, LEFT + words + 8 + show);
            final NoteText text = NoteText.of(n.text(), Math.max(20, rw - 10 - show), width);
            return new NoticeRow(
                rw,
                ROW + (text.lines()
                    .size() - 1) * LINE,
                text,
                LEFT,
                3,
                0,
                false);
        }
        final int chip = width.applyAsInt(tag) + 2 * CHIP_PAD;
        final int beside = LEFT + chip + CHIP_GAP + words + 8 + show;
        if (beside <= max) return new NoticeRow(
            beside,
            ROW,
            NoteText.of(n.text(), words, width),
            LEFT + chip + CHIP_GAP,
            3,
            chip,
            false);
        // The chip and the key on the first line, the sentence under them across the row.
        final int rw = Math.min(max, Math.max(LEFT + chip + 8 + show, LEFT + words + 8));
        final NoteText text = NoteText.of(n.text(), Math.max(20, rw - LEFT - 8), width);
        return new NoticeRow(
            rw,
            ROW + text.lines()
                .size() * LINE,
            text,
            LEFT,
            3 + LINE,
            chip,
            true);
    }
}
