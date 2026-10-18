package com.gtnhplanner.ui;

import java.util.List;
import java.util.UUID;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardFace;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Notices along the top of the board, one row each: a stripe in the severity's colour, the sentence, and "Show me",
 * which frames the cards and drawers the notice is about. A sentence too long for the board wraps onto more lines in
 * its row, never cut. A ring's alarm (the website's NoticeBar) is tinted in the ring's tone with a chip naming it
 * before the sentence, chip and stripe dimming with the ring's card outlines on the prompts' clock; where the sentence
 * does not fit beside the chip it goes on the lines under it. At most {@value #MAX_ROWS} rows; the rest are counted.
 */
final class NoticeBar extends Widget<NoticeBar> implements Interactable {

    static final int ROW = NoticeRow.ROW;
    static final int MAX_ROWS = 4;
    private static final int LINE = NoticeRow.LINE, LEFT = NoticeRow.LEFT, CHIP_PAD = NoticeRow.CHIP_PAD;
    /** The notice's fill, and an alarm chip's words in its bright half. */
    private static final int FILL = 0xFF181A1F, CHIP_INK = 0xFF18151D;

    /** Which card "Show me" went to last, per notice, for notices it walks through. */
    private final java.util.Map<String, Integer> turns = new java.util.HashMap<>();

    private final BoardSession session;
    private final BoardCanvas canvas;
    private int shownHeight = -1;

    NoticeBar(final BoardSession session, final BoardCanvas canvas) {
        this.session = session;
        this.canvas = canvas;
        height(0);
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    private NoticeRow row(final Notice n) {
        return NoticeRow.of(n, getArea().width, Hyb::width);
    }

    /** All the rows' height, with the count of the rest when there are more. */
    private int totalHeight() {
        final List<Notice> notices = session.notices();
        int h = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) h += row(notices.get(i)).height();
        return notices.size() > MAX_ROWS ? h + ROW : h;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final int h = totalHeight();
        if (h != shownHeight) {
            shownHeight = h;
            height(h);
            scheduleResize();
        }
    }

    @Override
    public boolean canHover() {
        return shownHeight > 0;
    }

    private static int stripe(final com.gtnhplanner.data.flowchart.balancer.Severity severity) {
        return switch (severity) {
            case ERROR -> Hyb.RED_INK;
            case WARN -> Hyb.AMBER_INK;
            case INFO -> 0xFF6A8CB5;
        };
    }

    /** An alarm's tone dimmed for the prompt clock's grey half: 35% of it over the notice's fill. */
    private static int dim(final int tone) {
        return Hyb.mix(tone, FILL, 0.35f);
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<Notice> notices = session.notices();
        final int w = getArea().width;
        final int hover = isHovering() ? rowAt(localY()) : -1;
        // An alarm blinks with its ring's card outlines (their cards may be off screen); steady in a picture.
        final boolean bright = com.gtnhplanner.ui.canvas.PlanPicture.drawing() || CardPaint.promptOn();
        int y = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final Notice n = notices.get(i);
            final NoticeRow r = row(n);
            final int rw = r.width(), rh = r.height();
            if (n.loop() != null) {
                // The row tinted 16% with the ring's tone, its stripe and chip in it, the chip's word dark.
                final int tone = CardFace.loopTone(n.loop());
                Hyb.rect(0, y, rw, rh - 1, Hyb.mix(tone, FILL, 0.16f) & 0x00FFFFFF | 0xE0000000);
                Hyb.rect(0, y, 3, rh - 1, bright ? tone : dim(tone));
                Hyb.rect(LEFT, y + 1, r.chipWidth(), LINE + 1, bright ? tone : dim(tone));
                Hyb.text(n.tag(), LEFT + CHIP_PAD, y + 3, bright ? CHIP_INK : tone);
            } else {
                Hyb.rect(0, y, rw, rh - 1, 0xE0181A1F);
                Hyb.rect(0, y, 3, rh - 1, stripe(n.severity()));
            }
            final NoteText text = r.text();
            for (int l = 0; l < text.lines()
                .size(); l++)
                Hyb.text(
                    text.shown(l)
                        .strip(),
                    r.textX(),
                    y + r.textY() + l * LINE,
                    Hyb.INK);
            if (n.hasKey()) {
                final int showW = Hyb.width(n.keyLabel());
                final boolean hot = hover == i && localX() >= rw - showW - 8 && localX() < rw;
                final int color = hot ? 0xFFFFFFFF : Hyb.SELECTION;
                Hyb.text(n.keyLabel(), rw - showW - 5, y + 3, color);
                Hyb.rect(rw - showW - 5, y + 12, showW, 1, color);
            }
            y += rh;
        }
        if (notices.size() > MAX_ROWS) {
            Hyb.rect(0, y, w, ROW - 1, 0xE0181A1F);
            Hyb.text("+" + (notices.size() - MAX_ROWS) + " more", LEFT, y + 3, Hyb.MUTED);
        }
    }

    /** The notice row at {@code y}, or -1. */
    private int rowAt(final int y) {
        final List<Notice> notices = session.notices();
        int top = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final int rh = row(notices.get(i)).height();
            if (y >= top && y < top + rh) return i;
            top += rh;
        }
        return -1;
    }

    /**
     * The GUI rectangle {x, y, w, h} of the first notice's key labelled {@code label} (the tour presses one), or null
     * when none shows.
     */
    public int[] keyRect(final String label) {
        final List<Notice> notices = session.notices();
        int y = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final Notice n = notices.get(i);
            final NoticeRow r = row(n);
            if (n.hasKey() && label.equals(n.keyLabel())) {
                final int showW = Hyb.width(n.keyLabel());
                return new int[] { getArea().x + r.width() - showW - 5, getArea().y + y + 2, showW, LINE + 2 };
            }
            y += r.height();
        }
        return null;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.IGNORE;
        final int row = rowAt(localY());
        final List<Notice> notices = session.notices();
        if (row < 0) return Result.IGNORE;
        final Notice n = notices.get(row);
        final int rw = row(n).width();
        if (localX() >= rw) return Result.IGNORE;
        if (!n.hasKey() || localX() < rw - Hyb.width(n.keyLabel()) - 8) return Result.SUCCESS;
        if (n.action() != null) {
            Hyb.click();
            n.action()
                .run();
            return Result.SUCCESS;
        }
        // All at once while that still shows their ports; else one at a time, in turn, close enough to read.
        Hyb.click();
        final List<UUID> focus = n.focus();
        if (focus.size() == 1 || canvas.framesReadably(focus)) canvas.frame(focus);
        else canvas.frame(List.of(focus.get((turns.merge(n.text(), 1, Integer::sum) - 1) % focus.size())));
        return Result.SUCCESS;
    }
}
