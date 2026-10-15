package com.gtnhplanner.cloud.map;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.FfPlanParser;
import com.gtnhplanner.library.PlanExport;

/**
 * A board written as a shared plan ({@link CloudPlans#write}), against the plan it was read from. A thing nobody
 * changed is the plan's own, exactly; a moved one only moves; a changed one takes the fields its change touched (what
 * the game writes for it now against what it wrote for it when read) and carries the game's data for it; a new one is
 * written whole; one the board lost is gone, and the wires the game had no place for go with it. Everything the game
 * has no place for stays as it was.
 */
final class PlanWrite {

    /** The collections every plan has (the website's schema asks for them), written empty when there is nothing. */
    private static final List<String> ALWAYS = List
        .of("recipes", "nodes", "storages", "annotations", "pockets", "edges", "fuelProfiles");

    private final Graph graph;
    private final CloudGame game;
    private final JsonObject base;
    private final Basis basis;
    private final FfPlanParser.Things things;
    private final Basis next = new Basis();
    /** Recipes the board's cards changed or brought, by id. */
    private final Map<String, JsonObject> recipes = new LinkedHashMap<>();
    /** Plan ids of the cards and drawers the board lost. */
    private final Set<String> gone = new HashSet<>();

    PlanWrite(final Graph graph, final CloudGame game, @Nullable final JsonObject base, @Nullable final Basis basis) {
        this.graph = graph;
        this.game = game;
        this.base = base == null ? new JsonObject() : base;
        this.basis = basis == null ? new Basis() : basis;
        this.things = new FfPlanParser.Things(this.base);
        next.carried.putAll(this.basis.carried);
    }

    CloudPlans.Written run() {
        final Map<String, Fresh.Thing> fresh = Fresh.of(graph, game, basis);
        final JsonObject out = new JsonObject();
        meta(out);
        final Map<String, JsonObject> baseRecipes = new LinkedHashMap<>();
        for (final JsonObject r : PlanRead.objects(base, "recipes")) {
            final String id = PlanRead.str(r, "id");
            if (id != null) baseRecipes.putIfAbsent(id, r);
        }
        for (final String c : LiveOps.COLLECTIONS) {
            if ("recipes".equals(c)) continue;
            final JsonArray list = new JsonArray();
            for (final JsonElement e : LiveOps.collection(base, c)) {
                final String id = LiveJson.idOf(e);
                final Basis.Entry entry = id == null ? null : basis.entry(Basis.key(c, id));
                if (entry == null) {
                    // Nothing the game has a place for: as it was, unless it is a wire to something the board lost.
                    if (!"edges".equals(c) || !touchesGone(e.getAsJsonObject())) list.add(e);
                    continue;
                }
                final Fresh.Thing now = fresh.remove(entry.gameKey());
                if (now == null) {
                    if ("nodes".equals(c) || "storages".equals(c)) gone.add(id);
                    continue;
                }
                list.add(emit(id, e.getAsJsonObject(), entry, now, baseRecipes));
            }
            for (final Iterator<Fresh.Thing> it = fresh.values()
                .iterator(); it.hasNext();) {
                final Fresh.Thing now = it.next();
                if (!now.c()
                    .equals(c)) continue;
                it.remove();
                list.add(add(now));
            }
            if (list.size() > 0 || base.has(c) || ALWAYS.contains(c)) out.add(c, list);
        }
        out.add("recipes", recipes(baseRecipes, out));
        return new CloudPlans.Written(out, next);
    }

    // region The plan's own fields

    /**
     * The plan's fields: the plan's own, the ones the board changed taken from it, and the board's game data for the
     * whole plan when that changed. A new plan gets an id.
     */
    private void meta(final JsonObject out) {
        for (final Map.Entry<String, JsonElement> e : base.entrySet())
            if (!LiveOps.isCollection(e.getKey())) out.add(e.getKey(), e.getValue());
        final Fresh.Meta now = Fresh.meta(graph, game, basis);
        merge(out, basis.metaSite == null ? new JsonObject() : basis.metaSite, now.site());
        // The plan's game data as the plan had it while the board's did not change (none, on a plan the website made).
        final String state = Canon.of(now.game());
        if (!state.equals(basis.metaState)) {
            final JsonObject ext = new JsonObject();
            ext.addProperty("v", 1);
            ext.add("game", now.game());
            ext.addProperty("sum", PlanRead.metaSum(out));
            out.add("gtnhplanner", ext);
        }
        if (!out.has("id")) out.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        next.metaState = state;
        next.metaSite = now.site();
    }

    // endregion

    // region Things

    /**
     * A thing the plan has (under {@code id}, which a drawer's link the website made keeps) and the board holds, as the
     * board has it now.
     */
    private JsonObject emit(final String id, final JsonObject thing, final Basis.Entry entry, final Fresh.Thing now,
        final Map<String, JsonObject> baseRecipes) {
        next.map(now.c(), id, now.gameKey());
        final boolean moved = now.placed() && (now.x() != entry.x() || now.y() != entry.y());
        if (now.state()
            .equals(entry.state())) {
            next.things.put(
                Basis.key(now.c(), id),
                new Basis.Entry(entry.gameKey(), entry.state(), entry.site(), entry.recipe(), now.x(), now.y()));
            final Boolean sum = basis.sums.get(thing);
            if (!moved) {
                if (sum != null) next.sums.put(thing, sum);
                return thing;
            }
            final JsonObject o = LiveJson.shallow(thing);
            o.add("position", position(now, thing));
            return o;
        }
        next.things.put(Basis.key(now.c(), id), Basis.Entry.of(now));
        final JsonObject o = LiveJson.shallow(thing);
        merge(o, entry.site(), now.site());
        if (moved) o.add("position", position(now, thing));
        if ("nodes".equals(now.c())) recipe(o, entry.recipe(), now, baseRecipes);
        if (now.game() != null) o.add("gtnhplanner", Fresh.extension(now.game(), o));
        return o;
    }

