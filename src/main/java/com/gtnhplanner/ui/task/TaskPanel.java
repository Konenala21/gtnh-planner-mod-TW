package com.gtnhplanner.ui.task;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.PlannerScale;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.TextArea;
import com.gtnhplanner.ui.popup.TextPopup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.theme.Icons;

/**
 * A task, open in a box in the middle of the screen in the card's look: its title (written in place), its column, who
 * is on it; on the left its notes and the cards it is about (a click shows one on the plan, centred and selected); on
 * the right its checklist (tick, write, drag to reorder, delete, link a line to one of its cards) and its comments
 * (who wrote each and when; your own can be deleted). Every change is one undoable edit of the plan, made as it
 * happens; the box follows the plan, so undo shows here too, and a task undone away closes it.
 *
 * <p>
 * The box fits the planner it opens in. Narrow (GTNH's GUI scale 4 leaves the planner 330 to 440 pixels wide), the two
 * sides go one under the other; short, everything between the title bar and the foot scrolls as a page, and a field
 * the page has scrolled part way out of view hides until it is back.
 */
public final class TaskPanel extends ParentWidget<TaskPanel> implements Interactable {

    /**
     * The box's widest, and the least width that holds two columns side by side (narrower, the parts go one under the
     * other); the least body that holds both columns' lists (shorter, the page scrolls).
     */
    private static final int MAX_W = 460, TWO_COLUMNS = 400, MIN_BODY = 136;
    private static final int TITLE = 26, PAD = 10, CHIP_H = 16, LABEL = 12, FIELD_H = 14, KEY_H = 16, FOOT = 26;
    /** Room the assign key and a few faces need beside the column chips; with less they go on a row of their own. */
    private static final int PEOPLE_ROOM = 130;
    private static final int WELL = 0xFF16171A, CONTROL = 0xFF2A2C31, CONTROL_HOVER = 0xFF33363C, CYAN = 0xFF22D3EE,
        ROW_HOVER = 0xFF2A2C31;
    private static final int DRAG_SLOP = 3;

    /** The box open now, for the dev harness. */
    private static WeakReference<TaskPanel> showing = new WeakReference<>(null);

    private final BoardSession session;
    private final String taskId;
    /** The box's size, fitted to the planner when it opened. */
    private final int boxW, h;
    /** The two columns, left and right; on a narrow box both span it, one under the other. */
    private final int leftW, rightX, rightW;
    private final TextFieldWidget titleField, addField;
    private final TextArea notes, comment;
    /**
     * Where the parts go, top to bottom, on the box's page: everything between the title bar and the foot, which
     * scrolls when the planner is too short for it.
     */
    private final int statusY, peopleY, notesY, notesH, cardsY, cardsBottom, itemsY, itemsBottom, addY, commentsY,
        commentsBottom, commentBoxY, pageBottom;
    private int cardsScroll, itemsScroll, commentsScroll, cardsH, itemsH, commentsH;
    /** How far the page is scrolled, and where the fields were last put for it. */
    private int page, fieldsAt = -1;

    /** A checklist line pressed, where, and whether it is being carried to a new place. */
    @Nullable
    private String itemPressed;
    private int itemPressX, itemPressY, itemDrop;
    private boolean itemCarrying;

    private enum Kind {
        CLOSE,
        STATUS,
        ASSIGN,
        SIGN_IN,
        CARD,
        UNLINK,
        BOX,
        ITEM,
        ITEM_LINK,
        ITEM_DELETE,
        COMMENT,
        COMMENT_DELETE,
        POST,
        DELETE
    }

