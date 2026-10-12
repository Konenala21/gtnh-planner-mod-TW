package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternative;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternatives;

/**
 * The plan's leftovers ("spare") and who bears them. When pinned machines disagree, something leaves the plan (or comes
 * into it) at a wired port with no drawer: an open gate. Often more than one gate could take that leftover, and it can
 * be shared between them: a machine further down runs slower, or material leaves at another port. The alternatives
 * search already finds each single place ({@link Alternatives}, one decision per open gate); this groups them per
 * leftover and honours a stored split by mixing their points.
 *
 * <p>
 * A split is kept as shares of the leftover (unitless, summing to one), never amounts, so it survives the plan changing
 * size and works across units. Each bearer's point is a solution the chain itself reached, so any mix of them keeps
 * every linear row (conservation, pins, drawers, pools): the plan stays exactly as feasible, the leftover is simply
 * divided. Several leftovers mix independently, each around the solver's own point; the result is checked like any
 * other point and dropped for the solver's own when it does not conserve.
 */
public final class Spares {

    private Spares() {}

    /**
     * One place a leftover can be borne: a gate, named by its anchor port.
     *
     * @param anchor     the gate's anchor ({@link SolveContext#anchorOf}), what a stored share is keyed on.
     * @param flows      what crosses at the gate's ports now (per second).
     * @param max        what crosses there when it bears the whole leftover (per second).
     * @param share      its part of the leftover now, 0 to 1.
     * @param solverPick whether this is where the solver puts it by itself.
     */
    public record Bearer(PortRef anchor, List<External> flows, double max, double share, boolean solverPick) {

        public double amount() {
            double total = 0;
            for (final External e : flows) total += e.ratePerSecond();
            return total;
        }

        /** The key a share is stored under: the anchor's node, side and port. */
        public String key() {
            return keyOf(anchor);
        }
    }

    /**
     * One leftover: the places that could bear it, the solver's pick first.
     *
     * @param key       its bearers' keys, sorted and joined: what its shares are stored under.
     * @param importing whether it is brought in (a shortfall) rather than sent out (a surplus).
     * @param bearers   every place that could bear it (one when there is no other).
     * @param custom    whether a stored split is in force; false when the solver's own pick stands.
     */
    public record Leftover(String key, boolean importing, List<Bearer> bearers, boolean custom) {}

    /** A port's key in a stored split. */
    public static String keyOf(final PortRef ref) {
        return ref.nodeId() + (ref.input() ? ":i:" : ":o:") + ref.portIndex();
    }

