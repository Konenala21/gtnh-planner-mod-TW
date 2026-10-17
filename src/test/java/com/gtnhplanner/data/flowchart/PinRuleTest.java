package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.FluidTank;

/** A card's count rule (docs/design/up-to.md): Any, Up to or Exactly, read and set in one place, and saved. */
class PinRuleTest {

    private static Node card() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        return new Node(UUID.randomUUID(), 0, 0);
    }

    @Test
    void aSetCountIsUpToUnlessItSaysExactly() {
        final Node n = card();
        assertEquals(PinRule.ANY, PinRule.of(n));
        n.setMachineCountFixed(true);
        assertEquals(PinRule.UP_TO, PinRule.of(n), "every count set before the rule");
        n.setMachineCountExact(true);
        assertEquals(PinRule.EXACT, PinRule.of(n));
        // Unsetting the count lets go of Exactly: set again, it is Up to.
        n.setMachineCountFixed(false);
        assertEquals(PinRule.ANY, PinRule.of(n));
        assertFalse(n.isMachineCountExact());
        n.setMachineCountFixed(true);
        assertEquals(PinRule.UP_TO, PinRule.of(n));
        // Exactly with no count set is no count.
        final Node loose = card();
        loose.setMachineCountExact(true);
        assertEquals(PinRule.ANY, PinRule.of(loose));
        assertFalse(loose.isMachineCountExact());
    }

    @Test
    void aCustomRateCardIsExactlyAndATankNeverSet() {
        final Node rate = card();
        rate.powerSource = CustomRate.ID;
        assertEquals(PinRule.ANY, PinRule.of(rate), "unpinned, the plan scales it");
        rate.setMachineCountFixed(true);
        assertEquals(PinRule.EXACT, PinRule.of(rate), "its dial");
        assertFalse(rate.isMachineCountExact(), "the flag is the card's own; the rule says Exactly");

        final Node tank = card();
        tank.powerSource = FluidTank.ID;
        PinRule.set(tank, PinRule.EXACT, 3);
        assertEquals(PinRule.ANY, PinRule.of(tank));
        assertFalse(tank.isMachineCountFixed());
        assertFalse(tank.isMachineCountExact());
    }

    @Test
    void settingARuleSetsTheCountAsTheWebsiteDoes() {
        final Node n = card();
        PinRule.set(n, PinRule.EXACT, 3.4);
        assertEquals(PinRule.EXACT, PinRule.of(n));
        assertEquals(3, n.machineConfig.getMachineCount());
        PinRule.set(n, PinRule.UP_TO, 0.2);
        assertEquals(PinRule.UP_TO, PinRule.of(n));
        assertEquals(1, n.machineConfig.getMachineCount(), "at least one machine");
        PinRule.set(n, PinRule.EXACT, 0);
        assertEquals(PinRule.ANY, PinRule.of(n), "no count is Any");
        PinRule.set(n, PinRule.UP_TO, Double.NaN);
        assertEquals(PinRule.ANY, PinRule.of(n));
        n.machineConfig.setMachineCount(5);
        PinRule.set(n, PinRule.EXACT);
        assertEquals(PinRule.EXACT, PinRule.of(n));
        assertEquals(5, n.machineConfig.getMachineCount(), "the count is kept");
        PinRule.set(n, PinRule.ANY);
        assertEquals(PinRule.ANY, PinRule.of(n));

        assertEquals(
            List.of(PinRule.UP_TO, PinRule.EXACT, PinRule.ANY),
            List.of(PinRule.ANY.next(), PinRule.UP_TO.next(), PinRule.EXACT.next()));
        assertEquals(
            List.of("Any", "Up to", "Exactly"),
            List.of(PinRule.ANY.label, PinRule.UP_TO.label, PinRule.EXACT.label));
    }

    @Test
    void aSharedMachineHasTheSameRule() {
        final MachineGroup g = new MachineGroup();
        g.setMachineCapacity(4);
        assertEquals(PinRule.ANY, PinRule.of(g), "a capacity nobody set is a plain cap");
        g.setPinned(true);
        assertEquals(PinRule.UP_TO, PinRule.of(g));
        g.setExact(true);
        assertEquals(PinRule.EXACT, PinRule.of(g));
        g.setPinned(false);
        assertFalse(g.isExact(), "unpinned lets go of Exactly");
        g.setPinned(true);
        assertEquals(PinRule.UP_TO, PinRule.of(g));

        PinRule.set(g, PinRule.EXACT, 6);
        assertEquals(PinRule.EXACT, PinRule.of(g));
        assertEquals(6, g.getMachineCapacity());
        PinRule.set(g, PinRule.ANY, 6);
        assertEquals(PinRule.ANY, PinRule.of(g));
        assertEquals(0, g.getMachineCapacity());
    }

    // region Saves

    @Test
    void anOldSaveReadsUpToAndExactlySurvivesASave() {
        final GtnhFlowLoader.LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Node upTo = chart.machine(0), exact = chart.machine(1), any = chart.machine(2);
        upTo.setMachineCountFixed(true);
        exact.setMachineCountFixed(true);
        exact.setMachineCountExact(true);
        any.setMachineCountFixed(false);

        // A set count without the flag is how every save before the rule spells a pin.
        final JsonObject old = Serializer.nodeToJson(upTo);
        assertTrue(old.has("machineCountFixed"));
        assertFalse(old.has("machineCountExact"), "only Exactly is written");
        assertTrue(
            Serializer.nodeToJson(exact)
                .get("machineCountExact")
                .getAsBoolean());

        final Graph back = Serializer.decode(Serializer.encode(chart.graph()));
        assertEquals(PinRule.UP_TO, PinRule.of(back.nodes.get(upTo.id)));
        assertEquals(PinRule.EXACT, PinRule.of(back.nodes.get(exact.id)));
        assertEquals(PinRule.ANY, PinRule.of(back.nodes.get(any.id)));
        assertEquals(
            PinRule.UP_TO,
            PinRule.of(Serializer.jsonToNode(old)),
            "a card on its own, as the cloud reads one");
    }

    @Test
    void aSharedMachinesRuleSurvivesASaveAndAnOldOneIsUpTo() {
        final GtnhFlowLoader.LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph g = chart.graph();
        final MachineGroup shared = new MachineGroup();
        shared.addSection(chart.machine(0).id);
        shared.addSection(chart.machine(1).id);
        PinRule.set(shared, PinRule.UP_TO, 3);
        g.groups.put(shared.getId(), shared);

        // Nothing new in the save of a plan with no Exactly: it reads and writes as before the rule.
        final String upTo = Serializer.GSON.toJson(Serializer.GSON.toJsonTree(shared));
        assertFalse(upTo.contains("\"exact\""), upTo);
        final MachineGroup readUpTo = (MachineGroup) Serializer.decode(Serializer.encode(g)).groups.get(shared.getId());
        assertEquals(PinRule.UP_TO, PinRule.of(readUpTo));
        assertEquals(3, readUpTo.getMachineCapacity());

        PinRule.set(shared, PinRule.EXACT);
        final MachineGroup readExact = (MachineGroup) Serializer.decode(Serializer.encode(g)).groups
            .get(shared.getId());
        assertEquals(PinRule.EXACT, PinRule.of(readExact));
    }

    @Test
    void undoPutsTheRuleBack() {
        final UndoHistories histories = new UndoHistories();
        final Graph start = GtnhFlowLoader.load("light_fuel")
            .graph();
        final Node card = start.getNodes()
            .iterator()
            .next();
        PinRule.set(card, PinRule.EXACT, 2);
        final UndoHistory history = histories.of(start);
        final String before = history.beginEdit(start);
        PinRule.set(card, PinRule.UP_TO);
        assertTrue(history.commitEdit(before, start), "Exactly to Up to is an edit");

        final Graph undone = history.undo(start);
        assertEquals(PinRule.EXACT, PinRule.of(undone.nodes.get(card.id)));
        final Graph redone = histories.of(undone)
            .redo(undone);
        assertEquals(PinRule.UP_TO, PinRule.of(redone.nodes.get(card.id)));
    }

    // endregion
}
