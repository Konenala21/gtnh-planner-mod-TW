package com.gtnhplanner.library;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.FfSettings;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSource;

/**
 * A plan as Factory Flow's project JSON (its {@code factoryProjectSchema}, version 1), for posting to the library:
 * each card's recipe written out in full (Factory Flow plans carry their recipes), the cards, the drawers, the wires
 * and the notes (its text annotations), in Solve mode, laid out at Factory Flow's scale. The game's side (ids, names,
 * solved counts) comes in through {@link World}, so this stays free of Minecraft and can be tested on its own.
 */
public final class PlanExport {

    /** One item or fluid as Factory Flow names it: {@code item} or {@code fluid}, its id, and its name. */
    public record Res(String kind, String id, String name) {}

    /** EU, as the site names it: a generator's output and an EU drawer's resource. */
    public static final Res EU = new Res("power", "eu", "EU");

    /** What the export needs from the game. */
    public interface World {

        /** A port's item or fluid, or null to leave it out (nothing the site could name). */
        @Nullable
        Res port(Port<?> port);

        /** A drawer's item or fluid, from its resource key. */
        @Nullable
        Res resource(String key);

        /** The machine a card runs on, by name. */
        String machine(Node node);

        /** How many machines the plan runs the card at (the solve's answer), 0 when unknown. */
        double machines(Node node);

        /** The card's tier name ("LV", "HV"...). */
        String tier(Node node);

        /** Whether the card's machine is a multiblock (its tier is then its hatches'). */
        default boolean multiblock(final Node node) {
            return node.machineConfig != null && Boolean.TRUE.equals(node.machineConfig.settings.get("gt_multiblock"));
        }

        /** The website's id for the card's machine among the recipe's handlers, or null for the recipe's first. */
        @Nullable
        default String handlerId(final Node node) {
            return null;
        }

        /** A crop card's crop; null on any other card. */
        @Nullable
        default Crop crop(final Node node) {
            return null;
        }
    }

    /** A CropsNH crop: its id ({@code cropsnh:sugarBeet}) and the facts the website models it from. */
    public record Crop(String id, com.gtnhplanner.machines.CropsNh.Stats stats) {}

    /** Factory Flow's cards are 380 wide to our 320, and taller (as the importer scales them the other way). */
    private static final double SCALE_X = 380.0 / 320.0, SCALE_Y = 1 / 0.6;
    /** Factory Flow's board cell: it grows every drawing's size to whole cells when it loads a plan. */
    private static final int CELL = 20;
    /** The colour tags the site's schema takes (its factoryNodeColorTagSchema); a note in any other goes untagged. */
    private static final Set<String> COLOR_TAGS = Set.of(
        "white",
        "orange",
        "magenta",
        "light_blue",
        "yellow",
        "lime",
        "pink",
        "gray",
        "light_gray",
        "cyan",
        "purple",
        "blue",
        "brown",
        "green",
        "red",
        "black",
        "scarlet",
        "amber",
        "emerald",
        "azure",
        "steel",
        "onyx");

    private PlanExport() {}

    /** An item or fluid as the face of a plan or a post: the site's {@code EntryIcon}, without its picture. */
    public static JsonObject icon(final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("resourceId", r.id());
        if (!r.name()
            .isEmpty()) o.addProperty("displayName", r.name());
        return o;
    }

