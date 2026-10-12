package com.gtnhplanner.ui.tutorial;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;

/**
 * What the player's mouse and keys may do while a beat lets them look round the board ({@link Director#freeLook}): move
 * the camera (the middle button anywhere, the wheel and the left button on empty board, the pan and zoom keys) and move
 * a card or drawer by its body or bar. Nothing that changes the plan: no ports, keys, menus or rates.
 */
final class FreeLook {

    private FreeLook() {}

    /** Whether the left button went down where it may (so its release goes through too). */
    private static boolean leftDown;

    /** Whether the mouse event now reaches the board. */
    static boolean lets() {
        final int button = Mouse.getEventButton();
        final boolean down = Mouse.getEventButtonState();
        if (button < 0) return Mouse.getEventDWheel() == 0 || overEmptyBoard();
        if (button == 2) return true;
        if (button != 0) return false;
        if (!down) {
            final boolean was = leftDown;
            leftDown = false;
            return was;
        }
        leftDown = overEmptyBoard() || overCarryable();
        return leftDown;
    }

    /** The board's pan and zoom keys. */
    static boolean cameraKey(final int key) {
        return switch (key) {
            case Keyboard.KEY_W, Keyboard.KEY_A, Keyboard.KEY_S, Keyboard.KEY_D, Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT, Keyboard.KEY_PRIOR, Keyboard.KEY_NEXT, Keyboard.KEY_EQUALS, Keyboard.KEY_MINUS, Keyboard.KEY_ADD, Keyboard.KEY_SUBTRACT -> true;
            default -> false;
        };
    }

    private static IWidget hovered() {
        final BoardScreen b = Targets.board();
        return b == null ? null
            : b.getContext()
                .getHovered();
    }

    private static boolean overEmptyBoard() {
        return hovered() instanceof BoardCanvas;
    }

    /** A card's body or a drawer's bar: what carries it. */
    private static boolean overCarryable() {
        final IWidget w = hovered();
        if (w instanceof final RecipeCard card) return card.partUnderMouse() == RecipeCard.Part.BODY;
        if (w instanceof final DrawerCard drawer) return drawer.partUnderMouse() == DrawerCard.Part.BAR;
        return false;
    }
}
