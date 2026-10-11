package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * CropsNh against the website's own crop functions: every crop in its 2.9 dataset (179) on a spread of settings under
 * both harvesters, written by the website's passive-production.ts (growth, harvest time, drops, the harvester's
 * setup, machines, power, and the solver's multipliers as machine-effects.ts takes them).
 */
class CropsNhGoldensTest {

    private static final String FILE = "/crop-goldens.jsonl.gz";

    @Test
    void matchesTheWebsite() throws Exception {
        final InputStream raw = CropsNhGoldensTest.class.getResourceAsStream(FILE);
        assertNotNull(raw, FILE);
        int cases = 0;
        final List<String> mismatches = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.isEmpty()) continue;
                final JsonObject c = new JsonParser().parse(line)
                    .getAsJsonObject();
                cases++;
                check(c, mismatches);
            }
        }
        assertTrue(cases > 4000, "cases: " + cases);
        assertEquals(
            List.of(),
            mismatches.subList(0, Math.min(20, mismatches.size())),
            mismatches.size() + " mismatches");
    }

    private static void check(final JsonObject c, final List<String> mismatches) {
        final CropsNh.Stats stats = stats(c.getAsJsonObject("stats"));
        final Map<String, Object> settings = new HashMap<>();
        for (final Map.Entry<String, JsonElement> e : c.getAsJsonObject("tiers")
            .entrySet())
            settings.put(
                e.getKey(),
                e.getValue()
                    .getAsString());
        settings.put(
            CropsNh.HARVESTER,
            c.get("handler")
                .getAsString());
        final long crops = c.get("crops")
            .getAsLong();
        final JsonObject x = c.getAsJsonObject("expected");
        final String at = c.get("crop")
            .getAsString() + " "
            + c.get("handler")
                .getAsString()
            + " "
            + c.getAsJsonObject("tiers");

        final CropsNh.Setup setup = CropsNh.setup(settings, stats);
        final JsonObject s = x.getAsJsonObject("setup");
        same(
            mismatches,
            at,
            "setup.id",
            s.get("id")
                .getAsString(),
            setup.id());
        same(
            mismatches,
            at,
            "setup.tier",
            s.get("tierIndex")
                .getAsInt(),
            setup.tier());
        same(
            mismatches,
            at,
            "setup.growth",
            s.get("growthUnits")
                .getAsInt(),
            setup.growthUnits());
        same(
            mismatches,
            at,
            "setup.fert",
            s.get("fertilizerUnits")
                .getAsInt(),
            setup.fertilizerUnits());
        same(
            mismatches,
            at,
            "setup.harvest",
            s.get("harvestUnits")
                .getAsInt(),
            setup.harvestUnits());
        same(
            mismatches,
            at,
            "setup.env",
            s.get("environmentUnits")
                .getAsInt(),
            setup.environmentUnits());
        same(
            mismatches,
            at,
            "setup.oc",
            s.get("overclocks")
                .getAsInt(),
            setup.overclocks());
        same(
            mismatches,
            at,
            "setup.fed",
            s.get("fertilized")
                .getAsBoolean(),
            setup.fertilized());
        same(
            mismatches,
            at,
            "setup.subsoil",
            s.get("subSoil")
                .getAsBoolean(),
            setup.subSoil());

        final CropsNh.Environment env = CropsNh.harvesterEnvironment(setup, CropsNh.environment(settings));
        final JsonObject e = x.getAsJsonObject("envH");
        same(
            mismatches,
            at,
            "env.water",
            e.get("water")
                .getAsInt(),
            env.water());
        same(
            mismatches,
            at,
            "env.fert",
            e.get("fertilizer")
                .getAsInt(),
            env.fertilizer());
        same(
            mismatches,
            at,
            "env.biome",
            e.get("biomeBonus")
                .getAsInt(),
            env.biomeBonus());
        same(
            mismatches,
            at,
            "nutrient",
            x.get("nutrient")
                .getAsInt(),
            CropsNh.nutrientScore(env));
        same(
            mismatches,
            at,
            "rate",
            x.get("rate")
                .getAsInt(),
            CropsNh.growthRate(stats, env));
        close(mismatches, at, "ticks", num(x.get("ticks")), CropsNh.harvestTicks(stats, env));
        close(
            mismatches,
            at,
            "rounds",
            x.get("rounds")
                .getAsDouble(),
            CropsNh.harvestRoundMultiplier(setup));
        close(
            mismatches,
            at,
            "speed",
            x.get("speed")
                .getAsDouble(),
            CropsNh.growthSpeedMultiplier(setup));
        same(
            mismatches,
            at,
            "perMachine",
            x.get("perMachine")
                .getAsInt(),
            CropsNh.cropsPerMachine(setup));
        same(
            mismatches,
            at,
            "machines",
            x.get("machines")
                .getAsInt(),
            CropsNh.machineCount(setup, crops));
        same(
            mismatches,
            at,
            "slots",
            x.get("slots")
                .getAsInt(),
            CropsNh.upgradeSlots(setup.tier()));
        same(
            mismatches,
            at,
            "used",
            x.get("used")
                .getAsInt(),
            CropsNh.unitSlotsUsed(setup));
        close(
            mismatches,
            at,
            "eutPerCrop",
            x.get("eutPerCrop")
                .getAsDouble(),
            CropsNh.eutPerCrop(setup));
        close(
            mismatches,
            at,
            "farmEut",
            x.get("farmEut")
                .getAsDouble(),
            CropsNh.farmEut(setup));
        close(
            mismatches,
            at,
            "managerEu",
            x.get("managerEu")
                .getAsDouble(),
            CropsNh.managerEuPerHarvest(setup));
        close(
            mismatches,
            at,
            "duration",
            x.get("durationMultiplier")
                .getAsDouble(),
            CropsNh.durationMultiplier(stats, settings));
        close(
            mismatches,
            at,
            "draw",
            x.get("draw")
                .getAsDouble(),
            CropsNh.drawEut(stats, settings, crops));
        final JsonArray outs = x.getAsJsonArray("outputMultipliers");
        for (int i = 0; i < outs.size(); i++) close(
            mismatches,
            at,
            "output " + i,
            outs.get(i)
                .getAsDouble(),
            CropsNh.outputMultiplier(
                stats,
                settings,
                stats.drops()
                    .get(i)
                    .id()));
        // The formulas run on every case without failing, and end on the power line.
        final List<FormulaLine> lines = CropsNh.formulas(stats, settings, crops);
        if (lines.isEmpty() || !"power".equals(
            lines.get(lines.size() - 1)
                .label()))
            mismatches.add(at + ": formulas " + lines);
    }

    private static CropsNh.Stats stats(final JsonObject o) {
        final List<CropsNh.Drop> drops = new ArrayList<>();
        for (final JsonElement d : o.getAsJsonArray("drops")) {
            final JsonObject dd = d.getAsJsonObject();
            drops.add(
                new CropsNh.Drop(
                    dd.get("id")
                        .getAsString(),
                    dd.get("stackSize")
                        .getAsInt(),
                    dd.get("weight")
                        .getAsInt()));
        }
        return new CropsNh.Stats(
            o.get("tier")
                .getAsInt(),
            o.get("growthPoints")
                .getAsInt(),
            o.get("dropChance")
                .getAsDouble(),
            o.get("growthCycleTicks")
                .getAsInt(),
            o.get("growthMultiplier")
                .getAsDouble(),
            o.get("machineOnly")
                .getAsBoolean(),
            o.get("minSeedBedTier")
                .getAsInt(),
            o.get("subSoil")
                .isJsonNull() ? null
                    : o.get("subSoil")
                        .getAsString(),
            drops);
    }

    private static double num(final JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive()
            .isString() ? Double.parseDouble(v.getAsString()) : v.getAsDouble();
    }

    private static void same(final List<String> out, final String at, final String what, final Object want,
        final Object got) {
        if (!want.equals(got)) out.add(at + ": " + what + " website " + want + ", mod " + got);
    }

    private static void close(final List<String> out, final String at, final String what, final double want,
        final double got) {
        if (Double.isInfinite(want) && Double.isInfinite(got)) return;
        if (Math.abs(want - got) > 1e-9 * Math.max(1, Math.abs(want)))
            out.add(at + ": " + what + " website " + want + ", mod " + got);
    }
}
