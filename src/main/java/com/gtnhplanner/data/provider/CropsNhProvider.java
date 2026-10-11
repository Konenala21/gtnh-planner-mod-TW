package com.gtnhplanner.data.provider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.cropsnh.api.ICropCard;
import com.gtnewhorizon.cropsnh.api.IGrowthRequirement;
import com.gtnewhorizon.cropsnh.compatibility.NEI.NEICropsNHCropHandler;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.Effects;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.CropsNh;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.machines.game.MachineModels;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;

/**
 * CropsNH's crops (its NEI Crops page), as the website models them (machines/CropsNh): a card per crop whose machine
 * count is the crops planted, harvested by a Crop Manager or an Industrial Farm. The seed is never used up, so it is
 * not a port; the outputs are the crop's drops at the website's baseline (perfect seeds on a fully supplied stick in a
 * two-tag biome), and the card's settings scale them (CropCards, after every refresh).
 */
public final class CropsNhProvider implements PropertyProvider {

    public static final String PROFILE = "cropsnh:crop";

    @Override
    public void register() {
        RecipePropertyAPI.registerExtractor(NEICropsNHCropHandler.class, this);
        // NEI finds a recipe again by searching for its result; a crop's page has none it can search by.
        com.gtnhplanner.nei.RecipeRefs.addFallback(CropsNhProvider::findAgain);
        final MachineProfile.Builder builder = MachineProfile.builder(PROFILE, "Crop")
            .setting(Settings.MACHINES.def());
        for (final String key : CropsNh.SETTING_KEYS)
            builder.setting(SettingDef.enumDef(key, "", List.of(), (v, c) -> null));
        MachineProfileRegistry.register(
            builder.effect(
                Effects.durationFromHandler()
                    .andThen(new CropCards.Step()))
                .build());
    }

    @Override
    public boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        return card(handler, recipeIndex) != null;
    }

    @Override
    @Nullable
    public String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        return card(handler, recipeIndex) != null ? PROFILE : null;
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final ICropCard card = card(handler, recipeIndex);
        if (card == null) return PropertyProvider.super.extract(node, handler, recipeIndex);
        final List<CropsNh.Drop> drops = new ArrayList<>();
        final List<ItemStack> stacks = new ArrayList<>();
        // Heaviest first, as NEI lists them.
        final List<Map.Entry<ItemStack, Integer>> table = new ArrayList<>(
            card.getDropTable()
                .entrySet());
        table.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (final Map.Entry<ItemStack, Integer> e : table) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            drops.add(
                new CropsNh.Drop(MachineModels.websiteId(e.getKey()), Math.max(1, e.getKey().stackSize), e.getValue()));
            stacks.add(e.getKey());
        }
        boolean machineOnly = false;
        String subSoil = null;
        for (final IGrowthRequirement r : card.getGrowthRequirements()) {
            if (r == null) continue;
            if (r.getClass()
                .getName()
                .toLowerCase(java.util.Locale.ROOT)
                .contains("machineonly")) machineOnly = true;
            final String text = description(r);
            if (text != null && text.toLowerCase(java.util.Locale.ROOT)
                .contains("under it")) subSoil = text;
        }
        final CropsNh.Stats stats = new CropsNh.Stats(
            card.getTier(),
            card.getGrowthDuration(),
            card.getDropChance(),
            staticInt("com.gtnewhorizon.cropsnh.reference.Constants", "GROWTH_CYCLE_TICKS", 256),
            staticNumber("com.gtnewhorizon.cropsnh.handler.ConfigurationHandler", "growthMultiplier", 1),
            machineOnly,
            card.getMinSeedBedTier(),
            subSoil,
            drops);
        node.inputs.clear();
        node.outputs.clear();
        for (int i = 0; i < drops.size(); i++) {
            final ItemStack one = stacks.get(i)
                .copy();
            one.stackSize = 1;
            final Port<ItemStack> port = new Port<>(RecipePropertyAPI.ITEM, one, 1f);
            port.setExactAmount(CropsNh.expectedDrop(stats, CropsNh.REFERENCE.gain(), drops.get(i)));
            node.outputs.add(port);
        }
        final Map<RecipeProperty<?>, Object> props = new java.util.HashMap<>();
        props.put(CropCards.STATS, CropCards.write(stats));
        props.put(CropCards.CROP_ID, card.getId());
        props.put(RecipePropertyAPI.DURATION_TICKS, (int) Math.round(CropsNh.harvestTicks(stats, CropsNh.REFERENCE)));
        return props;
    }

    /** The crop a recipe of NEI's Crops page shows; null for anything else. */
    @Nullable
    public static ICropCard card(final IRecipeHandler handler, final int recipeIndex) {
        if (!(handler instanceof final TemplateRecipeHandler t) || !(handler instanceof NEICropsNHCropHandler)) return null;
        if (recipeIndex < 0 || recipeIndex >= t.arecipes.size()) return null;
        final Object recipe = t.arecipes.get(recipeIndex);
        try {
            if (cropCardField == null) {
                cropCardField = recipe.getClass()
                    .getDeclaredField("cropCard");
                cropCardField.setAccessible(true);
            }
            final Object card = cropCardField.get(recipe);
            return card instanceof final ICropCard c && !c.getDropTable()
                .isEmpty() ? c : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.debug("CropsNH: no crop card on {}", recipe, e);
            return null;
        }
    }

    private static Field cropCardField;

    /** Every crop, on a Crops page of our own, loaded once. */
    private static NEICropsNHCropHandler allCrops;

    /** A crop card's recipe from its saved id: the crop on that page whose id matches. */
    @Nullable
    private static codechicken.nei.recipe.RecipeHandlerRef findAgain(final codechicken.nei.recipe.Recipe.RecipeId id) {
        if (allCrops == null) {
            final NEICropsNHCropHandler h = new NEICropsNHCropHandler();
            h.loadCraftingRecipes(h.getOverlayIdentifier());
            if (h.arecipes.isEmpty()) return null;
            allCrops = h;
        }
        for (int i = 0; i < allCrops.arecipes.size(); i++) if (codechicken.nei.recipe.Recipe.RecipeId.of(allCrops, i)
            .equals(id)) return codechicken.nei.recipe.RecipeHandlerRef.of(allCrops, i);
        return null;
    }

    @Nullable
    private static String description(final IGrowthRequirement r) {
        for (final String method : new String[] { "getDescriptionForNEI", "getDescription" }) {
            try {
                final Object v = r.getClass()
                    .getMethod(method)
                    .invoke(r);
                if (v != null) return v.toString();
            } catch (final ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }

    private static int staticInt(final String className, final String field, final int fallback) {
        return (int) staticNumber(className, field, fallback);
    }

    private static double staticNumber(final String className, final String field, final double fallback) {
        try {
            final Field f = Class.forName(className)
                .getDeclaredField(field);
            f.setAccessible(true);
            final Object v = f.get(null);
            return v instanceof final Number n && n.doubleValue() > 0 ? n.doubleValue() : fallback;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return fallback;
        }
    }
}