    /** A thing the board has that the plan does not: written whole. */
    private JsonObject add(final Fresh.Thing now) {
        next.map(now.c(), now.id(), now.gameKey());
        next.things.put(Basis.key(now.c(), now.id()), Basis.Entry.of(now));
        final JsonObject o = LiveJson.copy(now.site());
        if (now.placed()) o.add("position", PlanExport.position(now.x(), now.y()));
        if (now.recipe() != null) recipes.put(ownRecipe(now.id()), withId(now.recipe(), ownRecipe(now.id())));
        if (now.game() != null) o.add("gtnhplanner", Fresh.extension(now.game(), o));
        return o;
    }

    /**
     * A changed card's recipe. Unchanged, the card keeps the one it has. Changed, a recipe of the card's own takes the
     * fields that changed; a recipe the card shares with others (or one the website made) is left to them, and the
     * card gets a recipe of its own.
     */
    private void recipe(final JsonObject card, @Nullable final JsonObject was, final Fresh.Thing now,
        final Map<String, JsonObject> baseRecipes) {
        if (now.recipe() == null || LiveJson.equal(was, now.recipe())) return;
        final String own = ownRecipe(now.id());
        final JsonObject had = baseRecipes.get(PlanRead.str(card, "recipeId"));
        if (own.equals(PlanRead.str(card, "recipeId")) && had != null && was != null) {
            final JsonObject merged = LiveJson.shallow(had);
            merge(merged, was, now.recipe());
            recipes.put(own, merged);
            return;
        }
        recipes.put(own, withId(now.recipe(), own));
        card.addProperty("recipeId", own);
    }

    private static String ownRecipe(final String cardId) {
        return FfPlan.GAME_RECIPE_PREFIX + cardId;
    }

    private static JsonObject withId(final JsonObject recipe, final String id) {
        final JsonObject o = LiveJson.copy(recipe);
        o.addProperty("id", id);
        return o;
    }

    /**
     * The recipes: the plan's, in its order (one a card changed as changed), but not one only the board's cards used
     * that none uses now; then the new ones. A recipe no card used is kept: it is the website's business.
     */
    private JsonArray recipes(final Map<String, JsonObject> baseRecipes, final JsonObject out) {
        final Set<String> used = used(out), usedBefore = used(base);
        final JsonArray list = new JsonArray();
        for (final JsonElement r : LiveOps.collection(base, "recipes")) {
            final String id = LiveJson.idOf(r);
            if (id == null) {
                list.add(r);
                continue;
            }
            final JsonObject changed = recipes.remove(id);
            if (changed != null) list.add(changed);
            else if (used.contains(id) || !usedBefore.contains(id)) list.add(r);
        }
        for (final JsonObject r : recipes.values()) list.add(r);
        return list;
    }

    /** The recipes a plan's cards run, their shared machines' later recipes too. */
    private static Set<String> used(final JsonObject plan) {
        final Set<String> ids = new HashSet<>();
        for (final JsonObject n : PlanRead.objects(plan, "nodes")) {
            final String id = PlanRead.str(n, "recipeId");
            if (id != null) ids.add(id);
            for (final JsonObject x : PlanRead.objects(n, "extraRecipes")) {
                final String extra = PlanRead.str(x, "recipeId");
                if (extra != null) ids.add(extra);
            }
        }
        return ids;
    }

    /** Whether a wire runs to a card or drawer the board lost. */
    private boolean touchesGone(final JsonObject edge) {
        final String source = PlanRead.str(edge, "source"), target = PlanRead.str(edge, "target");
        return source != null && gone.contains(source) || target != null && gone.contains(target);
    }

    // endregion

    /**
     * Sets on {@code target} each field {@code now} has that differs from {@code was}, and deletes each field
     * {@code was} has that {@code now} lacks: the fields a change touched, over whatever else the target holds.
     */
    private static void merge(final JsonObject target, final JsonObject was, final JsonObject now) {
        for (final Map.Entry<String, JsonElement> e : now.entrySet()) {
            if ("id".equals(e.getKey())) continue;
            if (!LiveJson.equal(was.get(e.getKey()), e.getValue())) target.add(e.getKey(), LiveJson.copy(e.getValue()));
        }
        for (final Map.Entry<String, JsonElement> e : was.entrySet())
            if (!now.has(e.getKey()) && !"id".equals(e.getKey())) target.remove(e.getKey());
    }

    /** A moved thing's place at the site's scale, on the board it sits on. */
    private JsonObject position(final Fresh.Thing now, final JsonObject thing) {
        final JsonObject p = PlanExport.position(now.x(), now.y());
        final double[] off = things.offset(PlanRead.str(thing, "pocketId"));
        if (off[0] == 0 && off[1] == 0) return p;
        final JsonObject o = new JsonObject();
        o.add(
            "x",
            new JsonPrimitive(
                p.get("x")
                    .getAsDouble() - off[0]));
        o.add(
            "y",
            new JsonPrimitive(
                p.get("y")
                    .getAsDouble() - off[1]));
        return o;
    }
}
