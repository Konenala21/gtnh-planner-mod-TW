package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.gtnhplanner.machines.web.HandlerData;

/**
 * The crusher's mobs as the website's dataset builds them: each case is a raw export (the website exporter's shape,
 * src/test/resources/eec/*-raw.json) and what the website's own pipeline made of it (*-website.json, written by
 * tools/dev/eec/website-eec.mjs), and machines/MobDrops must make the same outputs and metadata. The fixture is the
 * website's own test of its pipeline; the dev game's mobs are every mob kubatech lists there (vanilla's, EnderIO's...).
 */
class MobDropsTest {

    @Test
    void theWebsitesFixture() {
        final int checked = compare("fixture");
        assertEquals(3, checked);
    }

    @Test
    void theDevGamesMobs() {
        if (MobDropsTest.class.getResourceAsStream("/eec/devgame-raw.json.gz") == null) return;
        assertTrue(compare("devgame") > 20);
    }

    @Test
    void gatesAndCuts() {
        final MobDrops.Machine machine = new MobDrops.Machine(55.0, 9.0, 4.0, 0.1, Map.of());
        final MobDrops.Drop plain = drop("a", 10000, false, List.of());
        assertEquals(10000, MobDrops.chance(plain, 0, 0.1));
        // A player-only drop comes out at a tenth, never under one.
        assertEquals(500, MobDrops.chance(drop("b", 5000, true, List.of()), 0, 0.1));
        assertEquals(1, MobDrops.chance(drop("c", 3, true, List.of()), 0, 0.1));
        // An enchantment it needs, or a class this has no rule for, gates it.
        assertEquals(
            0,
            MobDrops.chance(
                drop(
                    "d",
                    5000,
                    false,
                    List.of(new MobDrops.Modifier("IChanceModifier$DropsOnlyWithEnchant", Map.of()))),
                0,
                0.1));
        assertEquals(
            0,
            MobDrops.chance(drop("e", 5000, false, List.of(new MobDrops.Modifier("Mystery$Gate", Map.of()))), 0, 0.1));
        // Looting's step, as a number from the game or {id} from the website's export.
        final MobDrops.Modifier greed = new MobDrops.Modifier(
            "IChanceModifier$EachLevelOfGives",
            Map.of("enchantment", 21, "change", 5.0));
        assertEquals(0, MobDrops.chance(drop("f", 0, false, List.of(greed)), 0, 0.1));
        assertEquals(500, MobDrops.chance(drop("f", 0, false, List.of(greed)), 1, 0.1));
        // The Overworld only: another dimension's drop is gated.
        final MobDrops.Modifier nether = new MobDrops.Modifier(
            "IChanceModifier$DropsOnlyInDimension",
            Map.of("dimension", -1));
        assertEquals(0, MobDrops.chance(drop("g", 5000, false, List.of(nether)), 0, 0.1));

        final MobDrops.Recipe r = MobDrops.recipe(
            new MobDrops.Mob(
                "Blaze",
                "Blaze",
                null,
                20,
                1920,
                55,
                true,
                true,
                false,
                List.of(plain, drop("d", 0, false, List.of(nether)))),
            machine);
        assertEquals(15360, r.eut());
        assertEquals(List.of("d"), r.gated());
        assertEquals(
            2,
            r.outputs()
                .size());
        assertTrue(
            r.outputs()
                .get(1)
                .xp());
        assertTrue(
            r.eec()
                .get("alwaysInfernal")
                .getAsBoolean());
        assertEquals("LuV", HandlerData.recipeMinimumTier(r.eut()));
    }

    @Test
    void slugsAsTheWebsiteDoes() {
        assertEquals("thaumcraft-brainyzombie", MobDrops.slug("Thaumcraft.BrainyZombie"));
        assertEquals("twilightforest-block-chain-goblin", MobDrops.slug("TwilightForest.Block&Chain Goblin"));
        assertEquals("zombie", MobDrops.slug("Zombie"));
    }

    private static MobDrops.Drop drop(final String id, final int chance, final boolean playerOnly,
        final List<MobDrops.Modifier> modifiers) {
        return new MobDrops.Drop(id, id, 1, chance, false, playerOnly, false, modifiers);
    }

    /** Builds every mob of {@code <name>-raw.json} and holds it against {@code <name>-website.json}; the count. */
    private static int compare(final String name) {
        final JsonObject raw = read("/eec/" + name + "-raw.json").getAsJsonObject();
        final JsonArray website = read("/eec/" + name + "-website.json").getAsJsonArray();
        final JsonObject domain = raw.getAsJsonArray("domains")
            .get(0)
            .getAsJsonObject();
        final MobDrops.Machine machine = machine(domain);
        final Map<String, JsonObject> expected = new LinkedHashMap<>();
        for (final JsonElement e : website) expected.put(
            e.getAsJsonObject()
                .get("mob")
                .getAsString(),
            e.getAsJsonObject());
        int checked = 0;
        for (final JsonElement e : domain.getAsJsonArray("mobs")) {
            final MobDrops.Mob mob = mob(e.getAsJsonObject());
            final JsonObject want = expected.get(mob.key());
            assertNotNull(want, mob.key() + " is missing from the website's recipes");
            final MobDrops.Recipe got = MobDrops.recipe(mob, machine);
            assertEquals(
                want.get("eut")
                    .getAsInt(),
                got.eut(),
                mob.key() + " eut");
            assertEquals(
                want.get("durationTicks")
                    .getAsInt(),
                got.duration(),
                mob.key() + " ticks");
            assertEquals(
                want.get("minimumTier")
                    .getAsString(),
                HandlerData.recipeMinimumTier(got.eut()),
                mob.key() + " tier");
            final JsonArray outs = want.getAsJsonArray("outputs");
            assertEquals(
                outs.size(),
                got.outputs()
                    .size(),
                mob.key() + " outputs");
            for (int i = 0; i < outs.size(); i++) {
                final JsonObject o = outs.get(i)
                    .getAsJsonObject();
                final MobDrops.Output out = got.outputs()
                    .get(i);
                final String at = mob.key() + " output " + i;
                assertEquals(
                    o.get("id")
                        .getAsString(),
                    out.id(),
                    at);
                assertEquals(
                    o.get("amount")
                        .getAsDouble(),
                    out.amount(),
                    1e-12,
                    at + " amount");
                if (o.get("chance")
                    .isJsonNull()) assertEquals(null, out.chance(), at + " chance");
                else assertEquals(
                    o.get("chance")
                        .getAsDouble(),
                    out.chance(),
                    1e-12,
                    at + " chance");
            }
            assertEquals(want.get("eec"), got.eec(), mob.key() + " metadata");
            final String notes = want.get("notes")
                .isJsonNull() ? ""
                    : want.get("notes")
                        .getAsString();
            for (final String gated : got.gated()) assertTrue(notes.contains(gated), mob.key() + " gates " + gated);
            if (got.gated()
                .isEmpty()) assertFalse(notes.contains("Never drops"), mob.key() + " gates nothing");
            checked++;
        }
        assertEquals(expected.size(), checked, "every recipe the website made has its mob");
        return checked;
    }

