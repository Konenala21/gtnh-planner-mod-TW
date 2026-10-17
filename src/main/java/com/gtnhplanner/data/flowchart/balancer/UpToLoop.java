package com.gtnhplanner.data.flowchart.balancer;

import java.util.List;
import java.util.UUID;

/**
 * A ring of Up to cards that blame each other (docs/design/up-to.md, the website's {@code UpToLoop}): old Build's dead
 * loop and clog lock, found over the read-out.
 *
 * @param kind  DEAD_LOOP: every member STARVED, each short of what the next makes; CLOG_LOCK: every member CLOGGED or
 *              UNUSED, each backed up into the next
 * @param nodes the members' {@link UpToReadout#node}, in pool order
 * @param ports each member's blaming ports ({@link UpToReadout#port}, {@link UpToReadout#tied}) that lie on the ring
 */
public record UpToLoop(Kind kind, List<UUID> nodes, List<PortRef> ports) {

    public enum Kind {
        DEAD_LOOP,
        CLOG_LOCK
    }

    public UpToLoop {
        nodes = List.copyOf(nodes);
        ports = List.copyOf(ports);
    }
}
