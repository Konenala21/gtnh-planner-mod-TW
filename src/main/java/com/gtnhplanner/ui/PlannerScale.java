package com.gtnhplanner.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;

import com.gtnhplanner.ui.tutorial.Pointer;
import com.gtnhplanner.ui.tutorial.Tutorial;

/**
 * The board's own GUI scale: a step or two below the game's, which NEI's list beside it keeps (Size in the gear).
 * The board lays out at its scale and draws scaled down to the game's ({@link #ratio()}), so a planner pixel is still a
 * whole number of screen pixels and its text stays crisp. Off the board, and while the tour plays (its moves are laid
 * out on the game's scale), it is the game's.
 */
public final class PlannerScale {

    private PlannerScale() {}

    /** The game's GUI scale factor: what NEI and every other screen draw at. */
    public static int game() {
        final Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    /** The board's GUI scale factor, never below 1. */
    public static int board() {
        final int game = game();
        if (Tutorial.active()) return game;
        return PlannerSettings.boardScale(game);
    }

    /** The scale of whatever is open: the board's on the board, the game's anywhere else. */
    public static int current() {
        return Planner.isPlanner(Minecraft.getMinecraft().currentScreen) ? board() : game();
    }

    /** The board's pixel against the game's: what its drawing is scaled by (1 when they are the same). */
    public static float ratio() {
        return board() / (float) game();
    }

    /** The screen's width and height in the board's pixels (as Minecraft rounds them, up). */
    public static int boardWidth() {
        return (int) Math.ceil(Minecraft.getMinecraft().displayWidth / (double) board());
    }

    public static int boardHeight() {
        return (int) Math.ceil(Minecraft.getMinecraft().displayHeight / (double) board());
    }

    /** The screen's width and height in the pixels of whatever is open (see {@link #current()}). */
    public static int width() {
        return (int) Math.ceil(Minecraft.getMinecraft().displayWidth / (double) current());
    }

    public static int height() {
        return (int) Math.ceil(Minecraft.getMinecraft().displayHeight / (double) current());
    }

    /** The mouse in the board's pixels, from the window's (as Minecraft reads it, the tour's cursor included). */
    public static int mouseX() {
        return Pointer.windowX(Mouse.getX()) * boardWidth() / Minecraft.getMinecraft().displayWidth;
    }

    public static int mouseY() {
        final int h = boardHeight();
        return h - Pointer.windowY(Mouse.getY()) * h / Minecraft.getMinecraft().displayHeight - 1;
    }

    /** A length in the game's GUI pixels (NEI's), in the board's. */
    public static int fromGame(final int gamePixels) {
        return Math.round(gamePixels / ratio());
    }

    /** A length in the board's pixels, in the game's (NEI's, and the harness's). */
    public static int toGame(final float boardPixels) {
        return Math.round(boardPixels * ratio());
    }
}
