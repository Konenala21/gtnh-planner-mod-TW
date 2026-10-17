package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ojalgo.optimisation.Variable;

/**
 * The Up to rule in the solver (docs/design/up-to.md; the website's {@code guard.ts} and {@code push.ts}): the guard
 * that keeps forced flow from making up an Up to card's gap, and the push that runs every Up to card as high as the
 * plan lets it, fairly.
 *
 * <p>
 * <b>The guard.</b> An Up to machine slows down, as in the game, instead of the plan bringing in or sending out what it
 * cannot do. The guarded ports are every connected port on a wire network an Up to machine is on, except the ports a
 * drawer is linked to (the player's own end: a byproduct drawer is permission to run, a source drawer is supply). Once
 * per context, before the first pass, one LP asks what they must trade at the least: the base model with pins and pools
 * but without drawer rows (a drawer's rate never opens a forced flow on a guarded network) and without the guard.
 * Nothing, at rounding: every model the context builds closes them. Something (an Exactly pin needs it): they are held
 * together to that least. No answer at all: no guard (the pins conflict whatever happens; the chain fails and diagnoses
 * as before).
 *
 * <p>
 * <b>The push.</b> A pool's level is its machine time over its capacity, in 0..1. Stage A, everything runs: the most
 * the levels can add up to, S (old Build's first stage). Stage B, fairness: progressive max-min over the levels with
 * their sum held at S (old Build's round-robin split). Each round lifts the least level z of the pools not yet frozen;
 * a pool is frozen at z only when a probe proves it cannot go higher (its own level maximised with every unfrozen pool
 * at least z; the round's candidates are first asked all at once, and freeze together when even their sum cannot rise);
 * a pool some feasible point of the round already shows above z is a certificate that it can, and is not probed. Each
 * level is then held in a narrow band ({@link Solver.Trades#floors()}) through least flow, the canonical point and
 * every
 * later model of the support. The levels are a property of the plan, not of the solve path: stage A's optimum is
 * unique, so is the max-min vector over a convex set, and every freeze is decided by a value, never by which vertex an
 * engine returns. Work is counted, never timed.
 */
public final class UpTo {

    private UpTo() {}

    // ── The guard ──

