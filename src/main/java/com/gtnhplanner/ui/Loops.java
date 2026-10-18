package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;

/**
 * Old Build's dead loops and clog locks on the board ({@code SolutionView.upToLoops}, docs/design/up-to.md; the
 * website's {@code clean/loops.ts}): which cards are in a ring, which wires join them along its rows, and what its
 * notice says. Pure.
 */
public final class Loops {

    private Loops() {}

    /** A ring's look: a dead loop in STARVED's amber, a clog lock in CLOGGED's purple. */
    public enum Tone {

        DEAD(0xFFE0A63A),
        CLOG(0xFFB49CF0);

        public final int colour;

        Tone(final int colour) {
            this.colour = colour;
        }

        public static Tone of(final UpToLoop loop) {
            return loop.kind() == UpToLoop.Kind.DEAD_LOOP ? DEAD : CLOG;
        }
    }

    /** The ring each card is in (the first, when it is in several), as its tone, by card. */
    public static Map<UUID, Tone> cardTones(final List<UpToLoop> loops, final Function<UUID, UUID> cardOf) {
        final Map<UUID, Tone> out = new HashMap<>();
        for (final UpToLoop loop : loops)
            for (final UUID node : loop.nodes()) out.putIfAbsent(cardOf.apply(node), Tone.of(loop));
        return out;
    }

    /** A ring's cards, each once, in its members' order: a shared machine's recipes are its one card. */
    public static List<UUID> cards(final UpToLoop loop, final Function<UUID, UUID> cardOf) {
        final Set<UUID> out = new LinkedHashSet<>();
        for (final UUID node : loop.nodes()) out.add(cardOf.apply(node));
        return new ArrayList<>(out);
    }

    /**
     * The wires that join a ring's cards along its rows: both ends on the ring's cards, and one end on a row the ring
     * names (its members' blaming ports: a dead loop's inputs, a clog lock's outputs). By wire id, the ring's tone; a
     * wire on two rings takes the first's.
     */
    public static Map<UUID, Tone> wires(final Iterable<Edge> edges, final List<UpToLoop> loops,
        final Function<UUID, UUID> cardOf) {
        final Map<UUID, Tone> out = new HashMap<>();
        for (final UpToLoop loop : loops) {
            final Set<UUID> cards = new HashSet<>(cards(loop, cardOf));
            final Set<PortRef> rows = new HashSet<>(loop.ports());
            for (final Edge e : edges) {
                if (out.containsKey(e.id) || !cards.contains(cardOf.apply(e.sourceNodeId))
                    || !cards.contains(cardOf.apply(e.targetNodeId))) continue;
                if (rows.contains(new PortRef(e.sourceNodeId, e.sourceOutputIndex, false))
                    || rows.contains(new PortRef(e.targetNodeId, e.targetInputIndex, true)))
                    out.put(e.id, Tone.of(loop));
            }
        }
        return out;
    }

    /** What a ring is: the chip its notice wears before its sentence, in the ring's tone and blinking. */
    public static String tag(final UpToLoop.Kind kind) {
        return com.gtnhplanner.ui.card.CardFace.loopWord(kind);
    }

    /**
     * The notice for a ring after its chip, one short sentence (the website's loopSentence): its cards starve (a dead
     * loop) or clog (a clog lock) each other in a downward spiral, each holding the next back. Which cards is for Show
     * me, their pulsing outlines and the lit wires to say; {@code cards} is how many.
     */
    public static String sentence(final UpToLoop.Kind kind, final int cards) {
        final boolean dead = kind == UpToLoop.Kind.DEAD_LOOP;
        if (cards == 1) return "This machine " + (dead ? "starves" : "clogs") + " itself in a downward spiral.";
        return "These machines " + (dead ? "starve" : "clog") + " each other in a downward spiral.";
    }
}
