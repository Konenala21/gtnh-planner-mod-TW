package com.gtnhplanner.data.flowchart;

import com.gtnhplanner.power.CustomRate;

/**
 * A card's machine count rule (docs/design/up-to.md, the website's pin-rule.ts): Any (the plan picks), Up to N (what
 * you have, the default for a set count) or Exactly N. Kept as {@link Node#isMachineCountFixed()} (a count is set) and
 * {@link Node#isMachineCountExact()} (Exactly), and on a shared machine as {@link MachineGroup#isPinned()} and
 * {@link MachineGroup#isExact()}. The board and the solver input read the rule here, never the flags, so they agree.
 */
public enum PinRule {

    ANY("Any"),
    UP_TO("Up to"),
    EXACT("Exactly");

    /** The words the board shows for it. */
    public final String label;

    PinRule(final String label) {
        this.label = label;
    }

    /** The next rule a click on the rule word steps to: Any, Up to, Exactly, then Any again. */
    public PinRule next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * A card's rule. A custom rate card's set count is always Exactly (its dial); a fluid tank's is always Any. A
     * recipe of a shared machine has no count of its own: its machine's is {@link #of(MachineGroup)}.
     */
    public static PinRule of(final Node node) {
        if (!node.isMachineCountFixed()) return ANY;
        if (CustomRate.is(node)) return EXACT;
        return node.isMachineCountExact() ? EXACT : UP_TO;
    }

    /** A shared machine's rule. A capacity nobody set (not pinned) is a plain cap, which is Any here. */
    public static PinRule of(final MachineGroup group) {
        if (!group.isPinned() || group.getMachineCapacity() <= 0) return ANY;
        return group.isExact() ? EXACT : UP_TO;
    }

    /** Sets a card's rule and keeps its count: Any lets go of it, Up to and Exactly set it. */
    public static void set(final Node node, final PinRule rule) {
        node.setMachineCountFixed(rule != ANY);
        node.setMachineCountExact(rule == EXACT);
    }

    /**
     * Sets a card's rule at a count, the website's withPinRule: Any lets go of the count, Up to and Exactly set it (to
     * whole machines, at least one). A count that is not a positive number means Any.
     */
    public static void set(final Node node, final PinRule rule, final double count) {
        if (rule == ANY || !(count > 0) || Double.isInfinite(count)) {
            set(node, ANY);
            return;
        }
        node.machineConfig.setMachineCount(machines(count));
        set(node, rule);
    }

    /** Sets a shared machine's rule and keeps its capacity. */
    public static void set(final MachineGroup group, final PinRule rule) {
        group.setPinned(rule != ANY);
        group.setExact(rule == EXACT);
    }

    /**
     * Sets a shared machine's rule at a count: Any lets go of it (no capacity), Up to and Exactly set it. A count that
     * is not a positive number means Any.
     */
    public static void set(final MachineGroup group, final PinRule rule, final double count) {
        if (rule == ANY || !(count > 0) || Double.isInfinite(count)) {
            set(group, ANY);
            group.setMachineCapacity(0);
            return;
        }
        group.setMachineCapacity(machines(count));
        set(group, rule);
    }

    private static int machines(final double count) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(count)));
    }
}
