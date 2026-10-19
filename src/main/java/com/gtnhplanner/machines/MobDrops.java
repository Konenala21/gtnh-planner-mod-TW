package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.machines.web.ExtremeEntityCrusher;

/**
 * kubatech's Extreme Entity Crusher's mobs as the website's dataset builds them (its tools/dataset-pipeline:
 * eec-drops.mjs and normalize-oracle-export.mjs's normalizeMobDrops, over MobsInfo's MobDrop and IChanceModifier at
 * GT5U 5.09.54.20): which drops a plain crusher can roll and at what chance, what one kill yields of each item, and the
 * recipe's {@code metadata.eec} that machines/web/ExtremeEntityCrusher replays (weapon, Looting, infernals, the ritual,
 * the void switch). Pure: data/provider/MobDropsProvider reads kubatech's mob table into these records.
 * <p>
 * A drop's chance is out of 10,000. The crusher turns it into a percent, runs every chance modifier over it in order,
 * truncates back, cuts a player-only drop to a tenth (at least 1), and only then adds Looting. Modifiers read the
 * world,
 * the killer and the mob: this models a plain crusher in the Overworld, the kill made by its own fake player holding
 * the weapon set, an unpowered, unpotioned mob. A modifier class it has no rule for counts as a gate, so a card never
 * promises a drop the machine may not make. Transcribed one for one: do not "fix" a rule here without the website.
 */
public final class MobDrops {

    private MobDrops() {}

    /** The website's recipe map id and category for the crusher's recipes (handlers.json keys them so). */
    public static final String MAP_ID = "kubatech-eec";
    public static final String MACHINE_TYPE = ExtremeEntityCrusher.MACHINE_TYPE;
    /** Liquid XP, 120 L a kill. */
    public static final String XP_FLUID = "xpjuice";
    public static final int XP_PER_KILL = 120;

    /** Looting's enchantment id, which EachLevelOfGives checks the held weapon for. */
    private static final int LOOTING_ENCHANTMENT_ID = 21;

    // region The tables

    /**
     * One chance modifier: the last part of its class name ({@code IChanceModifier$NormalChance}) and its fields as
     * plain values (numbers, strings, switches; an enchantment as its id).
     */
    public record Modifier(String name, Map<String, Object> fields) {

        public Modifier {
            fields = Map.copyOf(fields);
        }
    }

    /**
     * One roll of a mob's drop table (MobsInfo's MobDrop).
     *
     * @param id       the item's website id
     * @param amount   the stack's size
     * @param chance   out of 10,000, before modifiers
     * @param voidable damaged or randomly enchanted: what the crusher's void switch throws away
     */
    public record Drop(String id, String name, int amount, int chance, boolean lootable, boolean playerOnly,
        boolean voidable, List<Modifier> modifiers) {

        public Drop {
            modifiers = List.copyOf(modifiers);
        }
    }

    /**
     * A mob a Powered Spawner can hold (kubatech's MobEECRecipe over MobsInfo's MobRecipe), by its entity name
     * ({@code Zombie}, {@code Thaumcraft.BrainyZombie}).
     *
     * @param maxHealth NaN when the table has none (20 is used)
     * @param eut       the recipe's EU/t, 0 for none (1920 is used)
     * @param duration  its ticks, 0 for none (55 is used)
     */
    public record Mob(String key, String name, @Nullable String entityName, double maxHealth, int eut, int duration,
        boolean alwaysInfernal, boolean infernalityAllowed, boolean peacefulAllowed, List<Drop> drops) {

        public Mob {
            drops = List.copyOf(drops);
        }
    }

