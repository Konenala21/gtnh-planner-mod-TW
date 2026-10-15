package com.gtnhplanner.ui.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.PlannerScale;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.TextPopup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.theme.Icons;

/**
 * The open plan's task board, in the board's place (the overview and the top bar stay): four columns, To do, Doing,
 * Blocked and Done, each a stack of task cards (the title, who is on it, the checklist's progress, comments and cards
 * linked). A card is carried by the board's rules: a press let go within {@value #DRAG_SLOP} pixels is a click and
 * opens
 * the task; moved further it lifts, the others glide apart where it would land, and letting go puts it there. The
 * wheel scrolls a column.
 */
public final class TaskBoardView extends Widget<TaskBoardView> implements Interactable {

    private static final int HEAD_H = 24, PAD = 8, GAP = 8, COL_HEAD = 20, INSET = 4, CARD_GAP = 4, LINE = 10,
        CARD_PAD = 6, KEY_H = 16;
    /**
     * The narrowest a column gets: its name, count and + key, and titles that wrap readably. Narrower than four of
     * them, the board scrolls sideways (Shift and the wheel, the wheel over the column names, or the bar along the
     * bottom), as the website's does.
     */
    private static final int MIN_COL = 112, BAR_H = 3;
    private static final int COLUMN_BG = 0xFF18191C, COLUMN_EDGE = 0xFF26282D, CARD_HOT = 0xFF2A2C32;
    /** How far the mouse goes before a press on a card carries it, as on the board. */
    private static final int DRAG_SLOP = 3;
    /** How quickly cards glide to their places: about 120 ms, as on the board. */
    private static final float EASE_SECONDS = 0.035f;

    private final BoardSession session;
    private final int[] scroll = new int[4], contentH = new int[4];
    /** How far the columns are scrolled sideways, and the bar's thumb while it is dragged (where it was taken). */
    private int across;
    private boolean draggingBar;
    private int barGrab;

    /** Where each card is drawn, gliding to where it belongs: task id to {x, y}. */
    private final Map<String, float[]> shown = new HashMap<>();
    private long glideNanos = System.nanoTime();

    /** The card a left press went down on, where, and whether it has gone far enough to be carried. */
    @Nullable
    private String pressed;
    private int pressX, pressY;
    private boolean carrying;
    /** The mouse in the carried card, where it is drawn, and its height. */
    private float grabX, grabY, carryX, carryY;
    private int carryH;
    /** Where the carried card would land: its column and its place among the others there. */
    @Nullable
    private Tasks.Status dropStatus;
    private int dropIndex;

    private enum Kind {
        CARD,
        ADD,
        NEW,
        FROM_CARDS
    }

