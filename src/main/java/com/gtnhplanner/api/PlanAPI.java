package com.gtnhplanner.api;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.StatCollector;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.UndoHistory;

import codechicken.nei.NEIClientConfig;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.bookmark.BookmarkPayload;

public final class PlanAPI {

    /** NBT key used to store the encoded graph in the share ItemStack. */
    public static final String DATA_KEY = "gtnhplanner_data";
    /** What shares were called before the rename: still read. */
    private static final String LEGACY_DATA_KEY = "plannh_data";

    @Nonnull
    public static Graph getActiveGraph() {
        return Plan.getActiveGraph();
    }

    /** The active slot's undo history. */
    public static UndoHistory undoHistory() {
        return undoHistory(getActiveGraph());
    }

    /** The undo history of the slot {@code graph} is; see {@link Plan#history(Graph)}. */
    public static UndoHistory undoHistory(final Graph graph) {
        return Plan.getInstance()
            .history(graph);
    }

    /**
     * Runs {@code edit} as one undo step; no-op edits leave no trace. An edit that changed the graph moves its version
     * (at least as a layout edit), so everything keyed on it, the save's kept encodings too, sees the change.
     */
    public static void recordEdit(final Graph graph, final Runnable edit) {
        final UndoHistory history = undoHistory(graph);
        final String before = history.beginEdit(graph);
        edit.run();
        if (history.commitEdit(before, graph)) graph.touchLayout();
    }

    /**
     * Encodes the given graph and sends it as an NEI item-link chat message.
     * Other GTNH Planner clients see an import link; vanilla clients see a dirt-item
     * tooltip with a bookmark prompt.
     */
    public static void shareGraph(final Graph graph) {
        final String encoded = Serializer.encode(graph);
        final ItemStack stack = createShareStack();
        stack.getTagCompound()
            .setString(DATA_KEY, encoded);
        try {
            NEIClientUtils.sendChatItemLink(
                BookmarkPayload.of(stack, null)
                    .toNBT());
        } catch (final NoClassDefFoundError | NoSuchMethodError older) {
            // NEI before GTNH 2.9's beta-3 links an ItemStack itself and has no BookmarkPayload.
            try {
                NEIClientUtils.class.getMethod("sendChatItemLink", ItemStack.class)
                    .invoke(null, stack);
            } catch (final ReflectiveOperationException e) {
                GtnhPlanner.LOG.warn("[compat] this NEI cannot link items in chat", e);
            }
        }
    }

    /** Copies the serialised graph to the system clipboard. */
    public static void copyToClipboard(final Graph graph) {
        GuiScreen.setClipboardString(Serializer.encode(graph));
    }

