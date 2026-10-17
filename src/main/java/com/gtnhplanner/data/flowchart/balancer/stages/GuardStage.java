package com.gtnhplanner.data.flowchart.balancer.stages;

import com.gtnhplanner.data.flowchart.balancer.Prelude;
import com.gtnhplanner.data.flowchart.balancer.SolveContext;
import com.gtnhplanner.data.flowchart.balancer.UpTo;

/**
 * The Up to guard (docs/design/up-to.md, {@link UpTo#prepareGuard}), worked out once per context before the fast path:
 * every model the context builds after it keeps forced flow from making up an Up to card's gap. Nothing without Up to
 * pools.
 */
public final class GuardStage implements Prelude {

    @Override
    public String name() {
        return "up to guard";
    }

    @Override
    public void run(final SolveContext ctx) {
        UpTo.prepareGuard(ctx);
    }
}