    private record Hit(Kind kind, int x0, int y0, int x1, int y1, @Nullable Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();

    /** A card laid out: its task, its column, its place and size, and its title in lines. */
    private record Placed(Tasks.Task task, int column, float x, float y, int w, int h, NoteText title) {}

    public TaskBoardView(final BoardSession session) {
        this.session = session;
    }

    private Graph graph() {
        return session.graph();
    }

    // region Layout

    private int columnW() {
        return Math.max(MIN_COL, (getArea().width - 2 * PAD - 3 * GAP) / 4);
    }

    private int columnX(final int c) {
        return PAD + c * (columnW() + GAP) - across;
    }

    /** How far the columns scroll sideways: nothing while all four fit. */
    private int maxAcross() {
        return Math.max(0, 2 * PAD + 4 * columnW() + 3 * GAP - getArea().width);
    }

    private int bodyTop() {
        return HEAD_H + PAD + COL_HEAD;
    }

    private int bodyBottom() {
        return getArea().height - PAD;
    }

    /** Titles in lines, kept while they and the width stay the same. */
    private final Map<String, NoteText> titles = new HashMap<>();

    private NoteText title(final String title, final int width) {
        if (titles.size() > 512) titles.clear();
        return titles.computeIfAbsent(width + "\n" + title, k -> NoteText.of(title, width, Hyb::width));
    }

    private int titleWidth(final int cardW) {
        return cardW - 2 * CARD_PAD - 2;
    }

    /** Each task's checklist (ticked, all) and comments, counted once per change of the plan. */
    private Map<String, int[]> counts = Map.of();
    private Graph countedGraph;
    private long countedVersion = Long.MIN_VALUE;

    /** A task's {ticked, lines, comments}. */
    private int[] counts(final String taskId) {
        final Graph g = graph();
        if (g != countedGraph || g.version() != countedVersion) {
            final Map<String, int[]> next = new HashMap<>();
            for (final Tasks.Item i : g.taskItems) {
                final int[] c = next.computeIfAbsent(i.taskId(), k -> new int[3]);
                if (i.done()) c[0]++;
                c[1]++;
            }
            for (final Tasks.Comment c : g.taskComments) next.computeIfAbsent(c.taskId(), k -> new int[3])[2]++;
            counts = next;
            countedGraph = g;
            countedVersion = g.version();
        }
        final int[] c = counts.get(taskId);
        return c == null ? new int[3] : c;
    }

    /** Whether a card has a row under its title: faces, or counts. */
    private boolean hasMeta(final Tasks.Task t) {
        final int[] c = counts(t.id());
        return !t.assignees()
            .isEmpty()
            || !t.nodeIds()
                .isEmpty()
            || c[1] > 0
            || c[2] > 0;
    }

    private int cardH(final Tasks.Task t, final int cardW) {
        final int lines = title(t.title(), titleWidth(cardW)).lines()
            .size();
        return CARD_PAD + lines * LINE + (hasMeta(t) ? 3 + TaskLook.FACE : 0) + CARD_PAD - 2;
    }

    /** Every card where it belongs this frame, the carried one left out and a gap where it would land. */
    private List<Placed> layout() {
        final List<Placed> out = new ArrayList<>();
        final int cw = columnW() - 2 * INSET;
        final Tasks.Status[] statuses = Tasks.Status.values();
        for (int c = 0; c < statuses.length; c++) {
            final List<Tasks.Task> tasks = TaskEdits.column(graph(), statuses[c]);
            if (carrying) tasks.removeIf(
                t -> t.id()
                    .equals(pressed));
            final int top = bodyTop() + INSET;
            float y = top - scroll[c];
            for (int i = 0; i <= tasks.size(); i++) {
                if (carrying && dropStatus == statuses[c] && dropIndex == i) y += carryH + CARD_GAP;
                if (i == tasks.size()) break;
                final Tasks.Task t = tasks.get(i);
                final int h = cardH(t, cw);
                out.add(new Placed(t, c, columnX(c) + INSET, y, cw, h, title(t.title(), titleWidth(cw))));
                y += h + CARD_GAP;
            }
            contentH[c] = Math.round(y + scroll[c] - top);
        }
        return out;
    }

    /** Where a card carried to the mouse would land: the column under it, and the place there by the cards' middles. */
    private void findDrop(final int mx, final int my) {
        final int cw = columnW();
        int c = 0;
        for (int k = 0; k < 4; k++) if (mx >= columnX(k) - GAP / 2) c = k;
        c = Math.max(0, Math.min(3, c));
        final Tasks.Status status = Tasks.Status.values()[c];
        final List<Tasks.Task> others = TaskEdits.column(graph(), status);
        others.removeIf(
            t -> t.id()
                .equals(pressed));
        final float middle = carryY + carryH / 2f;
        float y = bodyTop() + INSET - scroll[c];
        int index = others.size();
        for (int i = 0; i < others.size(); i++) {
            final int h = cardH(others.get(i), cw - 2 * INSET);
            if (middle < y + h / 2f) {
                index = i;
                break;
            }
            y += h + CARD_GAP;
        }
        dropStatus = status;
        dropIndex = index;
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final int w = getArea().width, h = getArea().height;
        Hyb.rect(0, 0, w, h, Hyb.CANVAS);
        final Hit hover = carrying ? null : hitAt(lastHits);
        final long now = System.nanoTime();
        final float dt = Math.min(0.05f, (now - glideNanos) / 1e9f);
        glideNanos = now;
        final float k = 1 - (float) Math.exp(-dt / EASE_SECONDS);

        lift();
        if (carrying) {
            carryX += (localX() - grabX - carryX) * k;
            carryY += (localY() - grabY - carryY) * k;
            edgeScroll(dt);
            findDrop(localX(), localY());
        }
        across = Math.max(0, Math.min(maxAcross(), across));
        drawHeader(w, hover);
        final List<Placed> cards = layout();
        final Tasks.Status[] statuses = Tasks.Status.values();
        // The columns in the view: scrolled sideways, those past an edge are cut there.
        Stencil.apply(0, HEAD_H, w, h - HEAD_H, context);
        for (int c = 0; c < statuses.length; c++) drawColumn(c, statuses[c], hover, context);
        // The cards, column by column, each clipped to its column's body.
        final int top = bodyTop(), bottom = bodyBottom();
        for (int c = 0; c < statuses.length; c++) {
            final int x = columnX(c);
            Stencil.apply(x, top, columnW(), bottom - top, context);
            if (carrying && dropStatus == statuses[c]) drawGap(c, cards);
            for (final Placed p : cards) {
                if (p.column() != c) continue;
                final float[] at = shown.computeIfAbsent(
                    p.task()
                        .id(),
                    id -> new float[] { p.x(), p.y() });
                at[0] += (p.x() - at[0]) * k;
                at[1] += (p.y() - at[1]) * k;
                if (Math.abs(p.x() - at[0]) < 0.5f) at[0] = p.x();
                if (Math.abs(p.y() - at[1]) < 0.5f) at[1] = p.y();
                if (at[1] + p.h() < top || at[1] > bottom) continue;
                final boolean hot = hover != null && hover.kind() == Kind.CARD
                    && p.task()
                        .id()
                        .equals(hover.data());
                drawCard(p.task(), p.title(), at[0], at[1], p.w(), p.h(), hot, false);
                final int y0 = Math.max(top, Math.round(at[1])), y1 = Math.min(bottom, Math.round(at[1]) + p.h());
                if (y1 > y0) hits.add(
                    new Hit(
                        Kind.CARD,
                        Math.round(at[0]),
                        y0,
                        Math.round(at[0]) + p.w(),
                        y1,
                        p.task()
                            .id()));
            }
            if (graph().tasks.isEmpty() && c == 0) drawEmpty(x, top);
            Stencil.remove();
            scroll[c] = Math.max(0, Math.min(scroll[c], contentH[c] - (bottom - top - INSET)));
        }
        Stencil.remove();
        drawAcrossBar(w, h);
        // The carried card over everything, lifted.
        if (carrying) {
            final Tasks.Task t = TaskEdits.task(graph(), pressed);
            if (t == null) carrying = false;
            else {
                final int cw = columnW() - 2 * INSET;
                drawCard(t, title(t.title(), titleWidth(cw)), carryX, carryY, cw, carryH, true, true);
            }
        }
        lastHits.clear();
        lastHits.addAll(hits);
    }

    /**
     * The board's title and how many tasks are open and done, then (right to left) a new task and a task from the
     * cards selected on the plan. Narrow, the counts go first, then the second key's words shorten, then it goes (a
     * right-click on the cards does the same).
     */
    private void drawHeader(final int w, @Nullable final Hit hover) {
        Hyb.rect(0, 0, w, HEAD_H, 0xFF2A2D33);
        Hyb.rect(0, HEAD_H - 1, w, 1, Hyb.RING);
        Hyb.text("Tasks", PAD, (HEAD_H - 8) / 2f, Hyb.INK);
        int right = w - PAD;
        right = key(right, "New task", Kind.NEW, null, hover) - 6;
        final int left = PAD + Hyb.width("Tasks") + 8;
        final int selected = TaskActions.selectedCards(session)
            .size();
        if (selected > 0) {
            final String cards = selected + (selected == 1 ? " card" : " cards");
            for (final String label : new String[] { "Make a task from " + cards, "Task from " + cards }) {
                if (right - Hyb.width(label) - 12 < left - 8) continue;
                right = key(right, label, Kind.FROM_CARDS, null, hover) - 6;
                break;
            }
        }
        int open = 0, done = 0;
        for (final Tasks.Task t : graph().tasks) if (t.status() == Tasks.Status.DONE) done++;
        else open++;
        final String counts = open + " open and " + done + " done";
        if (left + Hyb.width(counts) <= right - 4) Hyb.text(counts, left, (HEAD_H - 8) / 2f, Hyb.MUTED);
    }

    /** While the columns are wider than the view: a thin bar along the bottom, its thumb where the view is. */
    private void drawAcrossBar(final int w, final int h) {
        final int max = maxAcross();
        if (max <= 0) return;
        final int thumb = thumbW(), tx = (w - thumb) * across / max, y = h - PAD / 2 - BAR_H / 2 - 1;
        Hyb.rect(0, y, w, BAR_H, 0x60000000);
        Hyb.rect(tx, y, thumb, BAR_H, draggingBar || onAcrossBar() ? Hyb.INK : 0xFF6A6C74);
    }

    private int thumbW() {
        final int w = getArea().width;
        return Math.max(16, w * w / Math.max(1, w + maxAcross()));
    }

    /** Whether the mouse is on the bar's band, in the bottom padding, while there is one. */
    private boolean onAcrossBar() {
        return maxAcross() > 0 && isHovering() && localY() >= bodyBottom();
    }

    /** Scrolls so the bar's thumb starts at {@code x}. */
    private void acrossTo(final int x) {
        final int room = getArea().width - thumbW();
        if (room > 0) across = Math.max(0, Math.min(maxAcross(), Math.round((float) x * maxAcross() / room)));
    }

    /** A key in the header, right-aligned at {@code right}; returns its left edge. */
    private int key(final int right, final String label, final Kind kind, @Nullable final Object data,
        @Nullable final Hit hover) {
        final int kw = Hyb.width(label) + 12, x = right - kw, y = (HEAD_H - KEY_H) / 2;
        final boolean hot = hover != null && hover.kind() == kind;
        Hyb.bevel(x, y, kw, KEY_H, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(label, x + 6, y + 4, Hyb.INK);
        hits.add(new Hit(kind, x, y, right, y + KEY_H, data));
        return x;
    }

    private void drawColumn(final int c, final Tasks.Status status, @Nullable final Hit hover,
        final ModularGuiContext context) {
        final int x = columnX(c), w = columnW(), top = HEAD_H + PAD;
        // Its name with its colour and how many, and the key that adds a task to it.
        TaskLook.dot(x + 2, top + 6, 6, TaskLook.colour(status));
        final String name = TaskLook.name(status);
        Hyb.text(name, x + 12, top + 5, Hyb.INK);
        Hyb.text(
            Integer.toString(
                TaskEdits.column(graph(), status)
                    .size()),
            x + 12 + Hyb.width(name) + 5,
            top + 5,
            Hyb.MUTED);
        final int ax = x + w - 16, ay = top + 2;
        final boolean hot = hover != null && hover.kind() == Kind.ADD && hover.data() == status;
        if (hot) Hyb.rect(ax, ay, 16, 16, Hyb.KEY_HOVER);
        Hyb.rect(ax + 4, ay + 7, 8, 2, hot ? Hyb.INK : Hyb.MUTED);
        Hyb.rect(ax + 7, ay + 4, 2, 8, hot ? Hyb.INK : Hyb.MUTED);
        // Only while it shows: a column scrolled past an edge has its key cut there.
        if (ax >= 0 && ax + 16 <= getArea().width) hits.add(new Hit(Kind.ADD, ax, ay, ax + 16, ay + 16, status));
        // The column's well, lit faintly while a carried card would land in it.
        final int bt = bodyTop(), bh = bodyBottom() - bt;
        Hyb.rect(x, bt, w, bh, COLUMN_EDGE);
        Hyb.rect(x + 1, bt + 1, w - 2, bh - 2, COLUMN_BG);
        if (carrying && dropStatus == status)
            Hyb.rect(x + 1, bt + 1, w - 2, bh - 2, TaskLook.colour(status) & 0x00FFFFFF | 0x0C000000);
        // A thin bar while there is more than fits.
        final int visible = bh - INSET;
        if (contentH[c] > visible) {
            final int bar = Math.max(12, visible * visible / contentH[c]);
            final int by = bt + (visible - bar) * scroll[c] / Math.max(1, contentH[c] - visible);
            Hyb.rect(x + w - 3, by + 2, 2, bar - 4, 0xFF4A4C54);
        }
    }

    /** Where the carried card would land: its outline, dashed. */
    private void drawGap(final int c, final List<Placed> cards) {
        float y = bodyTop() + INSET - scroll[c];
        int i = 0;
        for (final Placed p : cards) {
            if (p.column() != c) continue;
            if (i == dropIndex) break;
            y = p.y() + p.h() + CARD_GAP;
            i++;
        }
        Hyb.dashed(columnX(c) + INSET, y, columnW() - 2 * INSET, carryH, 0x80A1A4AD);
    }

    /** A task's card: its colour down the left, its title, then who is on it and how far along it is. */
    private void drawCard(final Tasks.Task t, final NoteText title, final float x, final float y, final int w,
        final int h, final boolean hot, final boolean lifted) {
        if (lifted) {
            Hyb.rect(x + 4, y + 6, w, h, 0x50000000);
            Hyb.rect(x + 2, y + 3, w, h, 0x40000000);
        }
        Hyb.rect(x, y, w, h, CardPaint.EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, hot ? CARD_HOT : CardPaint.SURFACE);
        Hyb.rect(x + 1, y + 1, 2, h - 2, TaskLook.colour(t.status()));
        final boolean done = t.status() == Tasks.Status.DONE;
        final float tx = x + CARD_PAD + 2;
        float ty = y + CARD_PAD;
        for (int i = 0; i < title.lines()
            .size(); i++) {
            Hyb.text(title.shown(i), tx, ty, done ? Hyb.MUTED : Hyb.INK);
            ty += LINE;
        }
        if (!hasMeta(t)) return;
        final float my = y + h - CARD_PAD + 2 - TaskLook.FACE;
        TaskLook.faces(graph(), t.assignees(), tx, my);
        // Right to left: cards linked, comments, the checklist's progress.
        float right = x + w - CARD_PAD;
        final int links = t.nodeIds()
            .size();
        if (links > 0) right = count(Icons::card, Integer.toString(links), right, my, Hyb.MUTED) - 6;
        final int[] c = counts(t.id());
        if (c[2] > 0) right = count(Icons::comment, Integer.toString(c[2]), right, my, Hyb.MUTED) - 6;
        if (c[1] > 0) count(Icons::check, c[0] + "/" + c[1], right, my, c[0] == c[1] ? Hyb.OK_INK : Hyb.MUTED);
    }

    private interface Picture {

        void draw(int x, int y, int colour);
    }

    /** A small picture and a number, right-aligned at {@code right}; returns its left edge. */
    private static float count(final Picture picture, final String n, final float right, final float y,
        final int colour) {
        final float x = right - Hyb.width(n) - Icons.SIZE - 2;
        picture.draw(Math.round(x), Math.round(y + 1), colour);
        Hyb.text(n, x + Icons.SIZE + 2, y + 2, colour);
        return x;
    }

    /** A board with no tasks says how to make one, each line wrapped to the column. */
    private void drawEmpty(final int x, final int top) {
        final int cx = x + columnW() / 2, width = columnW() - 12;
        float y = centred("No tasks yet.", cx, top + 14, width, Hyb.MUTED) + 4;
        y = centred("Press + to add one.", cx, y, width, 0xFF6A6C74);
        centred("Or right-click cards on the plan.", cx, y, width, 0xFF6A6C74);
    }

    /** Words centred on {@code cx}, wrapped to {@code width}; returns the y under them. */
    private static float centred(final String words, final int cx, final float y, final int width, final int colour) {
        final NoteText t = NoteText.of(words, width, Hyb::width);
        float at = y;
        for (int i = 0; i < t.lines()
            .size(); i++) {
            Hyb.textCentered(
                t.shown(i)
                    .strip(),
                cx,
                at,
                colour);
            at += LINE;
        }
        return at;
    }

    /** Tips wait until the mouse has rested on their thing a moment. */
    private final Tip.Wait tipWait = new Tip.Wait();

    @Override
    public void drawOverlay(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        super.drawOverlay(context, widgetTheme);
        // A popup over the board explains itself.
        if (getScreen().getPanelManager()
            .getTopMostPanel() != getPanel()) return;
        final Hit hover = carrying || pressed != null ? null : hitAt(lastHits);
        if (!tipWait.ready(hover == null ? null : hover.data() == null ? hover.kind() : hover.data())) return;
        final Tip tip = tip(hover);
        if (tip != null) tip.drawNear(localX(), localY(), getArea().width, getArea().height);
    }

    @Nullable
    private Tip tip(@Nullable final Hit hit) {
        if (hit == null) return null;
        return switch (hit.kind()) {
            case CARD -> {
                final Tasks.Task t = TaskEdits.task(graph(), (String) hit.data());
                yield t == null ? null
                    : Tip.of(t.title())
                        .sub(TaskLook.name(t.status()))
                        .action(Tip.Input.LEFT, "Open it")
                        .action(Tip.Input.DRAG, "Move it")
                        .action(Tip.Input.RIGHT, "More");
            }
            case ADD -> Tip.of("Add a task to " + TaskLook.name((Tasks.Status) hit.data()));
            case NEW -> Tip.of("Add a task to To do");
            case FROM_CARDS -> Tip.of("Make a task from the cards selected on the plan")
                .muted("A checklist line for each card with how many to build.");
        };
    }

    // endregion

    // region Input

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton == 0 && onAcrossBar()) {
            // On the thumb it is taken where it was grabbed; on the track the thumb jumps to centre on the mouse.
            final int max = maxAcross(), thumb = thumbW(), tx = (getArea().width - thumb) * across / max, mx = localX();
            barGrab = mx >= tx && mx < tx + thumb ? mx - tx : thumb / 2;
            draggingBar = true;
            acrossTo(mx - barGrab);
            return Result.SUCCESS;
        }
        final Hit hit = hitAt(lastHits);
        if (hit == null) return Result.ACCEPT;
        if (mouseButton == 1 && hit.kind() == Kind.CARD) {
            openMenu((String) hit.data());
            return Result.SUCCESS;
        }
        if (mouseButton != 0) return Result.ACCEPT;
        switch (hit.kind()) {
            case CARD -> {
                pressed = (String) hit.data();
                pressX = getContext().getAbsMouseX();
                pressY = getContext().getAbsMouseY();
                final float[] at = shown.get(pressed);
                grabX = localX() - (at == null ? hit.x0() : at[0]);
                grabY = localY() - (at == null ? hit.y0() : at[1]);
            }
            case ADD -> {
                Hyb.click();
                newTask((Tasks.Status) hit.data(), hit);
            }
            case NEW -> {
                Hyb.click();
                newTask(Tasks.Status.TODO, hit);
            }
            case FROM_CARDS -> {
                Hyb.click();
                final String id = TaskActions.fromCards(session, session.selection());
                if (id != null) session.clearSelection();
            }
        }
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (draggingBar) acrossTo(localX() - barGrab);
        lift();
    }

