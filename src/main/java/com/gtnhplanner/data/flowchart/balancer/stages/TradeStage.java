package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.Entry;
import com.gtnhplanner.data.flowchart.balancer.Handles;
import com.gtnhplanner.data.flowchart.balancer.ModelData;
import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolveResult;
import com.gtnhplanner.data.flowchart.balancer.Solver;

/**
 * The AUTO chain's entry: where the plan trades with the market. When the pins cannot be met by the plan's own machines
 * alone, something is bought or sold at a wired port. The plan buys as far upstream as it can and sells as far
 * downstream as it can, always: level by level ({@link Solver#levels}), buying first, it trades as little as it can at
 * each, holding every level before. So it buys raw goods and runs its own machines rather than buying a part they could
 * make, never buys more in order to sell more, and sells spare only where nothing further on can take it. Each level is
 * one LP whose least value is a property of the plan, so the answer is too.
 */
public final class TradeStage implements Entry<Solver.Trades> {

    @Override
    public String name() {
        return "trades";
    }

    @Override
    public Outcome<Solver.Trades> run(final SolveContext ctx) {
        return settle(ctx, null);
    }

    /** The plan's trading settled level by level, trading only at {@code open}'s gates (every gate when null). */
    public static Outcome<Solver.Trades> settle(final SolveContext ctx, @Nullable final Set<Integer> open) {
        Solver.Trades held = Solver.Trades.within(open);
        for (final Solver.Level level : Solver.levels(ctx)) {
            final SolveResult least = Solver.leastAt(ctx, held, level);
            if (least.isRejected()) return new Outcome.Fail<>(least.rejection());
            held = held
                .holding(level, Solver.heldAt(ctx, level.measure(ctx, least.point().externals), least.point().extents));
        }
        return new Outcome.Continue<>(held);
    }

    /**
     * The drawer stages' levels (docs/design/up-to.md, "Drawers and buffers"): the source drawers' wired ports, then
     * the
     * sinks' with every port a buffer banks at (what its takers do not take: old Build's "ship before banking"), each
     * only when there are any.
     */
    private static List<Solver.Level> drawerLevels(final SolveContext ctx) {
        final Set<Integer> sources = new TreeSet<>(), sinks = new TreeSet<>();
        for (final ModelData.DrawerRow row : ctx.model.drawers) {
            for (final int p : row.externalPorts()) (row.kind() == Drawer.Kind.SOURCE ? sources : sinks).add(p);
        }
        sinks.addAll(ctx.bankPorts.keySet());
        final List<Solver.Level> out = new ArrayList<>();
        if (!sources.isEmpty()) out.add(level(true, sources));
        if (!sinks.isEmpty()) out.add(level(false, sinks));
        return out;
    }

    private static Solver.Level level(final boolean buying, final Set<Integer> ports) {
        return new Solver.Level(
            buying,
            0,
            ports.stream()
                .mapToInt(Integer::intValue)
                .toArray());
    }

    /**
     * After the push (docs/design/up-to.md): the least the plan draws from its source drawers ("recycle before
     * importing"), then the least it banks in its sink drawers ("ship before banking"), with everything before held,
     * each held where it was reached like a trade level. Returns {@code pushed} itself without Up to pools or wired
     * drawers. A stage that finds nothing (numerics) stops where it is.
     */
    public static Solver.Trades settleDrawers(final SolveContext ctx, final Solver.Trades pushed) {
        if (ctx.upToPools.isEmpty()) return pushed;
        Solver.Trades held = pushed;
        for (final Solver.Level level : drawerLevels(ctx)) {
            final Handles h = Solver.tradeModel(ctx, held);
            for (final int p : level.ports()) h.extVars()[p].weight(ctx.externalWeight(p));
            final SolveResult least = Solver
                .run(ctx, h, level.buying() ? "least from source drawers" : "least into sink drawers");
            if (least.isRejected()) return held;
            held = held
                .holding(level, Solver.heldAt(ctx, level.measure(ctx, least.point().externals), least.point().extents));
        }
        return held;
    }
}
