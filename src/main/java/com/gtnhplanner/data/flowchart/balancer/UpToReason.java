package com.gtnhplanner.data.flowchart.balancer;

/**
 * Why an Up to card runs where it does (docs/design/up-to.md, the website's {@code UpToReason}).
 */
public enum UpToReason {
    /** At its count; nothing else in the plan would use more of it. */
    FULL,
    /** At its count, and the rest of the plan would use more ({@link UpToReadout#wants()}). */
    BOTTLENECK,
    /** Below its count because an input runs short ({@link UpToReadout#port()}). */
    STARVED,
    /** Below its count because an output has nowhere to go ({@link UpToReadout#port()}). */
    CLOGGED,
    /** At zero because nothing takes what it makes ({@link UpToReadout#port()}). */
    UNUSED,
    /** Below its count for a reason no single port explains (another rule of the plan). */
    LIMITED
}