    /** A press that has gone {@value #DRAG_SLOP} pixels lifts its card. Checked as the mouse moves and every frame. */
    private void lift() {
        if (pressed == null || carrying) return;
        final int dx = getContext().getAbsMouseX() - pressX, dy = getContext().getAbsMouseY() - pressY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) < DRAG_SLOP) return;
        final Tasks.Task t = TaskEdits.task(graph(), pressed);
        if (t == null) {
            pressed = null;
            return;
        }
        final float[] at = shown.get(pressed);
        carryX = at == null ? localX() - grabX : at[0];
        carryY = at == null ? localY() - grabY : at[1];
        carryH = cardH(t, columnW() - 2 * INSET);
        carrying = true;
        findDrop(localX(), localY());
        Sfx.LIFT.play();
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        if (mouseButton != 0 || pressed == null) return false;
        release();
        return true;
    }

    /**
     * The screen hears every release: a press let go off the view (over the overview, the top bar) never reaches it,
     * and
     * ends here all the same.
     */
    public void screenReleased(final int mouseButton) {
        if (mouseButton == 0) draggingBar = false;
        if (mouseButton == 0 && pressed != null) release();
    }

    /** The press ends: a click opens the task; a carry puts the card where it would land. */
    private void release() {
        final String id = pressed;
        pressed = null;
        if (!carrying) {
            TaskPanel.open(session, id);
            return;
        }
        carrying = false;
        final Tasks.Task t = TaskEdits.task(graph(), id);
        if (t == null || dropStatus == null) return;
        // It glides from where it was let go to its place.
        shown.put(id, new float[] { carryX, carryY });
        final Tasks.Status to = dropStatus;
        final int index = dropIndex;
        dropStatus = null;
        final List<Tasks.Task> column = TaskEdits.column(graph(), to);
        final boolean stays = to == t.status() && column.indexOf(t) == index;
        if (!stays) session.editTasks(() -> TaskEdits.move(session.graph(), id, to, index, TaskLook.now()));
        Sfx.DROP.play();
    }

    /** Close to the top or bottom of a column, a carried card scrolls it; close to the view's sides, the columns. */
    private void edgeScroll(final float dt) {
        final int mx = localX(), zone = 24;
        final float speed = 400 * dt;
        if (mx < zone) across = Math.max(0, across - Math.round(speed));
        else if (mx > getArea().width - zone) across = Math.min(maxAcross(), across + Math.round(speed));
        if (dropStatus == null) return;
        final int c = dropStatus.ordinal(), my = localY();
        if (my < bodyTop() + zone) scroll[c] = Math.max(0, scroll[c] - Math.round(speed));
        else if (my > bodyBottom() - zone) scroll[c] += Math.round(speed);
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int mx = localX();
        // Sideways: Shift held, or over the column names or the bar.
        if (maxAcross() > 0 && (net.minecraft.client.gui.GuiScreen.isShiftKeyDown() || localY() < bodyTop()
            || localY() >= bodyBottom())) {
            across = Math.max(0, Math.min(maxAcross(), across + (direction == UpOrDown.UP ? -1 : 1) * 40));
            return true;
        }
        for (int c = 0; c < 4; c++) if (mx >= columnX(c) && mx < columnX(c) + columnW()) {
            scroll[c] = Math.max(0, scroll[c] + (direction == UpOrDown.UP ? -1 : 1) * 24);
            return true;
        }
        return true;
    }

    /** Asks for a new task's title, then adds it at the bottom of {@code status}. */
    private void newTask(final Tasks.Status status, final Hit under) {
        Popup.open(session.panel(), TextPopup.create("New task in " + TaskLook.name(status), "", title -> {
            if (title == null || title.isBlank()) return;
            final String id = UUID.randomUUID()
                .toString();
            session.editTasks(
                () -> TaskEdits.add(session.graph(), id, title, status, List.of(), TaskLook.me(), TaskLook.now()));
            Sfx.NOTE_STICK.play();
        }), getArea().x + under.x0(), getArea().y + under.y1() + 2);
    }

    /** A card's right-click menu: open it, move it to another column, or delete it. */
    private void openMenu(final String id) {
        final Tasks.Task t = TaskEdits.task(graph(), id);
        if (t == null) return;
        Hyb.click();
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Open", () -> TaskPanel.open(session, id)));
        for (final Tasks.Status s : Tasks.Status.values())
            if (s != t.status()) rows.add(PickList.Entry.of("Move to " + TaskLook.name(s), () -> {
                session.editTasks(
                    () -> TaskEdits.move(
                        session.graph(),
                        id,
                        s,
                        TaskEdits.column(session.graph(), s)
                            .size(),
                        TaskLook.now()));
                Sfx.DROP.play();
            }));
        rows.add(new PickList.Entry(null, "Delete task", "", Hyb.RED_INK, false, () -> {
            session.editTasks(() -> TaskEdits.remove(session.graph(), id));
            Sfx.NOTE_CRUMPLE.play();
        }));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_menu", null, rows, false, 120),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    // endregion

    // region For the dev harness

    /** The board as data: each column's tasks top first, with each card's GUI rectangle in the game's pixels. */
    public Map<String, Object> describe() {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Tasks.Status s : Tasks.Status.values()) {
            final List<Object> column = new ArrayList<>();
            for (final Tasks.Task t : TaskEdits.column(graph(), s)) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id());
                m.put("title", t.title());
                for (final Hit h : lastHits) if (h.kind() == Kind.CARD && t.id()
                    .equals(h.data())) m.put("rect", rect(h));
                column.add(m);
            }
            out.put(s.wire, column);
        }
        final Map<String, Object> keys = new LinkedHashMap<>();
        for (final Hit h : lastHits) {
            if (h.kind() == Kind.ADD) keys.put("add:" + ((Tasks.Status) h.data()).wire, rect(h));
            else if (h.kind() != Kind.CARD) keys.put(
                h.kind()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT),
                rect(h));
        }
        out.put("keys", keys);
        return out;
    }

    private Map<String, Object> rect(final Hit h) {
        final int x = PlannerScale.toGame(getArea().x + h.x0()), y = PlannerScale.toGame(getArea().y + h.y0());
        final int w = PlannerScale.toGame(getArea().x + h.x1()) - x, hh = PlannerScale.toGame(getArea().y + h.y1()) - y;
        return Map.of("x", x, "y", y, "w", w, "h", hh, "cx", x + w / 2, "cy", y + hh / 2);
    }

    // endregion

    // region Helpers

    @Nullable
    private Hit hitAt(final List<Hit> from) {
        if (!isHovering()) return null;
        final int x = localX(), y = localY();
        for (int i = from.size() - 1; i >= 0; i--) if (from.get(i)
            .contains(x, y)) return from.get(i);
        return null;
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    // endregion
}
