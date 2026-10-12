package com.gtnhplanner.ui.world;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The plan overlaid on the world: every card of the plan last open in the planner that has been placed on a spot is
 * drawn as the board draws it just above the ghost of its machine, never on it, a line down to it; the same size within
 * ten blocks and shrinking with distance beyond. A card is small and faint until you look at its machine (or the card),
 * then grows and comes up solid; with Focus on, only that one shows. Every ghost's edges always show. Cards move out of
 * each other's way, their lines pointing back to their machines. The plan's wires between placed cards are drawn flat
 * on the screen as the board draws them, from block to block, with arrowheads moving towards the card fed
 * and a tag naming what goes along and how much. Nothing shows until a card is placed. What the crosshair is on is
 * highlighted.
 */
public final class PlanOverlay {

    public static final PlanOverlay INSTANCE = new PlanOverlay();

    /**
     * Cards and wire tags are drawn at {@link #SIZE} of the board's size within this many blocks of the eye, and shrink
     * with distance beyond it as objects do.
     */
    private static final float FULL_SIZE_WITHIN = 10f, SIZE = 0.55f;

    /** The room kept between cards (and wire tags) on the screen, in GUI pixels. */
    private static final float CARD_GAP = 3;

    /** How far the crosshair reaches to a wire's line, in GUI pixels. */
    private static final float WIRE_REACH = 24;

    /** A placed card this frame: where its block is, and where and how big its card is on the screen. */
    private static final class Placed {

        final PlanSnapshot.Card card;
        final int x, y, z, facing, size;
        /** The card's first spot: the one its card stands over and its wires run from. */
        final boolean main;
        /** Where the machine being placed or moved would go now: the picker draws its ghost. */
        final boolean preview;
        /** Where the card stands: the middle of its spot, or of its structure. */
        final double[] home;
        /** What it fills in the world, its spot or its structure: {x0, y0, z0, x1, y1, z1}. */
        final double[] box;
        double distance;
        /** How much its card is looked at, 0 to 1 (eased): it grows and comes up solid as it is. */
        float a;
        /** What its box covers on the screen {x0, y0, x1, y1}, or null when none of it is in front of the camera. */
        @Nullable
        float[] rect;
        /**
         * Where the card's centre would be on the screen, just above its machine; the middle of the machine's top (the
         * card's line ends there); its scale and size there; and where it is drawn, its top left, once moved out of the
         * way.
         */
        float sx, sy, bx, by, scale, w, h, left, top;

        /** Which of its card's placements it is, from 1 (its place in the card's list); 0 for the one being placed. */
        int nth;

        Placed(final PlanSnapshot.Card card, final int[] at, final boolean main) {
            this(card, at, main, false);
        }

        Placed(final PlanSnapshot.Card card, final int[] at, final boolean main, final boolean preview) {
            this.preview = preview;
            this.card = card;
            this.x = at[1];
            this.y = at[2];
            this.z = at[3];
            this.facing = WorldLinks.facing(at);
            this.size = WorldLinks.size(at);
            this.main = main;
            box = WorldMarks.extent(card.machine(), card.needs(), size, x, y, z, facing);
            home = new double[] { (box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2 };
        }

        boolean covers(final float px, final float py) {
            return px >= left && px < left + w && py >= top && py < top + h;
        }
    }

    @Nullable
    private UUID looked;
    /** The card the crosshair is on, its spot or the card itself, found on the screen last frame; drawn highlighted. */
    @Nullable
    private UUID lit;
    /** The placement the crosshair meets (its ghost), found with {@link #looked}. */
    @Nullable
    private Placed lookedAt;
    /**
     * The placement lit gold this frame, its card or its ghost under the crosshair, for the keys that act on a placed
     * machine; null when none is (or a wire is).
     */
    @Nullable
    private WorldLinks.Spot highlight;
    /** The wire under the crosshair, found on the screen last frame, and drawn lit. */
    @Nullable
    private Conn hovered;

    /**
     * A wire of the plan between two placed cards: its ends, what goes along it (from the card that makes it), and
     * where its ends fell on the screen this frame.
     */
    private record Conn(Placed from, Placed to, PlanSnapshot.Line line, @Nullable PlanSnapshot.Flow flow) {

        String what() {
            return flow != null ? flow.name() : "Wire";
        }

        boolean same(@Nullable final Conn o) {
            return o != null && o.line.from()
                .equals(line.from())
                && o.line.to()
                    .equals(line.to())
                && o.line.resource()
                    .equals(line.resource());
        }
    }

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16),
        projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer out = BufferUtils.createFloatBuffer(3);
    private double camX, camY, camZ;
    private boolean cameraKnown;

    private PlanOverlay() {}

    /** The placement lit gold by the plan over the world this frame, or null. */
    @Nullable
    static WorldLinks.Spot highlighted() {
        return INSTANCE.highlight;
    }

    public static boolean on() {
        return PlannerSettings.arLens();
    }

    /**
     * How solid a card is drawn (a touch see-through, so the world behind it still shows). A card shows only with Focus
     * on, for the machine you look at, and grows from {@link #POP_SIZE} of its size as it comes up.
     */
    private static final float SOLID = 0.85f, POP_SIZE = 0.6f;

    /**
     * How solid a wire is drawn, and with a card it joins looked at; its tag shows once one is looked at this much (or
     * the crosshair is on it).
     */
    private static final float WIRE_SOLID = 0.6f, TAG_FROM = 0.3f;

    /** The room kept between a card and its machine on the screen, in GUI pixels. */
    private static final float GHOST_GAP = 4;

    /** How solid a card is drawn, looked at {@code a}. */
    private static float solid(final float a) {
        return SOLID * a;
    }

    /** How solid a wire is drawn, its cards looked at {@code a}. */
    private static float wireSolid(final float a) {
        return WIRE_SOLID + (SOLID - WIRE_SOLID) * a;
    }

    /** A card's size, looked at {@code a}, as a share of its full size: it pops up as it comes in. */
    private static float sized(final float a) {
        return POP_SIZE + (1 - POP_SIZE) * a;
    }

