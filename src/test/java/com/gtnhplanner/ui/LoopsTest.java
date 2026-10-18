package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;

/** Dead loops and clog locks on the board ({@link Loops}), case for case with the website's {@code loops.test.ts}. */
class LoopsTest {

    private static final UpToLoop.Kind DEAD = UpToLoop.Kind.DEAD_LOOP, CLOG = UpToLoop.Kind.CLOG_LOCK;

    @Test
    void namesWhatItIsAndSaysItIsADownwardSpiralInOneShortSentence() {
        assertEquals("DEAD LOOP", Loops.tag(DEAD));
        assertEquals("CLOG LOCK", Loops.tag(CLOG));
        assertEquals("These machines starve each other in a downward spiral.", Loops.sentence(DEAD, 3));
        assertEquals("These machines clog each other in a downward spiral.", Loops.sentence(CLOG, 2));
        assertEquals("This machine starves itself in a downward spiral.", Loops.sentence(DEAD, 1));
        assertEquals("This machine clogs itself in a downward spiral.", Loops.sentence(CLOG, 1));
    }

    private static final UUID A = id(1), B = id(2), C = id(3), SPLIT = id(4), MAKE = id(5), OTHER = id(6),
        SHARED = id(7), SHARED2 = id(8);

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    /** Every node its own card, but SHARED2, a recipe on SHARED's card. */
    private static final Function<UUID, UUID> CARD = n -> n.equals(SHARED2) ? SHARED : n;

    private static Edge edge(final int n, final UUID from, final int out, final UUID to, final int in) {
        return new Edge(id(100 + n), from, to, out, in);
    }

    @Test
    void tonesADeadLoopAsStarvedsAndAClogLockAsCloggeds() {
        final List<UpToLoop> loops = List
            .of(new UpToLoop(DEAD, List.of(A, B, C), List.of()), new UpToLoop(CLOG, List.of(SPLIT, MAKE), List.of()));
        assertEquals(0xFFE0A63A, Loops.Tone.of(loops.get(0)).colour);
        assertEquals(0xFFB49CF0, Loops.Tone.of(loops.get(1)).colour);
        final Map<UUID, Loops.Tone> tones = Loops.cardTones(loops, CARD);
        assertEquals(Loops.Tone.DEAD, tones.get(B));
        assertEquals(Loops.Tone.CLOG, tones.get(MAKE));
        assertNull(tones.get(OTHER));
        assertEquals(Map.of(), Loops.cardTones(List.of(), CARD));
        // A shared machine's recipe in a ring puts its card in it.
        assertEquals(
            Map.of(A, Loops.Tone.DEAD, SHARED, Loops.Tone.DEAD),
            Loops.cardTones(List.of(new UpToLoop(DEAD, List.of(A, SHARED2), List.of())), CARD));
    }

    @Test
    void lightsTheWiresThatJoinItsCardsAlongItsRowsAndNoOthers() {
        // A dead ring a -> b -> c -> a, each short of its input 0; a clog lock split -> make stuck on hydrogen (output
        // 0), while its oxygen (output 1) and a wire out of the ring are not on it.
        final List<UpToLoop> loops = List.of(
            new UpToLoop(
                DEAD,
                List.of(A, B, C),
                List.of(new PortRef(A, 0, true), new PortRef(B, 0, true), new PortRef(C, 0, true))),
            new UpToLoop(CLOG, List.of(SPLIT, MAKE), List.of(new PortRef(SPLIT, 0, false))));
        final List<Edge> edges = List.of(
            edge(1, A, 0, B, 0),
            edge(2, B, 0, C, 0),
            edge(3, C, 0, A, 0),
            edge(4, SPLIT, 0, MAKE, 0),
            edge(5, SPLIT, 1, MAKE, 1),
            edge(6, A, 1, OTHER, 0));
        assertEquals(
            Map.of(
                id(101),
                Loops.Tone.DEAD,
                id(102),
                Loops.Tone.DEAD,
                id(103),
                Loops.Tone.DEAD,
                id(104),
                Loops.Tone.CLOG),
            Loops.wires(edges, loops, CARD));
        assertEquals(Map.of(), Loops.wires(edges, List.of(), CARD));
    }

    @Test
    void findsASharedMachinesRecipeRowByItsCard() {
        // The wire runs into the second recipe of a shared card; the ring names that recipe's row.
        final List<UpToLoop> ring = List
            .of(new UpToLoop(DEAD, List.of(A, SHARED2), List.of(new PortRef(SHARED2, 0, true))));
        assertEquals(Map.of(id(101), Loops.Tone.DEAD), Loops.wires(List.of(edge(1, A, 0, SHARED2, 0)), ring, CARD));
        assertEquals(List.of(A, SHARED), Loops.cards(ring.get(0), CARD));
    }

    @Test
    void namesEachCardOnce() {
        final UpToLoop loop = new UpToLoop(
            CLOG,
            List.of(SHARED, SHARED2, MAKE),
            List.of(new PortRef(SHARED, 0, false), new PortRef(SHARED2, 0, false), new PortRef(MAKE, 2, false)));
        assertEquals(List.of(SHARED, MAKE), Loops.cards(loop, CARD));
    }
}
