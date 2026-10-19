package com.gtnhplanner.machines.game;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.importer.game.GameIds;

/**
 * The game's side of an Extreme Entity Crusher card: the mob on the node (read by data/provider/MobDropsProvider from
 * kubatech's table, built by machines/MobDrops), and its {@code metadata.eec} lined up with the card's outputs as they
 * are now, for the website's crusher maths (WebCards). Knows nothing of kubatech's classes, so it loads without them.
 */
public final class EecCards {

    private EecCards() {}

    /** The mob's entity name ({@code Zombie}), its name as the game shows it, and the metadata as built. */
    public static final RecipeProperty<String> MOB = RecipeProperty.<String>builder("gtnhplanner.eec.mob", "")
        .build();
    public static final RecipeProperty<String> MOB_NAME = RecipeProperty.<String>builder("gtnhplanner.eec.mob_name", "")
        .build();
    public static final RecipeProperty<String> METADATA = RecipeProperty.<String>builder("gtnhplanner.eec.metadata", "")
        .build();
    /** The website id of each output the metadata's outputs describe, one a line, in the same order. */
    public static final RecipeProperty<String> OUTPUT_IDS = RecipeProperty
        .<String>builder("gtnhplanner.eec.output_ids", "")
        .build();
    /** The drops a plain crusher never makes (they need an enchantment, a weapon, a dimension...), by name. */
    public static final RecipeProperty<String> GATED = RecipeProperty.<String>builder("gtnhplanner.eec.gated", "")
        .build();

    public static boolean isEec(@Nullable final Node node) {
        return node != null && !node.isPower() && isEec(node.properties);
    }

    public static boolean isEec(final Map<RecipeProperty<?>, Object> props) {
        return props.get(MOB) instanceof final String s && !s.isEmpty();
    }

    /** The mob's entity name, or null on any other card. */
    @Nullable
    public static String mob(final Node node) {
        return isEec(node) ? (String) node.properties.get(MOB) : null;
    }

    /** The mob's name as the game shows it ("Zombie"), or null on any other card. */
    @Nullable
    public static String mobName(final Node node) {
        if (!isEec(node)) return null;
        return node.properties.get(MOB_NAME) instanceof final String s && !s.isEmpty() ? s : mob(node);
    }

    /** The drops it never makes in a plain crusher, by name; empty when it makes every one. */
    public static String gated(final Node node) {
        return node.properties.get(GATED) instanceof final String s ? s : "";
    }

    /**
     * The card's {@code metadata.eec}, its outputs lined up with the card's outputs as they are now (by item: an output
     * the card lacks, the XP in a game with no Liquid XP, is left out); null on any other card.
     */
    @Nullable
    public static JsonObject metadata(final Node node) {
        if (!isEec(node) || !(node.properties.get(METADATA) instanceof final String text)) return null;
        final JsonObject eec;
        try {
            eec = new JsonParser().parse(text)
                .getAsJsonObject();
        } catch (final RuntimeException e) {
            return null;
        }
        final String[] ids = node.properties.get(OUTPUT_IDS) instanceof final String s ? s.split("\n", -1)
            : new String[0];
        final JsonArray metas = eec.getAsJsonArray("outputs");
        final JsonArray lined = new JsonArray();
        for (final Port<?> port : node.outputs) {
            final String id = websiteId(port);
            JsonElement meta = null;
            for (int i = 0; i < ids.length && i < metas.size() && meta == null; i++)
                if (ids[i].equals(id)) meta = metas.get(i);
            // An output not built from the table (none today) yields nothing the maths know of.
            lined.add(meta != null ? meta : new JsonObject());
        }
        eec.add("outputs", lined);
        return eec;
    }

    /** A port's website id: an item's {@code mod:name@meta}, a fluid's name. */
    public static String websiteId(final Port<?> port) {
        if (port.getValue() instanceof final ItemStack stack) return GameIds.itemId(stack);
        if (port.getValue() instanceof final FluidStack fluid) return GameIds.fluidId(fluid);
        return "";
    }

    /** The website's id for a mob's spawner, its recipe's one input that is never used up. */
    public static String spawnerId(final String mob) {
        return "factoryflow:eec_mob:" + com.gtnhplanner.machines.MobDrops.slug(mob);
    }

    /** Every card's output ids, in a list: handy for tests and the harness. */
    public static List<String> outputIds(final Node node) {
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (final Port<?> p : node.outputs) out.add(websiteId(p));
        return out;
    }
}
