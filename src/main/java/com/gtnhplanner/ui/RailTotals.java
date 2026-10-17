package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The overview's lists, worked out from what every card makes and uses (the website's {@code rail-model.ts}): what
 * the plan brings in, sends out and keeps inside, each biggest first, and the machines it lists. Pure: the session
 * gives it the numbers and makes the lines ({@link BoardSession#totals()}).
 */
final class RailTotals {

    /** Below this a rate (per second) is solver noise, not a flow worth a line. */
    static final double EPS = 1e-6;

    private RailTotals() {}

    /** The three lists of resource lines. */
    record Lines<L> (List<L> inputs, List<L> outputs, List<L> internal) {}

    /**
     * The resource lines. {@code flow} is per canonical key what is made ([0]) and used ([1]), in the order the plan
     * met them; {@code trashed} what trash voids of it. A resource an unwired port takes ({@code openIn}) or makes
     * ({@code openOut}) is listed on its side at 0 when nothing else lists it there, so it can be set from the
     * overview;
     * so is one a drawer holds ({@code sourceDrawers}, {@code productDrawers}, after the ports). One card's unwired
     * output that another card's unwired input takes nets out of both sides' amounts, but both lines stay.
     * {@code line} makes a line of a key and its amount, {@code keyOf} reads it back.
     */
    static <L> Lines<L> lines(final Map<String, double[]> flow, final Map<String, Double> trashed,
        final Set<String> openIn, final Set<String> openOut, final List<String> sourceDrawers,
        final List<String> productDrawers, final java.util.function.BiFunction<String, Double, L> line,
        final Function<L, String> keyOf, final ToDoubleFunction<L> amountOf) {
        final List<L> in = new ArrayList<>(), out = new ArrayList<>(), internal = new ArrayList<>();
        for (final Map.Entry<String, double[]> e : flow.entrySet()) {
            final double made = e.getValue()[0], used = e.getValue()[1];
            final double leaves = made - used - trashed.getOrDefault(e.getKey(), 0.0);
            if (used - made > EPS) in.add(line.apply(e.getKey(), used - made));
            if (leaves > EPS) out.add(line.apply(e.getKey(), leaves));
            if (Math.min(made, used) > EPS) internal.add(line.apply(e.getKey(), Math.min(made, used)));
        }
        // Biggest first; equal amounts keep the order the plan met them in (the sort is stable).
        final Comparator<L> byAmount = (a, b) -> Double.compare(amountOf.applyAsDouble(b), amountOf.applyAsDouble(a));
        in.sort(byAmount);
        out.sort(byAmount);
        internal.sort(byAmount);
        atZero(in, openIn, line, keyOf);
        atZero(out, openOut, line, keyOf);
        atZero(in, sourceDrawers, line, keyOf);
        atZero(out, productDrawers, line, keyOf);
        return new Lines<>(in, out, internal);
    }

    /** Adds a line at 0 for each key the list does not have yet, in the keys' order. */
    private static <L> void atZero(final List<L> list, final java.util.Collection<String> keys,
        final java.util.function.BiFunction<String, Double, L> line, final Function<L, String> keyOf) {
        if (keys.isEmpty()) return;
        final Set<String> have = new HashSet<>();
        for (final L l : list) have.add(keyOf.apply(l));
        for (final String key : keys) if (have.add(key)) list.add(line.apply(key, 0.0));
    }

    /**
     * The machine lines: every card, at zero too, whatever its rule, but a fluid tank (free and instant: nothing to
     * build). A shared machine is one machine: its recipes that run each have a line, and when none runs its first
     * recipe ({@code host}) stands for it at zero. {@code machineOf} is the shared machine a card is a recipe of (null
     * for a card of its own).
     */
    static <C> List<C> machines(final List<C> cards, final Predicate<C> tank, final Predicate<C> runs,
        final Function<C, Object> machineOf, final Predicate<C> host) {
        final Map<Object, Boolean> running = new HashMap<>();
        for (final C c : cards) {
            final Object m = machineOf.apply(c);
            if (m != null && !tank.test(c)) running.merge(m, runs.test(c), Boolean::logicalOr);
        }
        final List<C> out = new ArrayList<>();
        for (final C c : cards) {
            if (tank.test(c)) continue;
            final Object m = machineOf.apply(c);
            if (m == null || runs.test(c) || !running.get(m) && host.test(c)) out.add(c);
        }
        return out;
    }
}
