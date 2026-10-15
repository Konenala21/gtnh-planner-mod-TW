package com.gtnhplanner.cloud.map;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * What a shared plan was read as: for each of its things the board thing it became, that thing's state then and the
 * website fields the game would have written for it. Writing the board back against the same plan uses it to tell what
 * the player changed, so a thing nobody changed is written exactly as the plan had it (a website card stays the
 * website's), and a changed one changes only the fields its change touched. Things the game has no place for are not
 * here, and are written back untouched. Kept beside the board while the plan is open; each write gives the next one.
 */
public final class Basis {

    /**
     * One thing as the board holds it.
     *
     * @param gameKey what it is on the board ({@code node:<uuid>}, {@code link:<drawer>:<node>:<port>}, ...)
     * @param state   the game's own data for it, in one spelling ({@link Canon}), place left out
     * @param site    the website fields the game writes for it, place left out
     * @param recipe  a card's recipe as the game writes it; null on anything else
     * @param x       its place on the board, when it has one
     */
    public record Entry(String gameKey, String state, JsonObject site, @Nullable JsonObject recipe, int x, int y) {

        static Entry of(final Fresh.Thing t) {
            return new Entry(t.gameKey(), t.state(), t.site(), t.recipe(), t.x(), t.y());
        }
    }

    /** By thing (collection and id, {@link #key}). */
    final Map<String, Entry> things = new LinkedHashMap<>();
    /** By board thing, the plan thing it is. */
    final Map<String, String> byGame = new HashMap<>();
    /**
     * Board things a website card brings beyond itself (a shared machine's later recipes and their group), by game key:
     * the card's thing. They are written only as part of the card.
     */
    final Map<String, String> carried = new HashMap<>();
    /** The plan-wide game data then, in one spelling, and the plan's website fields the game would have written. */
    @Nullable
    String metaState;
    @Nullable
    JsonObject metaSite;

    /** A thing's key: its collection and id. */
    static String key(final String c, final String id) {
        return c + "\n" + id;
    }

    static String idOfKey(final String key) {
        return key.substring(key.indexOf('\n') + 1);
    }

    /** Reading: the plan thing became this board thing. A board thing two plan things became is the last one's. */
    void map(final String c, final String id, final String gameKey) {
        byGame.put(gameKey, key(c, id));
    }

    void carry(final String gameKey, final String c, final String ownerId) {
        carried.put(gameKey, key(c, ownerId));
    }

    /**
     * Reading done: each plan thing's entry from what the board now writes for its board thing. A board thing that is
     * gone (a later wire took its ports) leaves its plan thing to be kept untouched, as one the game has no place for.
     */
    void settle(final Map<String, Fresh.Thing> fresh) {
        for (final Iterator<Map.Entry<String, String>> it = byGame.entrySet()
            .iterator(); it.hasNext();) {
            final Map.Entry<String, String> m = it.next();
            final Fresh.Thing t = fresh.get(m.getKey());
            if (t == null) it.remove();
            else things.put(m.getValue(), Entry.of(t));
        }
    }

    @Nullable
    Entry entry(final String key) {
        return things.get(key);
    }

    @Nullable
    String thingOf(final String gameKey) {
        return byGame.get(gameKey);
    }

    /**
     * The plan id of a board thing ({@code node}, {@code drawer}, {@code edge}, {@code note}): the one it was read from
     * (a carried thing's card's), else its UUID.
     */
    String id(final String kind, final UUID uuid) {
        final String gameKey = kind + ":" + uuid;
        String key = byGame.get(gameKey);
        if (key == null) key = carried.get(gameKey);
        return key == null ? uuid.toString() : idOfKey(key);
    }

    boolean isCarried(final String gameKey) {
        return carried.containsKey(gameKey);
    }

    /** How many things of the plan the board holds. */
    public int size() {
        return things.size();
    }
}
