package com.gtnhplanner.power;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.ui.Resources;

/**
 * The fluid tank card (the website's Tank, its free mirror of every fluid Canner recipe): it empties a filled cell into
 * its fluid and the empty cell, or fills empty cells, for nothing and at once. The litres a cell holds are the game's
 * own (Forge's fluid container registry, where every cell, bucket and capsule is listed), never a guessed 1000.
 * <p>
 * Like the custom rate card it holds nothing until a port is dropped on it, and lets go when nothing is wired to it:
 * a cell it is given is emptied, a cell asked of it is filled, a fluid asked of it comes out of cells, a fluid given to
 * it goes into them. A card, one craft a second: its count is the cells a second it handles. Settings, as strings on
 * {@link Node#powerSettings}: {@link #MODE} and {@link #CELL} (the filled container's resource key, absent while
 * empty).
 */
public final class FluidTank {

    /** Its source id, stored in plans: never rename. */
    public static final String ID = "fluid-tank";
    public static final String MODE = "mode", CELL = "cell";
    public static final String EMPTY = "empty", FILL = "fill";

    public static final PowerSetting.Select MODE_SETTING = new PowerSetting.Select(
        MODE,
        "Mode",
        List.of(new PowerSetting.Option(EMPTY, "Empty cells"), new PowerSetting.Option(FILL, "Fill cells")),
        EMPTY);

    /** Its entry for {@link PowerRegistry#get}: never in the generator catalog. */
    public static final PowerSource SOURCE = new PowerSource(
        ID,
        "Fluid Tank",
        PowerGroup.OTHER,
        null,
        "Empties cells into their fluid, or fills them, free and instant. Wire a cell or a fluid to it.",
        List.of(MODE_SETTING),
        read -> new PowerModel(0, List.of(), List.of(), List.of()));

    private FluidTank() {}

    public static boolean is(@Nullable final Node node) {
        return node != null && ID.equals(node.powerSource);
    }

    /** The filled container it handles, or null while it is empty. */
    @Nullable
    public static String cell(final Node node) {
        final String key = node.powerSettings.get(CELL);
        return key == null || key.isEmpty() ? null : key;
    }

    public static boolean emptying(final Node node) {
        return !FILL.equals(MODE_SETTING.value(node.powerSettings.get(MODE)));
    }

    /** A filled container, its fluid (the litres it holds) and the container it leaves. */
    public record Pair(ItemStack cell, FluidStack fluid, ItemStack empty) {}

    /** What a filled container's key holds; null when it is no container this game knows. */
    @Nullable
    public static Pair pair(@Nullable final String cellKey) {
        if (cellKey == null) return null;
        final ItemStack cell = Resources.item(cellKey);
        if (cell == null) return null;
        cell.stackSize = 1;
        final FluidStack fluid = FluidContainerRegistry.getFluidForFilledItem(cell);
        final ItemStack empty = FluidContainerRegistry.drainFluidContainer(cell);
        if (fluid == null || fluid.amount <= 0 || empty == null) return null;
        return new Pair(cell, fluid, empty);
    }

    /**
     * The cell a fluid goes in: the plain Empty Cell's, else any cell's, else any container's (a bucket); null when the
     * fluid has none.
     */
    @Nullable
    public static String cellFor(final String fluidKey) {
        // Asked every frame while a wire is in hand (which cards would take it): the registry is walked once a fluid.
        final String known = CELLS.computeIfAbsent(fluidKey, k -> {
            final String found = findCell(k);
            return found == null ? "" : found;
        });
        return known.isEmpty() ? null : known;
    }

    private static final Map<String, String> CELLS = new java.util.concurrent.ConcurrentHashMap<>();

    @Nullable
    private static String findCell(final String fluidKey) {
        final FluidStack fluid = Resources.fluid(fluidKey);
        if (fluid == null) return null;
        String cell = null, other = null;
        for (final FluidContainerRegistry.FluidContainerData data : FluidContainerRegistry
            .getRegisteredFluidContainerData()) {
            if (data == null || data.fluid == null
                || data.filledContainer == null
                || data.emptyContainer == null
                || !data.fluid.isFluidEqual(fluid)) continue;
            final String key = com.gtnhplanner.ui.card.CardDefaults.itemKey(data.filledContainer);
            if (PLAIN_CELL.equals(String.valueOf(Item.itemRegistry.getNameForObject(data.emptyContainer.getItem()))))
                return key;
            if (cell == null && data.emptyContainer.getDisplayName()
                .toLowerCase(java.util.Locale.ROOT)
                .contains("cell")) cell = key;
            if (other == null) other = key;
        }
        return cell != null ? cell : other;
    }

    /** IC2's Empty Cell, which GregTech fills with every fluid it has. */
    private static final String PLAIN_CELL = "IC2:itemCellEmpty";

    /**
     * The pair a resource on a side makes it handle ({@code supply}: it gives that resource out, else takes it in), as
     * {mode, cell key}; null when it cannot (an empty cell, a fluid with no cell, an item that holds no fluid).
     */
    @Nullable
    public static String[] take(final String key, final boolean supply) {
        if (key.startsWith("fluid:")) {
            final String cell = cellFor(key);
            // A fluid asked of it comes out of cells; a fluid given to it goes into them.
            return cell == null ? null : new String[] { supply ? EMPTY : FILL, cell };
        }
        if (pair(key) == null) return null;
        // A cell given to it is emptied; a cell asked of it is filled.
        return new String[] { supply ? FILL : EMPTY, key };
    }

    /** Takes the pair for a resource on a side; false, changing nothing, when it cannot. */
    public static boolean hold(final Node node, final String key, final boolean supply) {
        final String[] pick = take(key, supply);
        if (pick == null) return false;
        node.powerSettings.put(MODE, pick[0]);
        node.powerSettings.put(CELL, pick[1]);
        return true;
    }

    /** Lets go of its pair, keeping its mode; the caller rebuilds the ports. */
    public static void release(final Node node) {
        node.powerSettings.remove(CELL);
    }

    /** A new card's settings: none held. */
    public static Map<String, String> fresh() {
        return new java.util.LinkedHashMap<>();
    }

    /**
     * Its ports, one craft a second: emptying, a cell in and its fluid and the empty cell out; filling, the fluid and
     * an
     * empty cell in and the cell out. None while it holds nothing.
     */
    public static void build(final Node node) {
        node.inputs.clear();
        node.outputs.clear();
        node.properties.clear();
        node.powerModel = SOURCE.compute(node.powerSettings);
        node.machineName = SOURCE.name();
        node.properties.put(RecipePropertyAPI.DURATION_TICKS, 20);
        final Pair p = pair(cell(node));
        if (p == null) return;
        final Port<ItemStack> cell = items(p.cell()), empty = items(p.empty());
        final FluidStack litres = p.fluid()
            .copy();
        final Port<FluidStack> fluid = new Port<>(RecipePropertyAPI.FLUID, litres, 1f);
        fluid.setExactAmount(litres.amount);
        if (emptying(node)) {
            node.inputs.add(cell);
            node.outputs.add(fluid);
            node.outputs.add(empty);
        } else {
            node.inputs.add(fluid);
            node.inputs.add(empty);
            node.outputs.add(cell);
        }
    }

    private static Port<ItemStack> items(final ItemStack stack) {
        final ItemStack one = stack.copy();
        one.stackSize = 1;
        final Port<ItemStack> port = new Port<>(RecipePropertyAPI.ITEM, one, 1f);
        port.setExactAmount(1);
        return port;
    }
}