    /** The connected ports on an Up to machine's wire networks that no drawer is linked to, ascending. */
    static int[] guardedPorts(final SolveContext ctx) {
        final ModelData model = ctx.model;
        final Set<Integer> upTo = new HashSet<>();
        for (final int p : ctx.upToPools) upTo.addAll(
            model.pools.get(p)
                .machines());
        if (upTo.isEmpty()) return new int[0];
        final Set<Integer> networks = new HashSet<>();
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            if (upTo.contains(
                model.connectedPorts.get(p)
                    .machine()))
                networks.add(model.portComponent[p]);
        }
        final List<Integer> ports = new ArrayList<>();
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            if (networks.contains(model.portComponent[p]) && !ctx.drawnPorts.contains(p)) ports.add(p);
        }
        return ports.stream()
            .mapToInt(Integer::intValue)
            .toArray();
    }

    /** Works the guard out once per context (a no-op after the first call, and without Up to pools). */
    public static void prepareGuard(final SolveContext ctx) {
        if (ctx.guardReady) return;
        synchronized (ctx) {
            if (ctx.guardReady) return;
            try {
                final int[] ports = guardedPorts(ctx);
                if (ports.length == 0) return;
                final Handles h = Solver.baseModel(ctx, false, false);
                for (final int p : ports) h.extVars()[p].weight(ctx.externalWeight(p));
                // The question commits nothing and must not leave its own rejection behind.
                final Note rejection = ctx.rejection;
                final SolveResult least = Solver.run(ctx, h, "guard");
                ctx.rejection = rejection;
                if (least.isRejected()) return;
                double reached = 0;
                for (final int p : ports) reached += least.point().externals[p] * ctx.externalWeight(p);
                final double held = Solver.heldAt(ctx, reached, least.point().extents);
                final double qtyEps = ctx.heuristics.numerics().qtyEps;
                ctx.guard = new SolveContext.Guard(
                    ports,
                    held <= 0 ? 0 : held * (1 + qtyEps) + qtyEps * Math.max(1, held));
            } finally {
                ctx.guardReady = true;
            }
        }
    }

    // ── The push ──

    /** A pool's level at a point: its machine time over its capacity. */
    public static double levelOf(final SolveContext ctx, final int pool, final double[] extents) {
        return ctx.poolTime(pool, extents) / ctx.model.pools.get(pool)
            .capacity();
    }

    /** The Up to pools the models built now keep (the relaxation's and the read-out's probes drop some). */
    static List<Integer> keptUpTo(final SolveContext ctx) {
        final List<Integer> out = new ArrayList<>();
        for (final int p : ctx.upToPools) if (!ctx.droppedPools.contains(p)) out.add(p);
        return out;
    }

    /** {@code level >= at} for one pool. */
    private static void atLeast(final SolveContext ctx, final Handles h, final int pool, final double at) {
        Solver.levelTerms(ctx, h, pool)
            .row(h, "at_least_" + pool, at, Double.POSITIVE_INFINITY);
    }

    /**
     * A pool held from its level to a little over it, within 0..1. A pool that cannot rise past the push's tolerance is
     * held at nothing (as {@link Solver#heldAt} closes a trade level at rounding): a sliver over zero is no level.
     */
    static Solver.Band bandAt(final SolveContext ctx, final int pool, final double level) {
        final Numerics n = ctx.heuristics.numerics();
        if (level <= n.pushTol) return new Solver.Band(pool, 0, 0);
        return new Solver.Band(pool, level, Math.min(1, level + n.bandAbove));
    }

    /**
     * Stage B's region: the held trading, stage A's sum (less its room), and the frozen pools at least at their levels
     * (the ones frozen at nothing held there, as their bands will hold them).
     */
    private static Handles fairRegion(final SolveContext ctx, final Solver.Trades held, final List<Integer> pools,
        final double sum, final Map<Integer, Double> frozen) {
        final Handles h = Solver.tradeModel(ctx, held);
        final Solver.Terms all = new Solver.Terms();
        for (final int p : pools) all.addAll(Solver.levelTerms(ctx, h, p));
        all.row(h, "push_sum", sum - ctx.heuristics.numerics().pushTol * Math.max(1, sum), Double.POSITIVE_INFINITY);
        for (final Map.Entry<Integer, Double> e : frozen.entrySet()) {
            final Solver.Band band = bandAt(ctx, e.getKey(), e.getValue());
            if (band.hi() > 0) atLeast(ctx, h, e.getKey(), e.getValue());
            else Solver.levelTerms(ctx, h, e.getKey())
                .row(h, "frozen_" + e.getKey(), 0, 0);
        }
        return h;
    }

    /**
     * {@code held} with every Up to pool's level held in its band. Returns {@code held} itself when the context has no
     * Up
     * to pool the models keep. A model that finds nothing (numerics) stops the push where it is: pools not yet frozen
     * get
     * no band.
     */
    public static Solver.Trades push(final SolveContext ctx, final Solver.Trades held) {
        final List<Integer> pools = keptUpTo(ctx);
        if (pools.isEmpty()) return held;
        final double tol = ctx.heuristics.numerics().pushTol;

        // Stage A: everything runs.
        final Handles all = Solver.tradeModel(ctx, held);
        for (final int p : pools) Solver.priceLevel(ctx, all, p, -1);
        final SolveResult most = Solver.run(ctx, all, "push: everything runs");
        if (most.isRejected()) return held;
        double sum = 0;
        for (final int p : pools) sum += levelOf(ctx, p, most.point().extents);

        // One pool: stage A was its own maximum.
        final Map<Integer, Double> levels = new LinkedHashMap<>();
        if (pools.size() == 1) levels.put(pools.get(0), Math.min(1, Math.max(0, sum)));

        // Stage B: fairness, round by round.
        List<Integer> active = unfrozen(pools, levels);
        while (!active.isEmpty()) {
            final Handles round = fairRegion(ctx, held, pools, sum, levels);
            final Variable z = round.model()
                .addVariable("push_level")
                .lower(0)
                .upper(1)
                .weight(-1);
            for (final int p : active) {
                Solver.levelTerms(ctx, round, p)
                    .add(z, -1)
                    .row(round, "push_least_" + p, 0, Double.POSITIVE_INFINITY);
            }
            final SolveResult lifted = Solver.run(ctx, round, "push: fairness");
            if (lifted.isRejected()) break;
            final double least = Solver.valueOf(z);
            // Every one at its capacity (z sits on its own bound, 1, unless a pool is a hair short of it).
            if (least >= 1 - tol || active.size() == 1) {
                // With one pool left, the round maximised that pool's level itself.
                for (final int p : active) levels.put(p, least);
                break;
            }
            final List<Integer> candidates = new ArrayList<>();
            for (final int p : active) if (levelOf(ctx, p, lifted.point().extents) <= least + tol) candidates.add(p);
            final List<Integer> activeNow = active;
            final Set<Integer> rises = new HashSet<>();
            if (candidates.size() > 1) {
                // All of them at once first: each is at least the level, so if their sum cannot pass k levels by the
                // tolerance, none of them can rise (one model where k would do, the common case of cards that sit
                // still).
                final SolveResult together = probe(
                    ctx,
                    held,
                    pools,
                    sum,
                    levels,
                    activeNow,
                    least,
                    candidates,
                    "push: can any rise");
                if (!together.isRejected()) {
                    double reached = 0;
                    for (final int q : candidates) reached += levelOf(ctx, q, together.point().extents);
                    if (reached <= least * candidates.size() + tol) {
                        for (final int q : candidates) levels.put(q, least);
                        active = unfrozen(active, levels);
                        continue;
                    }
                    for (final int q : candidates)
                        if (levelOf(ctx, q, together.point().extents) > least + tol) rises.add(q);
                }
            }
            final List<Integer> stuck = new ArrayList<>();
            for (final int p : candidates) {
                if (rises.contains(p)) continue;
                final SolveResult highest = probe(
                    ctx,
                    held,
                    pools,
                    sum,
                    levels,
                    activeNow,
                    least,
                    List.of(p),
                    "push: can it rise");
                if (highest.isRejected()) {
                    stuck.add(p);
                    continue;
                }
                if (levelOf(ctx, p, highest.point().extents) <= least + tol) stuck.add(p);
                // Every point of the probe is a point of the round's region: a candidate above the level there can
                // rise.
                for (final int q : candidates)
                    if (q != p && levelOf(ctx, q, highest.point().extents) > least + tol) rises.add(q);
            }
            // A round that proves nothing stuck (numerics) freezes its candidates, so the push always ends.
            final List<Integer> freeze = !stuck.isEmpty() ? stuck : !candidates.isEmpty() ? candidates : active;
            for (final int p : freeze) levels.put(p, least);
            active = unfrozen(active, levels);
        }

        final List<Solver.Band> bands = new ArrayList<>(held.floors());
        for (final int p : pools) {
            final Double level = levels.get(p);
            if (level != null) bands.add(bandAt(ctx, p, level));
        }
        return held.withFloors(bands);
    }

    /** The probe of a round: every active pool at least the level, and the {@code lift}ed pools' levels maximised. */
    private static SolveResult probe(final SolveContext ctx, final Solver.Trades held, final List<Integer> pools,
        final double sum, final Map<Integer, Double> levels, final List<Integer> active, final double least,
        final List<Integer> lift, final String label) {
        final Handles probe = fairRegion(ctx, held, pools, sum, levels);
        for (final int q : active) atLeast(ctx, probe, q, least);
        for (final int q : lift) Solver.priceLevel(ctx, probe, q, -1);
        return Solver.run(ctx, probe, label);
    }

    private static List<Integer> unfrozen(final List<Integer> pools, final Map<Integer, Double> levels) {
        final List<Integer> out = new ArrayList<>();
        for (final int p : pools) if (!levels.containsKey(p)) out.add(p);
        return out;
    }
}
