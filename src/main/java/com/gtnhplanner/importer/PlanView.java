package com.gtnhplanner.importer;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * The plan's {@code view} block, as the website's plan-view.ts writes it (capturePlanView) and reads it
 * (applyPlanView): a plan shared or exported carries the rate unit it was read in, and opens in it. Only the rate unit
 * is the board's to take; the website's other view fields (its canvas, its panels) have nothing here to set.
 */
public final class PlanView {

    private PlanView() {}

    /** The website's words for the rate key's settings, in {@link Fmt.RateUnit}'s order, then EU. */
    private static final String[] WORDS = { "tick", "second", "minute", "hour" };
    public static final String EU = "eu";

    /** The rate unit a plan's JSON says it was read in ({@code view.rateUnit}), or null for none it knows. */
    @Nullable
    public static String rateUnit(@Nullable final JsonObject root) {
        if (root == null || !(root.get("view") instanceof final JsonObject view)) return null;
        final JsonElement unit = view.get("rateUnit");
        if (unit == null || !unit.isJsonPrimitive()
            || !unit.getAsJsonPrimitive()
                .isString())
            return null;
        final String word = unit.getAsString();
        return EU.equals(word) || unit(word) != null ? word : null;
    }

    /** The website's word for the rate key on {@code unit}, or on EU. */
    public static String word(final Fmt.RateUnit unit, final boolean energy) {
        return energy ? EU : WORDS[unit.ordinal()];
    }

    /** The clock a website word names, or null (EU, or a word it does not know). */
    @Nullable
    public static Fmt.RateUnit unit(@Nullable final String word) {
        for (int i = 0; i < WORDS.length; i++) if (WORDS[i].equals(word)) return Fmt.RateUnit.values()[i];
        return null;
    }

    /** The view block for a plan read in {@code word}. */
    public static JsonObject view(final String word) {
        final JsonObject view = new JsonObject();
        view.addProperty("rateUnit", word);
        return view;
    }
}
