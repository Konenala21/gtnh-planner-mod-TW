package com.gtnhplanner.data.provider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.RecipeHandlerAccess;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.importer.game.GameIds;
import com.gtnhplanner.machines.MobDrops;
import com.gtnhplanner.machines.game.EecCards;
import com.gtnhplanner.ui.card.CardModel;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.TemplateRecipeHandler;

/**
 * The Extreme Entity Crusher's mobs: MobsInfo's Mob Drops page, which kubatech makes the crusher's NEI page, and
 * kubatech's own table of the mobs a Powered Spawner can hold (MobHandlerLoader.recipeMap, filled as a world starts).
 * Neither is compiled against: both are read by name, as the website's exporter reads them. A card per mob, its drops
 * and Liquid XP as outputs and the metadata the website's crusher maths replay (machines/MobDrops builds both); a GT
 * card otherwise, on the website's machine maths (WebCards, WebEffect), with the crusher's settings in its gear sheet.
 */
public final class MobDropsProvider implements PropertyProvider {

    /** MobsInfo's Mob Drops page. */
    private static final String HANDLER = "com.kuba6000.mobsinfo.nei.MobHandler";
    /** kubatech's table of crusher recipes, by mob. */
    private static final String LOADER = "kubatech.loaders.MobHandlerLoader";
    private static final String CRUSHER = "kubatech.tileentity.gregtech.multiblock.MTEExtremeEntityCrusher";
    private static final String KUBATECH_CONFIG = "kubatech.config.Config$MobHandler";
    private static final String INFERNAL_MOBS = "atomicstryker.infernalmobs.common.InfernalMobsCore";

    @Nullable
    private static Class<?> handlerClass;

    @Override
    public void register() {
        try {
            handlerClass = Class.forName(HANDLER);
            Class.forName(LOADER);
        } catch (final ClassNotFoundException | LinkageError e) {
            handlerClass = null;
            GtnhPlanner.LOG.info("[compat] no Mob Drops page or crusher table; crusher mobs are not read");
            return;
        }
        RecipePropertyAPI.registerExtractor(handlerClass, this);
        // NEI finds a recipe again by its result, and the page has none: the mob finds its own.
        com.gtnhplanner.nei.RecipeRefs.addFallback(MobDropsProvider::findAgain);
    }

