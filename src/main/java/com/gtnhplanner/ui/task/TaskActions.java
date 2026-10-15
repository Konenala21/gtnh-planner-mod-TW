package com.gtnhplanner.ui.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.cloud.map.PlanIds;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.sound.Sfx;

/**
 * What the task boards do with a plan's cards: a card as a task names it (its machine, tier and how many the solve
 * builds), and a task made from cards selected on a plan, into one of the player's task boards (a plan of its own kind:
 * its tasks link to cards of other plans).
 */
public final class TaskActions {

    private TaskActions() {}

    /**
     * A card as a task shows it: its machine, the tier of a GregTech card ("" for none) and how many to build, all its
     * recipes' together on a shared machine, rounded up. {@code where} is the plan it is on; {@code gone} marks a link
     * whose plan or card is no longer there.
     */
    public record Card(Tasks.Link link, String name, String where, String tier, int count, @Nullable ItemStack icon,
        boolean gone) {

        /** "4× Electric Blast Furnace (HV)": a checklist line's words. */
        public String line() {
            return (count > 0 ? count + "× " : "") + name + (tier.isEmpty() ? "" : " (" + tier + ")");
        }
    }

    /** A card of the open plan by its node, or null when the plan has no such card (or it is not solved yet). */
    @Nullable
    public static Card card(final BoardSession session, final UUID nodeId) {
        final UUID host = session.hostOf(nodeId);
        final CardModel m = session.model(host);
        if (m == null) return null;
        double builds = 0;
        for (final UUID s : session.sectionsOf(host)) {
            final CardModel section = session.model(s);
            if (section != null) builds += CropCards.builds(section.node, section.machines);
        }
        final String tier = m.gregtech && !m.steam() && m.tier != null ? m.tier : "";
        return new Card(
            session.linkOf(host),
            m.machineName,
            session.graph()
                .getName(),
            tier,
            (int) Math.ceil(builds - 1e-9),
            m.machineStack,
            false);
    }

    /** The cards a task is about, in its order, each once; a link whose plan or card is gone is listed as gone. */
    public static List<Card> cards(final BoardSession session, final Graph holder, final Tasks.Task task) {
        final List<Card> out = new ArrayList<>();
        final Set<Tasks.Link> seen = new java.util.HashSet<>();
        for (final Tasks.Link link : task.links()) {
            if (!seen.add(link)) continue;
            final Graph on = session.planOf(link, holder);
            final Node node = on == null ? null : on.nodes.get(PlanIds.uuid(link.node()));
            if (node == null) {
                out.add(
                    new Card(
                        link,
                        on == null ? "A card on a plan that is gone" : "A card that is gone",
                        on == null ? "" : on.getName(),
                        "",
                        0,
                        null,
                        true));
                continue;
            }
            // A card's machine is named by its item key when it has no better name.
            net.minecraft.item.ItemStack icon = null;
            String name = node.machineName;
            if (name != null && name.startsWith("item:")) {
                icon = com.gtnhplanner.ui.Resources.item(name);
                if (icon != null) name = icon.getDisplayName();
            }
            out.add(new Card(link, name, on.getName(), "", 0, icon, false));
        }
        return out;
    }

    /** The cards selected on the board, by their host node. */
    public static List<UUID> selectedCards(final BoardSession session) {
        return session.cardsIn(session.selection());
    }

    /**
     * "Make a task" on cards: a menu of the player's task boards and a new one, then the task goes into the board
     * chosen.
     */
    public static void makeMenu(final BoardSession session, final ModularPanel panel, final Collection<UUID> ids,
        final int sx, final int sy) {
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final Graph board : session.taskBoards())
            rows.add(PickList.Entry.of(board.getName(), () -> fromCards(session, ids, board)));
        if (!rows.isEmpty()) rows.add(PickList.Entry.rule());
        rows.add(PickList.Entry.of("New task board", () -> fromCards(session, ids, session.addTaskBoard(false))));
        Popup.open(panel, PickList.popup("gtnhplanner_task_boards", null, rows, false, 150), sx, sy);
    }

    /**
     * Makes a task in To do on {@code board} from cards of the open plan: titled from their machines, a checklist line
     * for each card with how many to build, linked to all of them. Returns its id, or null when none of them is a card.
     */
    @Nullable
    public static String fromCards(final BoardSession session, final Collection<UUID> ids, final Graph board) {
        final List<Card> cards = new ArrayList<>();
        for (final UUID id : session.cardsIn(ids)) {
            final Card c = card(session, id);
            if (c != null) cards.add(c);
        }
        if (cards.isEmpty()) {
            Sfx.DENY.play();
            return null;
        }
        final String id = UUID.randomUUID()
            .toString();
        final String title = title(cards);
        final List<Tasks.Link> links = new ArrayList<>();
        for (final Card c : cards) links.add(c.link());
        PlanAPI.recordEdit(board, () -> {
            TaskEdits.add(board, id, title, Tasks.Status.TODO, links, TaskLook.me(), TaskLook.now());
            for (final Card c : cards) TaskEdits.addItem(
                board,
                UUID.randomUUID()
                    .toString(),
                id,
                c.line(),
                c.link());
        });
        board.touchLayout();
        PlanAPI.save();
        Sfx.NOTE_STICK.play();
        session.flash(
            Severity.INFO,
            "Made a task in '" + board.getName()
                + "' from "
                + cards.size()
                + (cards.size() == 1 ? " card." : " cards."));
        return id;
    }

    /** "Build the Electric Blast Furnace", "Build A and B", "Build A and 3 more": a title from cards' machines. */
    static String title(final List<Card> cards) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Card c : cards) names.add(c.name());
        final List<String> n = new ArrayList<>(names);
        final String title = switch (n.size()) {
            case 1 -> "Build " + n.get(0);
            case 2 -> "Build " + n.get(0) + " and " + n.get(1);
            default -> "Build " + n.get(0) + " and " + (n.size() - 1) + " more";
        };
        return TaskEdits.clip(title, TaskEdits.TITLE_MAX);
    }
}
