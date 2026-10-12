package com.gtnhplanner.importer;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;

/**
 * Builds GTNH Planner nodes for matched recipes and tells the importer what their ports hold. The game's implementation
 * builds a real node from the NEI recipe; tests build nodes from plain data.
 */
public interface NodeMaker {

    /**
     * The machine FF ran a card's recipe on.
     *
     * @param handlerId FF's id for it ("extractor", "steam-extractor", "large-chemical-reactor"), which the game's copy
     *                  of the website's machine data lists with its blocks; null for the recipe's first machine
     * @param label     its name ("Extractor", "Large Chemical Reactor"); null when the plan does not say
     */
    record Machine(@Nullable String handlerId, @Nullable String label) {}

    /**
     * A node for the recipe, with the board's default settings for a new card applied, at (0, 0); null when the
     * game cannot build one.
     *
     * @param machine the machine FF ran the recipe on, to pick that one of the recipe's machines when the game offers
     *                it; null for the default
     */
    @Nullable
    Node make(GameRecipe recipe, @Nullable Machine machine);

    /**
     * A power card (a generator) for one of the site's power sources at its settings, at (0, 0); null when the source
     * is unknown or the maker builds no generators.
     */
    @Nullable
    default Node makePower(final String sourceId, final Map<String, String> settings) {
        return null;
    }

    /**
     * A custom rate card at its settings ({@link com.gtnhplanner.power.CustomRate}: mode, rate, the resource's key), at
     * (0, 0); null when the maker builds none (then the card comes in as a drawer) or the game lacks the resource.
     */
    @Nullable
    default Node makeCustomRate(final Map<String, String> settings) {
        return null;
    }

    /** What a port of a node made here holds. */
    PortInfo describe(Port<?> port);

    /**
     * Puts the imported machine settings on a node ({@link com.gtnhplanner.data.Settings} keys). The maker drops
     * the ones the node's machine has no use for (a coil on a crafting table).
     */
    void applySettings(Node node, Map<String, Object> settings);

    /**
     * @param kind  "item" or "fluid"
     * @param key   GTNH Planner's resource key for a drawer of this resource ({@code item:<registry name>:<meta>},
     *              {@code fluid:<name>})
     * @param label the name a player reads
     * @param ids   the FF-style ids it answers to, its own first; ore dictionary names as {@code oredict:<name>}
     */
    record PortInfo(String kind, String key, String label, List<String> ids) {}
}
