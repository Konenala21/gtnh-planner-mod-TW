package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.FfPlan.FfRecipe;

/**
 * The website's Extreme Entity Crusher cards and its empty crop farm, from a plan it would write
 * (factory-flow/crusher-and-empty-crop-farm.json: two of its dataset's crusher recipes, one slimmed to its spawner,
 * and its crop farm placeholder): a crusher is found by its mob and carries its settings, hatches and pin; an empty
 * farm is the planner's own empty crop farm.
 */
class CrusherAndCropFarmImportTest {

    private static final String PLAN = "crusher-and-empty-crop-farm.json";

    @Test
    void theRecipesSayWhatTheyAre() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture(PLAN));
        final Map<String, FfRecipe> recipes = plan.recipesById();
        final FfRecipe zombie = recipes.get("oracle:local-2.9.0-beta-2:kubatech-eec:zombie");
        final FfRecipe blaze = recipes.get("oracle:local-2.9.0-beta-2:kubatech-eec:blaze");
        final FfRecipe farm = recipes.get(FfRecipe.EMPTY_CROP_FARM);
        assertTrue(zombie.isEec());
        assertEquals("zombie", zombie.eecSlug());
        assertFalse(zombie.isEmptyCropFarm());
        // No raw recipe id: its spawner names the mob.
        assertNull(blaze.rawRecipeId());
        assertEquals("blaze", blaze.eecSlug());
        assertTrue(farm.isEmptyCropFarm());
        assertTrue(farm.isCrop());
        assertNull(farm.cropSlug());
        assertFalse(farm.isEec());
        // Nothing else is either.
        final FfRecipe furnace = new FfRecipe(
            "x",
            "Iron Ingot",
            "gregtech_machine",
            "furnace",
            "Furnace",
            "NONE",
            200,
            0,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null);
        assertFalse(furnace.isEec());
        assertFalse(furnace.isEmptyCropFarm());
    }

    @Test
    void crushersComeInByTheirMobWithTheirSettings() {
        final FakeGame game = new FakeGame();
        game.routed = true;
        game.generators = true;
        final FfConverter.Result result = FfImport.importFromText(FakeGame.fixture(PLAN), game, game);
        final Graph g = result.graph();
        assertEquals(
            3,
            g.nodes.size(),
            result.report()
                .toString());
        assertEquals("eec", game.paths.get("oracle:local-2.9.0-beta-2:kubatech-eec:zombie"));
        assertEquals("eec", game.paths.get("oracle:local-2.9.0-beta-2:kubatech-eec:blaze"));

        final Node zombie = card(g, "rotten_flesh"), blaze = card(g, "blaze_rod");
        final Map<String, Object> z = zombie.machineConfig.settings;
        assertEquals("10", z.get("machine:eecWeaponDamage"));
        assertEquals("3", z.get("machine:eecLooting"));
        assertEquals(true, z.get("gt_multiblock"), "a multiblock, though the website's recipe lists no machine");
        assertEquals("IV", z.get("voltage"));
        assertEquals(2, z.get("amp"));
        assertTrue(zombie.isMachineCountFixed());
        assertFalse(zombie.isMachineCountExact());
        assertEquals(2, zombie.machineConfig.getMachineCount());

        final Map<String, Object> b = blaze.machineConfig.settings;
        assertEquals("off", b.get("machine:eecInfernal"));
        assertEquals("LuV", b.get("voltage"));
        assertEquals(4, b.get("amp"));
        assertTrue(blaze.isMachineCountExact());
    }

    @Test
    void anEmptyCropFarmIsOne() {
        final FakeGame game = new FakeGame();
        game.routed = true;
        game.generators = true;
        final Graph g = FfImport.importFromText(FakeGame.fixture(PLAN), game, game)
            .graph();
        final Node farm = g.nodes.values()
            .stream()
            .filter(n -> com.gtnhplanner.power.CropFarm.ID.equals(n.powerSource))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no empty crop farm on the board"));
        assertTrue(farm.inputs.isEmpty() && farm.outputs.isEmpty());
        assertFalse(farm.isMachineCountFixed());
        assertEquals(800, farm.x - card(g, "rotten_flesh").x, "placed where the plan puts it");
    }

    /** The card making an item (by the end of its id). */
    private static Node card(final Graph g, final String item) {
        for (final Node n : g.nodes.values()) for (final com.gtnhplanner.data.flowchart.Port<?> p : n.outputs)
            if (com.gtnhplanner.harness.TestIngredients.nameOf(p)
                .endsWith(item)) return n;
        throw new AssertionError("no card makes " + item);
    }
}
