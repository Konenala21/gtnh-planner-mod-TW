package com.gtnhplanner.data.flowchart.balancer.stages;

import com.gtnhplanner.data.flowchart.balancer.Outcome;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.Solver;
import com.gtnhplanner.data.flowchart.balancer.Stage;
import com.gtnhplanner.data.flowchart.balancer.UpTo;

/**
 * Between the trades and least flow (docs/design/up-to.md): every Up to card pushed as high as the plan lets it, fairly
 * ({@link UpTo#push}), then the drawer stages ({@link TradeStage#settleDrawers}). What the point is settled under, the
 * trades and the push's bands without the drawer stages, is kept for the read-out
 * ({@link SolveContext#committedTrades}). Hands the held trading on unchanged without Up to pools.
 */
public final class PushStage implements Stage<Solver.Trades, Solver.Trades> {

    @Override
    public String name() {
        return "up to push";
    }

    @Override
    public Outcome<Solver.Trades> run(final SolveContext ctx, final Solver.Trades held) {
        final Solver.Trades pushed = UpTo.push(ctx, held);
        ctx.committedTrades = pushed;
        return new Outcome.Continue<>(TradeStage.settleDrawers(ctx, pushed));
    }
}
