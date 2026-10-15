package com.gtnhplanner.cloud.map.game;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.map.CloudGame;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.importer.RecipeIndex;
import com.gtnhplanner.importer.game.GameNodeMaker;
import com.gtnhplanner.importer.game.NeiRecipeIndex;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.library.Posting;
import com.gtnhplanner.machines.Bees;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.power.Energy;

/**
 * The game's side of a shared plan's mapping: the posting export's names (library/Posting), NEI's recipes and the
 * importer's node maker, and the game's own key for what the website names. Client thread only (NEI).
 */
public final class GameCloud implements CloudGame {

    /** Cards are written at the count they are set to, never the solve's: the export's count is never asked. */
    private final PlanExport.World world = Posting.world(id -> 0d);
    private final RecipeIndex recipes = new NeiRecipeIndex();
    private final NodeMaker maker = new GameNodeMaker();

    @Override
    public RecipeIndex recipes() {
        return recipes;
    }

    @Override
    public NodeMaker maker() {
        return maker;
    }

    @Override
    @Nullable
    public String key(final String kind, final String id) {
        if ("power".equals(kind)) return Energy.KEY;
        return Posting.keyOf(new CommunityApi.Resource(kind, id, "", 0));
    }

    @Override
    @Nullable
    public PlanExport.Res port(final Port<?> port) {
        return world.port(port);
    }

    @Override
    @Nullable
    public PlanExport.Res resource(final String key) {
        return world.resource(key);
    }

    @Override
    public String machine(final Node node) {
        return world.machine(node);
    }

    @Override
    public double machines(final Node node) {
        return node.machineConfig.getMachineCount();
    }

    @Override
    public String tier(final Node node) {
        return world.tier(node);
    }

    @Override
    public boolean multiblock(final Node node) {
        return world.multiblock(node);
    }

    @Override
    @Nullable
    public String handlerId(final Node node) {
        return world.handlerId(node);
    }

    @Override
    @Nullable
    public PlanExport.Crop crop(final Node node) {
        return world.crop(node);
    }

    @Override
    @Nullable
    public Web.Recipe gregtech(final Node node) {
        return world.gregtech(node);
    }

    @Override
    @Nullable
    public JsonObject neiRecipe(final Node node) {
        return world.neiRecipe(node);
    }

    @Override
    @Nullable
    public Bees.Species bee(final Node node) {
        return world.bee(node);
    }
}