    /** The cards placed in this dimension within range, nearest first. */
    private static List<Placed> placed(final Minecraft mc) {
        final List<Placed> all = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null || mc.theWorld == null || mc.renderViewEntity == null) return all;
        final int dim = mc.theWorld.provider.dimensionId;
        final Node picking = LinkPicker.active() ? LinkPicker.node() : null;
        final int[] preview = picking == null ? null : LinkPicker.preview();
        for (final PlanSnapshot.Card c : snap.cards()) {
            final Node n = snap.graph().nodes.get(c.id());
            if (n == null) continue;
            boolean first = true;
            // The machine in hand goes where it would be put down, card and wires with it, as dragging on the board.
            final boolean inHand = picking != null && c.nodeIds()
                .contains(picking.id);
            if (inHand && preview != null) {
                all.add(new Placed(c, preview, true, true));
                first = false;
            }
            for (int i = 0; i < n.worldLinks.size(); i++) {
                final int[] l = n.worldLinks.get(i);
                // Where the machine in hand was is empty while it is in hand.
                if (l[0] != dim || inHand && LinkPicker.hidden(l)) continue;
                final Placed p = new Placed(c, l, first);
                p.nth = i + 1;
                all.add(p);
                first = false;
            }
        }
        final double range = PlannerSettings.arRange();
        final EntityLivingBase eye = mc.renderViewEntity;
        final List<Placed> list = new ArrayList<>();
        for (final Placed p : all) {
            final double dx = p.home[0] - eye.posX, dy = p.home[1] - eye.posY, dz = p.home[2] - eye.posZ;
            p.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (p.distance <= range) list.add(p);
        }
        list.sort(Comparator.comparingDouble(p -> p.distance));
        return list;
    }

    /** The placed spot under the crosshair, as far as the overlay reaches. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        looked = null;
        lookedAt = null;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || mc.theWorld == null || mc.renderViewEntity == null || mc.currentScreen != null) return;
        // The spot the crosshair meets first, through blocks: spots are often empty, and the plan shows over the world.
        final net.minecraft.util.Vec3 from = mc.renderViewEntity.getPosition(1f), dir = mc.renderViewEntity.getLook(1f);
        double first = PlannerSettings.arRange();
        for (final Placed p : placed(mc)) {
            if (p.preview) continue;
            final double t = WorldLinks.enter(from, dir, p.box);
            if (t >= 0 && t < first) {
                first = t;
                looked = p.card.id();
                lookedAt = p;
            }
        }
    }

    // region The world: the camera, the connectors, the block looked at

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        cameraKnown = false;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || PlanSnapshot.latest() == null || mc.theWorld == null) return;
        modelview.clear();
        projection.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        camX = WorldMarks.cameraX();
        camY = WorldMarks.cameraY();
        camZ = WorldMarks.cameraZ();
        cameraKnown = true;
        final List<Placed> placed = placed(mc);
        if (placed.isEmpty()) return;
        WorldMarks.begin();
        // Each placed card's spot: the machine's ghost (unless the machine is built there), see-through, solid or not
        // shown as set; the one looked at outlined. Every multiblock's depth first, so each shows its outside alone and
        // nearer ghosts hide farther ones.
        final int look = PlannerSettings.arGhosts();
        final boolean shown = look != PlannerSettings.GHOSTS_HIDDEN, solid = look == PlannerSettings.GHOSTS_SOLID;
        if (shown) for (final Placed p : placed) if (!p.preview && p.card.machine() != null)
            WorldMarks.ghostDepth(p.card.machine(), p.card.needs(), p.size, p.x, p.y, p.z, p.facing);
        for (final Placed p : placed) {
            if (p.preview) continue;
            if (shown && p.card.machine() != null) WorldMarks
                .machineGhost(p.card.machine(), p.card.needs(), p.size, p.x, p.y, p.z, p.facing, built(mc, p), solid);
            // The one looked at outlined: what the keys act on.
            if (p.card.id()
                .equals(lit)) WorldMarks.outline(p.box, Hyb.LIT & 0xFFFFFF, 0.012f, 1.5f, 0.6f);
        }
        WorldMarks.end();
    }

    /** Whether the card's machine is built on its block already: the block there is the machine. */
    private static boolean built(final Minecraft mc, final Placed p) {
        final net.minecraft.block.Block block = mc.theWorld.getBlock(p.x, p.y, p.z);
        if (block == null || block.isAir(mc.theWorld, p.x, p.y, p.z)) return false;
        final net.minecraft.item.ItemStack here = LinkPicker.pickBlock(
            mc,
            new MovingObjectPosition(p.x, p.y, p.z, 1, net.minecraft.util.Vec3.createVectorHelper(p.x, p.y, p.z)));
        final net.minecraft.item.ItemStack machine = p.card.machine();
        return here != null && machine != null
            && here.getItem() == machine.getItem()
            && here.getItemDamage() == machine.getItemDamage();
    }

    /** Each wire of the plan between two placed cards, with what goes along it. */
    private static List<Conn> conns(final List<Placed> placed) {
        final List<Conn> out = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return out;
        final Map<UUID, Placed> byNode = new HashMap<>();
        for (final Placed p : placed) if (p.main) for (final UUID id : p.card.nodeIds()) byNode.put(id, p);
        for (final PlanSnapshot.Line w : snap.wires()) {
            if (w.from() == null || w.to() == null) continue;
            final Placed a = byNode.get(w.from()), b = byNode.get(w.to());
            if (a == null || b == null || a == b) continue;
            PlanSnapshot.Flow flow = null;
            for (final PlanSnapshot.Flow f : a.card.outputs()) if (f.key()
                .equals(w.resource())) flow = f;
            out.add(new Conn(a, b, w, flow));
        }
        return out;
    }

    // endregion

    // region The cards

    /** Under the minimap, which goes over them. */
    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.HIGH)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minimap.INSTANCE.point(null, null);
        highlight = null;
        if (!on() || !cameraKnown) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return;
        final ScaledResolution sr = event.resolution;
        final List<Placed> all = placed(mc);
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        // Where each machine is on the screen, and how much its card is looked at.
        for (final Placed p : all) p.rect = screenBox(p.box, sr, mc);
        final Map<UUID, Float> seen = attention(all);
        for (final Placed p : all) p.a = seen.getOrDefault(p.card.id(), 0f);
        // A card stands over one of its machines, the one the crosshair meets, else its first (the one in hand while
        // placing): the others show as ghosts.
        final Map<UUID, Placed> stand = new HashMap<>();
        for (final Placed p : all) if (p.main) stand.put(p.card.id(), p);
        if (lookedAt != null && !LinkPicker.active()) for (final Placed p : all) if (!p.preview && p.card.id()
            .equals(lookedAt.card.id()) && p.x == lookedAt.x && p.y == lookedAt.y && p.z == lookedAt.z)
            stand.put(p.card.id(), p);
        final List<Placed> shown = new ArrayList<>();
        for (final Placed p : all)
            if (stand.get(p.card.id()) == p && solid(p.a) > 0.01f && project(p, sr, mc)) shown.add(p);
        // The card looked at takes its place first, then the rest nearest first.
        shown.sort(
            Comparator.comparingInt((Placed p) -> p.a > 0.5f ? 0 : 1)
                .thenComparingDouble(p -> p.distance));
        // The plan's wires, flat on the screen as the board draws them, and the one the crosshair is on.
        final List<Run> runs = runs(conns(all), seen, sr, mc, snap);
        layout(shown, runs, all, sr);
        // What the crosshair is on: a wire's tag, else the nearest card it is over, else a spot (its ghost), else the
        // wire whose line is within reach.
        Run near = null;
        for (final Run r : runs) if (r.tag && r.covers(cx, cy)) near = r;
        Placed over = null;
        if (near == null) for (final Placed p : shown) if (p.covers(cx, cy)) {
            over = p;
            break;
        }
        overCard = over == null ? null : over.card.id();
        // While a machine is in hand, it is the one lit, and nothing else is under the crosshair.
        final Node inHand = LinkPicker.active() ? LinkPicker.node() : null;
        if (inHand != null) {
            near = null;
            over = null;
        }
        final UUID card = inHand != null ? inHand.id : near != null ? null : over != null ? over.card.id() : looked;
        if (inHand == null && near == null && card == null) {
            float nearest = WIRE_REACH;
            for (final Run r : runs) {
                final float d = distance(cx, cy, r.ax, r.ay, r.bx, r.by);
                if (d < nearest) {
                    nearest = d;
                    near = r;
                }
            }
        }
        hovered = near == null ? null : near.conn;
        lit = card;
        // What the keys act on: the card the crosshair is over, else the ghost it meets.
        final Placed target = inHand != null || near != null ? null
            : over != null ? over
                : lookedAt != null && lookedAt.card.id()
                    .equals(card) ? lookedAt : null;
        final Node targetNode = target == null || target.preview ? null : snap.graph().nodes.get(target.card.id());
        if (targetNode != null) highlight = new WorldLinks.Spot(
            new WorldLinks.Hit(snap.graph(), targetNode),
            target.x,
            target.y,
            target.z,
            target.distance);
        Minimap.INSTANCE.point(card, hovered == null ? null : hovered.line);
        Hyb.beginBatch();
        for (final Run r : runs) {
            Hyb.fadeOut = 1 - wireSolid(shown(r, seen));
            if (Hyb.fadeOut < 0.99f) wire(r, r.conn.same(hovered));
        }
        Hyb.fadeOut = 0;
        Hyb.endBatch();
        // Each machine's label in its ghost, near to far, each clear of those before it.
        if (PlannerSettings.arLabels()) {
            final List<float[]> labels = new ArrayList<>();
            for (final Placed p : all) if (!p.preview) label(p, sr, mc, labels);
        }
        // Far to near, so nearer cards cover farther ones as objects do, the one looked at over the rest; the one the
        // crosshair is on over them all.
        Placed top = null;
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Placed p = shown.get(i);
            if (p.card.id()
                .equals(lit)) top = p;
            else {
                Hyb.fadeOut = 1 - solid(p.a);
                card(p, snap, false);
            }
        }
        if (top != null) {
            Hyb.fadeOut = 1 - solid(top.a);
            card(top, snap, true);
        }
        // Over the cards: what each wire carries, where a card it joins is looked at.
        final boolean always = PlannerSettings.arWireLabels() == PlannerSettings.WIRE_LABELS_ALWAYS;
        for (final Run r : runs) if (r.tag && !r.conn.same(hovered)) {
            Hyb.fadeOut = 1 - (always ? SOLID : solid(shown(r, seen)));
            if (Hyb.fadeOut < 0.99f) tag(r, false);
        }
        Hyb.fadeOut = 1 - SOLID;
        if (near != null && PlannerSettings.arWireLabels() != PlannerSettings.WIRE_LABELS_OFF) tag(near, true);
        Hyb.fadeOut = 0;
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // region Labels in the ghosts

    /** A label's padding, unscaled, and its largest scale. */
    private static final int LABEL_PAD = 4;

    private static final float LABEL_MAX = 0.75f;

    /** Power a generator makes, on its label. */
    private static final int MADE = 0xFF8FD694;

    /**
     * A machine's label in the middle of its ghost on the screen, on a soft dark backing: its name (over two lines when
     * long), then its tier, the power it draws (or makes) and how many machines its card has ("2/4": which of them this
     * is). It always fits in the
     * ghost: as the ghost gets small on the screen the name goes first, then the power, then the count, and with not
     * even its tier fitting at a letter pixel a screen pixel (the smallest that reads), there is no label. It keeps
     * clear
     * of the nearer machines' labels ({@code taken}) the same way: less of it, or none.
     */
    private void label(final Placed p, final ScaledResolution sr, final Minecraft mc, final List<float[]> taken) {
        final float[] r = p.rect, at = screen(p.home, sr, mc);
        if (r == null || at == null) return;
        final PlanSnapshot.Card c = p.card;
        // One of several: which, "2/4"; a card of one machine, how many, "×1".
        final int of = (int) Math.ceil(c.machines() - 1e-9);
        final String count = of > 1 && p.nth > 0 ? p.nth + "/" + of
            : "×" + com.gtnhplanner.ui.theme.Fmt.machines(c.machines());
        final boolean makes = c.madeEuPerTick() > 0;
        final double eu = makes ? c.madeEuPerTick() : c.euPerTick();
        final String power = eu > 0 ? (makes ? "+" : "") + com.gtnhplanner.ui.theme.Fmt.power(eu) + " EU/t" : "";
        final String amps = c.amps() > 1 ? c.amps() + "A" : null;
        final int tierW = c.tier()
            .isEmpty() ? 0 : Hyb.tierTagWidth(c.tier(), amps);
        final int countW = Hyb.width(count);
        final int full = tierW + (tierW > 0 && !power.isEmpty() ? 4 : 0) + Hyb.width(power) + 6 + countW;
        final List<String> name = wrap(c.name(), Math.max(full, 70));
        int nameW = 0;
        for (final String s : name) nameW = Math.max(nameW, Hyb.width(s));
        // Most first, each {width, height, name lines, power shown, count shown}.
        final int[][] levels = { { Math.max(nameW, full), 4 + name.size() * 10 + 15, name.size(), 1, 1 },
            { full, 17, 0, 1, 1 }, { tierW + (tierW > 0 ? 6 : 0) + countW, 17, 0, 0, 1 },
            { tierW > 0 ? tierW : countW, 17, 0, 0, tierW > 0 ? 0 : 1 } };
        // Inside the ghost's face, clear of its neighbours' labels.
        final float roomW = (r[2] - r[0]) * 0.8f, roomH = (r[3] - r[1]) * 0.7f, least = 1f / sr.getScaleFactor();
        for (final int[] l : levels) {
            final int w = l[0] + 2 * LABEL_PAD, h = l[1];
            final float scale = Math.min(Math.min(roomW / w, roomH / h), LABEL_MAX);
            if (scale < least) continue;
            // Clear of the nearer machines' labels, else less of it, else none.
            final float x0 = at[0] - w * scale / 2, y0 = at[1] - h * scale / 2, x1 = x0 + w * scale,
                y1 = y0 + h * scale;
            boolean clear = true;
            for (final float[] t : taken)
                if (x0 < t[2] + 1 && t[0] < x1 + 1 && y0 < t[3] + 1 && t[1] < y1 + 1) clear = false;
            if (!clear) continue;
            taken.add(new float[] { x0, y0, x1, y1 });
            GL11.glPushMatrix();
            GL11.glTranslatef(x0, y0, 0);
            GL11.glScalef(scale, scale, 1);
            Hyb.roundRect(0, 0, w, h, 3, 0xA8141518);
            for (int i = 0; i < l[2]; i++) Hyb.text(name.get(i), LABEL_PAD, 3 + 10 * i, Hyb.INK);
            final float y = h - 13;
            float x = LABEL_PAD;
            if (tierW > 0) {
                Hyb.tierTag(c.tier(), amps, x, y);
                x += tierW + 4;
            }
            if (l[3] == 1 && !power.isEmpty()) Hyb.text(power, x, y, makes ? MADE : Hyb.MUTED);
            if (l[4] == 1) Hyb.textRight(count, w - LABEL_PAD, y, Hyb.INK);
            GL11.glPopMatrix();
            return;
        }
    }

    /** A name on one line, or two split at a space when it is wider than {@code room}, each cut to fit. */
    private static List<String> wrap(final String s, final int room) {
        if (Hyb.width(s) <= room) return List.of(s);
        final String[] words = s.split(" ");
        String first = words[0];
        int i = 1;
        while (i < words.length && Hyb.width(first + " " + words[i]) <= room) first += " " + words[i++];
        if (i >= words.length) return List.of(Hyb.fit(first, room));
        return List.of(
            Hyb.fit(first, room),
            Hyb.fit(String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)), room));
    }

    // endregion

    // region How much each card is looked at

    /** How quickly a card comes up or goes down: a share of the way a second. */
    private static final float EASE = 12;

    /** Each card's share looked at, eased from frame to frame; and when it was last eased. */
    private final Map<UUID, Float> attention = new HashMap<>();
    private long attentionAt;
    /** The card the crosshair was over last frame: a card being read stays up. */
    @Nullable
    private UUID overCard;

    /**
     * How much each card is looked at, by card, easing in and out: with Focus on, whole with the crosshair on its
     * machine (its ghost, through blocks) or on its card, else none; with Focus off, and while placing, none.
     */
    private Map<UUID, Float> attention(final List<Placed> all) {
        final long now = System.nanoTime();
        final float step = attentionAt == 0 ? 1 : Math.min(1, (now - attentionAt) / 1e9f * EASE);
        attentionAt = now;
        final boolean focus = PlannerSettings.arFocus() && !LinkPicker.active();
        final Map<UUID, Float> want = new HashMap<>();
        for (final Placed p : all) {
            final UUID id = p.card.id();
            final float w = focus && (id.equals(looked) || id.equals(overCard)) ? 1 : 0;
            want.merge(id, w, Math::max);
        }
        attention.keySet()
            .retainAll(want.keySet());
        for (final Map.Entry<UUID, Float> e : want.entrySet()) {
            final float was = attention.getOrDefault(e.getKey(), 0f);
            attention.put(e.getKey(), was + (e.getValue() - was) * step);
        }
        return attention;
    }

    /**
     * What a box covers on the screen {x0, y0, x1, y1}, from its corners in front of the camera; null when none are.
     */
    @Nullable
    private float[] screenBox(final double[] box, final ScaledResolution sr, final Minecraft mc) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        int corners = 0;
        for (int i = 0; i < 8; i++) {
            final float[] at = screen(
                (i & 1) == 0 ? box[0] : box[3],
                (i & 2) == 0 ? box[1] : box[4],
                (i & 4) == 0 ? box[2] : box[5],
                sr,
                mc);
            if (at == null) continue;
            corners++;
            x0 = Math.min(x0, at[0]);
            y0 = Math.min(y0, at[1]);
            x1 = Math.max(x1, at[0]);
            y1 = Math.max(y1, at[1]);
        }
        return corners == 0 ? null : new float[] { x0, y0, x1, y1 };
    }

    /** How much of a wire shows: as much as the more looked at of the two cards it joins. */
    private static float shown(final Run r, final Map<UUID, Float> seen) {
        return Math.max(seen.getOrDefault(r.conn.from().card.id(), 0f), seen.getOrDefault(r.conn.to().card.id(), 0f));
    }

    // endregion

    // region The wires

    /** A wire's tag, unscaled: its height, icon and the rate's size against the name's. */
    private static final int TAG_H = 26, TAG_ICON = 20;

    private static final float TAG_RATE = 1.5f;

    /** Arrowheads along a wire: how far apart and how fast they move, in blocks and blocks a second. */
    private static final double ARROW_GAP = 0.8, ARROW_SPEED = 0.67;

    /** How far from either end of a wire its arrowheads fade, in blocks. */
    private static final double ARROW_FADE = 0.35;

    /**
     * A wire on the screen this frame: its ends (where it leaves one block and meets the other, cut short at the
     * camera, moved aside into its lane), its arrowheads {x, y, direction x, direction y}, how big its tag is drawn,
     * and the tag's text and box.
     */
    private static final class Run {

        final Conn conn;
        final float ax, ay, bx, by, scale;
        final List<float[]> arrows;
        final String name, rate;
        float tx, ty, tw, th;
        /** Whether its tag shows: a card it joins is looked at, or the crosshair is on it. */
        boolean tag;

        Run(final Conn conn, final float[] a, final float[] b, final List<float[]> arrows, final float scale,
            final String name, final String rate) {
            this.conn = conn;
            this.ax = a[0];
            this.ay = a[1];
            this.bx = b[0];
            this.by = b[1];
            this.arrows = arrows;
            this.scale = scale;
            this.name = name;
            this.rate = rate;
        }

        boolean covers(final float x, final float y) {
            return x >= tx && x < tx + tw && y >= ty && y < ty + th;
        }
    }

    /**
     * Each wire's run on the screen, its tag at its middle. Several wires between the same two cards run side by side
     * in
     * lanes, each tag beside its own lane on its outer side.
     */
    private List<Run> runs(final List<Conn> conns, final Map<UUID, Float> seen, final ScaledResolution sr,
        final Minecraft mc, final PlanSnapshot snap) {
        final List<Run> out = new ArrayList<>();
        final Map<String, List<Conn>> pairs = new java.util.LinkedHashMap<>();
        for (final Conn c : conns) {
            final UUID a = c.from.card.id(), b = c.to.card.id();
            pairs.computeIfAbsent(a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a, key -> new ArrayList<>())
                .add(c);
        }
        final double clock = System.currentTimeMillis() / 1000.0;
        for (final List<Conn> pair : pairs.values()) for (int i = 0; i < pair.size(); i++) {
            final Run r = run(pair.get(i), i - (pair.size() - 1) / 2f, clock, seen, sr, mc, snap);
            if (r == null) continue;
            // Its tag: always, for the machine looked at (or the wire under the crosshair), or never, as set.
            final int labels = PlannerSettings.arWireLabels();
            r.tag = labels == PlannerSettings.WIRE_LABELS_ALWAYS
                || labels == PlannerSettings.WIRE_LABELS_LOOKED && (shown(r, seen) > TAG_FROM || r.conn.same(hovered));
            out.add(r);
        }
        return out;
    }

    /**
     * One wire on the screen, {@code lane} lanes aside (on the same side whichever way it runs, so a pair's two
     * directions part), or null when none of it is in front of the camera. Its arrowheads are set out along it in the
     * world, so they keep their pace and spacing however you move.
     */
    @Nullable
    private Run run(final Conn c, final float lane, final double clock, final Map<UUID, Float> seen,
        final ScaledResolution sr, final Minecraft mc, final PlanSnapshot snap) {
        // From the block of the one nearest the other to the block of the other nearest that: a structure's wire
        // leaves from its side toward where it goes, and meets the near side of where it arrives.
        final double[] start = nearest(c.from, c.to.home), to = nearest(c.to, start), from = nearest(c.from, to);
        final double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Shown from where it leaves the one block to where it meets the other, cut short at the camera.
        final double leave = blockEdge(dx, dy, dz), meet = leave;
        if (leave + meet >= 1) return null;
        final double[] span = inFront(from, to, leave, 1 - meet);
        if (span == null) return null;
        final float[] sa = screen(along(from, to, span[0]), sr, mc), sb = screen(along(from, to, span[1]), sr, mc);
        final double[] middle = along(from, to, (span[0] + span[1]) / 2);
        final float[] mid = screen(middle, sr, mc);
        if (sa == null || sb == null || mid == null) return null;
        // Sized as the cards are: full size near, shrinking with distance.
        final double far = Math.sqrt(
            (middle[0] - camX) * (middle[0] - camX) + (middle[1] - camY) * (middle[1] - camY)
                + (middle[2] - camZ) * (middle[2] - camZ));
        final float scale = (float) (SIZE * Math.min(1, FULL_SIZE_WITHIN / far));
        // The lane: a step across the wire on the screen, measured the same way for both directions of a pair.
        final boolean forward = c.from.card.id()
            .compareTo(c.to.card.id()) < 0;
        final float ux = forward ? sb[0] - sa[0] : sa[0] - sb[0], uy = forward ? sb[1] - sa[1] : sa[1] - sb[1];
        final float run = (float) Math.hypot(ux, uy);
        // Lane to lane: a line, its two edges and a clear gap.
        final float step = run < 1 ? 0 : lane * (lineWidth(scale) + 2 + Math.max(3, 10 * scale)) / run;
        final float ox = -uy * step, oy = ux * step;
        final List<float[]> arrows = new ArrayList<>();
        for (double at = (clock * ARROW_SPEED) % ARROW_GAP; at < length; at += ARROW_GAP) {
            final double t = at / length;
            if (t <= span[0] || t >= span[1]) continue;
            // All the way along the line, fading in as it leaves one end and out as it reaches the other.
            final float fade = (float) Math.min(1, Math.min(t - span[0], span[1] - t) * length / ARROW_FADE);
            final float[] p = screen(along(from, to, t), sr, mc),
                q = screen(along(from, to, t + 0.05 / length), sr, mc);
            if (p == null || q == null) continue;
            final float ax = q[0] - p[0], ay = q[1] - p[1], al = (float) Math.hypot(ax, ay);
            if (al > 0) arrows.add(new float[] { p[0] + ox, p[1] + oy, ax / al, ay / al, fade });
        }
        final PlanSnapshot.Flow f = c.flow;
        final String rate = f == null ? ""
            : f.power() ? com.gtnhplanner.ui.theme.Fmt.power(f.perSecond() / 20) + " EU/t"
                : com.gtnhplanner.ui.theme.Fmt.rate(f.perSecond(), snap.rateUnit(), f.fluid() != null);
        final Run r = new Run(
            c,
            new float[] { sa[0] + ox, sa[1] + oy },
            new float[] { sb[0] + ox, sb[1] + oy },
            arrows,
            scale,
            Hyb.fit(c.what(), 120),
            rate);
        r.tw = (TAG_ICON + 9 + Math.max(Hyb.width(r.name), Hyb.width(rate) * TAG_RATE) + 5) * scale;
        r.th = TAG_H * scale;
        // On the line when it runs alone; beside its lane, on the side away from the other wires, when it has company.
        float cx = mid[0] + ox, cy = mid[1] + oy;
        final float side = (float) Math.hypot(ox, oy);
        if (side > 0) {
            final float nx = ox / side, ny = oy / side;
            final float apart = Math.abs(nx) * r.tw / 2 + Math.abs(ny) * r.th / 2 + lineWidth(scale) / 2 + 3;
            cx += nx * apart;
            cy += ny * apart;
        }
        r.tx = cx - r.tw / 2;
        r.ty = cy - r.th / 2;
        return r;
    }

    private static double[] along(final double[] a, final double[] b, final double t) {
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }

    /** Where a wire from end to end by (dx, dy, dz) leaves a block centred on its end, as a share of the wire. */
    private static double blockEdge(final double dx, final double dy, final double dz) {
        return 0.5 / Math.max(1e-9, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
    }

    /** Each placement's blocks' middles in the world, by its structure and where it is; kept from frame to frame. */
    @Nullable
    private Map<String, double[][]> cells;

    /** The middle of a placement's block nearest a point: one of its structure's, or its own block. */
    private double[] nearest(final Placed p, final double[] to) {
        final double[][] all = cells(p);
        double[] best = all[0];
        double bestD = Double.MAX_VALUE;
        for (final double[] c : all) {
            final double dx = c[0] - to[0], dy = c[1] - to[1], dz = c[2] - to[2];
            final double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private double[][] cells(final Placed p) {
        if (cells == null || cells.size() > 512) cells = new HashMap<>();
        final com.gtnhplanner.ui.gt.StructureGhosts.Ghost g = com.gtnhplanner.ui.gt.StructureGhosts
            .peek(p.card.machine(), p.card.needs(), p.size);
        final String key = System.identityHashCode(g) + " " + p.x + " " + p.y + " " + p.z + " " + p.facing;
        final double[][] known = cells.get(key);
        if (known != null) return known;
        final List<int[]> blocks = g == null ? List.of() : com.gtnhplanner.ui.gt.StructureGhosts.blocks(g);
        final double[][] out;
        if (blocks.isEmpty()) out = new double[][] { { p.x + 0.5, p.y + 0.5, p.z + 0.5 } };
        else {
            out = new double[blocks.size()][];
            for (int i = 0; i < out.length; i++) {
                final int[] b = blocks.get(i);
                final int[] at = com.gtnhplanner.ui.gt.StructureGhosts.turn(b[0], b[2], p.facing);
                out[i] = new double[] { p.x + at[0] + 0.5, p.y + b[1] + 0.5, p.z + at[1] + 0.5 };
            }
        }
        cells.put(key, out);
        return out;
    }

    /**
     * The part of a segment's stretch from {@code t0} to {@code t1} (fractions of it) in front of the camera, or null
     * when none is.
     */
    @Nullable
    private double[] inFront(final double[] a, final double[] b, final double t0, final double t1) {
        final float near = 0.1f;
        final double za = eyeZ(a), zb = eyeZ(b);
        double lo = t0, hi = t1;
        final double slope = zb - za;
        if (slope == 0) return za < -near ? new double[] { lo, hi } : null;
        // Where the segment crosses the camera's near side; in front is the part going away from the camera.
        final double cross = (-near - za) / slope;
        if (slope > 0) hi = Math.min(hi, cross);
        else lo = Math.max(lo, cross);
        return lo < hi ? new double[] { lo, hi } : null;
    }

    /** How far in front of the camera a point is, negative in front (the camera looks down its -z). */
    private float eyeZ(final double[] p) {
        final float x = (float) (p[0] - camX), y = (float) (p[1] - camY), z = (float) (p[2] - camZ);
        return modelview.get(2) * x + modelview.get(6) * y + modelview.get(10) * z + modelview.get(14);
    }

    // region Making room

    /**
     * Each card's offset from its home just above its machine, in its own widths (right) and heights (up), kept from
     * frame to frame: where it is going {x, y} and where it is drawn now {x, y}.
     */
    private final Map<UUID, float[]> offsets = new HashMap<>();
    private long laidOut;

    /**
     * Moves cards out of each other's way, as map labels are: the one looked at first, then nearest first, each keeps
     * where it is (at home, just above its machine) while that is clear and otherwise takes the clear place nearest
     * home, aside, up or down past its machine, a line pointing back to it. Clear is on the screen and off the cards
     * already placed, the wires' tags, its own machine, and the machine being placed or looked at. A card that moved
     * goes home again once that is clear, and moves quickly rather than gliding.
     */
    private void layout(final List<Placed> shown, final List<Run> runs, final List<Placed> all,
        final ScaledResolution sr) {
        final long now = System.currentTimeMillis();
        final float ease = laidOut == 0 ? 1 : (float) (1 - Math.exp(-(now - laidOut) / 50.0));
        laidOut = now;
        final float sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        final List<float[]> taken = new ArrayList<>();
        for (final Run r : runs) if (r.tag) taken.add(new float[] { r.tx, r.ty, r.tw, r.th });
        // The minimap is drawn over the cards: they keep out from under it.
        final int[] map = Minimap.bounds(sr);
        if (map != null) taken.add(new float[] { map[0], map[1], map[2], map[3] });
        final int[] pins = PinList.bounds();
        if (pins != null) taken.add(new float[] { pins[0], pins[1], pins[2], pins[3] });
        final int[] title = LinkPicker.titleBounds();
        if (title != null) taken.add(new float[] { title[0], title[1], title[2], title[3] });
        // No card covers the machine being placed or the one looked at (each card keeps off its own anyway).
        final List<Placed> keep = new ArrayList<>();
        for (final Placed p : all) if (p.rect != null && (p.preview || p.a > 0.5f)) keep.add(p);
        for (final Placed p : shown) {
            final float[] own = around(p.rect);
            final List<float[]> others = new ArrayList<>(taken);
            for (final Placed k : keep) if (k != p) others.add(around(k.rect));
            final float[] o = offsets.computeIfAbsent(p.card.id(), id -> new float[4]);
            float bestX = o[0], bestY = o[1];
            final boolean clear = clear(p, bestX, bestY, others, own, sw, sh);
            final float now0 = cost(p, bestX, bestY);
            if (!clear || now0 > 0) {
                // A clear place, nearest home first; where it is now is clear too, only one much nearer home. Far
                // enough aside and down to pass its machine, in steps that keep the search small.
                float best = clear ? now0 * 0.6f : Float.MAX_VALUE;
                final float across = 1.5f + own[2] / p.w / 2, down = 1.5f + own[3] / p.h;
                final float stepX = Math.max(0.125f, across / 20), stepY = Math.max(0.25f, (down + 1.5f) / 28);
                for (int j = -(int) (down / stepY); j * stepY <= 1.5f; j++)
                    for (int i = -(int) (across / stepX); i * stepX <= across; i++) {
                        final float dx = i * stepX, dy = j * stepY, c = cost(p, dx, dy);
                        if (c < best && clear(p, dx, dy, others, own, sw, sh)) {
                            best = c;
                            bestX = dx;
                            bestY = dy;
                        }
                    }
            }
            o[0] = bestX;
            o[1] = bestY;
            o[2] += (o[0] - o[2]) * ease;
            o[3] += (o[1] - o[3]) * ease;
            // Kept on the screen, whatever happens.
            p.left = Math.max(2, Math.min(sw - 2 - p.w, p.sx + o[2] * p.w - p.w / 2));
            p.top = Math.max(2, Math.min(sh - 2 - p.h, p.sy - o[3] * p.h - p.h / 2));
            taken.add(new float[] { p.sx + o[0] * p.w - p.w / 2, p.sy - o[1] * p.h - p.h / 2, p.w, p.h });
        }
    }

    /** A machine's rectangle on the screen {x0, y0, x1, y1} with room around it, as {x, y, w, h}. */
    private static float[] around(final float[] r) {
        return new float[] { r[0] - GHOST_GAP, r[1] - GHOST_GAP, r[2] - r[0] + 2 * GHOST_GAP,
            r[3] - r[1] + 2 * GHOST_GAP };
    }

    /**
     * How far from home an offset takes a card, on the screen; going up costs a little more than going aside, and down
     * more again.
     */
    private static float cost(final Placed p, final float dx, final float dy) {
        return Math.abs(dx) * p.w + (dy >= 0 ? 1.15f : 1.4f) * Math.abs(dy) * p.h;
    }

    /**
     * Whether a card at an offset is on the screen (where it fits) and keeps clear of everything placed and of its own
     * machine.
     */
    private static boolean clear(final Placed p, final float dx, final float dy, final List<float[]> taken,
        final float[] own, final float sw, final float sh) {
        final float x = p.sx + dx * p.w - p.w / 2, y = p.sy - dy * p.h - p.h / 2;
        if (p.w < sw - 4 && (x < 2 || x + p.w > sw - 2) || p.h < sh - 4 && (y < 2 || y + p.h > sh - 2)) return false;
        // Its own machine, a touch inside its margin, so home (touching it) counts as clear.
        if (x < own[0] + own[2] - 0.5f && own[0] + 0.5f < x + p.w
            && y < own[1] + own[3] - 0.5f
            && own[1] + 0.5f < y + p.h) return false;
        final float gx = x - CARD_GAP, gy = y - CARD_GAP, w = p.w + 2 * CARD_GAP, h = p.h + 2 * CARD_GAP;
        for (final float[] t : taken)
            if (gx < t[0] + t[2] && t[0] < gx + w && gy < t[1] + t[3] && t[1] < gy + h) return false;
        return true;
    }

    // endregion

    /**
     * A card where the layout put it, off its machine, with a line from its nearest edge to the middle of the machine's
     * top, ending in a small pin.
     */
    private static void card(final Placed p, final PlanSnapshot snap, final boolean ringed) {
        final boolean home = p.bx >= p.left && p.bx < p.left + p.w && p.by >= p.top && p.by < p.top + p.h;
        if (!home) {
            final int line = ringed ? 0x90000000 | Hyb.LIT & 0xFFFFFF : 0x90A4A8B0;
            final float ex = Math.max(p.left, Math.min(p.left + p.w, p.bx)),
                ey = Math.max(p.top, Math.min(p.top + p.h, p.by));
            band(ex, ey, p.bx, p.by, 2, line);
            Hyb.rect(p.bx - 2, p.by - 2, 4, 4, line);
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(Math.round(p.left), Math.round(p.top), 0);
        GL11.glScalef(p.scale, p.scale, 1);
        com.gtnhplanner.ui.card.CleanCardView.draw(p.card, snap.rateUnit(), ringed);
        GL11.glPopMatrix();
    }

    /** A point's distance from a segment on the screen. */
    private static float distance(final float px, final float py, final float ax, final float ay, final float bx,
        final float by) {
        final float dx = bx - ax, dy = by - ay;
        final float len2 = dx * dx + dy * dy;
        final float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /**
     * A wire as the board draws one, in the resource's colour on a dark edge, with arrowheads sliding along towards the
     * card it feeds; faint where nothing flows yet; edged in the highlight when the crosshair is on it.
     */
    private static void wire(final Run r, final boolean lit) {
        if (Math.hypot(r.bx - r.ax, r.by - r.ay) < 4) return;
        final int colour = 0xFF000000 | r.conn.line.color() & 0xFFFFFF;
        final boolean flowing = r.conn.line.flowing();
        final float w = lineWidth(r.scale), arrow = arrowWidth(r.scale);
        // Its own colour always; lit, its edge is the highlight instead of dark.
        final int edge = lit ? Hyb.LIT : flowing ? 0xB0000000 : 0x70000000;
        band(r.ax, r.ay, r.bx, r.by, w + (lit ? 3 : 2), edge);
        band(r.ax, r.ay, r.bx, r.by, w, flowing || lit ? colour : colour & 0x00FFFFFF | 0x99000000);
        // The arrowheads, set out along the wire in the world.
        final float head = 5.6f * arrow, half = 3f * arrow;
        final int tip = Hyb.mix(colour, 0xFFFFFFFF, 0.6f);
        for (final float[] a : r.arrows) {
            final float px = a[0], py = a[1], ux = a[2], uy = a[3], fade = a[4];
            final float fx = px + ux * head * 0.6f, fy = py + uy * head * 0.6f;
            final float bx = px - ux * head * 0.4f, by = py - uy * head * 0.4f;
            Hyb.triangle(
                fx + ux,
                fy + uy,
                bx - uy * (half + 1),
                by + ux * (half + 1),
                bx + uy * (half + 1),
                by - ux * (half + 1),
                faded(lit ? Hyb.LIT : 0xB0000000, fade));
            Hyb.triangle(fx, fy, bx - uy * half, by + ux * half, bx + uy * half, by - ux * half, faded(tip, fade));
        }
    }

    /** The size arrowheads are measured in, on the screen, at a scale: they stop growing early. */
    private static float arrowWidth(final float scale) {
        return Math.max(1.1f, Math.min(1.5f, 3f * scale));
    }

    /** How thick a wire is drawn on the screen at a scale. */
    private static float lineWidth(final float scale) {
        return 2 * Math.max(1.2f, 3f * scale);
    }

    /** A colour at a share of its own opacity. */
    private static int faded(final int argb, final float share) {
        return (int) ((argb >>> 24) * share) << 24 | argb & 0xFFFFFF;
    }

    /** A straight band {@code w} wide from one point to another. */
    private static void band(final float ax, final float ay, final float bx, final float by, final float w,
        final int colour) {
        final float len = (float) Math.hypot(bx - ax, by - ay), h = w / 2;
        final float nx = -(by - ay) / len * h, ny = (bx - ax) / len * h;
        Hyb.triangle(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, colour);
        Hyb.triangle(ax + nx, ay + ny, bx - nx, by - ny, ax - nx, ay - ny, colour);
    }

    /**
     * What a wire carries, at its middle: the item or fluid, how much in large type, and its name small under it, on a
     * plain soft dark backing. The wire the crosshair is on is ringed in the highlight.
     */
    private static void tag(final Run r, final boolean lit) {
        GL11.glPushMatrix();
        GL11.glTranslatef(r.tx, r.ty, 0);
        GL11.glScalef(r.scale, r.scale, 1);
        final int w = Math.round(r.tw / r.scale), h = TAG_H;
        Hyb.roundRect(0, 0, w, h, 4, 0xC8141518);
        if (lit) Hyb.ring(-1, -1, w + 2, h + 2, 1, 0xD0000000 | Hyb.LIT & 0xFFFFFF);
        final PlanSnapshot.Flow f = r.conn.flow;
        if (f != null) {
            if (f.power()) RecipeCard.euIcon(4, 3, TAG_ICON);
            else Hyb.icon(f.item(), f.fluid(), 4, 3, TAG_ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        final int text = 4 + TAG_ICON + 5;
        Hyb.text(r.rate, text, 3, TAG_RATE, Hyb.INK);
        Hyb.text(r.name, text, 3 + 8 * TAG_RATE + 2, Hyb.MUTED);
        GL11.glPopMatrix();
    }

    // endregion

    /**
     * A card's home on the GUI, just above its machine (never on it: the machine stays in view), where its line ends
     * (the middle of the machine's top), and its scale: {@link #SIZE} within {@link #FULL_SIZE_WITHIN} blocks,
     * shrinking with distance beyond, and smaller as it pops up. False when its machine is off the screen.
     */
    private boolean project(final Placed p, final ScaledResolution sr, final Minecraft mc) {
        final float[] r = p.rect;
        if (r == null || r[2] < 0 || r[0] > sr.getScaledWidth() || r[3] < 0 || r[1] > sr.getScaledHeight())
            return false;
        final float[] top = screen(p.home[0], p.box[4], p.home[2], sr, mc);
        p.bx = top != null ? top[0] : (r[0] + r[2]) / 2;
        p.by = top != null ? top[1] : r[1];
        p.scale = (float) (SIZE * Math.min(1, FULL_SIZE_WITHIN / Math.max(0.5, p.distance))) * sized(p.a);
        p.w = com.gtnhplanner.ui.card.CleanCardView.width() * p.scale;
        p.h = com.gtnhplanner.ui.card.CleanCardView.height(p.card) * p.scale;
        p.sx = Math.max(r[0], Math.min(r[2], p.bx));
        p.sy = r[1] - GHOST_GAP - p.h / 2;
        return p.scale > 0.02f;
    }

    @Nullable
    private float[] screen(final double[] p, final ScaledResolution sr, final Minecraft mc) {
        return screen(p[0], p[1], p[2], sr, mc);
    }

    @Nullable
    private float[] screen(final double x, final double y, final double z, final ScaledResolution sr,
        final Minecraft mc) {
        out.clear();
        if (!GLU.gluProject(
            (float) (x - camX),
            (float) (y - camY),
            (float) (z - camZ),
            modelview,
            projection,
            viewport,
            out)) return null;
        final float wz = out.get(2);
        if (wz <= 0 || wz >= 1) return null;
        final int f = sr.getScaleFactor();
        return new float[] { out.get(0) / f, (mc.displayHeight - out.get(1)) / f };
    }

    // endregion
}
