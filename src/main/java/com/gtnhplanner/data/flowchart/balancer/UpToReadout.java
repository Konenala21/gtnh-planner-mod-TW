package com.gtnhplanner.data.flowchart.balancer;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * The read-out of one Up to pool at the committed point (docs/design/up-to.md, the website's {@code UpToReadout}).
 *
 * @param node     the pool's first machine: the card (a shared card's first section)
 * @param machines every machine of the pool, in pool order
 * @param capacity up to this many machines (the pool's capacity)
 * @param reached  machines' worth running: the pool's machine time, extent * durTicks / 20 summed
 * @param reason   null when the read-out's work budget ran out before this pool
 * @param port     STARVED, CLOGGED, UNUSED: the port that holds it back (the first in machine, then port order)
 * @param tied     other ports that hold it back just as alone
 * @param wants    BOTTLENECK: how many machines the rest of the plan would use
 * @param upstream STARVED: the Up to cards ({@link #node}) making the short input that are FULL or BOTTLENECK
 */
public record UpToReadout(UUID node, List<UUID> machines, double capacity, double reached, @Nullable UpToReason reason,
    @Nullable PortRef port, List<PortRef> tied, @Nullable Double wants, List<UUID> upstream) {

    public UpToReadout {
        machines = List.copyOf(machines);
        tied = List.copyOf(tied);
        upstream = List.copyOf(upstream);
    }
}
