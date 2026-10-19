package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.machines.web.Web;

/**
 * A crusher card and an empty crop farm posted as the website's own: the crusher's recipe filed under kubatech's map,
 * named for its mob, its spawner the input that names the mob, its metadata the website's crusher maths read; the
 * farm as the website's empty crop farm, making nothing. (tools/dev/eec/plan-schema.local.test.ts holds a whole posted
 * plan of them against the website's schema.)
 */
class CrusherAndCropFarmExportTest {

    private static final JsonObject EEC = new com.google.gson.JsonParser().parse("""
        {"mob": "Zombie", "maxHealth": 20, "baseEut": 1920,
         "outputs": [{"drops": [{"amount": 1, "c0": 10000, "lootable": true}]}, {"xp": true}]}""")
        .getAsJsonObject();

    private static final class World implements PlanExport.World {

        @Override
        public PlanExport.Res port(final Port<?> port) {
            final String name = port.getDisplayName();
            return new PlanExport.Res(
                "item",
                "test:" + name.toLowerCase(Locale.ROOT)
                    .replace(' ', '_'),
                name);
        }

        @Override
        public PlanExport.Res resource(final String key) {
            return new PlanExport.Res("item", "test:" + key, key);
        }

        @Override
        public String machine(final Node node) {
            return "Extreme Entity Crusher";
        }

        @Override
        public double machines(final Node node) {
            return 3;
        }

        @Override
        public String tier(final Node node) {
            return "IV";
        }

        @Override
        public boolean multiblock(final Node node) {
            return !node.isPower();
        }

        @Override
        public Web.Recipe gregtech(final Node node) {
            if (node.isPower()) return null;
            final Web.Recipe r = new Web.Recipe();
            r.name = "Extreme Entity Crusher: Zombie";
            r.category = "kubatech-eec";
            r.machineType = "Extreme Entity Crusher";
            r.minimumTier = "EV";
            r.source = new Web.Source();
            r.source.recipeMap = "Extreme Entity Crusher";
            r.source.rawRecipeId = "Zombie";
            r.metadata = new JsonObject();
            r.metadata.addProperty("recipeMapId", "kubatech-eec");
            r.metadata.add("eec", EEC);
            return r;
        }
    }

    @org.junit.jupiter.api.BeforeAll
    static void profiles() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @Test
    void aCrusherPostsAsTheWebsitesCrusherCard() {
        final Graph g = new Graph("Crusher");
        final Node zombie = new Node(UUID.randomUUID(), 0, 0);
        zombie.outputs.add(TestIngredients.port("Rotten Flesh", 1));
        zombie.outputs.add(TestIngredients.port("Liquid XP", 120));
        zombie.machineConfig.settings.put("amp", 2);
        zombie.machineConfig.settings.put("machine:eecLooting", "3");
        zombie.machineConfig.settings.put("machine:eecWeaponDamage", "10");
        g.addNode(zombie);
        final JsonObject p = PlanExport.project(g, "Crusher", new World());
        final JsonObject recipe = p.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        assertEquals("Extreme Entity Crusher: Zombie", string(recipe, "name"));
        assertEquals("kubatech-eec", string(recipe, "category"));
        assertEquals("Extreme Entity Crusher", string(recipe, "machineType"));
        final JsonObject spawner = recipe.getAsJsonArray("inputs")
            .get(0)
            .getAsJsonObject();
        assertEquals("factoryflow:eec_mob:zombie", string(spawner, "id"));
        assertEquals("Zombie Spawner", string(spawner, "displayName"));
        assertFalse(
            spawner.get("consumed")
                .getAsBoolean());
        assertEquals("Zombie", string(recipe.getAsJsonObject("source"), "rawRecipeId"));
        assertEquals(
            EEC,
            recipe.getAsJsonObject("metadata")
                .get("eec"));
        final JsonObject node = p.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            Map.of("eecLooting", "3", "eecWeaponDamage", "10"),
            new com.google.gson.Gson().fromJson(node.get("machineConfigTiers"), Map.class));
        assertEquals("IV", string(node, "hatchVoltageTier"));
        assertEquals(
            2,
            node.get("hatchAmps")
                .getAsDouble());
    }

    @Test
    void anEmptyCropFarmPostsAsTheWebsitesOwn() {
        final Graph g = new Graph("Farm");
        // As the board has it (Node.power, which reads its ports from the game): no ports, no settings.
        final Node farm = new Node(UUID.randomUUID(), 40, 80);
        farm.powerSource = com.gtnhplanner.power.CropFarm.ID;
        farm.machineName = com.gtnhplanner.power.CropFarm.SOURCE.name();
        g.addNode(farm);
        final JsonObject p = PlanExport.project(g, "Farm", new World());
        final JsonObject recipe = p.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        assertEquals("Crop Farm", string(recipe, "name"));
        assertEquals("crop_produce", string(recipe, "kind"));
        assertEquals("cropsnh-crop", string(recipe, "category"));
        assertEquals("Crop Farm", string(recipe, "machineType"));
        assertEquals(
            0,
            recipe.getAsJsonArray("outputs")
                .size());
        assertFalse(recipe.has("power"), "a crop farm, not a generator");
        final JsonObject node = p.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject();
        assertEquals("NONE", string(node, "overclockTier"));
        // The website reads it back as the planner's empty crop farm.
        final com.gtnhplanner.importer.FfPlan plan = com.gtnhplanner.importer.FfPlanParser.parse(p.toString());
        assertTrue(
            plan.recipes()
                .get(0)
                .isEmptyCropFarm());
        assertEquals(
            List.of(),
            plan.recipes()
                .get(0)
                .outputs());
    }

    private static String string(final JsonObject o, final String key) {
        return o.get(key)
            .getAsString();
    }
}