    private record Hit(Kind kind, int x0, int y0, int x1, int y1, @Nullable Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();

    /** The column chips side by side: their width. */
    private static int chipsW() {
        int w = 0;
        for (final Tasks.Status s : Tasks.Status.values()) w += Hyb.width(TaskLook.name(s)) + 20 + 4;
        return w - 4;
    }

    private TaskPanel(final BoardSession session, final String taskId, final int boxW, final int roomH) {
        this.session = session;
        this.taskId = taskId;
        this.boxW = boxW;
        statusY = TITLE + 8;
        // Who is on it at the chips' row end, or on a row of its own under them when they would not fit beside.
        peopleY = boxW - 2 * PAD - chipsW() - 8 >= PEOPLE_ROOM ? statusY : statusY + CHIP_H + 6;
        final int columnsY = peopleY + CHIP_H + 12;
        if (boxW >= TWO_COLUMNS) {
            leftW = (boxW - 2 * PAD - 16) * 196 / (MAX_W - 2 * PAD - 16);
            rightX = PAD + leftW + 16;
            rightW = boxW - rightX - PAD;
            h = roomH;
            // The body fills the box down to the foot, or takes its least and the page scrolls.
            final int bottom = Math.max(h - FOOT - 4, columnsY + MIN_BODY);
            // Left: the notes, then the cards.
            notesY = columnsY + LABEL;
            notesH = Math.max(40, Math.min(84, (bottom - columnsY) / 3));
            cardsY = notesY + notesH + 10 + LABEL;
            cardsBottom = bottom;
            // Right: the checklist and its box, then the comments and theirs.
            final int share = bottom - columnsY - 2 * LABEL - FIELD_H - 10 - 28;
            itemsY = columnsY + LABEL;
            itemsBottom = itemsY + share / 2;
            addY = itemsBottom + 2;
            commentsY = addY + FIELD_H + 10 + LABEL;
            commentBoxY = bottom - 26;
            commentsBottom = commentBoxY - 4;
            pageBottom = bottom + 4;
        } else {
            // One under the other: the notes, the cards, the checklist and its box, the comments and theirs.
            leftW = boxW - 2 * PAD;
            rightX = PAD;
            rightW = leftW;
            notesY = columnsY + LABEL;
            notesH = 44;
            cardsY = notesY + notesH + 10 + LABEL;
            cardsBottom = cardsY + 40;
            itemsY = cardsBottom + 10 + LABEL;
            itemsBottom = itemsY + 44;
            addY = itemsBottom + 2;
            commentsY = addY + FIELD_H + 10 + LABEL;
            commentsBottom = commentsY + 56;
            commentBoxY = commentsBottom + 4;
            pageBottom = commentBoxY + 26 + 6;
            h = Math.min(roomH, pageBottom + FOOT);
        }
        size(boxW, h);

        titleField = new TextFieldWidget().value(new StringValue.Dynamic(() -> {
            final Tasks.Task t = task();
            return t == null ? "" : t.title();
        }, this::setTitle));
        titleField.setMaxLength(TaskEdits.TITLE_MAX)
            .pos(PAD + 12, (TITLE - FIELD_H) / 2)
            .size(boxW - PAD - 12 - 30, FIELD_H);
        child(titleField);

        notes = new TextArea("Add notes").maxLength(TaskEdits.TEXT_MAX)
            .onDone(this::setText);
        child(notes.size(leftW - 2, notesH - 2));

        addField = new TextFieldWidget().value(new StringValue.Dynamic(() -> "", this::addItem));
        addField.setMaxLength(TaskEdits.ITEM_MAX)
            .hintText("Add a line")
            .size(rightW, FIELD_H);
        child(addField);

        comment = new TextArea("Write a comment").maxLength(TaskEdits.COMMENT_MAX);
        child(comment.size(rightW - 64 - 2, 24));
        placeFields();
    }

    // region The page

    /** The page's window: from under the title bar to the foot. */
    private int pageTop() {
        return TITLE + 1;
    }

    private int pageEnd() {
        return h - FOOT;
    }

    private int maxPage() {
        return Math.max(0, pageBottom - pageEnd());
    }

    /**
     * The fields where the page puts them; one the page has scrolled part way out of the window hides until it is
     * back.
     */
    private void placeFields() {
        fieldsAt = page;
        place(notes, PAD + 1, notesY + 1, notesH - 2);
        place(addField, rightX, addY, FIELD_H);
        place(comment, rightX + 1, commentBoxY + 1, 24);
    }

    private void place(final com.cleanroommc.modularui.widget.Widget<?> field, final int x, final int pageY,
        final int fieldH) {
        final int y = pageY - page;
        field.pos(x, y);
        field.setEnabled(y >= pageTop() && y + fieldH <= pageEnd());
    }

    /** The mouse on the page: the box's y, scrolled. */
    private int pageMouseY() {
        return mouseY() + page;
    }

    /**
     * Clips to a rectangle on the page. The stencil's shape is drawn under the page's own shift, so it takes the page's
     * coordinates.
     */
    private void clip(final int x, final int pageY, final int w, final int ch, final ModularGuiContext context) {
        Stencil.apply(x, pageY, w, ch, context);
    }

    // endregion

    /** Opens a task in a box in the middle of the planner, as big as it has room for. */
    public static void open(final BoardSession session, final String taskId) {
        if (TaskEdits.task(session.graph(), taskId) == null) return;
        final int[] room = Popup.room(session.panel());
        final int w = Math.min(MAX_W, room[2] - 8);
        final TaskPanel panel = new TaskPanel(session, taskId, w, Math.min(380, room[3] - 8));
        final Popup popup = new Popup("gtnhplanner_task", w, panel.h) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.rect(-getArea().x, -getArea().y, PlannerScale.width(), PlannerScale.height(), 0x60000000);
                Hyb.dropShadow(0, 0, getArea().width, getArea().height);
                CardPaint.surface(getArea().width, getArea().height);
            }
        };
        popup.child(panel.pos(0, 0));
        popup.onCloseAction(panel::closing);
        showing = new WeakReference<>(panel);
        Popup.openCentred(session.panel(), popup, 0.5f);
    }

    @Nullable
    private Tasks.Task task() {
        return TaskEdits.task(session.graph(), taskId);
    }

    private Graph graph() {
        return session.graph();
    }

    // region Edits

    private void edit(final Runnable change) {
        session.editTasks(change);
    }

    /** Puts a changed task in its place. */
    private void put(final java.util.function.UnaryOperator<Tasks.Task> change) {
        final Tasks.Task t = task();
        if (t == null) return;
        final Tasks.Task next = change.apply(t);
        if (!next.equals(t)) edit(() -> TaskEdits.put(session.graph(), next));
    }

    private void setTitle(@Nullable final String title) {
        if (title == null || title.isBlank()) return;
        put(t -> TaskEdits.withTitle(t, title));
    }

    private void setText(@Nullable final String text) {
        put(t -> TaskEdits.withText(t, text));
    }

    private void addItem(@Nullable final String text) {
        if (text == null || text.isBlank() || task() == null) return;
        edit(
            () -> TaskEdits.addItem(
                session.graph(),
                UUID.randomUUID()
                    .toString(),
                taskId,
                text,
                null));
        // Empty again for the next line (the box tells its text again as it lets go of the keyboard).
        addField.setText("");
        Sfx.NOTE_STICK.play(0.6f, 1.2f);
    }

    private void putItem(final Tasks.Item item) {
        edit(() -> TaskEdits.putItem(session.graph(), item));
    }

    private void post() {
        final String text = comment.text()
            .strip();
        if (text.isEmpty() || task() == null) {
            Sfx.DENY.play();
            return;
        }
        edit(
            () -> TaskEdits.addComment(
                session.graph(),
                UUID.randomUUID()
                    .toString(),
                taskId,
                TaskLook.me(),
                TaskLook.myName(),
                TaskLook.now(),
                text));
        comment.text("");
        Sfx.NOTE_STICK.play();
    }

    private void unlink(final Tasks.Task t, final Tasks.Link link) {
        final List<Tasks.Link> links = new ArrayList<>(t.links());
        links.remove(link);
        put(x -> TaskEdits.withLinks(x, links));
        Sfx.WIRE_CUT.play();
    }

    /** Shows a card on its plan: this box closes and the plan opens with the card centred, selected. */
    private void show(final Tasks.Link link) {
        close();
        session.openLink(link);
    }

    private void close() {
        if (getPanel() != null) getPanel().closeIfOpen();
        Sfx.CLOSE.play();
    }

    /** What is written but not yet put in the plan goes in as the box closes, however it closes. */
    private void closing() {
        closed = true;
        final Tasks.Task t = task();
        if (t == null) return;
        final String title = titleField.getText();
        if (title != null && !title.isBlank()
            && !title.strip()
                .equals(t.title()))
            setTitle(title);
        final String text = notes.text()
            .isBlank() ? null
                : notes.text()
                    .strip();
        if (!java.util.Objects.equals(text, t.text())) setText(text);
    }

    /** Set once the box has closed. */
    private boolean closed;

    /**
     * The screen hears every release: a checklist line let go off the box never reaches it, and ends here all the
     * same.
     */
    public static void screenReleased(final int mouseButton) {
        final TaskPanel p = showing.get();
        if (mouseButton == 0 && p != null && !p.closed && p.itemPressed != null) p.releaseItem();
    }

    // endregion

    @Override
    public void onUpdate() {
        super.onUpdate();
        final Tasks.Task t = task();
        // Undone away, or deleted: nothing left to show.
        if (t == null) {
            if (getPanel() != null) getPanel().closeIfOpen();
            return;
        }
        if (!notes.isFocused()) notes.text(t.text() == null ? "" : t.text());
    }

    // region Drawing

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int mouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final Tasks.Task t = task();
        if (t == null) return;
        liftItem();
        final Hit hover = itemCarrying ? null : hitAt(lastHits);
        final float z = context.getCurrentDrawingZ();
        // The title bar: the task's colour, its title (a field), the close key.
        TaskLook.dot(PAD, (TITLE - 7) / 2f, 7, TaskLook.colour(t.status()));
        Hyb.rect(PAD + 11, (TITLE - FIELD_H) / 2 - 1, boxW - PAD - 11 - 29, FIELD_H + 2, CardPaint.EDGE);
        final int cx = boxW - 20, cy = (TITLE - 14) / 2;
        final boolean closeHot = is(hover, Kind.CLOSE);
        if (closeHot) Hyb.rect(cx, cy, 14, 14, CONTROL_HOVER);
        cross(cx + 7f, cy + 7f, closeHot ? Hyb.INK : Hyb.MUTED);
        hits.add(new Hit(Kind.CLOSE, cx, cy, cx + 14, cy + 14, null));
        Hyb.rect(1, TITLE, boxW - 2, 1, CardPaint.HAIR);

        // The page between the title bar and the foot, scrolled when it is taller than its window.
        page = Math.max(0, Math.min(maxPage(), page));
        if (page != fieldsAt) {
            placeFields();
            scheduleResize();
        }
        final int pageHits = hits.size();
        Stencil.apply(0, pageTop(), boxW, pageEnd() - pageTop(), context);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, -page, 0);
        drawStatus(t, hover);
        drawPeople(t, hover);
        drawNotes();
        drawCards(t, hover, z, context);
        drawChecklist(t, hover, context);
        drawComments(t, hover, context);
        GL11.glPopMatrix();
        Stencil.remove();
        // What was hit on the page, where it shows in its window.
        for (int i = hits.size() - 1; i >= pageHits; i--) {
            final Hit p = hits.get(i);
            final int y0 = Math.max(pageTop(), p.y0() - page), y1 = Math.min(pageEnd(), p.y1() - page);
            if (y1 <= y0) hits.remove(i);
            else hits.set(i, new Hit(p.kind(), p.x0(), y0, p.x1(), y1, p.data()));
        }
        if (maxPage() > 0) {
            // A thin bar down the right edge: how far down the page the window is.
            final int track = pageEnd() - pageTop() - 4, bar = Math.max(12, track * track / (track + maxPage()));
            Hyb.rect(boxW - 4, pageTop() + 2 + (track - bar) * page / maxPage(), 2, bar, 0xFF4A4C54);
        }
        drawFoot(t, hover);
        lastHits.clear();
        lastHits.addAll(hits);
    }

    private static boolean is(@Nullable final Hit hover, final Kind kind) {
        return hover != null && hover.kind() == kind;
    }

    private static boolean is(@Nullable final Hit hover, final Kind kind, final Object data) {
        return is(hover, kind) && data.equals(hover.data());
    }

    /** The four columns as chips: the task's own filled in its colour. */
    private void drawStatus(final Tasks.Task t, @Nullable final Hit hover) {
        int x = PAD;
        for (final Tasks.Status s : Tasks.Status.values()) {
            final String name = TaskLook.name(s);
            final int w = Hyb.width(name) + 20, colour = TaskLook.colour(s);
            final boolean picked = t.status() == s, hot = is(hover, Kind.STATUS, s);
            Hyb.rect(x, statusY, w, CHIP_H, picked ? colour : hot ? 0xFF5A5D66 : 0xFF3A3C42);
            Hyb.rect(
                x + 1,
                statusY + 1,
                w - 2,
                CHIP_H - 2,
                picked ? Hyb.mix(colour, CardPaint.SURFACE, 0.2f) : hot ? CONTROL_HOVER : CONTROL);
            TaskLook.dot(x + 6, statusY + 5, 6, picked || hot ? colour : Hyb.mix(colour, CONTROL, 0.6f));
            Hyb.text(name, x + 15, statusY + 4, picked || hot ? Hyb.INK : Hyb.MUTED);
            hits.add(new Hit(Kind.STATUS, x, statusY, x + w, statusY + CHIP_H, s));
            x += w + 4;
        }
    }

    /**
     * Who is on it, and the key to choose who (a shared plan's owner and members), to put yourself on it or take
     * yourself off (signed in, a plan on this computer), or to sign in.
     */
    private void drawPeople(final Tasks.Task t, @Nullable final Hit hover) {
        int right = boxW - PAD;
        final String me = Account.userId();
        final boolean shared = !TaskLook.people(graph())
            .isEmpty();
        if (me == null) {
            final String s = "Sign in to assign";
            right -= Hyb.width(s);
            Hyb.text(s, right, peopleY + 4, is(hover, Kind.SIGN_IN) ? 0xFFFFFFFF : CYAN);
            hits.add(new Hit(Kind.SIGN_IN, right, peopleY, right + Hyb.width(s), peopleY + CHIP_H, null));
        } else {
            final String s = shared ? "Assign"
                : t.assignees()
                    .contains(me) ? "Unassign me" : "Assign me";
            final int kw = Hyb.width(s) + 12;
            right -= kw;
            Hyb.bevel(
                right,
                peopleY,
                kw,
                KEY_H,
                is(hover, Kind.ASSIGN) ? Hyb.KEY_HOVER : Hyb.KEY,
                Hyb.KEY_HI,
                Hyb.KEY_LO,
                0,
                1);
            Hyb.text(s, right + 6, peopleY + 4, Hyb.INK);
            hits.add(new Hit(Kind.ASSIGN, right, peopleY, right + kw, peopleY + KEY_H, shared ? Boolean.TRUE : null));
        }
        if (t.assignees()
            .isEmpty()) return;
        int w = 0;
        for (final String id : t.assignees())
            w += TaskLook.faceWidth(TaskLook.initials(TaskLook.nameOf(graph(), id))) - 2;
        TaskLook.faces(graph(), t.assignees(), right - 6 - w, peopleY + 2);
    }

    /** A shared plan's owner and members, under the Assign key: a pick puts them on the task or takes them off. */
    private void pickPeople(final Tasks.Task t, final Hit key) {
        final String me = Account.userId();
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final TaskLook.Person p : TaskLook.people(graph())) {
            final boolean on = t.assignees()
                .contains(p.id());
            rows.add(
                new PickList.Entry(
                    null,
                    p.id()
                        .equals(me) ? p.name() + " (you)" : p.name(),
                    on ? "On it" : "",
                    Hyb.INK,
                    on,
                    () -> {
                        final Tasks.Task now = task();
                        if (now == null) return;
                        final List<String> next = new ArrayList<>(now.assignees());
                        final boolean add = !next.remove(p.id());
                        if (add) next.add(p.id());
                        put(x -> TaskEdits.withAssignees(x, next));
                        (add ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
                    }));
        }
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_task_people", "Who is on it", rows, rows.size() > 8, 150),
            getArea().x + key.x0(),
            getArea().y + key.y1() + 2);
    }

    private void drawNotes() {
        Hyb.text("Notes", PAD, notesY - LABEL + 1, Hyb.MUTED);
        Hyb.rect(PAD, notesY, leftW, notesH, CardPaint.EDGE);
        Hyb.rect(PAD + 1, notesY + 1, leftW - 2, notesH - 2, WELL);
    }

    /** The cards the task is about, on whatever plans: each a row to click to see it on its plan. */
    private void drawCards(final Tasks.Task t, @Nullable final Hit hover, final float z,
        final ModularGuiContext context) {
        final List<TaskActions.Card> cards = TaskActions.cards(session, graph(), t);
        final int labelY = cardsY - LABEL + 1;
        Hyb.text("Cards", PAD, labelY, Hyb.MUTED);
        Hyb.text(Integer.toString(cards.size()), PAD + Hyb.width("Cards") + 4, labelY, 0xFF6A6C74);
        final int top = cardsY, bottom = cardsBottom, tw = leftW - 22 - 14;
        if (cards.isEmpty()) {
            final List<String> hint = List.of("Right-click cards on a plan", "and Make a task.");
            for (int i = 0; i < hint.size(); i++) Hyb.text(hint.get(i), PAD + 2, top + 4 + i * 10, 0xFF6A6C74);
            return;
        }
        clip(PAD, top, leftW, bottom - top, context);
        int y = top - cardsScroll;
        for (final TaskActions.Card c : cards) {
            final NoteText words = NoteText.of(
                c.where()
                    .isEmpty() ? c.line() : c.line() + " on " + c.where(),
                tw,
                Hyb::width);
            final int rh = Math.max(
                18,
                words.lines()
                    .size() * 10 + 8);
            final boolean hot = y + rh > top && y < bottom
                && (is(hover, Kind.CARD, c.link()) || is(hover, Kind.UNLINK, c.link()));
            if (hot) Hyb.rect(PAD, y, leftW, rh, ROW_HOVER);
            if (c.icon() != null) Hyb.item(c.icon(), PAD + 2, y + (rh - 16) / 2f, 16, z);
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
            for (int i = 0; i < words.lines()
                .size(); i++)
                Hyb.text(
                    words.shown(i),
                    PAD + 22,
                    y + (rh - words.lines()
                        .size() * 10) / 2f + 1 + i * 10,
                    c.gone() ? Hyb.MUTED : Hyb.INK);
            final int y0 = Math.max(top, y), y1 = Math.min(bottom, y + rh);
            if (y1 > y0) hits.add(new Hit(Kind.CARD, PAD, y0, PAD + leftW, y1, c.link()));
            // Hovered, the key that unlinks it.
            if (hot) {
                final int ux = PAD + leftW - 12, uy = y + (rh - 10) / 2;
                final boolean onX = is(hover, Kind.UNLINK, c.link());
                cross(ux + 5f, uy + 5f, onX ? Hyb.RED_INK : Hyb.MUTED);
                if (y >= top && y + rh <= bottom)
                    hits.add(new Hit(Kind.UNLINK, ux - 2, uy - 2, ux + 12, uy + 12, c.link()));
            }
            y += rh;
        }
        Stencil.remove();
        cardsH = y + cardsScroll - top;
        cardsScroll = Math.max(0, Math.min(cardsScroll, cardsH - (bottom - top)));
    }

    /** The checklist: a box to tick, the words (click to write, drag to move), a card it points at, delete. */
    private void drawChecklist(final Tasks.Task t, @Nullable final Hit hover, final ModularGuiContext context) {
        final List<Tasks.Item> items = TaskEdits.items(graph(), taskId);
        final long ticked = items.stream()
            .filter(Tasks.Item::done)
            .count();
        final int labelY = itemsY - LABEL + 1;
        Hyb.text("Checklist", rightX, labelY, Hyb.MUTED);
        if (!items.isEmpty()) {
            final String n = ticked + "/" + items.size();
            final int nx = rightX + Hyb.width("Checklist") + 4;
            Hyb.text(n, nx, labelY, ticked == items.size() ? Hyb.OK_INK : 0xFF6A6C74);
            final int bx = nx + Hyb.width(n) + 6, bw = rightX + rightW - bx;
            if (bw > 10) {
                Hyb.rect(bx, labelY + 3, bw, 3, 0xFF2C2E34);
                Hyb.rect(bx, labelY + 3, Math.round(bw * ticked / (float) items.size()), 3, Hyb.OK_INK);
            }
        }
        final int top = itemsY, bottom = itemsBottom, tw = rightW - 14 - 30;
        if (items.isEmpty()) {
            Hyb.text("Nothing on it yet.", rightX + 2, top + 4, 0xFF6A6C74);
            return;
        }
        clip(rightX, top, rightW, bottom - top, context);
        int y = top - itemsScroll;
        int dropY = -1, index = 0;
        final boolean anyCards = !t.links()
            .isEmpty();
        for (final Tasks.Item item : items) {
            final boolean carried = itemCarrying && item.id()
                .equals(itemPressed);
            final NoteText words = NoteText.of(item.text(), tw, Hyb::width);
            final int rh = Math.max(
                14,
                words.lines()
                    .size() * 10 + 4);
            if (itemCarrying && index == itemDrop) dropY = y;
            if (!carried) index++;
            final boolean rowHot = !itemCarrying && hover != null
                && item.id()
                    .equals(hover.data());
            if (rowHot) Hyb.rect(rightX, y, rightW, rh, ROW_HOVER);
            if (carried) Hyb.rect(rightX, y, rightW, rh, 0x3022D3EE);
            // The box.
            final int bx = rightX + 2, by = y + 3;
            final boolean boxHot = is(hover, Kind.BOX, item.id());
            Hyb.rect(bx, by, 9, 9, item.done() ? Hyb.OK_INK : boxHot ? 0xFF8F939C : 0xFF5A5D66);
            if (!item.done()) Hyb.rect(bx + 1, by + 1, 7, 7, WELL);
            else {
                // A tick in the box.
                Hyb.rect(bx + 2, by + 4, 1, 2, 0xFF0B1A12);
                Hyb.rect(bx + 3, by + 5, 1, 2, 0xFF0B1A12);
                Hyb.rect(bx + 4, by + 4, 1, 2, 0xFF0B1A12);
                Hyb.rect(bx + 5, by + 3, 1, 2, 0xFF0B1A12);
                Hyb.rect(bx + 6, by + 2, 1, 2, 0xFF0B1A12);
            }
            for (int i = 0; i < words.lines()
                .size(); i++) Hyb.text(words.shown(i), rightX + 15, y + 3 + i * 10, item.done() ? 0xFF8A8C94 : Hyb.INK);
            final int y0 = Math.max(top, y), y1 = Math.min(bottom, y + rh);
            if (y1 > y0) {
                hits.add(new Hit(Kind.ITEM, rightX, y0, rightX + rightW, y1, item.id()));
                hits.add(new Hit(Kind.BOX, rightX, y0, rightX + 13, y1, item.id()));
            }
            final boolean inView = y >= top && y + rh <= bottom;
            // A line pointing at a card shows it; hovered, its keys.
            final int kx = rightX + rightW - 24, ky = y + 2;
            if (item.link() != null || rowHot && anyCards) {
                final boolean on = is(hover, Kind.ITEM_LINK, item.id());
                Icons.card(kx, ky + 1, item.link() != null ? on ? 0xFFFFFFFF : CYAN : on ? Hyb.INK : Hyb.MUTED);
                if (inView) hits.add(new Hit(Kind.ITEM_LINK, kx - 2, ky - 1, kx + 11, ky + 11, item.id()));
            }
            if (rowHot) {
                final boolean on = is(hover, Kind.ITEM_DELETE, item.id());
                cross(kx + 18f, ky + 5.5f, on ? Hyb.RED_INK : Hyb.MUTED);
                if (inView) hits.add(new Hit(Kind.ITEM_DELETE, kx + 12, ky - 1, kx + 24, ky + 11, item.id()));
            }
            y += rh;
        }
        if (itemCarrying && dropY < 0) dropY = y;
        if (itemCarrying) Hyb.rect(rightX, dropY - 1, rightW, 2, CYAN);
        Stencil.remove();
        // The line in hand follows the mouse, lifted.
        final Tasks.Item carried = itemCarrying ? TaskEdits.item(graph(), itemPressed) : null;
        if (carried != null) {
            final NoteText words = NoteText.of(carried.text(), tw, Hyb::width);
            final int rh = Math.max(
                14,
                words.lines()
                    .size() * 10 + 4),
                cy = Math.max(top, Math.min(bottom, pageMouseY())) - rh / 2;
            Hyb.rect(rightX + 3, cy + 4, rightW, rh, 0x50000000);
            Hyb.rect(rightX, cy, rightW, rh, CardPaint.EDGE);
            Hyb.rect(rightX + 1, cy + 1, rightW - 2, rh - 2, ROW_HOVER);
            for (int i = 0; i < words.lines()
                .size(); i++) Hyb.text(words.shown(i), rightX + 15, cy + 3 + i * 10, Hyb.INK);
        }
        itemsH = y + itemsScroll - top;
        itemsScroll = Math.max(0, Math.min(itemsScroll, itemsH - (bottom - top)));
        if (itemCarrying) findItemDrop(items);
    }

    /** Where a carried line would land: before the first other line whose middle is below the mouse. */
    private void findItemDrop(final List<Tasks.Item> items) {
        final int tw = rightW - 14 - 30, my = pageMouseY();
        int y = itemsY - itemsScroll, index = 0, drop = -1;
        for (final Tasks.Item item : items) {
            final int rh = Math.max(
                14,
                NoteText.of(item.text(), tw, Hyb::width)
                    .lines()
                    .size() * 10 + 4);
            if (!item.id()
                .equals(itemPressed)) {
                if (drop < 0 && my < y + rh / 2) drop = index;
                index++;
            }
            y += rh;
        }
        itemDrop = drop < 0 ? index : drop;
    }

    /** The comments, oldest first: a face, who and when, then the words; your own has Delete while hovered. */
    private void drawComments(final Tasks.Task t, @Nullable final Hit hover, final ModularGuiContext context) {
        final List<Tasks.Comment> comments = TaskEdits.comments(graph(), taskId);
        final int labelY = commentsY - LABEL + 1;
        Hyb.text("Comments", rightX, labelY, Hyb.MUTED);
        if (!comments.isEmpty())
            Hyb.text(Integer.toString(comments.size()), rightX + Hyb.width("Comments") + 4, labelY, 0xFF6A6C74);
        final int top = commentsY, bottom = commentsBottom, tw = rightW - 6;
        if (comments.isEmpty()) Hyb.text("No comments yet.", rightX + 2, top + 4, 0xFF6A6C74);
        else {
            clip(rightX, top, rightW, bottom - top, context);
            int y = top - commentsScroll;
            for (final Tasks.Comment c : comments) {
                final NoteText words = NoteText.of(c.text(), tw, Hyb::width);
                final int rh = 14 + words.lines()
                    .size() * 10 + 6;
                // The whole comment is a row, so hovering it shows its Delete.
                final int y0 = Math.max(top, y), y1 = Math.min(bottom, y + rh);
                if (y1 > y0) hits.add(new Hit(Kind.COMMENT, rightX, y0, rightX + rightW, y1, c.id()));
                final boolean rowHot = hover != null && c.id()
                    .equals(hover.data());
                final int fw = TaskLook.face(graph(), c.by(), rightX + 1, y);
                final String who = c.byName()
                    .isEmpty() ? "Someone" : c.byName();
                Hyb.text(who, rightX + fw + 5, y + 2, Hyb.INK);
                Hyb.text(TaskLook.ago(c.at()), rightX + fw + 5 + Hyb.width(who) + 5, y + 2, 0xFF6A6C74);
                if (TaskLook.mine(c.by()) && rowHot) {
                    final String del = "Delete";
                    final int dx = rightX + rightW - Hyb.width(del) - 2;
                    final boolean on = is(hover, Kind.COMMENT_DELETE, c.id());
                    Hyb.text(del, dx, y + 2, on ? 0xFFFF8080 : Hyb.RED_INK);
                    if (y >= top)
                        hits.add(new Hit(Kind.COMMENT_DELETE, dx - 2, y, dx + Hyb.width(del) + 2, y + 12, c.id()));
                }
                for (int i = 0; i < words.lines()
                    .size(); i++) Hyb.text(words.shown(i), rightX + 2, y + 15 + i * 10, 0xFFD4D5DA);
                y += rh;
            }
            Stencil.remove();
            commentsH = y + commentsScroll - top;
            commentsScroll = Math.max(0, Math.min(commentsScroll, commentsH - (bottom - top)));
        }
        // The box to write in, and its key.
        Hyb.rect(rightX, commentBoxY, rightW - 64, 26, CardPaint.EDGE);
        Hyb.rect(rightX + 1, commentBoxY + 1, rightW - 66, 24, WELL);
        final int kx = rightX + rightW - 58, ky = commentBoxY + 5;
        final boolean hot = is(hover, Kind.POST);
        Hyb.rect(kx, ky, 58, KEY_H, hot ? 0xFF38E1F5 : CYAN);
        Hyb.rect(kx, ky, 58, 1, 0x66FFFFFF);
        Hyb.text("Comment", kx + (58 - Hyb.width("Comment")) / 2f, ky + 4, 1f, 0xFF0B1A1E, false);
        hits.add(new Hit(Kind.POST, kx, ky, kx + 58, ky + KEY_H, null));
    }

    /** Who made it and when; the key that deletes it. */
    private void drawFoot(final Tasks.Task t, @Nullable final Hit hover) {
        final int y = h - FOOT;
        Hyb.rect(1, y, boxW - 2, 1, CardPaint.HAIR);
        final String by = t.createdBy() == null ? null : TaskLook.nameOf(graph(), t.createdBy());
        final String when = TaskLook.ago(t.createdAt());
        final String made = TaskLook.mine(t.createdBy()) ? "You made it" + (when.isEmpty() ? "" : " " + when)
            : by != null ? "Made by " + by + (when.isEmpty() ? "" : " " + when) : when.isEmpty() ? "" : "Made " + when;
        Hyb.text(made, PAD, y + 9, 0xFF6A6C74);
        final String del = "Delete task";
        final int kw = Hyb.width(del) + 12, kx = boxW - PAD - kw, ky = y + 5;
        Hyb.bevel(kx, ky, kw, KEY_H, is(hover, Kind.DELETE) ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(del, kx + 6, ky + 4, Hyb.RED_INK);
        hits.add(new Hit(Kind.DELETE, kx, ky, kx + kw, ky + KEY_H, null));
    }

    private static void cross(final float cx, final float cy, final int colour) {
        for (int d = -3; d <= 3; d++) {
            Hyb.rect(cx + d - 0.5f, cy + d - 0.5f, 1, 1, colour);
            Hyb.rect(cx + d - 0.5f, cy - d - 0.5f, 1, 1, colour);
        }
    }

    /** Tips wait until the mouse has rested on their thing a moment. */
    private final Tip.Wait tipWait = new Tip.Wait();

    @Override
    public void drawOverlay(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        super.drawOverlay(context, widgetTheme);
        // A menu or form over the box explains itself.
        if (getScreen().getPanelManager()
            .getTopMostPanel() != getPanel()) return;
        final Hit hover = itemCarrying ? null : hitAt(lastHits);
        if (!tipWait.ready(hover == null ? null : hover.kind() + ":" + hover.data())) return;
        final Tip tip = tip(hover);
        if (tip != null) tip.drawNear(mouseX(), mouseY(), boxW, h);
    }

    @Nullable
    private Tip tip(@Nullable final Hit hit) {
        if (hit == null) return null;
        return switch (hit.kind()) {
            case CLOSE -> Tip.of("Close (Esc)");
            case STATUS -> Tip.of("Move it to " + TaskLook.name((Tasks.Status) hit.data()));
            case ASSIGN -> Tip.of("Put yourself on this task or take yourself off");
            case SIGN_IN -> Tip.of("Sign in to your account");
            case CARD -> Tip.of("Show it on its plan");
            case UNLINK -> Tip.of("Unlink this card");
            case BOX -> Tip.of("Tick it off");
            case ITEM -> Tip.of("A line of the checklist")
                .action(Tip.Input.LEFT, "Change the words")
                .action(Tip.Input.DRAG, "Move it");
            case COMMENT -> null;
            case ITEM_LINK -> Tip.of("Point this line at one of the task's cards");
            case ITEM_DELETE -> Tip.of("Delete this line");
            case COMMENT_DELETE -> Tip.of("Delete your comment");
            case POST -> Tip.of("Add your comment");
            case DELETE -> Tip.of("Delete this task with its checklist and comments");
        };
    }

    // endregion

    // region Input

    @Override
    public Result onMousePressed(final int mouseButton) {
        // A press anywhere in the box but its fields ends the writing (what was written goes in), so the keys are the
        // board's again: undo, redo.
        if (BoardScreen.textFocused(getScreen())) getContext().removeFocus();
        final Hit hit = hitAt(lastHits);
        final Tasks.Task t = task();
        if (hit == null || t == null) return Result.ACCEPT;
        if (mouseButton != 0) return Result.ACCEPT;
        switch (hit.kind()) {
            case CLOSE -> close();
            case STATUS -> {
                final Tasks.Status s = (Tasks.Status) hit.data();
                if (s != t.status()) {
                    edit(
                        () -> TaskEdits.move(
                            session.graph(),
                            taskId,
                            s,
                            TaskEdits.column(session.graph(), s)
                                .size(),
                            TaskLook.now()));
                    Sfx.TICK.play(1f + 0.1f * s.ordinal());
                }
            }
            case ASSIGN -> {
                final String me = Account.userId();
                if (me == null) break;
                if (hit.data() != null) {
                    Hyb.click();
                    pickPeople(t, hit);
                    break;
                }
                final List<String> next = new ArrayList<>(t.assignees());
                final boolean on = !next.remove(me);
                if (on) next.add(me);
                put(x -> TaskEdits.withAssignees(x, next));
                (on ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
            }
            case SIGN_IN -> {
                Hyb.click();
                com.gtnhplanner.ui.library.AccountForms.signIn(session.panel(), () -> {});
            }
            case CARD -> show((Tasks.Link) hit.data());
            case UNLINK -> unlink(t, (Tasks.Link) hit.data());
            case BOX -> {
                final Tasks.Item item = TaskEdits.item(graph(), (String) hit.data());
                if (item != null) {
                    putItem(TaskEdits.withDone(item, !item.done()));
                    (item.done() ? Sfx.TOGGLE_OFF : Sfx.TOGGLE_ON).play();
                }
            }
            case ITEM -> {
                // A checklist line: let go here to write in it, or carry it.
                itemPressed = (String) hit.data();
                itemPressX = getContext().getAbsMouseX();
                itemPressY = getContext().getAbsMouseY();
            }
            case COMMENT -> {}
            case ITEM_LINK -> openItemLink(t, (String) hit.data(), hit);
            case ITEM_DELETE -> {
                edit(() -> TaskEdits.removeItem(session.graph(), (String) hit.data()));
                Sfx.NOTE_CRUMPLE.play();
            }
            case COMMENT_DELETE -> {
                edit(() -> TaskEdits.removeComment(session.graph(), (String) hit.data()));
                Sfx.NOTE_CRUMPLE.play();
            }
            case POST -> post();
            case DELETE -> confirmDelete(hit);
        }
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        liftItem();
    }

    /**
     * A checklist line pressed and moved {@value #DRAG_SLOP} pixels is carried. As the mouse moves, and every frame.
     */
    private void liftItem() {
        if (itemPressed == null || itemCarrying) return;
        final int dx = getContext().getAbsMouseX() - itemPressX, dy = getContext().getAbsMouseY() - itemPressY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) < DRAG_SLOP) return;
        itemCarrying = true;
        Sfx.LIFT.play();
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (mouseButton != 0 || itemPressed == null) return false;
        releaseItem();
        return true;
    }

    /** A checklist line let go: a click writes in it; a carry puts it where it would land. */
    private void releaseItem() {
        final String id = itemPressed;
        itemPressed = null;
        final Tasks.Item item = TaskEdits.item(graph(), id);
        if (item == null) {
            itemCarrying = false;
            return;
        }
        if (!itemCarrying) {
            Popup.open(session.panel(), TextPopup.create("Checklist line", item.text(), text -> {
                if (text == null || text.isBlank()) return;
                final Tasks.Item now = TaskEdits.item(session.graph(), id);
                if (now != null && !text.strip()
                    .equals(now.text())) putItem(TaskEdits.withText(now, text));
            }), getContext().getAbsMouseX(), getContext().getAbsMouseY());
            return;
        }
        itemCarrying = false;
        final List<Tasks.Item> items = TaskEdits.items(graph(), taskId);
        final int was = items.indexOf(item), to = itemDrop;
        if (was != to) edit(() -> TaskEdits.moveItem(session.graph(), id, to));
        Sfx.DROP.play();
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int step = (direction == UpOrDown.UP ? -1 : 1) * 20, x = mouseX(), y = pageMouseY();
        // A list under the mouse scrolls while it has further to go that way; else the page does.
        if (x >= PAD && x < PAD + leftW
            && y >= cardsY
            && y < cardsBottom
            && moves(cardsScroll, cardsH - (cardsBottom - cardsY), step)) cardsScroll = Math.max(0, cardsScroll + step);
        else if (x >= rightX && y >= itemsY
            && y < itemsBottom
            && moves(itemsScroll, itemsH - (itemsBottom - itemsY), step)) itemsScroll = Math.max(0, itemsScroll + step);
        else if (x >= rightX && y >= commentsY
            && y < commentsBottom
            && moves(commentsScroll, commentsH - (commentsBottom - commentsY), step))
            commentsScroll = Math.max(0, commentsScroll + step);
        else page = Math.max(0, Math.min(maxPage(), page + step));
        return true;
    }

    /** Whether a list scrolled {@code at} of {@code max} goes anywhere by {@code step}. */
    private static boolean moves(final int at, final int max, final int step) {
        return step < 0 ? at > 0 : at < max;
    }

    /** Which of the task's cards a checklist line points at: one of them, none, or show the one it has. */
    private void openItemLink(final Tasks.Task t, final String itemId, final Hit under) {
        final Tasks.Item item = TaskEdits.item(graph(), itemId);
        if (item == null) return;
        Hyb.click();
        final List<PickList.Entry> rows = new ArrayList<>();
        if (item.link() != null) rows.add(PickList.Entry.of("Show it on its plan", () -> show(item.link())));
        for (final TaskActions.Card c : TaskActions.cards(session, graph(), t)) {
            if (c.gone()) continue;
            final boolean current = c.link()
                .equals(item.link());
            rows.add(
                new PickList.Entry(
                    c.icon(),
                    c.line(),
                    "",
                    Hyb.INK,
                    current,
                    () -> putItem(TaskEdits.withLink(item, c.link()))));
        }
        if (item.link() != null)
            rows.add(PickList.Entry.of("Point at no card", () -> putItem(TaskEdits.withLink(item, null))));
        if (rows.isEmpty()) return;
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_item_card", null, rows, false, 160),
            getArea().x + under.x0(),
            getArea().y + under.y1() + 2);
    }

    /** Asks before deleting the task: its checklist and comments go with it. */
    private void confirmDelete(final Hit under) {
        Hyb.click();
        final List<PickList.Entry> rows = List.of(new PickList.Entry(null, "Delete it", "", Hyb.RED_INK, false, () -> {
            edit(() -> TaskEdits.remove(session.graph(), taskId));
            Sfx.NOTE_CRUMPLE.play();
        }), PickList.Entry.of("Keep it", () -> {}));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_delete", "Delete this task for good?", rows, false, 150),
            getArea().x + under.x0(),
            getArea().y + under.y0() - 64);
    }

    // endregion

    // region For the dev harness

    /** The box open now as data: its task, and the GUI rectangle of each part in the game's pixels; null if none. */
    @Nullable
    public static Map<String, Object> describe() {
        final TaskPanel p = showing.get();
        if (p == null || p.closed) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", p.taskId);
        final Map<String, Object> parts = new LinkedHashMap<>();
        for (final Hit h : p.lastHits) {
            if (h.x0() < 0) continue;
            final String key = h.kind()
                .name()
                .toLowerCase(java.util.Locale.ROOT) + (h.data() == null ? "" : ":" + h.data());
            parts.putIfAbsent(key, p.rect(h.x0(), h.y0(), h.x1() - h.x0(), h.y1() - h.y0()));
        }
        parts.put("title", p.rect(p.titleField));
        parts.put("notes", p.rect(p.notes));
        parts.put("add", p.rect(p.addField));
        parts.put("comment", p.rect(p.comment));
        out.put("parts", parts);
        return out;
    }

    private Map<String, Object> rect(final com.cleanroommc.modularui.widget.Widget<?> w) {
        return rect(w.getArea().rx, w.getArea().ry, w.getArea().width, w.getArea().height);
    }

    private Map<String, Object> rect(final int lx, final int ly, final int lw, final int lh) {
        final int x = PlannerScale.toGame(getArea().x + lx), y = PlannerScale.toGame(getArea().y + ly);
        final int w = PlannerScale.toGame(getArea().x + lx + lw) - x,
            hh = PlannerScale.toGame(getArea().y + ly + lh) - y;
        return Map.of("x", x, "y", y, "w", w, "h", hh, "cx", x + w / 2, "cy", y + hh / 2);
    }

    // endregion

    // region Helpers

    @Nullable
    private Hit hitAt(final List<Hit> from) {
        if (!isHovering()) return null;
        final int x = mouseX(), y = mouseY();
        for (int i = from.size() - 1; i >= 0; i--) if (from.get(i)
            .contains(x, y)) return from.get(i);
        return null;
    }

    // endregion
}
