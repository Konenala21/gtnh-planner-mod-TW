package com.gtnhplanner.data.flowchart.balancer.stages;

import java.util.Set;

import com.gtnhplanner.data.flowchart.balancer.Handles;
import com.gtnhplanner.data.flowchart.balancer.Prelude;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.SolveResult;
import com.gtnhplanner.data.flowchart.balancer.Solver;
import com.gtnhplanner.data.flowchart.balancer.StageOutcome;
import com.gtnhplanner.data.flowchart.balancer.UpTo;

/**
 * The zero-gate LP fast path, as a {@link Prelude}: if the chart balances with every gate closed
 * (the flow stage over the empty support and a zero quantity cap), the gate count and external
 * quantity are trivially optimal - 0 gates and 0 external quantity - so this single LP IS the whole
 * solve. Committing the canonical point short-circuits the chain so the entry and stages never run.
 *
 * <p>
 * With Up to pools (docs/design/up-to.md) "closed" means every forced crossing: a drawer's own ports stay open (the
 * player's supply or sink), the push runs, then the drawer stages, and least flow and the canonical point hold all of
 * it.
 */
public final class FastPathStage implements Prelude {

    @Override
    public String name() {
        return "fast path";
    }

    @Override
    public void run(final SolveContext ctx) {
        if (!ctx.upToPools.isEmpty()) {
            runUpTo(ctx);
            return;
        }
        final SolveResult fast = Solver.flowMinimal(ctx, Set.of(), 0.0);
        if (fast.isRejected()) {
            return; // does not balance gate-free; the rest of the chain runs
        }
        ctx.commit(Solver.canonicalize(ctx, Set.of(), fast.point()));
        ctx.committedTrades = Solver.Trades.within(Set.of());
        ctx.shortCircuit = true;
    }

    /** Every port no drawer is linked to, held at nothing. */
    private static Solver.Trades forcedClosed(final SolveContext ctx) {
        final int[] ports = java.util.stream.IntStream.range(0, ctx.model.connectedPorts.size())
            .filter(p -> !ctx.drawnPorts.contains(p))
            .toArray();
        final Solver.Trades open = Solver.Trades.within(null);
        return ports.length == 0 ? open : open.holding(new Solver.Level(true, 0, ports), 0);
    }

    private static void runUpTo(final SolveContext ctx) {
        final Solver.Trades closed = forcedClosed(ctx);
        final Handles h = Solver.tradeModel(ctx, closed);
        for (int e = 0; e < h.flowVars().length; e++) h.flowVars()[e].weight(1.0);
        if (Solver.run(ctx, h, "fast path")
            .isRejected()) return;
        final Solver.Trades pushed = UpTo.push(ctx, closed);
        final Solver.Trades drawn = TradeStage.settleDrawers(ctx, pushed);
        final SolveResult s3 = Solver.leastFlowHolding(ctx, drawn);
        if (s3.isRejected()) return;
        final StageOutcome best = Solver.canonicalHolding(ctx, drawn, s3.point());
        ctx.commit(best);
        ctx.openGates = ctx.carryingGates(best.externals, Set.of());
        ctx.committedTrades = pushed;
        ctx.shortCircuit = true;
    }
}
