package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The overview's lists ({@link RailTotals}), as the website's {@code rail-model.test.ts} has them: every machine and
 * every unwired port's resource listed, at zero too.
 */
class RailTotalsTest {

    private record Line(String key, double amount) {

        @Override
        public String toString() {
            return key + "=" + amount;
        }
    }

    private static RailTotals.Lines<Line> lines(final Map<String, double[]> flow, final Set<String> openIn,
        final Set<String> openOut, final List<String> sources, final List<String> products) {
        return RailTotals.lines(flow, Map.of(), openIn, openOut, sources, products, Line::new, Line::key, Line::amount);
    }

    private static Map<String, double[]> flow(final Object... keyMadeUsed) {
        final Map<String, double[]> out = new LinkedHashMap<>();
        for (int i = 0; i < keyMadeUsed.length; i += 3) out.put(
            (String) keyMadeUsed[i],
            new double[] { ((Number) keyMadeUsed[i + 1]).doubleValue(), ((Number) keyMadeUsed[i + 2]).doubleValue() });
        return out;
    }

    private static Set<String> keys(final String... keys) {
        return new LinkedHashSet<>(List.of(keys));
    }

    @Test
    void listsWhatComesInGoesOutAndStaysInsideBiggestFirst() {
        final RailTotals.Lines<Line> l = lines(
            flow("ore", 0, 2, "dust", 2, 1, "slag", 3, 0, "gem", 0, 5),
            keys(),
            keys(),
            List.of(),
            List.of());
        assertEquals(List.of(new Line("gem", 5), new Line("ore", 2)), l.inputs());
        assertEquals(List.of(new Line("slag", 3), new Line("dust", 1)), l.outputs());
        assertEquals(List.of(new Line("dust", 1)), l.internal());
    }

    @Test
    void listsAnUnwiredPortsResourceAtZeroWhenItsCardRunsNothing() {
        // A card at zero (or a failed solve: every flow 0): its unwired ports still list what they take and make.
        final RailTotals.Lines<Line> l = lines(
            flow("plate", 0, 0, "rod", 0, 0),
            keys("plate"),
            keys("rod"),
            List.of(),
            List.of());
        assertEquals(List.of(new Line("plate", 0)), l.inputs());
        assertEquals(List.of(new Line("rod", 0)), l.outputs());
        assertEquals(List.of(), l.internal());
    }

    @Test
    void keepsBothSidesWhenOneCardsUnwiredOutputIsAnothersUnwiredInput() {
        // The cutter makes 2 plates a second at an unwired output, the press takes them at an unwired input: they net
        // out of both amounts, but both lines stay, so either end can be set.
        final RailTotals.Lines<Line> l = lines(flow("plate", 2, 2), keys("plate"), keys("plate"), List.of(), List.of());
        assertEquals(List.of(new Line("plate", 0)), l.inputs());
        assertEquals(List.of(new Line("plate", 0)), l.outputs());
        assertEquals(List.of(new Line("plate", 2)), l.internal());
    }

    @Test
    void addsZeroLinesAfterTheFlowingOnesPortsBeforeDrawersEachOnce() {
        final RailTotals.Lines<Line> l = lines(
            flow("ore", 0, 2, "coal", 0, 0, "tin", 0, 0),
            keys("ore", "coal"),
            keys(),
            List.of("tin", "coal", "log"),
            List.of("ingot"));
        assertEquals(
            List.of(new Line("ore", 2), new Line("coal", 0), new Line("tin", 0), new Line("log", 0)),
            l.inputs());
        assertEquals(List.of(new Line("ingot", 0)), l.outputs());
    }

    @Test
    void trashLeavesWhatItVoidsOutOfTheOutputs() {
        final RailTotals.Lines<Line> l = RailTotals.lines(
            flow("slag", 3, 0),
            Map.of("slag", 3.0),
            keys(),
            keys(),
            List.of(),
            List.of(),
            Line::new,
            Line::key,
            Line::amount);
        assertEquals(List.of(), l.outputs());
    }

    /** A card for the machine list: its id, whether it is a fluid tank, and its shared machine. */
    private record Card(String id, boolean tank, String machine) {}

    private static List<String> machines(final Card... cards) {
        return RailTotals.machines(List.of(cards), Card::tank, Card::machine)
            .stream()
            .map(
                line -> line.stream()
                    .map(Card::id)
                    .collect(java.util.stream.Collectors.joining("+")))
            .toList();
    }

    @Test
    void listsEveryMachineAtZeroTooButAFluidTank() {
        assertEquals(
            List.of("comp", "cutter", "saw"),
            machines(
                new Card("comp", false, null),
                new Card("cutter", false, null),
                new Card("tank", true, null),
                new Card("saw", false, null)));
    }

    @Test
    void listsASharedMachineOnceWithAllItsRecipesWhereTheFirstStands() {
        // As the website's machine list: a shared machine is one card, one line, its recipes summed.
        assertEquals(
            List.of("bend1+bend2", "saw", "press1+press2"),
            machines(
                new Card("bend1", false, "bender"),
                new Card("saw", false, null),
                new Card("bend2", false, "bender"),
                new Card("press1", false, "press"),
                new Card("press2", false, "press")));
    }
}
