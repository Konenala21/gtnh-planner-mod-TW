package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.machines.MobDrops;

/**
 * A crusher card's numbers against the website's: the dev game's mobs as the website's pipeline built them
 * (eec/devgame-website.json.gz), at the settings of eec/rates-cases.json (weapon, Looting, the void switch, infernals
 * off, the ritual, hatches from EV 1 A to ZPM), through the recipe WebCards builds for the card and NodeMath, against
 * what the website's own getOverclockedRecipeStats and getMachineOutputMultiplier gave (eec/rates-website.json,
 * tools/dev/eec/eec-rates.local.test.ts): the tier, ticks a kill, EU/t, overclocks and every output's multiplier.
 */
class EecCardRatesTest {

    @Test
    void theWebsitesNumbers() {
        final Map<String, JsonObject> recipes = new LinkedHashMap<>();
        for (final JsonElement e : read("/eec/devgame-website.json.gz").getAsJsonArray()) recipes.put(
            e.getAsJsonObject()
                .get("mob")
                .getAsString(),
            e.getAsJsonObject());
        final JsonArray cases = read("/eec/rates-cases.json").getAsJsonArray();
        final JsonArray want = read("/eec/rates-website.json").getAsJsonArray();
        assertEquals(cases.size(), want.size());
        for (int i = 0; i < cases.size(); i++) {
            final JsonObject c = cases.get(i)
                .getAsJsonObject(),
                w = want.get(i)
                    .getAsJsonObject();
            final String mob = c.get("mob")
                .getAsString(), at = mob + " case " + i;
            final Web.Recipe recipe = recipe(recipes.get(mob));
            final Web.Node node = new Web.Node();
            node.overclockTier = c.get("voltage")
                .getAsString();
            node.hatchVoltageTier = node.overclockTier;
            node.hatchAmps = c.get("amps")
                .getAsDouble();
            node.powerInputMode = "amps";
            final Map<String, String> tiers = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> t : c.getAsJsonObject("tiers")
                .entrySet())
                tiers.put(
                    t.getKey(),
                    t.getValue()
                        .getAsString());
            node.machineConfigTiers = tiers;
            node.parallel = 1.0;
            node.machineCount = 1.0;
            final NodeMath.Result r = NodeMath.compute(recipe, node);
            assertEquals(
                w.get("tier")
                    .getAsString(),
                r.overclock()
                    .tier(),
                at);
            near(
                w.get("ticksPerKill")
                    .getAsDouble(),
                r.overclock()
                    .durationTicks(),
                at + " ticks");
            near(
                w.get("eut")
                    .getAsDouble(),
                r.overclock()
                    .eut(),
                at + " EU/t");
            assertEquals(
                w.get("overclocks")
                    .getAsInt(),
                r.overclock()
                    .overclockSteps(),
                at + " overclocks");
            final JsonArray mults = w.getAsJsonArray("multipliers");
            assertEquals(
                mults.size(),
                r.outputMultipliers()
                    .size(),
                at + " outputs");
            for (int k = 0; k < mults.size(); k++) near(
                mults.get(k)
                    .getAsDouble(),
                r.outputMultipliers()
                    .get(k),
                at + " output " + k);
        }
    }

    /** The website's recipe for a mob, as WebCards writes a crusher card's. */
    private static Web.Recipe recipe(final JsonObject built) {
        final Web.Recipe r = new Web.Recipe();
        r.id = "eec:" + built.get("mob")
            .getAsString();
        r.name = built.get("name")
            .getAsString();
        r.kind = "gregtech_machine";
        r.category = MobDrops.MAP_ID;
        r.machineType = MobDrops.MACHINE_TYPE;
        r.durationTicks = built.get("durationTicks")
            .getAsDouble();
        r.eut = built.get("eut")
            .getAsDouble();
        r.minimumTier = HandlerData.recipeMinimumTier(r.eut);
        r.specialValue = 0.0;
        r.source = new Web.Source();
        r.source.recipeMap = MobDrops.MACHINE_TYPE;
        r.metadata = new JsonObject();
        r.metadata.addProperty("recipeMapId", MobDrops.MAP_ID);
        r.metadata.add("eec", built.get("eec"));
        r.inputs = new ArrayList<>();
        final List<Web.Resource> outs = new ArrayList<>();
        for (final JsonElement e : built.getAsJsonArray("outputs")) {
            final JsonObject o = e.getAsJsonObject();
            final Web.Resource res = new Web.Resource();
            res.kind = o.get("kind")
                .getAsString();
            res.id = o.get("id")
                .getAsString();
            res.amount = o.get("amount")
                .getAsDouble();
            if (!o.get("chance")
                .isJsonNull())
                res.chance = o.get("chance")
                    .getAsDouble();
            outs.add(res);
        }
        r.outputs = outs;
        final HandlerData.MapEntry entry = HandlerData.map(MobDrops.MAP_ID);
        r.machineConfigControls = HandlerData.recipeControls(entry, 0);
        r.machineHandlers = HandlerData.handlers(entry, r.minimumTier, r.durationTicks, r.eut, r.machineConfigControls);
        return r;
    }

    private static void near(final double want, final double got, final String at) {
        assertEquals(want, got, 1e-9 * Math.max(1, Math.abs(want)), at);
    }

    private static JsonElement read(final String path) {
        try {
            InputStream in = EecCardRatesTest.class.getResourceAsStream(path);
            if (path.endsWith(".gz")) in = new GZIPInputStream(in);
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new JsonParser().parse(r);
            }
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