    public static JsonObject project(final Graph g, final String name, final World world) {
        final JsonArray recipes = new JsonArray(), nodes = new JsonArray(), storages = new JsonArray(),
            edges = new JsonArray();
        // Each port's resource, once: edges and drawers name them as the recipes do.
        final Map<Port<?>, Res> res = new HashMap<>();
        for (final Node n : g.getNodes()) {
            final String recipeId = "gtnhplanner:" + n.id;
            // The card's picks among the items a slot takes, by the recipe's input index: the site's overrides.
            final JsonObject picks = new JsonObject();
            recipes.add(recipe(n, recipeId, world, res, picks));
            final JsonObject node = node(n, recipeId, world);
            if (picks.entrySet()
                .size() > 0) node.add("recipeInputOverrides", picks);
            nodes.add(node);
        }
        for (final Edge e : g.getEdges()) {
            final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
            if (from == null || to == null || e.sourceOutputIndex >= from.outputs.size()) continue;
            final Res r = res.get(from.outputs.get(e.sourceOutputIndex));
            if (r == null) continue;
            edges.add(edge(e.id.toString(), from.id.toString(), to.id.toString(), r));
        }
        for (final Drawer d : g.getDrawers()) {
            final Res r = Energy.KEY.equals(d.getResourceKey()) ? EU : world.resource(d.getResourceKey());
            if (r == null) continue;
            final boolean source = d.getKind()
                .linksInputs();
            storages.add(storage(d, r));
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = g.nodes.get(link.nodeId());
                if (n == null) continue;
                final String id = d.getId() + ":" + link.nodeId() + ":" + link.portIndex();
                edges.add(
                    source ? edge(
                        id,
                        d.getId()
                            .toString(),
                        n.id.toString(),
                        r)
                        : edge(
                            id,
                            n.id.toString(),
                            d.getId()
                                .toString(),
                            r));
            }
        }
        final JsonArray annotations = new JsonArray();
        for (final Note n : g.getNotes()) annotations.add(note(n));
        final JsonObject p = new JsonObject();
        p.addProperty("schemaVersion", 1);
        p.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        p.addProperty("name", name);
        // The plan's face, which the website's plan card shows: its description and icon.
        if (!g.getDescription()
            .isEmpty()) p.addProperty("description", g.getDescription());
        final Res face = g.getIcon() == null ? null : world.resource(g.getIcon());
        if (face != null) p.add("icon", icon(face));
        p.addProperty("solveMode", true);
        p.add("recipes", recipes);
        p.add("nodes", nodes);
        p.add("storages", storages);
        p.add("annotations", annotations);
        p.add("pockets", new JsonArray());
        p.add("edges", edges);
        p.add("fuelProfiles", new JsonArray());
        final JsonObject meta = new JsonObject();
        meta.addProperty("source", "GTNH Planner");
        p.add("metadata", meta);
        return p;
    }

    private static JsonObject recipe(final Node n, final String id, final World world, final Map<Port<?>, Res> res,
        final JsonObject picks) {
        if (CustomRate.is(n)) return customRateRecipe(n, id, world, res);
        if (com.gtnhplanner.power.FluidTank.is(n)) return tankRecipe(n, id, world, res);
        if (n.isPower()) return powerRecipe(n, id, world, res);
        final Crop crop = world.crop(n);
        if (crop != null) return cropRecipe(n, id, crop, world, res);
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        final String machine = world.machine(n);
        r.addProperty(
            "name",
            n.outputs.isEmpty() ? machine
                : n.outputs.get(0)
                    .getDisplayName());
        r.addProperty("kind", "gregtech_machine");
        r.addProperty("machineType", machine);
        r.addProperty("minimumTier", world.tier(n));
        r.addProperty("durationTicks", Math.max(1, intProperty(n, "duration_ticks")));
        r.addProperty("eut", Math.max(0, longProperty(n, "gt.eu_per_tick")));
        r.add("inputs", ports(n.inputs, false, world, res, picks));
        r.add("outputs", ports(n.outputs, true, world, res));
        final JsonObject source = new JsonObject();
        source.addProperty("exporter", "unknown");
        source.addProperty("sourceMod", "GTNH Planner");
        r.add("source", source);
        return r;
    }

    /**
     * A crop, as the website's crop farm cards carry it: the Crop Farm's recipe for that crop, its facts under
     * {@code metadata.cropsNh} (getCropsNhStats reads them; the drops are its rates at the site's baseline, which the
     * card's settings scale), and the crop's id where the site's own dataset keeps it.
     */
    private static JsonObject cropRecipe(final Node n, final String id, final Crop crop, final World world,
        final Map<Port<?>, Res> res) {
        final com.gtnhplanner.machines.CropsNh.Stats s = crop.stats();
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty(
            "name",
            "Crop Farm" + (n.outputs.isEmpty() ? ""
                : ": " + n.outputs.get(0)
                    .getDisplayName()));
        r.addProperty("kind", "crop_produce");
        r.addProperty("category", "cropsnh-crop");
        r.addProperty("machineType", CROP_FARM);
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", Math.max(1, intProperty(n, "duration_ticks")));
        r.addProperty("eut", 0);
        r.add("inputs", new JsonArray());
        r.add("outputs", ports(n.outputs, true, world, res));
        final JsonObject source = new JsonObject();
        source.addProperty("recipeMap", CROP_FARM);
        source.addProperty("sourceMod", "GTNH Planner");
        source.addProperty("rawRecipeId", "cropsnh:" + crop.id());
        r.add("source", source);
        final JsonObject stats = new JsonObject();
        stats.addProperty("cropId", crop.id());
        stats.addProperty("tier", s.tier());
        stats.addProperty("growthPoints", s.growthPoints());
        stats.addProperty("dropChance", s.dropChance());
        stats.addProperty("growthCycleTicks", s.growthCycleTicks());
        stats.addProperty("growthMultiplier", s.growthMultiplier());
        if (s.machineOnly()) stats.addProperty("machineOnly", true);
        if (s.minSeedBedTier() > 0) stats.addProperty("minSeedBedTier", s.minSeedBedTier());
        if (s.subSoil() != null) {
            final JsonArray requirements = new JsonArray();
            requirements.add(new com.google.gson.JsonPrimitive(s.subSoil()));
            stats.add("requirements", requirements);
        }
        final JsonArray drops = new JsonArray();
        for (final com.gtnhplanner.machines.CropsNh.Drop d : s.drops()) {
            final JsonObject drop = new JsonObject();
            drop.addProperty("id", d.id());
            drop.addProperty("stackSize", d.stackSize());
            drop.addProperty("weight", d.weight());
            drops.add(drop);
        }
        stats.add("drops", drops);
        final JsonObject meta = new JsonObject();
        meta.add("cropsNh", stats);
        r.add("metadata", meta);
        return r;
    }

    /** The website's crop farm: its recipe map and machine type, which mark a recipe as a crop's. */
    private static final String CROP_FARM = "Crop Farm";

    /**
     * A generator, as the site's power cards carry it (buildPowerRecipe): a one-second custom recipe, flows per second
     * exact, EU first among the outputs, and the source with its readings; the site rebuilds it from the source id and
     * the node's settings when it loads the plan.
     */
    private static JsonObject powerRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final PowerSource source = PowerRegistry.get(n.powerSource);
        final String name = source != null ? source.name() : n.machineName;
        r.addProperty("id", id);
        r.addProperty("name", name);
        r.addProperty("kind", "custom");
        r.addProperty("category", "power-source");
        r.addProperty("machineType", name);
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 20);
        r.addProperty("eut", 0);
        r.add("inputs", ports(n.inputs, false, world, res));
        r.add("outputs", ports(n.outputs, true, world, res));
        if (source != null) r.addProperty("notes", source.blurb());
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "power-source");
        r.add("source", from);
        final JsonObject power = new JsonObject();
        power.addProperty("sourceId", n.powerSource);
        final PowerModel model = n.powerModel;
        power.addProperty("euPerTick", model == null ? 0 : model.euPerTick());
        final JsonArray stats = new JsonArray();
        if (model != null) for (final PowerModel.Stat s : model.stats()) {
            final JsonObject line = new JsonObject();
            line.addProperty("label", s.label());
            line.addProperty("value", s.value());
            stats.add(line);
        }
        power.add("stats", stats);
        if (model != null && !model.warnings()
            .isEmpty()) {
            final JsonArray warnings = new JsonArray();
            for (final String w : model.warnings()) warnings.add(new com.google.gson.JsonPrimitive(w));
            power.add("warnings", warnings);
        }
        r.add("power", power);
        return r;
    }

    /**
     * A custom rate card, as the site's own (custom-rate.ts): a one-second recipe of its one slot, the amount its rate
     * a second, named after what it holds; the dial rides on the node. The site wants an amount above nothing, so a
     * dial at zero posts the least it takes.
     */
    private static JsonObject customRateRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final JsonArray inputs = ports(n.inputs, false, world, res), outputs = ports(n.outputs, true, world, res);
        for (final JsonArray side : List.of(inputs, outputs)) for (final com.google.gson.JsonElement e : side) {
            final JsonObject slot = e.getAsJsonObject();
            slot.remove("byproduct");
            if (!(slot.get("amount")
                .getAsDouble() > 0)) slot.addProperty("amount", 0.001);
        }
        final com.google.gson.JsonElement held = outputs.size() > 0 ? outputs.get(0)
            : inputs.size() > 0 ? inputs.get(0) : null;
        r.addProperty("id", id);
        r.addProperty("name", held == null ? "Custom Rate" : "Custom Rate: " + slotName(held.getAsJsonObject()));
        r.addProperty("kind", "custom");
        r.addProperty("category", "custom-rate");
        r.addProperty("machineType", "Custom Rate");
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 20);
        r.addProperty("eut", 0);
        r.add("inputs", inputs);
        r.add("outputs", outputs);
        r.addProperty("notes", "Wire any port to this and it adopts that resource.");
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "custom-rate");
        r.add("source", from);
        return r;
    }

    /**
     * A fluid tank, as the website's own Tank (its free mirror of a fluid Canner recipe, map {@code planner.tank}): the
     * same cell, fluid and empty cell, at no power, a tick a craft.
     */
    private static JsonObject tankRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final JsonArray inputs = ports(n.inputs, false, world, res), outputs = ports(n.outputs, true, world, res);
        final JsonArray fluidSide = com.gtnhplanner.power.FluidTank.emptying(n) ? outputs : inputs;
        String fluid = "";
        for (final com.google.gson.JsonElement e : fluidSide) if ("fluid".equals(
            e.getAsJsonObject()
                .get("kind")
                .getAsString()))
            fluid = slotName(e.getAsJsonObject());
        r.addProperty("id", id);
        r.addProperty("name", fluid.isEmpty() ? "Tank" : "Tank: " + fluid);
        r.addProperty("kind", "custom");
        r.addProperty("machineType", "Tank");
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 1);
        r.addProperty("eut", 0);
        r.add("inputs", inputs);
        r.add("outputs", outputs);
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "Tank");
        from.addProperty("sourceMod", "GTNH Planner");
        r.add("source", from);
        final JsonObject meta = new JsonObject();
        meta.addProperty("recipeMapId", FfPlan.TANK_MAP);
        r.add("metadata", meta);
        return r;
    }

    private static JsonArray ports(final List<Port<?>> ports, final boolean outputs, final World world,
        final Map<Port<?>, Res> res) {
        return ports(ports, outputs, world, res, null);
    }

    /**
     * A side's ports as the recipe's slots. An input that can be set to another item writes the recipe's own item, the
     * others it takes as its {@code alternatives}, and, when the card is set to another, that one into {@code picks}
     * under the slot's index (the site's {@code recipeInputOverrides}); wires name the item it is set to.
     */
    private static JsonArray ports(final List<Port<?>> ports, final boolean outputs, final World world,
        final Map<Port<?>, Res> res, @Nullable final JsonObject picks) {
        final JsonArray out = new JsonArray();
        for (final Port<?> p : ports) {
            if (p.fromModel) continue;
            final Res r = p.getValue() instanceof Energy ? EU : world.port(p);
            if (r == null) continue;
            res.put(p, r);
            if (picks != null && !outputs && p.hasChoices() && Double.isNaN(p.getExactAmount())) {
                final Res own = world.port(p.withAlternative(p.own()));
                if (own != null) {
                    final int amount = Math.max(1, p.getAmount());
                    final JsonObject o = resource(own);
                    o.addProperty("amount", amount);
                    if (p.getAmount() <= 0) o.addProperty("consumed", false);
                    final JsonArray alternatives = new JsonArray();
                    for (final Object alt : p.getAlternatives()) {
                        final Res a = world.port(p.withAlternative(alt));
                        if (a != null && a.kind()
                            .equals(own.kind())
                            && !a.id()
                                .equals(own.id()))
                            alternatives.add(resource(a));
                    }
                    if (alternatives.size() > 0) o.add("alternatives", alternatives);
                    if (!r.id()
                        .equals(own.id())) {
                        final JsonObject pick = resource(r);
                        pick.addProperty("amount", amount);
                        picks.add(String.valueOf(out.size()), pick);
                    }
                    out.add(o);
                    continue;
                }
            }
            if (!Double.isNaN(p.getExactAmount())) {
                // A generator's exact rate per second (its craft is a second); EU rides as a byproduct, as on the site.
                final JsonObject o = resource(r);
                o.addProperty("amount", p.amount());
                if (r == EU) o.addProperty("byproduct", true);
                out.add(o);
                continue;
            }
            // The site wants an amount above nothing; a port that is not used up (a catalyst) still counts one.
            final int amount = Math.max(1, p.getAmount());
            final JsonObject o = resource(r);
            o.addProperty("amount", amount);
            if (outputs && p.getChance() > 0 && p.getChance() < 1) o.addProperty("chance", p.getChance());
            if (!outputs && p.getAmount() <= 0) o.addProperty("consumed", false);
            out.add(o);
        }
        return out;
    }

    private static JsonObject node(final Node n, final String recipeId, final World world) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", n.id.toString());
        o.addProperty("recipeId", recipeId);
        // A fluid tank runs a craft a second here, a tick there (its Tank map): the count is in its crafts.
        final double count = Math.max(0, world.machines(n)) / (com.gtnhplanner.power.FluidTank.is(n) ? 20.0 : 1.0);
        o.addProperty("machineCount", count);
        o.addProperty("parallel", 1);
        o.addProperty("overclockTier", CustomRate.is(n) || com.gtnhplanner.power.FluidTank.is(n) ? "NONE" : n.isPower() ? "LV" : world.tier(n));
        o.addProperty("enabled", true);
        if (CustomRate.is(n)) {
            // The dial, where the site keeps it: a second's worth, and which way it runs.
            final JsonObject dial = new JsonObject();
            dial.addProperty("perSecond", CustomRate.perSecond(n));
            dial.addProperty("mode", CustomRate.supply(n) ? CustomRate.SUPPLY : CustomRate.REQUEST);
            o.add("customRate", dial);
        } else if (n.isPower() && !com.gtnhplanner.power.FluidTank.is(n)) {
            // A power card's settings, where the site keeps them.
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, String> s : n.powerSettings.entrySet())
                settings.addProperty(s.getKey(), s.getValue());
            o.add("machineConfigTiers", settings);
        } else if (n.machineConfig != null) {
            final Map<String, Object> cfg = n.machineConfig.settings;
            if (world.multiblock(n)) {
                // The hatches, as the website keeps a multiblock's power: tier and amps.
                final String tier = world.tier(n);
                final double amps = cfg.get("amp") instanceof final Number a ? Math.max(1, a.doubleValue()) : 1;
                o.addProperty("hatchVoltageTier", tier);
                o.addProperty("hatchAmps", amps);
                o.addProperty("powerInputMode", "amps");
                o.addProperty("powerEuT", com.gtnhplanner.machines.web.Tiers.maxEuT(tier) * amps);
            }
            if (cfg.get(FfSettings.COIL) instanceof final String coil && !coil.isEmpty()) o.addProperty("coilTier", coil);
            if (cfg.get(FfSettings.HATCH_TYPE) instanceof final String type && !type.isEmpty())
                o.addProperty("energyHatchType", type);
            // A crop's harvester is the website's machine for it; our setting.
            final boolean crop = world.crop(n) != null;
            final String handler = crop ? cfg.get(com.gtnhplanner.machines.CropsNh.HARVESTER) instanceof final String h
                && !h.isEmpty() ? h : com.gtnhplanner.machines.CropsNh.MANAGER : world.handlerId(n);
            if (handler != null) o.addProperty("machineHandlerId", handler);
            // The machine's options under the website's own ids: the modelled machines' (machines/) as they are kept,
            // every other machine's from machine:<id>.
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, Object> s : cfg.entrySet()) {
                if (!(s.getValue() instanceof final String v) || v.isEmpty()) continue;
                if (com.gtnhplanner.machines.CropsNh.HARVESTER.equals(s.getKey())) continue;
                if (FfSettings.modelled(s.getKey())) settings.addProperty(s.getKey(), v);
                else if (s.getKey()
                    .startsWith(FfSettings.MACHINE))
                    settings.addProperty(
                        s.getKey()
                            .substring(FfSettings.MACHINE.length()),
                        v);
            }
            if (!settings.entrySet()
                .isEmpty()) o.add("machineConfigTiers", settings);
        }
        if (n.isMachineCountFixed()) o.addProperty("solvePin", count);
        o.add("position", position(n.x, n.y));
        return o;
    }

    private static JsonObject storage(final Drawer d, final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            d.getId()
                .toString());
        o.addProperty("kind", r.kind());
        o.addProperty("resourceId", r.id());
        o.addProperty("displayName", r.name());
        if (!d.getKind()
            .linksInputs()) {
            o.addProperty("drainMode", "product");
            final String mode = switch (d.getRule()) {
                case AT_LEAST -> "at-least";
                case AT_MOST -> "at-most";
                case EXACTLY -> "exact";
                case ANY -> null;
            };
            if (mode != null && d.getRate() > 0) {
                o.addProperty("targetPerSecond", d.getRate());
                o.addProperty("targetMode", mode);
            }
        }
        o.add("position", position(d.getX(), d.getY()));
        return o;
    }

    /**
     * A note as the site's text annotation, placed as a card is. Its size is rounded to whole cells: the site grows a
     * size to the next cell when it loads, so a size a pixel over one could gain a whole cell on a trip there and back.
     */
    private static JsonObject note(final Note n) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            n.getId()
                .toString());
        o.addProperty("kind", "text");
        if (COLOR_TAGS.contains(n.colorTag())) o.addProperty("colorTag", n.colorTag());
        o.addProperty("text", n.joined());
        o.add("position", position(n.getX(), n.getY()));
        final JsonObject size = new JsonObject();
        size.addProperty("width", cells(Math.max(Note.MIN_W, n.getWidth()) * SCALE_X));
        size.addProperty("height", cells(Math.max(Note.MIN_H, n.getHeight()) * SCALE_Y));
        o.add("size", size);
        o.addProperty("fontSize", n.fontSizeOrDefault());
        return o;
    }

    /** A length to the nearest whole number of the site's cells, at least one. */
    private static int cells(final double length) {
        return Math.max(CELL, (int) Math.round(length / CELL) * CELL);
    }

    private static JsonObject edge(final String id, final String source, final String target, final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("source", source);
        o.addProperty("target", target);
        o.addProperty("sourceHandle", "output:" + r.kind() + ":" + enc(r.id()));
        o.addProperty("targetHandle", "input:" + r.kind() + ":" + enc(r.id()));
        o.addProperty("resourceKind", r.kind());
        o.addProperty("resourceId", r.id());
        return o;
    }

    /** A slot's name as the site shows it: its display name, else its id. */
    private static String slotName(final JsonObject slot) {
        return (slot.has("displayName") ? slot.get("displayName") : slot.get("id")).getAsString();
    }

    private static JsonObject resource(final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("id", r.id());
        if (!r.name()
            .isEmpty()) o.addProperty("displayName", r.name());
        return o;
    }

    private static JsonObject position(final int x, final int y) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", Math.round(x * SCALE_X));
        o.addProperty("y", Math.round(y * SCALE_Y));
        return o;
    }

    private static int intProperty(final Node n, final String key) {
        return (int) longProperty(n, key);
    }

    private static long longProperty(final Node n, final String key) {
        for (final Map.Entry<RecipeProperty<?>, Object> e : n.properties.entrySet()) {
            if (key.equals(
                e.getKey()
                    .getKey())
                && e.getValue() instanceof final Number num) return num.longValue();
        }
        return 0;
    }

    /** {@code encodeURIComponent}, as the website writes handles. */
    private static String enc(final String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