    /**
     * The leftovers on the committed point, from its alternatives and their points, with {@code stored} splits applied:
     * when any applies, the mix is committed on {@code ctx}. A leftover with one possible place is listed too, with
     * nothing to choose.
     */
    static List<Leftover> apply(final SolveContext ctx, final Alternatives alternatives,
        final Map<ChoiceKey, StageOutcome> points, final Map<String, Map<String, Double>> stored) {
        final StageOutcome own = ctx.point();
        if (own == null || alternatives == null) return List.of();
        // The options per decision (the open gate each would replace), in the order the search listed them.
        final Map<PortRef, List<Alternative>> byDecision = new LinkedHashMap<>();
        for (final Alternative option : alternatives.options()) {
            byDecision.computeIfAbsent(option.replaces(), k -> new ArrayList<>())
                .add(option);
        }
        final List<Draft> drafts = new ArrayList<>();
        for (final List<Alternative> options : byDecision.values()) {
            final Alternative current = options.stream()
                .filter(Alternative::isCurrent)
                .findFirst()
                .orElse(null);
            if (current == null) continue;
            final boolean importing = current.opens()
                .input();
            final List<Alternative> usable = new ArrayList<>();
            final List<StageOutcome> vertices = new ArrayList<>();
            for (final Alternative option : options) {
                // A bearer on the other side would import and void at once: never mixed in.
                if (option.opens()
                    .input() != importing) continue;
                final StageOutcome point = option.isCurrent() ? own : points.get(option.key());
                if (point == null) continue;
                usable.add(option);
                vertices.add(point);
            }
            if (usable.isEmpty()) continue;
            final List<String> keys = new ArrayList<>();
            for (final Alternative option : usable) keys.add(keyOf(option.opens()));
            final List<String> sorted = new ArrayList<>(keys);
            sorted.sort(Comparator.naturalOrder());
            final String key = String.join("|", sorted);
            final double[] shares = shares(keys, stored.get(key), stored);
            drafts.add(new Draft(key, importing, usable, vertices, shares));
        }
        if (drafts.isEmpty()) return List.of();

        // The mix: the solver's own point, moved by each leftover's split.
        boolean anyCustom = false;
        final double[] extents = own.extents.clone(), flows = own.flows.clone(), externals = own.externals.clone();
        for (final Draft d : drafts) {
            if (d.shares == null) continue;
            anyCustom = true;
            for (int b = 0; b < d.vertices.size(); b++) {
                final double w = d.shares[b];
                final StageOutcome v = d.vertices.get(b);
                add(extents, v.extents, own.extents, w);
                add(flows, v.flows, own.flows, w);
                add(externals, v.externals, own.externals, w);
            }
        }
        boolean mixed = false;
        if (anyCustom) {
            if (ctx.validate(extents, flows, externals) == null) {
                clampDust(extents);
                clampDust(flows);
                clampDust(externals);
                ctx.commit(StageOutcome.of(ctx, extents, flows, externals, own.provenOptimal));
                mixed = true;
            } else {
                com.gtnhplanner.GtnhPlanner.LOG
                    .debug("A stored spare split does not conserve here; the solver's own answer stands");
            }
        }

        final double[] now = ctx.externals();
        final double dust = ctx.heuristics.numerics().dust * SolveContext.scaleOf(now);
        final List<Leftover> out = new ArrayList<>(drafts.size());
        for (final Draft d : drafts) {
            final boolean custom = mixed && d.shares != null;
            final List<Bearer> bearers = new ArrayList<>(d.options.size());
            for (int b = 0; b < d.options.size(); b++) {
                final Alternative option = d.options.get(b);
                final List<External> flowsHere = new ArrayList<>();
                final Integer gate = gateOf(ctx, option.opens());
                if (gate != null) {
                    for (final int p : ctx.model.gates.get(gate)
                        .ports()) {
                        if (now[p] > dust) flowsHere.add(new External(ctx.refOf(p), now[p]));
                    }
                }
                double max = 0;
                for (final External e : option.externals()) max += e.ratePerSecond();
                bearers.add(
                    new Bearer(
                        option.opens(),
                        List.copyOf(flowsHere),
                        max,
                        custom ? d.shares[b] : option.isCurrent() ? 1 : 0,
                        option.isCurrent()));
            }
            out.add(new Leftover(d.key, d.importing, List.copyOf(bearers), custom));
        }
        return List.copyOf(out);
    }

    /** A leftover being built: its options, their points and the split to apply (null: the solver's own). */
    private record Draft(String key, boolean importing, List<Alternative> options, List<StageOutcome> vertices,
        @Nullable double[] shares) {}

    /**
     * The stored split for these bearers, normalised to them; null when none applies. A split stored for a bearer set
     * that has since grown or shrunk still counts when it names at least two of these bearers.
     */
    private static @Nullable double[] shares(final List<String> keys, @Nullable final Map<String, Double> exact,
        final Map<String, Map<String, Double>> stored) {
        Map<String, Double> split = exact;
        if (split == null) {
            int best = 1;
            for (final Map<String, Double> candidate : stored.values()) {
                int common = 0;
                for (final String k : keys) if (candidate.containsKey(k)) common++;
                if (common > best) {
                    best = common;
                    split = candidate;
                }
            }
        }
        if (split == null) return null;
        final double[] shares = new double[keys.size()];
        double total = 0;
        for (int i = 0; i < shares.length; i++) {
            shares[i] = Math.max(0, split.getOrDefault(keys.get(i), 0.0));
            total += shares[i];
        }
        if (total <= 0) return null;
        for (int i = 0; i < shares.length; i++) shares[i] /= total;
        return shares;
    }

    /** {@code into += w * (vertex - own)}. */
    private static void add(final double[] into, final double[] vertex, final double[] own, final double w) {
        if (w == 0) return;
        for (int i = 0; i < into.length; i++) into[i] += w * (vertex[i] - own[i]);
    }

    /** Rounding left below zero by the mix goes to zero (validation already allowed it). */
    private static void clampDust(final double[] values) {
        for (int i = 0; i < values.length; i++) if (values[i] < 0) values[i] = 0;
    }

    /** The gate a port belongs to on this model, or null when the port is not wired here. */
    private static @Nullable Integer gateOf(final SolveContext ctx, final PortRef ref) {
        final Integer machine = ctx.model.machineIndex.get(ref.nodeId());
        if (machine == null) return null;
        final long key = ((long) machine << 32) | ((long) ref.portIndex() << 1) | (ref.input() ? 1 : 0);
        final Integer port = ctx.model.portLookup.get(key);
        return port == null ? null : ctx.model.portGate[port];
    }
}
