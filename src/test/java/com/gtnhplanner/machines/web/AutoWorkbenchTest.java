package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A crafting-grid recipe on GT++'s Auto Workbench, as the website's overclock.test.ts has it: 2048 EU a craft, so 64
 * ticks at 32 EU/t at LV, a perfect step each tier, one craft a tick from EV. The crafting table stays the second
 * choice, a tick at no EU. The recipe is the one machines/game/WebCards builds for a crafting card.
 */
class AutoWorkbenchTest {

    private static Web.Recipe crafting(final String type) {
        final Web.Recipe r = new Web.Recipe();
        r.id = "crafting:test";
        r.name = "test";
        r.kind = "gregtech_machine";
        r.category = "crafting";
        r.machineType = type;
        r.durationTicks = 1;
        r.eut = 0;
        r.minimumTier = "NONE";
        r.specialValue = 0.0;
        r.source = new Web.Source();
        r.source.recipeMap = type;
        r.inputs = List.of();
        r.outputs = List.of();
        return r;
    }

    private static Web.Node on(final String handlerId, final String tier) {
        final Web.Node n = new Web.Node();
        n.overclockTier = tier;
        n.hatchVoltageTier = tier;
        n.hatchAmps = 1.0;
        n.powerInputMode = "amps";
        n.machineHandlerId = handlerId;
        n.parallel = 1.0;
        n.machineCount = 1.0;
        return n;
    }

    @Test
    void theAutoWorkbenchComesFirstAndTheCraftingTableSecond() {
        for (final String type : new String[] { "Shaped Crafting", "Shapeless Crafting" }) {
            final List<Web.Handler> handlers = RecipeRules.machineHandlers(crafting(type));
            assertEquals(2, handlers.size(), type);
            assertEquals(RecipeRules.AUTO_WORKBENCH_HANDLER_ID, handlers.get(0).id);
            assertEquals("Auto Workbench", handlers.get(0).label);
            assertEquals(type, handlers.get(1).label);
        }
    }

    @Test
    void twoThousandFortyEightEuACraftOnePerTickFromEv() {
        final Object[][] ladder = { { "LV", 64, 32 }, { "MV", 16, 128 }, { "HV", 4, 512 }, { "EV", 1, 2048 },
            { "IV", 1, 2048 }, { "UV", 1, 2048 } };
        for (final Object[] step : ladder) {
            final String tier = (String) step[0];
            final Overclock.Stats stats = Overclock
                .stats(crafting("Shaped Crafting"), on(RecipeRules.AUTO_WORKBENCH_HANDLER_ID, tier));
            assertEquals(((Integer) step[1]).doubleValue(), stats.durationTicks(), 1e-9, tier);
            assertEquals(((Integer) step[2]).doubleValue(), stats.eut(), 1e-9, tier);
        }
    }

    @Test
    void theCraftingTableIsATickAtNoEu() {
        final Web.Recipe recipe = crafting("Shaped Crafting");
        final String table = RecipeRules.machineHandlers(recipe)
            .get(1).id;
        final NodeMath.Result r = NodeMath.compute(recipe, on(table, null));
        assertEquals(
            1,
            r.overclock()
                .durationTicks(),
            1e-9);
        assertEquals(0, r.euT(), 1e-9);
    }
}
