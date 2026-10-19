package com.gtnhplanner.dev;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.provider.MobDropsProvider;
import com.gtnhplanner.machines.game.EecCards;
import com.gtnhplanner.machines.game.WebEffect;
import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.ui.BoardSession;

import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * {@code /eec}: the Extreme Entity Crusher's mobs (MobsInfo's Mob Drops page, kubatech's table). Alone, how many mobs
 * it can hold and their names. {@code dump=<file>} writes kubatech's table as the website's exporter does, for
 * tools/dev/eec. {@code mob=<key>} makes that mob's card off the board, at {@code voltage}, {@code amp} and
 * {@code set=<key>:<value>,...} (the website's machine settings, eecLooting:3), and reports its outputs, metadata and
 * the website's crusher maths on it: ticks a kill, EU/t, each output's multiplier and rate a second for one machine.
 * {@code add=<key>} puts that mob's card on the open board. Client thread.
 */
final class DevEec {

    private DevEec() {}

    static Object report() {
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<MobDropsProvider.Choice> choices = MobDropsProvider.choices();
        out.put("mobs", choices.size());
        final List<String> names = new ArrayList<>();
        for (final MobDropsProvider.Choice c : choices) names.add(c.key() + " = " + c.name());
        out.put("names", names);
        return out;
    }

    static Object dump(final File file) throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("domains", List.of(MobDropsProvider.export()));
        final File parent = file.getAbsoluteFile()
            .getParentFile();
        if (parent != null) parent.mkdirs();
        Files.write(
            file.toPath(),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(root)
                .getBytes(StandardCharsets.UTF_8));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file.getAbsolutePath());
        return out;
    }

    static Object add(final String mob) {
        final RecipeHandlerRef ref = MobDropsProvider.mob(mob::equals);
        if (ref == null) return Map.of("error", "no crusher mob " + mob);
        final BoardSession session = BoardSession.current();
        if (session == null) return Map.of("error", "open the planner first");
        final Node node = session.addRecipe(ref.handler, ref.recipeIndex);
        return Map.of("added", node == null ? "" : node.id.toString());
    }

    static Object mob(final String mob, final Map<String, String> q) {
        final RecipeHandlerRef ref = MobDropsProvider.mob(mob::equals);
        if (ref == null) return Map.of("error", "no crusher mob " + mob);
        final Node node = new Node(ref.handler, ref.recipeIndex, 0, 0);
        if (q.containsKey("voltage")) node.machineConfig.settings.put("voltage", q.get("voltage"));
        if (q.containsKey("amp")) node.machineConfig.settings.put("amp", Integer.parseInt(q.get("amp")));
        node.machineConfig.settings.put("gt_multiblock", true);
        if (q.containsKey("set")) for (final String pair : q.get("set")
            .split(",")) {
                final int colon = pair.indexOf(':');
                if (colon > 0)
                    node.machineConfig.settings.put("machine:" + pair.substring(0, colon), pair.substring(colon + 1));
            }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("mob", EecCards.mob(node));
        out.put("name", EecCards.mobName(node));
        out.put("gated", EecCards.gated(node));
        final List<Object> outputs = new ArrayList<>();
        for (final Port<?> p : node.outputs) {
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", EecCards.websiteId(p));
            o.put("amount", p.amount());
            o.put("chance", p.getChance());
            outputs.add(o);
        }
        out.put("outputs", outputs);
        out.put("eec", String.valueOf(EecCards.metadata(node)));
        final NodeMath.Result r = WebEffect.result(node, node.machineConfig);
        if (r == null) {
            out.put("covered", false);
            return out;
        }
        final double ticks = r.overclock()
            .durationTicks();
        out.put(
            "tier",
            r.overclock()
                .tier());
        out.put("ticksPerKill", ticks);
        out.put(
            "eut",
            r.overclock()
                .eut());
        out.put(
            "overclocks",
            r.overclock()
                .overclockSteps());
        out.put("multipliers", r.outputMultipliers());
        final List<Object> rates = new ArrayList<>();
        for (int i = 0; i < node.outputs.size(); i++) {
            final Port<?> p = node.outputs.get(i);
            final double mult = i < r.outputMultipliers()
                .size() ? r.outputMultipliers()
                    .get(i) : 1;
            rates.add(EecCards.websiteId(p) + " " + p.amount() * p.getChance() * mult * 20 / ticks + "/s");
        }
        out.put("perSecond", rates);
        return out;
    }
}
