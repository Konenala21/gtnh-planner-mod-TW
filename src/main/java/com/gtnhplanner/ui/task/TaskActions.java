package com.gtnhplanner.ui.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.cloud.map.PlanIds;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.sound.Sfx;

/**
 * What the task board does with the plan's cards: a card as a task names it (its machine, tier and how many the solve
 * builds), and a task made from cards selected on the board.
 */
public final class TaskActions {

    private TaskActions() {}

    /**
     * A card as a task shows it: its machine, the tier of a GregTech card ("" for none) and how many to build, all its
     * recipes' together on a shared machine, rounded up.
     */
    public record Card(UUID id, String name, String tier, int count, @Nullable ItemStack icon) {

        /** "4× Electric Blast Furnace (HV)": a checklist line's words. */
        public String line() {
            return (count > 0 ? count + "× " : "") + name + (tier.isEmpty() ? "" : " (" + tier + ")");
        }
    }

    /** A card by its node, or null when the plan has no such card (or it is not solved yet). */
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
        return new Card(host, m.machineName, tier, (int) Math.ceil(builds - 1e-9), m.machineStack);
    }

    /** The cards a task is about, in its order, each once; those gone from the plan are left out. */
    public static List<Card> cards(final BoardSession session, final Tasks.Task task) {
        final List<Card> out = new ArrayList<>();
        final Set<UUID> seen = new java.util.HashSet<>();
        for (final String id : task.nodeIds()) {
            final UUID node = PlanIds.uuid(id);
            if (!session.graph().nodes.containsKey(node)) continue;
            final Card c = card(session, node);
            if (c != null && seen.add(c.id())) out.add(c);
        }
        return out;
    }

    /** The cards selected on the board, by their host node. */
    public static List<UUID> selectedCards(final BoardSession session) {
        return session.cardsIn(session.selection());
    }

    /**
     * Makes a task in To do from cards: titled from their machines, a checklist line for each card with how many to
     * build, linked to all of them. Returns its id, or null when none of them is a card.
     */
    @Nullable
    public static String fromCards(final BoardSession session, final Collection<UUID> ids) {
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
        final List<String> nodeIds = new ArrayList<>();
        for (final Card c : cards) nodeIds.add(
            c.id()
                .toString());
        session.editTasks(() -> {
            final Graph g = session.graph();
            TaskEdits.add(g, id, title, Tasks.Status.TODO, nodeIds, TaskLook.me(), TaskLook.now());
            for (final Card c : cards) TaskEdits.addItem(
                g,
                UUID.randomUUID()
                    .toString(),
                id,
                c.line(),
                c.id()
                    .toString());
        });
        Sfx.NOTE_STICK.play();
        session.flash(
            Severity.INFO,
            "Made a task in To do from " + cards.size() + (cards.size() == 1 ? " card." : " cards."));
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