    private static MobDrops.Machine machine(final JsonObject domain) {
        final JsonObject m = domain.has("machine") ? domain.getAsJsonObject("machine") : new JsonObject();
        final Map<String, Double> infernal = new LinkedHashMap<>();
        if (domain.has("infernal")) for (final Map.Entry<String, JsonElement> e : domain.getAsJsonObject("infernal")
            .entrySet())
            if (e.getValue()
                .isJsonPrimitive()
                && e.getValue()
                    .getAsJsonPrimitive()
                    .isNumber())
                infernal.put(
                    e.getKey(),
                    e.getValue()
                        .getAsDouble());
        return new MobDrops.Machine(
            number(m, "mobSpawnInterval"),
            number(m, "diamondSpikesDamage"),
            number(m, "maxLootingLevel"),
            number(m, "playerOnlyDropsModifier"),
            infernal);
    }

    private static MobDrops.Mob mob(final JsonObject o) {
        final List<MobDrops.Drop> drops = new ArrayList<>();
        for (final JsonElement e : o.getAsJsonArray("drops")) {
            final JsonObject d = e.getAsJsonObject();
            final JsonObject res = d.getAsJsonObject("resource");
            final List<MobDrops.Modifier> modifiers = new ArrayList<>();
            if (d.has("chanceModifiers")) for (final JsonElement me : d.getAsJsonArray("chanceModifiers")) {
                final JsonObject mo = me.getAsJsonObject();
                final String className = mo.get("className")
                    .getAsString();
                final Map<String, Object> fields = new LinkedHashMap<>();
                if (mo.has("fields")) for (final Map.Entry<String, JsonElement> f : mo.getAsJsonObject("fields")
                    .entrySet()) fields.put(f.getKey(), plain(f.getValue()));
                modifiers.add(new MobDrops.Modifier(className.substring(className.lastIndexOf('.') + 1), fields));
            }
            drops.add(
                new MobDrops.Drop(
                    res.get("id")
                        .getAsString(),
                    res.get("displayName")
                        .getAsString(),
                    res.has("amount") ? res.get("amount")
                        .getAsInt() : 1,
                    d.has("chance") ? d.get("chance")
                        .getAsInt() : 0,
                    bool(d, "lootable"),
                    bool(d, "playerOnly"),
                    d.has("damages") || d.has("enchantable") && !d.get("enchantable")
                        .isJsonNull(),
                    modifiers));
        }
        final Double health = number(o, "maxEntityHealth");
        final Double eut = number(o, "eut"), ticks = number(o, "durationTicks");
        return new MobDrops.Mob(
            o.get("key")
                .getAsString(),
            o.has("displayName") ? o.get("displayName")
                .getAsString()
                : o.get("key")
                    .getAsString(),
            o.has("entityName") ? o.get("entityName")
                .getAsString() : null,
            health == null ? Double.NaN : health,
            eut == null ? 0 : eut.intValue(),
            ticks == null ? 0 : ticks.intValue(),
            bool(o, "alwaysInfernal"),
            !o.has("infernalityAllowed") || o.get("infernalityAllowed")
                .getAsBoolean(),
            bool(o, "isPeacefulAllowed"),
            drops);
    }

    private static Object plain(final JsonElement e) {
        if (e.isJsonObject()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> f : e.getAsJsonObject()
                .entrySet()) m.put(f.getKey(), plain(f.getValue()));
            return m;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive()
            .isNumber()) return e.getAsDouble();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive()
            .isBoolean()) return e.getAsBoolean();
        return e.isJsonNull() ? "" : e.getAsString();
    }

    private static boolean bool(final JsonObject o, final String key) {
        return o.has(key) && o.get(key)
            .getAsBoolean();
    }

    private static Double number(final JsonObject o, final String key) {
        return o.has(key) && o.get(key)
            .isJsonPrimitive()
            && o.get(key)
                .getAsJsonPrimitive()
                .isNumber() ? o.get(key)
                    .getAsDouble() : null;
    }

    private static JsonElement read(final String path) {
        try {
            InputStream in = MobDropsTest.class.getResourceAsStream(path);
            if (in == null) in = new GZIPInputStream(MobDropsTest.class.getResourceAsStream(path + ".gz"));
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new JsonParser().parse(r);
            }
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