    @Override
    public boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        return mobKey(handler, recipeIndex) != null;
    }

    @Override
    @Nullable
    public String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        // A GregTech card: its tier, amps and settings, on the website's machine maths.
        return mobKey(handler, recipeIndex) != null ? CardModel.GT_PROFILE : null;
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final String key = mobKey(handler, recipeIndex);
        final Object eec = key == null ? null : recipes().get(key);
        final MobDrops.Mob mob = eec == null ? null : mob(key, eec);
        if (mob == null) return PropertyProvider.super.extract(node, handler, recipeIndex);
        final MobDrops.Recipe recipe = MobDrops.recipe(mob, machine());
        node.inputs.clear();
        node.outputs.clear();
        final List<ItemStack> stacks = stacks(eec);
        final List<String> ids = new ArrayList<>();
        for (final MobDrops.Output out : recipe.outputs()) {
            final Port<?> port = port(out, stacks);
            if (port == null) continue;
            node.outputs.add(port);
            ids.add(out.id());
        }
        final Map<RecipeProperty<?>, Object> props = new HashMap<>();
        props.put(RecipePropertyAPI.DURATION_TICKS, recipe.duration());
        props.put(GTKeys.EU_PER_TICK, (long) recipe.eut());
        props.put(GTKeys.TOTAL_EU, (long) recipe.eut() * recipe.duration());
        props.put(EecCards.MOB, mob.key());
        props.put(EecCards.MOB_NAME, mob.name());
        props.put(
            EecCards.METADATA,
            recipe.eec()
                .toString());
        props.put(EecCards.OUTPUT_IDS, String.join("\n", ids));
        if (!recipe.gated()
            .isEmpty()) props.put(EecCards.GATED, String.join(", ", recipe.gated()));
        return props;
    }

    /** One of the recipe's outputs as a port: a lone drop at its chance, several drops of an item at their sum. */
    @Nullable
    private static Port<?> port(final MobDrops.Output out, final List<ItemStack> stacks) {
        if (out.xp()) {
            final FluidStack xp = FluidRegistry.getFluidStack(MobDrops.XP_FLUID, (int) out.amount());
            return xp == null ? null : new Port<>(RecipePropertyAPI.FLUID, xp, 1f);
        }
        if (out.drop() < 0 || out.drop() >= stacks.size()) return null;
        final ItemStack stack = stacks.get(out.drop())
            .copy();
        if (out.chance() != null || out.amount() == Math.rint(out.amount())) {
            stack.stackSize = (int) Math.max(1, out.amount());
            return new Port<>(
                RecipePropertyAPI.ITEM,
                stack,
                out.chance() != null ? out.chance()
                    .floatValue() : 1f);
        }
        stack.stackSize = 1;
        final Port<ItemStack> port = new Port<>(RecipePropertyAPI.ITEM, stack, 1f);
        port.setExactAmount(out.amount());
        return port;
    }

    // region Reading the tables

    /** The mob a recipe of the Mob Drops page shows, by its entity name, when the crusher can hold it; else null. */
    @Nullable
    public static String mobKey(final IRecipeHandler handler, final int recipeIndex) {
        if (handlerClass == null || !handlerClass.isInstance(handler)) return null;
        try {
            final List<TemplateRecipeHandler.CachedRecipe> recipes = RecipeHandlerAccess
                .getArecipes((TemplateRecipeHandler) handler);
            if (recipeIndex < 0 || recipeIndex >= recipes.size()) return null;
            final Object name = field(recipes.get(recipeIndex), "mobname");
            return name instanceof final String key && recipes().containsKey(key) ? key : null;
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** kubatech's crusher recipes by mob; empty before a world has started, or without kubatech. */
    private static Map<?, ?> recipes() {
        try {
            final Object map = Class.forName(LOADER)
                .getField("recipeMap")
                .get(null);
            return map instanceof final Map<?, ?> m ? m : Map.of();
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Map.of();
        }
    }

    /** A mob's crusher recipe as plain data. */
    @Nullable
    private static MobDrops.Mob mob(final String key, final Object eec) {
        try {
            final Object recipe = field(eec, "recipe");
            final List<MobDrops.Drop> drops = new ArrayList<>();
            for (final Object drop : list(field(eec, "mOutputs"))) {
                final MobDrops.Drop read = drop(drop);
                if (read != null) drops.add(read);
            }
            return new MobDrops.Mob(
                key,
                name(key, recipe),
                field(recipe, "entityName") instanceof final String s ? s : null,
                number(field(recipe, "maxEntityHealth")),
                (int) number(field(eec, "mEUt")),
                (int) number(field(eec, "mDuration")),
                Boolean.TRUE.equals(field(recipe, "alwaysinfernal")),
                !Boolean.FALSE.equals(field(recipe, "infernalityAllowed")),
                Boolean.TRUE.equals(field(recipe, "isPeacefulAllowed")),
                drops);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.debug("Crusher: mob {} could not be read", key, e);
            return null;
        }
    }

    /** Each drop's stack, in the table's order (null for one with none). */
    private static List<ItemStack> stacks(final Object eec) {
        final List<ItemStack> out = new ArrayList<>();
        for (final Object drop : list(field(eec, "mOutputs")))
            if (field(drop, "stack") instanceof final ItemStack stack && stack.getItem() != null) out.add(stack);
        return out;
    }

    /** One MobDrop as plain data; null for one with no item (MobDrops' indexes then skip it, as stacks() does). */
    @Nullable
    private static MobDrops.Drop drop(final Object drop) {
        if (!(field(drop, "stack") instanceof final ItemStack stack) || stack.getItem() == null) return null;
        final List<MobDrops.Modifier> modifiers = new ArrayList<>();
        for (final Object m : list(field(drop, "chanceModifiers"))) if (m != null) modifiers.add(modifier(m));
        final boolean damaged = field(drop, "damages") instanceof final Map<?, ?> d && !d.isEmpty();
        return new MobDrops.Drop(
            GameIds.itemId(stack),
            stack.getDisplayName(),
            stack.stackSize,
            (int) number(field(drop, "chance")),
            Boolean.TRUE.equals(field(drop, "lootable")),
            Boolean.TRUE.equals(field(drop, "playerOnly")),
            damaged || field(drop, "enchantable") != null,
            modifiers);
    }

    /** A chance modifier: its class's last name and its plain fields (an enchantment as its id). */
    private static MobDrops.Modifier modifier(final Object modifier) {
        final String className = modifier.getClass()
            .getName();
        final Map<String, Object> fields = new LinkedHashMap<>();
        for (Class<?> type = modifier.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (final Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || fields.containsKey(f.getName())) continue;
                try {
                    f.setAccessible(true);
                    final Object value = f.get(modifier);
                    if (value instanceof Number || value instanceof Boolean || value instanceof String)
                        fields.put(f.getName(), value);
                    else if (value instanceof final Enchantment e) fields.put(f.getName(), e.effectId);
                } catch (final ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return new MobDrops.Modifier(className.substring(className.lastIndexOf('.') + 1), fields);
    }

    /** The mob's name as the game shows it: its translation, else the entity's own name, else its key. */
    private static String name(final String key, final Object recipe) {
        final String translationKey = "entity." + key + ".name";
        try {
            final String translated = StatCollector.translateToLocal(translationKey);
            if (translated != null && !translated.isEmpty() && !translated.equals(translationKey)) return translated;
        } catch (final RuntimeException ignored) {}
        try {
            final Object entity = field(recipe, "entity");
            if (entity != null) {
                final Object name = entity.getClass()
                    .getMethod("getCommandSenderName")
                    .invoke(entity);
                if (name instanceof final String s && !s.isEmpty() && !s.startsWith("entity.")) return s;
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        return key;
    }

    /** The crusher's constants and Infernal Mobs' odds, as this game has them. */
    private static MobDrops.Machine machine() {
        final Map<String, Double> infernal = new LinkedHashMap<>();
        try {
            final Object core = Class.forName(INFERNAL_MOBS)
                .getMethod("instance")
                .invoke(null);
            for (final String key : MobDrops.Machine.INFERNAL_KEYS) {
                final String getter = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                try {
                    final Object v = core.getClass()
                        .getMethod(getter)
                        .invoke(core);
                    if (v instanceof final Number n) infernal.put(key, n.doubleValue());
                } catch (final ReflectiveOperationException | RuntimeException ignored) {}
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // No Infernal Mobs: the machine's own defaults.
        }
        return new MobDrops.Machine(
            staticNumber(CRUSHER, "MOB_SPAWN_INTERVAL"),
            staticNumber(CRUSHER, "DIAMOND_SPIKES_DAMAGE"),
            staticNumber(CRUSHER, "MAX_LOOTING_LEVEL"),
            staticNumber(KUBATECH_CONFIG, "playerOnlyDropsModifier"),
            infernal);
    }

    // endregion

    // region Every mob

    /** One mob the crusher can hold: its recipe on a Mob Drops page of our own, its key, name and best drop. */
    public record Choice(RecipeHandlerRef ref, String key, String name, @Nullable ItemStack icon, double health,
        boolean alwaysInfernal, List<String> drops) {}

    /** Every mob, on a Mob Drops page of our own, loaded once (after a world has started, when the table is full). */
    @Nullable
    private static TemplateRecipeHandler allMobs;

    @Nullable
    private static TemplateRecipeHandler allMobs() {
        if (allMobs == null && handlerClass != null && !recipes().isEmpty()) {
            try {
                final TemplateRecipeHandler h = (TemplateRecipeHandler) handlerClass.getDeclaredConstructor()
                    .newInstance();
                h.loadCraftingRecipes(h.getOverlayIdentifier());
                if (!h.arecipes.isEmpty()) allMobs = h;
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                GtnhPlanner.LOG.warn("Crusher: the Mob Drops page could not be read", e);
            }
        }
        return allMobs;
    }

    @Nullable
    private static RecipeHandlerRef findAgain(final Recipe.RecipeId id) {
        final TemplateRecipeHandler mobs = allMobs();
        if (mobs == null) return null;
        for (int i = 0; i < mobs.arecipes.size(); i++) if (Recipe.RecipeId.of(mobs, i)
            .equals(id)) return RecipeHandlerRef.of(mobs, i);
        return null;
    }

    /** The Mob Drops page's recipe for the crusher mob whose key gives {@code matches}; null when none does. */
    @Nullable
    public static RecipeHandlerRef mob(final Predicate<String> matches) {
        final TemplateRecipeHandler mobs = allMobs();
        if (mobs == null) return null;
        for (int i = 0; i < mobs.arecipes.size(); i++) {
            final String key = mobKey(mobs, i);
            if (key != null && matches.test(key)) return RecipeHandlerRef.of(mobs, i);
        }
        return null;
    }

    /** Every mob the crusher can hold, by name. */
    public static List<Choice> choices() {
        final TemplateRecipeHandler mobs = allMobs();
        final List<Choice> out = new ArrayList<>();
        if (mobs == null) return out;
        final Map<?, ?> table = recipes();
        for (int i = 0; i < mobs.arecipes.size(); i++) {
            final String key = mobKey(mobs, i);
            final Object eec = key == null ? null : table.get(key);
            final MobDrops.Mob mob = eec == null ? null : mob(key, eec);
            if (mob == null) continue;
            final List<String> drops = new ArrayList<>();
            for (final MobDrops.Drop d : mob.drops()) if (!drops.contains(d.name())) drops.add(d.name());
            out.add(
                new Choice(
                    RecipeHandlerRef.of(mobs, i),
                    key,
                    mob.name(),
                    mainDrop(mob, stacks(eec)),
                    health(mob),
                    mob.alwaysInfernal(),
                    drops));
        }
        out.sort(
            (a, b) -> a.name()
                .toLowerCase(Locale.ROOT)
                .compareTo(
                    b.name()
                        .toLowerCase(Locale.ROOT)));
        return out;
    }

    private static double health(final MobDrops.Mob mob) {
        return Double.isFinite(mob.maxHealth()) ? mob.maxHealth() : 20;
    }

    /** What a kill yields most of, as the mob's face (every spawner looks the same): the website's mainDrop. */
    @Nullable
    private static ItemStack mainDrop(final MobDrops.Mob mob, final List<ItemStack> stacks) {
        final MobDrops.Recipe recipe = MobDrops.recipe(mob, machine());
        MobDrops.Output best = null;
        for (final MobDrops.Output out : recipe.outputs()) {
            if (out.xp()) continue;
            if (best == null || out.amount() * (out.chance() != null ? out.chance() : 1)
                > best.amount() * (best.chance() != null ? best.chance() : 1)) best = out;
        }
        return best == null || best.drop() >= stacks.size() ? null : stacks.get(best.drop());
    }

    // endregion

    // region The website's export

    /**
     * kubatech's whole mob table in the shape the website's exporter writes it (its GtnhCalcOracleExporter's mobDrops
     * domain: machine, infernal, mobs with their drops and chance modifiers), so its own pipeline can be run on this
     * game's mobs and the result held against machines/MobDrops (tools/dev/eec). Dev only.
     */
    public static Map<String, Object> export() {
        final Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("id", "mobDrops");
        final MobDrops.Machine machine = machine();
        final Map<String, Object> m = new LinkedHashMap<>();
        if (machine.spawnInterval() != null) m.put("mobSpawnInterval", machine.spawnInterval());
        if (machine.maxLooting() != null) m.put("maxLootingLevel", machine.maxLooting());
        if (machine.spikesDamage() != null) m.put("diamondSpikesDamage", machine.spikesDamage());
        if (machine.playerOnlyModifier() != null) m.put("playerOnlyDropsModifier", machine.playerOnlyModifier());
        final Map<String, Object> xp = new LinkedHashMap<>();
        xp.put("kind", "fluid");
        xp.put("id", MobDrops.XP_FLUID);
        xp.put("amount", MobDrops.XP_PER_KILL);
        xp.put("displayName", "Liquid XP");
        m.put("experience", xp);
        domain.put("machine", m);
        if (!machine.infernal()
            .isEmpty()) domain.put("infernal", new LinkedHashMap<>(machine.infernal()));
        final List<Map<String, Object>> mobs = new ArrayList<>();
        final Map<?, ?> table = recipes();
        final List<String> keys = new ArrayList<>();
        for (final Object k : table.keySet()) keys.add(String.valueOf(k));
        java.util.Collections.sort(keys);
        for (final String key : keys) {
            final Object eec = table.get(key);
            final Object recipe = field(eec, "recipe");
            if (recipe == null) continue;
            final Map<String, Object> mob = new LinkedHashMap<>();
            mob.put("key", key);
            mob.put("displayName", name(key, recipe));
            if (field(recipe, "entityName") instanceof final String s) mob.put("entityName", s);
            putNumber(mob, "maxEntityHealth", field(recipe, "maxEntityHealth"));
            putNumber(mob, "eut", field(eec, "mEUt"));
            putNumber(mob, "durationTicks", field(eec, "mDuration"));
            mob.put("isPeacefulAllowed", Boolean.TRUE.equals(field(recipe, "isPeacefulAllowed")));
            mob.put("alwaysInfernal", Boolean.TRUE.equals(field(recipe, "alwaysinfernal")));
            mob.put("infernalityAllowed", !Boolean.FALSE.equals(field(recipe, "infernalityAllowed")));
            final List<Map<String, Object>> drops = new ArrayList<>();
            for (final Object drop : list(field(eec, "mOutputs"))) {
                if (!(field(drop, "stack") instanceof final ItemStack stack) || stack.getItem() == null) continue;
                final Map<String, Object> d = new LinkedHashMap<>();
                final Map<String, Object> resource = new LinkedHashMap<>();
                resource.put("kind", "item");
                resource.put("id", GameIds.itemId(stack));
                resource.put("amount", stack.stackSize);
                resource.put("displayName", stack.getDisplayName());
                d.put("resource", resource);
                d.put("damageable", stack.isItemStackDamageable());
                putNumber(d, "chance", field(drop, "chance"));
                putNumber(d, "enchantable", field(drop, "enchantable"));
                if (field(drop, "damages") instanceof final Map<?, ?> damages && !damages.isEmpty()) {
                    final Map<String, Object> weights = new LinkedHashMap<>();
                    for (final Map.Entry<?, ?> e : damages.entrySet())
                        weights.put(String.valueOf(e.getKey()), e.getValue());
                    d.put("damages", weights);
                }
                d.put("lootable", Boolean.TRUE.equals(field(drop, "lootable")));
                d.put("playerOnly", Boolean.TRUE.equals(field(drop, "playerOnly")));
                final List<Map<String, Object>> modifiers = new ArrayList<>();
                for (final Object o : list(field(drop, "chanceModifiers"))) {
                    if (o == null) continue;
                    final MobDrops.Modifier read = modifier(o);
                    final Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put(
                        "className",
                        o.getClass()
                            .getName());
                    final Map<String, Object> fields = new LinkedHashMap<>();
                    for (final Map.Entry<String, Object> f : read.fields()
                        .entrySet()) {
                        // The exporter writes an enchantment as {id}.
                        if ("enchantment".equals(f.getKey()) && f.getValue() instanceof Number)
                            fields.put(f.getKey(), Map.of("id", f.getValue()));
                        else fields.put(f.getKey(), f.getValue());
                    }
                    if (!fields.isEmpty()) mod.put("fields", fields);
                    modifiers.add(mod);
                }
                if (!modifiers.isEmpty()) d.put("chanceModifiers", modifiers);
                drops.add(d);
            }
            mob.put("drops", drops);
            mobs.add(mob);
        }
        domain.put("mobs", mobs);
        return domain;
    }

    private static void putNumber(final Map<String, Object> to, final String key, @Nullable final Object value) {
        if (value instanceof final Number n) to.put(key, n);
    }

    // endregion

    // region Reflection

    @Nullable
    private static Object field(@Nullable final Object owner, final String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                final Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (final NoSuchFieldException ignored) {
                // Its superclass's, then.
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static List<?> list(@Nullable final Object value) {
        return value instanceof final List<?> l ? l : List.of();
    }

    private static double number(@Nullable final Object value) {
        return value instanceof final Number n ? n.doubleValue() : Double.NaN;
    }

    @Nullable
    private static Double staticNumber(final String className, final String field) {
        try {
            final Field f = Class.forName(className)
                .getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null) instanceof final Number n ? n.doubleValue() : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    // endregion
}
