package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.ShapedRecipeHandler;
import codechicken.nei.recipe.ShapelessRecipeHandler;
import gtPlusPlus.xmod.gregtech.api.enums.GregtechItemList;

/**
 * GT++'s Electric Auto Workbench, the machine that runs crafting-grid recipes (the website's recipe-rules.ts): a
 * crafting card can go on it as on the crafting table, which stays the free choice. On it the card is a GregTech card
 * (the GT profile: its tier chip, its EU), worked by the website's maths for the "Auto Workbench" handler: 2048 EU a
 * craft, 64 ticks at LV, a quarter of the time each tier up to EV's craft a tick. Back on the crafting table it is a
 * plain card again.
 */
public final class AutoWorkbench {

    private AutoWorkbench() {}

    /** The website's machine types for the crafting grid's two pages. */
    public static final String SHAPED = "Shaped Crafting", SHAPELESS = "Shapeless Crafting";

    /** The profile a crafting card has off the Auto Workbench: the one every recipe without a provider gets. */
    private static final String PLAIN_PROFILE = "default";

    private static List<ItemStack> stacks;

    /** The machine at every tier there is, LV to UV; empty in a pack without it. */
    public static List<ItemStack> stacks() {
        if (stacks != null) return stacks;
        final List<ItemStack> out = new ArrayList<>();
        try {
            for (final GregtechItemList tier : new GregtechItemList[] { GregtechItemList.GT4_Electric_Auto_Workbench_LV,
                GregtechItemList.GT4_Electric_Auto_Workbench_MV, GregtechItemList.GT4_Electric_Auto_Workbench_HV,
                GregtechItemList.GT4_Electric_Auto_Workbench_EV, GregtechItemList.GT4_Electric_Auto_Workbench_IV,
                GregtechItemList.GT4_Electric_Auto_Workbench_LuV, GregtechItemList.GT4_Electric_Auto_Workbench_ZPM,
                GregtechItemList.GT4_Electric_Auto_Workbench_UV }) {
                if (!tier.hasBeenSet()) continue;
                final ItemStack stack = tier.get(1);
                if (stack != null && stack.getItem() != null) out.add(stack);
            }
        } catch (final LinkageError | RuntimeException e) {
            // No GT++ here (the plain dev game): no Auto Workbench to offer.
            out.clear();
        }
        // Kept once it is known; while GregTech is still registering it, asked again.
        if (!out.isEmpty()) stacks = List.copyOf(out);
        return out;
    }

    /** Whether {@code machine} is the Auto Workbench at some tier. */
    public static boolean is(@Nullable final ItemStack machine) {
        if (machine == null) return false;
        for (final ItemStack s : stacks()) if (ItemStack.areItemStacksEqual(s, machine)) return true;
        return false;
    }

    /** Whether {@code node} is a crafting-grid recipe (NEI's shaped or shapeless crafting page). */
    public static boolean crafts(@Nullable final Node node) {
        if (node == null || node.isPower()) return false;
        final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(node.recipeId);
        return ref != null && crafts(ref.handler);
    }

    /** Whether {@code handler} is NEI's shaped or shapeless crafting page. */
    public static boolean crafts(@Nullable final IRecipeHandler handler) {
        return handler instanceof final ShapedRecipeHandler shaped && "crafting".equals(shaped.getOverlayIdentifier());
    }

    /** The website's machine type for {@code node}'s crafting page. */
    public static String machineType(final Node node) {
        final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(node.recipeId);
        return ref != null && ref.handler instanceof ShapelessRecipeHandler ? SHAPELESS : SHAPED;
    }

    /** Whether {@code node}, a crafting card, runs on the Auto Workbench. */
    public static boolean on(@Nullable final Node node) {
        return node != null && CardModel.GT_PROFILE.equals(node.machineConfig.profileId) && crafts(node);
    }

    /**
     * Before {@code node} goes on {@code machine}: onto the Auto Workbench, the card takes the GT profile (its
     * settings, at their defaults where it has none yet); off it, its own plain profile again.
     */
    public static void choose(final Node node, final ItemStack machine) {
        if (!crafts(node)) return;
        final MachineConfig cfg = node.machineConfig;
        if (is(machine)) {
            if (CardModel.GT_PROFILE.equals(cfg.profileId)) return;
            final MachineProfile gt = MachineProfileRegistry.get(CardModel.GT_PROFILE);
            if (gt == null) return;
            cfg.profileId = gt.id();
            for (final SettingDef<?> def : gt.settings()) cfg.settings.putIfAbsent(def.key, def.defaultValue);
            if ("OFF".equals(CardDefaults.stringSetting(cfg, "voltage"))) cfg.settings.put("voltage", "LV");
        } else if (CardModel.GT_PROFILE.equals(cfg.profileId) && MachineProfileRegistry.get(PLAIN_PROFILE) != null)
            cfg.profileId = PLAIN_PROFILE;
    }
}