    /**
     * The crusher's own constants and Infernal Mobs' odds, as the game has them; null where it could not say, and the
     * machine's default stands.
     *
     * @param infernal the odds and modifier counts by the website's names (eliteRarity ... mobModHealthFactor)
     */
    public record Machine(@Nullable Double spawnInterval, @Nullable Double spikesDamage, @Nullable Double maxLooting,
        @Nullable Double playerOnlyModifier, Map<String, Double> infernal) {

        public Machine {
            infernal = new LinkedHashMap<>(infernal);
        }

        /** The website's order for the infernal settings. */
        public static final List<String> INFERNAL_KEYS = List.of(
            "eliteRarity",
            "ultraRarity",
            "infernoRarity",
            "minEliteModifiers",
            "minUltraModifiers",
            "minInfernoModifiers",
            "mobModHealthFactor");
    }

    // endregion

    // region Chances

    /** Modifiers that leave the chance alone in a plain crusher. */
    private static final Set<String> KEEPS_CHANCE = Set.of(
        // Biome or weapon alternatives: the crusher's biome is not modelled and no special weapon is held.
        "IChanceModifier$OrBiome",
        "IChanceModifier$OrUsing",
        // Forbidden Magic's greed focus bonus is wand-only; the base passes through.
        "ForbiddenMagic$EachLevelOfGivesFocus",
        // Size checks against the entity copy MobsInfo already averaged into the chance.
        "Minecraft$MinecraftSlime",
        "Minecraft$MinecraftMagmaCube",
        "BloodMagic$MinorDemonGruntShards");

    /**
     * Modifiers that zero the drop in a plain crusher: a required enchantment, weapon, potion, a dimension other than
     * the Overworld, a charged creeper, a wither or non-player killer (it kills with a fake player), a vampire book.
     */
    private static final Set<String> ZEROES_CHANCE = Set.of(
        "IChanceModifier$DropsOnlyWithEnchant",
        "IChanceModifier$DropsOnlyUsing",
        "IChanceModifier$DropsOnlyWithWeaknessIII",
        "IChanceModifier$PoweredCreeper",
        "DraconicEvolution$DraconicEvolutionSoulChanceModifier",
        "ForbiddenMagic$NonPlayerEntity",
        "EtFuturum$WitherRoseModifier",
        "EtFuturum$DropsOnlyWhenKilledByPoweredCreeperModifier",
        "BloodMagic$DemonDemonPlacer",
        "OpenBlocks$OpenBlocksSmallChance",
        "Witchery$WitcheryVampireBook",
        "Witchery$WitcheryWarewolf",
        "Avaritia$AvaritiaSkullSwordModifier",
        "BloodArsenal$DropsOnlyWithWeakness",
        "TinkerToolEvents$BeheadingModifier");

    /** Whether a modifier class has a rule here (one without is a gate). */
    public static boolean known(final String name) {
        return name.equals("IChanceModifier$NormalChance") || name.equals("IChanceModifier$BaseChance")
            || name.equals("IChanceModifier$DropsOnlyInDimension")
            || name.equals("IChanceModifier$EachLevelOfGives")
            || KEEPS_CHANCE.contains(name)
            || ZEROES_CHANCE.contains(name);
    }

    /** One modifier's apply, in percent, for a plain Overworld crusher. */
    private static double apply(final double percent, final Modifier modifier, final double looting) {
        final Map<String, Object> fields = modifier.fields();
        switch (modifier.name()) {
            case "IChanceModifier$NormalChance", "IChanceModifier$BaseChance" -> {
                final double chance = number(fields.get("chance"));
                return Double.isFinite(chance) ? chance : percent;
            }
            case "IChanceModifier$DropsOnlyInDimension" -> {
                return percent != 0 && number(fields.get("dimension")) == 0 ? percent : 0;
            }
            case "IChanceModifier$EachLevelOfGives" -> {
                // Adds its step once when the held weapon has the enchantment at all.
                final double change = number(fields.get("change"));
                return enchantment(fields.get("enchantment")) == LOOTING_ENCHANTMENT_ID && looting > 0
                    ? percent + (Double.isFinite(change) ? change : 0)
                    : percent;
            }
            default -> {
                return KEEPS_CHANCE.contains(modifier.name()) ? percent : 0;
            }
        }
    }

