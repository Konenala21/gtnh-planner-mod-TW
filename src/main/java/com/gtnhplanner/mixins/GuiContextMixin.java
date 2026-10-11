package com.gtnhplanner.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.PlannerScale;

/**
 * The board lays out at its own GUI scale ({@link PlannerScale}), but ModularUI is handed the mouse in the game's GUI
 * pixels each frame: on the board, it gets it in the board's instead.
 */
@Mixin(value = GuiContext.class, remap = false)
public class GuiContextMixin {

    @ModifyVariable(method = "updateState", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private int gtnhplanner$boardMouseX(final int x) {
        return onBoard() ? PlannerScale.mouseX() : x;
    }

    @ModifyVariable(method = "updateState", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private int gtnhplanner$boardMouseY(final int y) {
        return onBoard() ? PlannerScale.mouseY() : y;
    }

    private boolean onBoard() {
        return (Object) this instanceof final ModularGuiContext context && context.getScreen() instanceof BoardScreen
            && PlannerScale.ratio() != 1f;
    }
}
