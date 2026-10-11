package com.gtnhplanner.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.cleanroommc.modularui.integration.nei.ModularUINEIGuiHandler;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.PlannerScale;

/**
 * NEI hides each slot of its list that a ModularUI panel covers. It asks with the slot in the game's GUI pixels, but
 * the board's panels are in its own ({@link PlannerScale}): on the board, the slot is asked in the board's.
 */
@Mixin(value = ModularUINEIGuiHandler.class, remap = false)
public class MuiNeiHandlerMixin {

    @ModifyVariable(method = "hideItemPanelSlot", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private int gtnhplanner$x(final int x) {
        return onBoard() ? PlannerScale.fromGame(x) : x;
    }

    @ModifyVariable(method = "hideItemPanelSlot", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private int gtnhplanner$y(final int y) {
        return onBoard() ? PlannerScale.fromGame(y) : y;
    }

    @ModifyVariable(method = "hideItemPanelSlot", at = @At("HEAD"), argsOnly = true, ordinal = 2, remap = false)
    private int gtnhplanner$w(final int w) {
        return onBoard() ? PlannerScale.fromGame(w) : w;
    }

    @ModifyVariable(method = "hideItemPanelSlot", at = @At("HEAD"), argsOnly = true, ordinal = 3, remap = false)
    private int gtnhplanner$h(final int h) {
        return onBoard() ? PlannerScale.fromGame(h) : h;
    }

    private static boolean onBoard() {
        final GuiContainer gui = Minecraft.getMinecraft().currentScreen instanceof GuiContainer g ? g : null;
        return Planner.isPlanner(gui) && PlannerScale.ratio() != 1f;
    }
}