    /**
     * Reads graph data from the system clipboard and deserialises it.
     *
     * @return the deserialised graph, or {@code null} if clipboard is empty
     *         or the data is invalid.
     */
    @Nullable
    public static Graph importFromClipboard() {
        final String data = GuiScreen.getClipboardString();
        if (data.isEmpty()) return null;
        try {
            return Serializer.decode(data);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Extracts GTNH Planner share data from a serialised ItemStack NBT string and
     * deserialises it. This is the receiver side of {@link #shareGraph(Graph)}.
     *
     * @param nbtString the full serialised NBT from the /nei_bookmark command
     * @return the deserialised graph, or {@code null} if the NBT doesn't
     *         contain a {@code gtnhplanner_data} tag or the data is invalid.
     */
    @Nullable
    public static Graph importFromNBT(final String nbtString) {
        try {
            final NBTTagCompound nbt = (NBTTagCompound) JsonToNBT.func_150315_a(nbtString);
            if (!nbt.hasKey("tag")) return null;
            final NBTTagCompound tag = nbt.getCompoundTag("tag");
            final String key = tag.hasKey(DATA_KEY) ? DATA_KEY : LEGACY_DATA_KEY;
            if (!tag.hasKey(key)) return null;
            return Serializer.decode(tag.getString(key));
        } catch (final NBTException e) {
            return null;
        }
    }

    /**
     * Checks whether a serialised ItemStack NBT string contains GTNH Planner share
     * data, without fully deserialising the graph.
     */
    public static boolean containsPlanData(@Nonnull final String nbtString) {
        try {
            final NBTTagCompound nbt = (NBTTagCompound) JsonToNBT.func_150315_a(nbtString);
            if (!nbt.hasKey("tag")) return false;
            final NBTTagCompound tag = nbt.getCompoundTag("tag");
            return tag.hasKey(DATA_KEY) || tag.hasKey(LEGACY_DATA_KEY);
        } catch (final NBTException e) {
            return false;
        }
    }

    /**
     * Wraps a deserialised graph in a new "Imported" slot, switches to it,
     * and persists. Does not open any GUI.
     */
    public static void importGraph(@Nonnull final Graph graph) {
        final Plan plan = Plan.getInstance();
        graph.setName(StatCollector.translateToLocal("gtnhplanner.share.slot_imported"));
        plan.getGraphs()
            .add(graph);
        plan.setActiveIndex(
            plan.getGraphs()
                .size() - 1);
        save();
    }

    /**
     * Creates a dirt ItemStack with GTNH Planner share display properties
     * (localised name and lore). Both the sender (share button) and
     * receiver (chat hover tooltip) build the same item so the visual
     * is consistent on both sides.
     */
    @Nonnull
    public static ItemStack createShareStack() {
        final ItemStack stack = new ItemStack(Blocks.dirt);
        final NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", StatCollector.translateToLocal("gtnhplanner.share.item_name"));
        final NBTTagList lore = new NBTTagList();
        lore.appendTag(new NBTTagString(StatCollector.translateToLocal("gtnhplanner.share.lore_shared")));
        lore.appendTag(new NBTTagString(StatCollector.translateToLocal("gtnhplanner.share.click_to_import")));
        display.setTag("Lore", lore);
        stack.setTagInfo("display", display);
        return stack;
    }

    /** Plans in the last save, to tell when one was deleted; -1 before the first save of the session. */
    private static int lastSavedPlans = -1;
    private static boolean backedUpThisSession;

    /**
     * Keeps old saves in a {@code backups} folder beside the save: one per session (the state the session started
     * from), and one before any save with fewer plans than the last (a plan was deleted). The newest ten are kept.
     */
    private static void backUp(final File saveFile, final int plans) {
        final boolean fewer = lastSavedPlans >= 0 && plans < lastSavedPlans;
        lastSavedPlans = plans;
        if (!saveFile.isFile() || backedUpThisSession && !fewer) return;
        backedUpThisSession = true;
        try {
            final File dir = new File(saveFile.getParentFile(), "backups");
            dir.mkdirs();
            final String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            final File copy = new File(dir, "plans-" + stamp + (fewer ? "-before-delete" : "") + ".dat");
            Files.copy(saveFile.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            final File[] all = dir.listFiles((d, name) -> name.startsWith("plans-") && name.endsWith(".dat"));
            if (all != null && all.length > 10) {
                java.util.Arrays.sort(all, java.util.Comparator.comparing(File::getName));
                for (int i = 0; i < all.length - 10; i++) all[i].delete();
            }
        } catch (final Exception e) {
            com.gtnhplanner.GtnhPlanner.LOG.warn("Could not back up the plans", e);
        }
    }

    /** A save waiting for the writer: the file, what goes in it, and how many plans it holds. */
    private record Pending(File file, com.google.gson.JsonObject tree, int plans) {}

    private static final java.util.concurrent.atomic.AtomicReference<Pending> PENDING = new java.util.concurrent.atomic.AtomicReference<>();

    /** Writes saves off the game thread, so an edit never waits on the disk; the newest save waiting wins. */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors
        .newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "GTNH Planner save");
            t.setDaemon(true);
            return t;
        });

    static {
        // A save still waiting when the game closes is written before it goes.
        Runtime.getRuntime()
            .addShutdownHook(new Thread(PlanAPI::writePending, "GTNH Planner save on exit"));
    }

    /**
     * Saves every plan. Each plan is encoded here, on the game thread, where the plans are edited, and only those
     * changed since they were last encoded ({@link Serializer#planTree}); the text is made and the disk written on the
     * writer's thread.
     */
    public static void save() {
        // The tour's own plans are never written.
        if (Plan.sandboxed()) return;
        final Pending save = new Pending(
            getSaveFile(),
            Serializer.planTree(Plan.getInstance()),
            Plan.getInstance()
                .getGraphs()
                .size());
        if (PENDING.getAndSet(save) == null) WRITER.execute(PlanAPI::writePending);
    }

    /** Writes the newest save waiting, if any: a backup first when one is due, then the file, replaced whole. */
    private static synchronized void writePending() {
        final Pending save = PENDING.getAndSet(null);
        if (save == null) return;
        try {
            save.file()
                .getParentFile()
                .mkdirs();
            backUp(save.file(), save.plans());
            final File next = new File(
                save.file()
                    .getParentFile(),
                save.file()
                    .getName() + ".new");
            Files.writeString(next.toPath(), Serializer.GSON.toJson(save.tree()), StandardCharsets.UTF_8);
            Files.move(
                next.toPath(),
                save.file()
                    .toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final Exception e) {
            GtnhPlanner.LOG.warn("Could not save the plans", e);
        }
    }

    /** The plans' folder for this world (or the game's, outside one). */
    private static File folder(final String name) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String worldName = NEIClientConfig.getWorldPath();
        if (worldName != null && !worldName.isEmpty())
            return new File(mc.mcDataDir, "saves/NEI/" + worldName + "/" + name);
        return new File(mc.mcDataDir, name);
    }

    public static File getSaveFile() {
        return new File(folder("gtnhplanner"), "plans.dat");
    }

    /** Where plans were kept before the mod was renamed: read while there is no save of the new name. */
    public static File getLegacySaveFile() {
        return new File(folder("plannh"), "plannh.dat");
    }

}
