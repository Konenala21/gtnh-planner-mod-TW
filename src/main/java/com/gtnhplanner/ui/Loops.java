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

    /** "A", "A and B", "A, B and C". */
    static String list(final List<String> words) {
        if (words.isEmpty()) return "";
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    /**
     * The notice for a ring, in plain short sentences: a dead loop's cards wait on each other and nothing feeds them; a
     * clog lock's are stuck on what they cannot get rid of, and a drawer for it frees them. {@code names} are its
     * cards'
     * machines, a card each; {@code resources} the names of what its rows carry, once each. A ring of one kind of
     * machine says how many cards.
     */
    public static String sentence(final UpToLoop.Kind kind, final List<String> names, final List<String> resources) {
        final List<String> distinct = new ArrayList<>(new LinkedHashSet<>(names));
        final boolean one;
        final String who;
        if (names.size() > 1 && distinct.size() == 1) {
            who = names.size() + " " + distinct.get(0) + " cards";
            one = false;
        } else {
            who = list(distinct);
            one = names.size() == 1;
        }
        if (kind == UpToLoop.Kind.DEAD_LOOP) return one ? "The " + who + " waits on itself. Nothing feeds the loop."
            : "The " + who + " wait on each other. Nothing feeds the loop.";
        final String what = resources.isEmpty() ? "what they make" : list(resources);
        final String drawer = resources.size() > 1 ? "A drawer for each" : "A drawer for it";
        return one ? "The " + who + " is stuck on " + what + ". " + drawer + " frees it."
            : "The " + who + " are stuck on " + what + ". " + drawer + " frees them.";
    }

    /**
     * The names of what a ring's rows carry, each resource once ({@code keyOf} a row's resource key, null for a row
     * that is gone), in row order.
     */
    public static List<String> resources(final UpToLoop loop, final Function<PortRef, String> keyOf,
        final Function<String, String> nameOf) {
        final Set<String> keys = new LinkedHashSet<>();
        for (final PortRef p : loop.ports()) {
            final String key = keyOf.apply(p);
            if (key != null) keys.add(key);
        }
        final List<String> out = new ArrayList<>(keys.size());
        for (final String key : keys) out.add(nameOf.apply(key));
        return out;
    }
}