    /**
     * eecDropChance: the drop's chance out of 10,000 after modifiers and the player-only cut, before Looting's lootable
     * bonus. {@code looting} only matters to modifiers that read the weapon's Looting (Forbidden Magic's greed shards).
     */
    public static int chance(final Drop drop, final double looting, final double playerOnlyModifier) {
        double percent = drop.chance() / 100.0;
        for (final Modifier modifier : drop.modifiers()) percent = apply(percent, modifier, looting);
        int chance = (int) trunc(percent * 100);
        if (!(chance > 0)) return 0;
        if (drop.playerOnly()) chance = Math.max(1, (int) trunc(chance * playerOnlyModifier));
        return chance;
    }

    // endregion

    // region The recipe

    /**
     * One of the recipe's outputs: an item made of one or more drops (several drops of one item share it, its amount
     * the expected count), or the Liquid XP.
     *
     * @param drop   the index in the mob's drops of its first drop (whose stack it shows); -1 for the XP
     * @param amount a lone drop's stack size, else what a kill yields at the reference Looting
     * @param chance a lone drop's chance, as a fraction, when under one; else null
     */
    public record Output(String id, int drop, double amount, @Nullable Double chance) {

        public boolean xp() {
            return drop < 0;
        }
    }

    /**
     * A mob's crusher recipe: its outputs in order, {@code metadata.eec} with one entry per output, the drops it can
     * never make in a plain crusher (by name), its EU/t (eight times the base for a mob that is always infernal) and
     * its ticks.
     */
    public record Recipe(List<Output> outputs, JsonObject eec, List<String> gated, int eut, int duration) {}

    /** normalizeMobDrops for one mob: the outputs, gated drops and metadata the website's dataset gives it. */
    public static Recipe recipe(final Mob mob, final Machine machine) {
        final double playerOnly = machine.playerOnlyModifier() != null ? machine.playerOnlyModifier() : 0.1;
        final Map<String, Group> groups = new LinkedHashMap<>();
        final List<String> gated = new ArrayList<>();
        for (int i = 0; i < mob.drops()
            .size(); i++) {
            final Drop drop = mob.drops()
                .get(i);
            final int c0 = chance(drop, 0, playerOnly);
            final int cL = chance(drop, 1, playerOnly);
            if (c0 == 0 && cL == 0) {
                if (!gated.contains(drop.name())) gated.add(drop.name());
                continue;
            }
            final ExtremeEntityCrusher.Drop entry = new ExtremeEntityCrusher.Drop();
            entry.amount = Math.max(1, drop.amount());
            entry.c0 = c0;
            if (cL != c0) entry.cL = (double) cL;
            if (drop.lootable()) entry.lootable = true;
            if (drop.voidable()) entry.voidable = true;
            groups.computeIfAbsent(drop.id(), k -> new Group(k, drop.name()))
                .add(i, entry);
        }
        // A mob whose every drop is gated still gives the crusher its Liquid XP.
        final List<Output> outputs = new ArrayList<>();
        final JsonArray metas = new JsonArray();
        for (final Group g : groups.values()) {
            // The static figure is what drops with no weapon, or with Looting I for a drop that only a Looting weapon
            // makes (Forbidden Magic's greed shards).
            final int reference = expected(g.drops, 0) > 0 ? 0 : 1;
            final boolean single = g.drops.size() == 1 && reference == 0;
            final ExtremeEntityCrusher.Drop first = g.drops.getFirst();
            outputs.add(
                new Output(
                    g.id,
                    g.first,
                    single ? first.amount : roundExpected(expected(g.drops, reference)),
                    single && first.c0 < 10000 ? first.c0 / 10000 : null));
            final JsonObject meta = new JsonObject();
            if (reference != 0) meta.addProperty("refLooting", reference);
            final JsonArray drops = new JsonArray();
            for (final ExtremeEntityCrusher.Drop d : g.drops) drops.add(drop(d));
            meta.add("drops", drops);
            metas.add(meta);
        }
        outputs.add(new Output(XP_FLUID, -1, XP_PER_KILL, null));
        final JsonObject xp = new JsonObject();
        xp.addProperty("xp", true);
        metas.add(xp);

        final int baseEut = mob.eut() > 0 ? mob.eut() : 1920;
        final double health = Double.isFinite(mob.maxHealth()) ? mob.maxHealth() : 20;
        final JsonObject eec = new JsonObject();
        eec.addProperty("mob", mob.key());
        if (mob.entityName() != null) eec.addProperty("entityName", mob.entityName());
        eec.addProperty("maxHealth", health);
        if (mob.alwaysInfernal()) eec.addProperty("alwaysInfernal", true);
        if (!mob.infernalityAllowed()) eec.addProperty("infernalityAllowed", false);
        if (mob.peacefulAllowed()) eec.addProperty("peacefulAllowed", true);
        eec.addProperty("baseEut", baseEut);
        if (finite(machine.spawnInterval())) eec.addProperty("spawnInterval", machine.spawnInterval());
        if (finite(machine.spikesDamage())) eec.addProperty("spikesDamage", machine.spikesDamage());
        if (finite(machine.maxLooting())) eec.addProperty("maxLooting", machine.maxLooting());
        final JsonObject infernal = new JsonObject();
        for (final String key : Machine.INFERNAL_KEYS) {
            final Double value = machine.infernal()
                .get(key);
            if (finite(value)) infernal.addProperty(key, value);
        }
        if (!infernal.entrySet()
            .isEmpty()) eec.add("infernal", infernal);
        eec.add("outputs", metas);
        return new Recipe(
            outputs,
            eec,
            gated,
            mob.alwaysInfernal() ? baseEut * 8 : baseEut,
            mob.duration() > 0 ? mob.duration() : 55);
    }

