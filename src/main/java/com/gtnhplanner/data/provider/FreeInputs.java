package com.gtnhplanner.data.provider;

import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;

/**
 * Inputs the game hands out for nothing (the website's free-input.ts). GregTech's Rock Breaker lists a placeholder
 * item named "IT'S FREE! Place Lava on Side": the recipe wants lava touching the machine, not a stack fed into it, so
 * no wire, drawer or rate is ever the answer for that slot. Its port is kept with an amount of 0, which the board reads
 * as nothing used up (never asked for, nothing to wire), and the card draws it greyed with "free" so the player knows
 * to set the lava down.
 */
public final class FreeInputs {

    private FreeInputs() {}

    /** The free items by their website ids ({@code FREE_INPUT_ITEM_IDS}). */
    private static final Set<String> ITEM_IDS = Set.of("gregtech:gt.metaitem.02@32765");

    /** Whether the item with website id {@code itemId} is one the game gives away. Pure: tests call it. */
    public static boolean isFreeId(@Nullable final String itemId) {
        return itemId != null && ITEM_IDS.contains(itemId);
    }

    /** Whether the game gives {@code stack} away. */
    public static boolean isFree(@Nullable final ItemStack stack) {
        return stack != null && stack.getItem() != null
            && isFreeId(com.gtnhplanner.importer.game.GameIds.itemId(stack));
    }

    /** Whether {@code port}, an input, is one the game gives away. */
    public static boolean isFree(@Nullable final Port<?> port) {
        return port != null && port.getValue() instanceof final ItemStack stack && isFree(stack);
    }

    /** Whether input {@code index} of {@code node} is one the game gives away; never an output. */
    public static boolean isFree(@Nullable final Node node, final boolean output, final int index) {
        return node != null && !output && index >= 0 && index < node.inputs.size() && isFree(node.inputs.get(index));
    }
}
