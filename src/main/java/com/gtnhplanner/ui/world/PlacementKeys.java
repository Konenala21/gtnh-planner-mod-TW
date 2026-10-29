package com.gtnhplanner.ui.world;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.UiText;
import com.gtnhplanner.ui.gt.StructureGhosts;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The plan over the world's keys, all with sneak (Shift) held, and their strip under the minimap (unless the settings
 * hide it). With a machine lit gold, it is in hand while sneak is held: R turns it about its middle, G picks it up to
 * put down elsewhere (the picker, where [ and ] size a structure that comes in sizes), and Delete takes it out of the
 * world, each one undo step in its plan. U turns Focus on and off (a card over the machine looked at), I the labels in
 * the ghosts; Y (the overlay's own key, with Shift or without) hides and shows the whole overlay. Each key is the one
 * set in Controls ({@link PlannerKeys}): these are the defaults. Moving is picking up and putting down only, so the
 * minimap keeps its arrows and [ ]. With the overlay off
 * none of this shows or acts: nothing is lit to act on.
 *
 * <p>
 * Sneak, not a key binding of its own: in 1.7.10 a key drives one binding, so one of ours on Shift would take it from
 * sneaking. Whatever sneak is bound to holds.
 */
public final class PlacementKeys {

    public static final PlacementKeys INSTANCE = new PlacementKeys();

    /** The placement in hand while sneak is held: its plan, card and where it is now; null when none. */
    @Nullable
    private Graph graph;
    @Nullable
    private Node node;
    @Nullable
    private int[] link;

    private PlacementKeys() {}

    /** Whether sneak is held in the world, not while placing. */
    static boolean adjusting() {
        final Minecraft mc = Minecraft.getMinecraft();
        return sneaking() && PlanOverlay.on() && mc.currentScreen == null && !LinkPicker.active();
    }

    /** Whether sneak is held: by its binding, or its key down (input the binding has not seen yet). */
    static boolean sneaking() {
        final net.minecraft.client.settings.KeyBinding sneak = Minecraft.getMinecraft().gameSettings.keyBindSneak;
        final int code = sneak.getKeyCode();
        return sneak.getIsKeyPressed() || code > 0 && code < 256 && Keyboard.isKeyDown(code);
    }

    /** Takes the placement under the crosshair in hand as the key goes down, and lets go when it comes up. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!adjusting()) {
            link = null;
            return;
        }
        grab();
    }

    /** Takes the placement under the crosshair in hand, unless one still is. */
    private void grab() {
        if (link != null && node != null && WorldLinks.indexOf(node, link[0], link[1], link[2], link[3]) >= 0) return;
        link = null;
        final WorldLinks.Spot spot = target();
        if (spot == null) return;
        final Node n = spot.hit()
            .node();
        final int i = WorldLinks
            .indexOf(n, Minecraft.getMinecraft().theWorld.provider.dimensionId, spot.x(), spot.y(), spot.z());
        if (i < 0) return;
        graph = spot.hit()
            .graph();
        node = n;
        link = n.worldLinks.get(i)
            .clone();
    }

    /**
     * The placement the keys act on: the one the plan over the world has lit gold (its card or its ghost under the
     * crosshair, with the overlay's margins); none with the overlay off.
     */
    @Nullable
    private static WorldLinks.Spot target() {
        return PlanOverlay.on() ? PlanOverlay.highlighted() : null;
    }

    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (!adjusting() || !Keyboard.getEventKeyState()) return;
        // Focus and Labels need no machine lit. Each key is the one set in Controls.
        final int key = Keyboard.getEventKey();
        if (PlannerKeys.is(PlannerKeys.FOCUS, key)) {
            com.gtnhplanner.ui.PlannerSettings.setArFocus(!com.gtnhplanner.ui.PlannerSettings.arFocus());
            WorldView.say(
                com.gtnhplanner.ui.PlannerSettings.arFocus() ? UiText.tr("placement.focus_on")
                    : UiText.tr("placement.focus_off"));
            return;
        }
        if (PlannerKeys.is(PlannerKeys.LABELS, key)) {
            com.gtnhplanner.ui.PlannerSettings.setArLabels(!com.gtnhplanner.ui.PlannerSettings.arLabels());
            WorldView.say(
                com.gtnhplanner.ui.PlannerSettings.arLabels() ? UiText.tr("placement.labels_on")
                    : UiText.tr("placement.labels_off"));
            return;
        }
        // The key can come in the same tick as sneak, before the tick takes the placement in hand.
        grab();
        if (link == null) return;
        if (PlannerKeys.is(PlannerKeys.ROTATE, key)) turn();
        else if (PlannerKeys.is(PlannerKeys.PICK_UP, key)) pickUp();
        else if (PlannerKeys.is(PlannerKeys.REMOVE, key)) remove();
    }

    /** Turns the placement a quarter, clockwise from above, about the middle of what it fills. */
    private void turn() {
        final int[] to = WorldLinks
            .link(link[0], link[1], link[2], link[3], WorldLinks.facing(link) + 1, WorldLinks.size(link));
        final double[] a = WorldLinks.extent(graph, node, link), b = WorldLinks.extent(graph, node, to);
        to[1] += (int) Math.round(((a[0] + a[3]) - (b[0] + b[3])) / 2);
        to[3] += (int) Math.round(((a[2] + a[5]) - (b[2] + b[5])) / 2);
        if (!WorldLinks.relink(graph, node, link, to, false)) {
            WorldView.say(UiText.tr("placement.a_machine_is_there_already"));
            com.gtnhplanner.ui.sound.Sfx.DENY.play();
            return;
        }
        link = to;
        Hyb.click();
    }

    private void pickUp() {
        final PlanSnapshot.Card card = card();
        LinkPicker.move(
            graph,
            node.id,
            WorldView.cardName(node),
            card == null ? null : card.machine(),
            card == null ? StructureGhosts.Needs.NONE : card.needs(),
            link);
        link = null;
    }

    private void remove() {
        WorldLinks.unlink(graph, node, link[0], link[1], link[2], link[3]);
        WorldView.say(UiText.tr("placement.removed_from_the_world"));
        com.gtnhplanner.ui.sound.Sfx.WORLD_REMOVE.play();
        link = null;
    }

    /** The card in hand as the plan last open shows it (its machine and what its structure needs), or null. */
    @Nullable
    private PlanSnapshot.Card card() {
        final PlanSnapshot snap = PlanSnapshot.latest();
        return snap == null || snap.graph() != graph || node == null ? null : snap.cardOf(node.id);
    }

    // region Drawing

    /** The placement in hand, outlined bright. */
    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        if (link == null || !adjusting()
            || Minecraft.getMinecraft().theWorld == null
            || Minecraft.getMinecraft().theWorld.provider.dimensionId != link[0]) return;
        WorldMarks.begin();
        final double[] box = WorldLinks.extent(graph, node, link);
        WorldMarks.outline(box, Hyb.LIT & 0xFFFFFF, 0.012f, 2.5f, 1f);
        WorldMarks.front(box, WorldLinks.facing(link), Hyb.GOLD & 0xFFFFFF, 1f);
        WorldMarks.end();
    }

    /** One line of the keys list: the keys, what they do, and whether they do anything now. */
    private record Item(String key, String what, boolean live) {}

    /** Whether the keys strip shows: while the plan is over the world, not over a screen, the HUD hidden or placing. */
    /** Where the strip was last drawn, {x, y, w, h}, or null when it is not showing. */
    @javax.annotation.Nullable
    static int[] stripBounds() {
        return stripShown() ? lastStrip : null;
    }

    @javax.annotation.Nullable
    private static int[] lastStrip;

    /** Whether the note is too long for the strip, and shows at the top of the screen instead. */
    private static boolean noteAtTop;

    static boolean noteAtTop() {
        return noteAtTop;
    }

    static boolean stripShown() {
        final Minecraft mc = Minecraft.getMinecraft();
        return PlanOverlay.on() && com.gtnhplanner.ui.PlannerSettings.arKeys()
            && mc.currentScreen == null
            && !mc.gameSettings.hideGUI
            && !LinkPicker.active();
    }

    /**
     * The keys, one word each, in a see-through strip at the right under the minimap (above it in a bottom corner, in
     * its corner when it is off), as wide as it needs; the last thing done (a note) is its last line while it lasts.
     * The
     * lines for the lit machine are dim while none is lit; Focus and Labels are gold while on.
     */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !stripShown()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        final boolean some = link != null && adjusting() || target() != null;
        final String shift = keyName(mc.gameSettings.keyBindSneak.getKeyCode()) + " + ";
        final boolean focus = com.gtnhplanner.ui.PlannerSettings.arFocus();
        final java.util.List<Item> items = java.util.List.of(
            new Item(shift + PlannerKeys.label(PlannerKeys.ROTATE), UiText.tr("placement.rotate"), some),
            new Item(shift + PlannerKeys.label(PlannerKeys.PICK_UP), UiText.tr("placement.pick_up"), some),
            new Item(shift + PlannerKeys.label(PlannerKeys.REMOVE), UiText.tr("placement.remove"), some),
            new Item(shift + PlannerKeys.label(PlannerKeys.FOCUS), UiText.tr("placement.focus"), true),
            new Item(shift + PlannerKeys.label(PlannerKeys.LABELS), UiText.tr("placement.labels"), true),
            new Item(shift + PlannerKeys.label(PlannerKeys.AR), UiText.tr("placement.hide"), true));

        final ScaledResolution sr = event.resolution;
        final int[] map = Minimap.bounds(sr);
        float keysW = 0, whatW = 0;
        for (final Item it : items) {
            keysW = Math.max(keysW, Hyb.width(it.key()));
            whatW = Math.max(whatW, Hyb.width(it.what()));
        }
        final int most = map == null ? 200 : map[2];
        // The last thing done, as its last line when it fits; a longer one goes at the top of the screen.
        final String note = WorldView.note();
        final int most0 = map == null ? 200 : map[2];
        noteAtTop = note != null && Hyb.width(note) + 10 > most0;
        final String said = noteAtTop ? null : note;
        final int rowH = 10;
        final int w = (int) Math.min(most, Math.max(keysW + 8 + whatW + 10, said == null ? 0 : Hyb.width(said) + 10));
        final int h = (items.size() + (said == null ? 0 : 1)) * rowH + 5;
        final boolean below = map == null || map[1] < sr.getScaledHeight() / 2;
        final float right = map == null ? sr.getScaledWidth() - 6 : map[0] + map[2];
        final float x = right - w;
        final float y = map == null ? 6 : below ? map[1] + map[3] : map[1] - h;
        lastStrip = new int[] { (int) x, (int) y, w, h };
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        Hyb.rect(x, y, w, h, Minimap.BACKING);
        float ry = y + 3;
        for (final Item it : items) {
            final boolean on = focus && it.what()
                .equals(UiText.tr("placement.focus")) || com.gtnhplanner.ui.PlannerSettings.arLabels()
                    && it.what()
                        .equals(UiText.tr("placement.labels"));
            final int key = on ? Hyb.GOLD : it.live() ? 0xFFC8CAD0 : 0x70C8CAD0;
            final int what = on ? Hyb.GOLD : it.live() ? Hyb.MUTED : 0x709A9CA4;
            Hyb.text(it.key(), x + 5, ry, key);
            Hyb.text(it.what(), x + 5 + keysW + 8, ry, what);
            ry += rowH;
        }
        if (said != null) Hyb.text(Hyb.fit(said, w - 10), x + 5, ry, WorldView.noteColor());
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A key's name as people say it: Ctrl, Shift, Alt, else the game's. */
    static String keyName(final int code) {
        return switch (code) {
            case Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL -> "Ctrl";
            case Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT -> "Shift";
            case Keyboard.KEY_LMENU, Keyboard.KEY_RMENU -> "Alt";
            case Keyboard.KEY_DELETE -> "Del";
            case Keyboard.KEY_BACK -> "Backspace";
            default -> {
                final String n = Keyboard.getKeyName(code);
                yield n == null ? "?"
                    : n.length() <= 1 ? n
                        : n.charAt(0) + n.substring(1)
                            .toLowerCase();
            }
        };
    }

    // endregion
}