    /** The drops of one item, in the order they came. */
    private static final class Group {

        final String id, name;
        final List<ExtremeEntityCrusher.Drop> drops = new ArrayList<>();
        int first = -1;

        Group(final String id, final String name) {
            this.id = id;
            this.name = name;
        }

        void add(final int index, final ExtremeEntityCrusher.Drop drop) {
            if (first < 0) first = index;
            drops.add(drop);
        }
    }

    private static JsonObject drop(final ExtremeEntityCrusher.Drop d) {
        final JsonObject o = new JsonObject();
        o.addProperty("amount", d.amount);
        o.addProperty("c0", d.c0);
        if (d.cL != null) o.addProperty("cL", d.cL);
        if (d.lootable != null) o.addProperty("lootable", d.lootable);
        if (d.voidable != null) o.addProperty("voidable", d.voidable);
        return o;
    }

    /** What a kill yields of an item's drops at a Looting level. */
    private static double expected(final List<ExtremeEntityCrusher.Drop> drops, final double looting) {
        double sum = 0;
        for (final ExtremeEntityCrusher.Drop d : drops) sum += ExtremeEntityCrusher.expectedItems(
            d.amount,
            looting > 0 ? (d.cL != null ? d.cL : d.c0) : d.c0,
            Boolean.TRUE.equals(d.lootable),
            looting);
        return sum;
    }

    /** {@code Math.round(value * 1e6) / 1e6}, JavaScript's rounding. */
    private static double roundExpected(final double value) {
        return Math.floor(value * 1e6 + 0.5) / 1e6;
    }

    /** The website's slug of a mob's key, as its recipe ids and spawner ids end ({@code thaumcraft-brainyzombie}). */
    public static String slug(final String key) {
        return key.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    }

    // endregion

    private static boolean finite(@Nullable final Double value) {
        return value != null && Double.isFinite(value);
    }

    private static double number(@Nullable final Object value) {
        return value instanceof final Number n ? n.doubleValue() : Double.NaN;
    }

    /** An enchantment field's id: the game side gives it as a number, the website's export as {id}. */
    private static double enchantment(@Nullable final Object value) {
        return value instanceof final Map<?, ?> m ? number(m.get("id")) : number(value);
    }

    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }
}
