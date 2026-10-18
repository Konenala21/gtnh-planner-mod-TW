package com.gtnhplanner.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.provider.FreeInputs;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.FfPlanParser;

/**
 * The Rock Breaker's "IT'S FREE! Place Lava on Side" (the website's free-input.ts): GregTech gives it a stack of 1, yet
 * nothing is ever fed in. The card keeps it as a port of amount 0, which nothing asks for.
 */
class FreeInputsTest {

    private static final String LAVA = "gregtech:gt.metaitem.02@32765";

    @Test
    void theLavaPlaceholderIsTheOneFreeItem() {
        assertTrue(FreeInputs.isFreeId(LAVA));
        assertFalse(FreeInputs.isFreeId("gregtech:gt.metaitem.02"));
        assertFalse(FreeInputs.isFreeId("gregtech:gt.metaitem.01@32765"));
        assertFalse(FreeInputs.isFreeId("minecraft:cobblestone"));
        assertFalse(FreeInputs.isFreeId(null));
    }

    @Test
    void aPlansRockBreakerNeverSuppliesTheLava() {
        // The website's export leaves the slot "consumed": the free rule holds whatever the plan says.
        final FfPlan plan = FfPlanParser.parse(
            "{\"name\":\"Cobble\",\"recipes\":[{\"id\":\"rb\",\"machineType\":\"Rock Breaker\",\"durationTicks\":16,"
                + "\"eut\":30,\"inputs\":[{\"kind\":\"item\",\"id\":\"minecraft:cobblestone\",\"amount\":1,"
                + "\"consumed\":false},{\"kind\":\"item\",\"id\":\""
                + LAVA
                + "\",\"amount\":1,\"displayName\":\"IT'S FREE! Place Lava on Side\"}],\"outputs\":[{\"kind\":"
                + "\"item\",\"id\":\"minecraft:cobblestone\",\"amount\":1}]}],\"nodes\":[]}");
        final List<FfPlan.FfSlot> inputs = plan.recipes()
            .getFirst()
            .inputs();
        assertFalse(
            inputs.get(1)
                .consumed(),
            "the lava is set down beside the machine");
    }

    @Test
    void anInputOfAmountZeroIsNeverAskedFor() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Node pinned = chart.machine(1);
        final BalanceResult before = Balancer.balance(chart.graph(), BalanceMode.AUTO);
        // What GTProvider makes of the lava: a port kept on the card with nothing used up.
        pinned.inputs.add(
            new com.gtnhplanner.data.flowchart.Port<>(
                TestIngredients.TEST,
                new TestIngredients.TestIngredient("lava placeholder", 0),
                1f));
        final BalanceResult after = Balancer.balance(chart.graph(), BalanceMode.AUTO);
        final int lava = pinned.inputs.size() - 1;
        assertEquals(
            0,
            after.nodeBalances()
                .get(pinned.id)
                .inputPerSecond(lava),
            1e-9);
        for (final Node machine : chart.machines()) assertEquals(
            before.nodeBalances()
                .get(machine.id)
                .operations(),
            after.nodeBalances()
                .get(machine.id)
                .operations(),
            1e-9,
            machine.machineName);
    }
}
