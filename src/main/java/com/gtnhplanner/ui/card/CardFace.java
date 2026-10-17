package com.gtnhplanner.ui.card;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.data.flowchart.balancer.UpToReason;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * What a card says about its machine count (docs/design/up-to.md, the website's clean-model.ts): the count under the
 * machine (what runs, big; under it the rule word and the set number) and the bar along an Up to card's foot (the
 * reason, one plain line, the fraction). Pure: the board's card, the minimap and the world draw these, and the tests
 * read them. Text is measured by the caller's font, so a line is shortened, never cut.
 */
public final class CardFace {

    private CardFace() {}

    /** Below this a count is nothing (the solver's round-off). */
    static final double NOTHING = 0.0000005;

    /**
     * The count under the machine: {@code run} is what runs, as printed ("×18.2"; a crop's has no times sign);
     * {@code ruleWord} and {@code setText} what is set ("up to", "24"), both empty for Any; {@code note} what Any says
     * under it instead ("(set count)" while nothing sets this part of the plan, a crop's "crops planted", or nothing).
     */
    public record Count(String run, double value, PinRule rule, String ruleWord, boolean ruleFixed, double set,
        String setText, String note, boolean prompt, boolean zero, String title) {

        /** A count is set: Up to or Exactly. */
        public boolean pinned() {
            return rule != PinRule.ANY;
        }
    }

    /**
     * A card's count. {@code set} is the count set (ignored for Any), {@code solved} the solved count, {@code readout}
     * the solver's read-out for an Up to card (null when it gave none: then what runs is the solved count),
     * {@code unset} whether nothing sets this part of the plan.
     */
    public static Count count(final PinRule rule, final double set, final double solved,
        @Nullable final UpToReadout readout, final boolean unset, final boolean crop, final boolean ruleFixed) {
        final boolean pinned = rule != PinRule.ANY;
        final UpToReadout upTo = rule == PinRule.UP_TO ? readout : null;
        final double value = upTo != null ? Math.max(0, upTo.reached()) : solved;
        final String note = pinned ? ""
            : unset ? CardPaint.SET_COUNT : crop ? Math.round(value) == 1 ? "crop planted" : "crops planted" : "";
        return new Count(
            (crop ? "" : "×") + Fmt.machines(value),
            value,
            rule,
            pinned ? rule.label.toLowerCase(Locale.ROOT) : "",
            ruleFixed,
            pinned ? set : 0,
            pinned ? setText(set) : "",
            note,
            !pinned && unset,
            upTo != null ? upTo.reached() <= NOTHING : value <= NOTHING,
            switch (rule) {
            case UP_TO -> "Runs as many as the plan lets it. Never more than you set.";
            case EXACT -> ruleFixed ? "Runs at its dial." : "Runs exactly this many. A gap shows as a forced flow.";
            case ANY -> crop ? "How many crops the plan plants." : "How many of this machine the plan needs.";
            });
    }

    /** The set count as the card prints it: up to three decimals, no trailing zeros. */
    public static String setText(final double value) {
        if (!(value > 0) || Double.isInfinite(value)) return "0";
        return BigDecimal.valueOf(Math.round(value * 1000) / 1000.0)
            .stripTrailingZeros()
            .toPlainString();
    }

    /**
     * The count one wheel step gives: from Any, Up to at what runs rounded up (at least one), whichever way; else the
     * set count rounded, one more or fewer. Null below one: back to Any.
     */
    @Nullable
    public static Long wheel(final PinRule rule, final double set, final double value, final int direction) {
        if (rule == PinRule.ANY || !(set > 0)) return (long) Math.max(1, Math.ceil(value - 0.000001));
        final long next = Math.round(set) + direction;
        return next >= 1 ? next : null;
    }

    /** The count a rule picked from the menu starts at: the set count, else what runs rounded up (at least one). */
    public static double ruleCount(final double set, final double value) {
        return set > 0 ? set : Math.max(1, Math.ceil(value - 0.000001));
    }

    /** The rule a typed or wheeled count keeps: its own, Up to from Any; a custom rate card's is always Exactly. */
    public static PinRule ruleForCount(final PinRule rule, final boolean ruleFixed) {
        if (ruleFixed) return PinRule.EXACT;
        return rule == PinRule.ANY ? PinRule.UP_TO : rule;
    }

    // region The bar along the foot

    /** The bar's colour: an Up to card's reason, FORCED IN, or plain (Up to with no reason worked out). */
    public enum Tone {

        FULL(0xFF5EE9B5),
        BOTTLENECK(0xFFF06262),
        STARVED(0xFFE0A63A),
        CLOGGED(0xFF6FB2D6),
        UNUSED(0xFF8A8E97),
        LIMITED(0xFFB49CF0),
        FORCED(0xFF94C3F3),
        PLAIN(0xFF9A9CA4);

        /** The word's colour, and what the fill is mixed from (the website's clean.css tones). */
        public final int colour;

        Tone(final int colour) {
            this.colour = colour;
        }

        static Tone of(@Nullable final UpToReason reason) {
            return reason == null ? PLAIN : valueOf(reason.name());
        }
    }

    /**
     * The bar's facts, for its tip: the reason (null when it was not worked out), FORCED IN, what runs of the count,
     * what the line wants (BOTTLENECK), the row it is about and the others that hold it back as much, the full cards
     * upstream of a starved one, and each forced input.
     */
    public record Facts(@Nullable UpToReason reason, boolean forced, double running, double capacity,
        @Nullable Double wants, @Nullable String resource, List<String> tied, List<String> upstream,
        List<String> forcedIn) {}

    /**
     * The bar along the foot: its tone, one word ("BOTTLENECK"; empty when the reason was not worked out), one plain
     * line shortened to fit beside them, the fraction the bar shows ("18.2 / 24"; empty for FORCED IN), the fill's
     * width in the track, and a bottleneck's striped run-on ({@code ghostWidth} 0 for none).
     */
    public record Foot(Tone tone, String word, String say, String frac, int fill, int ghostLeft, int ghostWidth,
        Facts facts) {

        public boolean ghost() {
            return ghostWidth > 0;
        }
    }

    /** An input forced in at one of the card's rows: its name and rate as printed ("720 L/s"). */
    public record Forced(String name, String rate) {}

    /** The bar's width (the card less its rails' insets), its track (2 in from each side), its words' room. */
    public static final int FOOT_W = CardLayout.W - 2 * CardLayout.IN_RAIL_X, FOOT_TRACK = FOOT_W - 4,
        FOOT_TEXT = FOOT_W - 14, FOOT_GAP = 6;

    /**
     * An Up to card's bar from its read-out, or an Exactly card's FORCED IN from the inputs forced in at its rows; null
     * when there is nothing to say (Any, Exactly with nothing forced, Up to with no read-out). {@code portName} names a
     * row, {@code cardName} a card, {@code width} measures text as the bar draws it.
     */
    @Nullable
    public static Foot foot(final PinRule rule, final double set, @Nullable final UpToReadout upTo,
        final List<Forced> forced, final Function<PortRef, String> portName, final Function<UUID, String> cardName,
        final ToIntFunction<String> width) {
        if (rule == PinRule.UP_TO && upTo != null) return upToFoot(set, upTo, portName, cardName, width);
        if (rule == PinRule.EXACT && !forced.isEmpty()) return forcedFoot(set, forced, width);
        return null;
    }

    private static Foot upToFoot(final double set, final UpToReadout upTo, final Function<PortRef, String> portName,
        final Function<UUID, String> cardName, final ToIntFunction<String> width) {
        final double capacity = upTo.capacity() > 0 ? upTo.capacity() : set;
        final double reached = Math.max(0, upTo.reached());
        final UpToReason reason = upTo.reason();
        final Double wants = reason == UpToReason.BOTTLENECK && upTo.wants() != null && upTo.wants() > capacity
            ? upTo.wants()
            : null;
        // The bar ends at the count, or at what the line wants when that is more.
        final double end = wants != null ? wants : capacity;
        final int fill = (int) Math.round(FOOT_TRACK * share(reached, end));
        final int ghostLeft = (int) Math.round(FOOT_TRACK * share(capacity, end));
        final int ghostWidth = wants != null ? FOOT_TRACK - ghostLeft : 0;
        final String frac = Fmt.machines(reached) + " / " + Fmt.machines(end);
        final String resource = reason == UpToReason.STARVED || reason == UpToReason.CLOGGED
            || reason == UpToReason.UNUSED ? name(upTo.port(), portName) : null;
        final List<String> tied = new ArrayList<>();
        for (final PortRef p : upTo.tied()) {
            final String n = name(p, portName);
            if (n != null) tied.add(n);
        }
        final List<String> upstream = new ArrayList<>();
        for (final UUID id : upTo.upstream()) {
            final String n = cardName.apply(id);
            upstream.add(n == null ? "a card" : n);
        }
        final String word = reason == null ? "" : reason.name();
        final int room = FOOT_TEXT - (word.isEmpty() ? 0 : width.applyAsInt(word) + FOOT_GAP)
            - width.applyAsInt(frac)
            - FOOT_GAP;
        final String m = wants == null ? "" : Fmt.machines(wants);
        final List<String> lines = reason == null ? List.of() : switch (reason) {
            case FULL -> List.of("nothing wants more");
            case BOTTLENECK -> wants != null ? List.of("the line wants " + m, "wants " + m)
                : List.of("the line wants more");
            case STARVED -> resource != null ? List.of("short of " + resource, "short of an input")
                : List.of("short of an input");
            case CLOGGED -> resource != null
                ? List.of(resource + " has nowhere to go", "nowhere for " + resource, "an output backs up")
                : List.of("an output backs up");
            case UNUSED -> resource != null ? List.of("nothing takes " + resource, "nothing takes it")
                : List.of("nothing takes it");
            case LIMITED -> List.of("the plan holds it back", "held back");
        };
        return new Foot(
            Tone.of(reason),
            word,
            fitLine(lines, room, width),
            frac,
            fill,
            ghostLeft,
            ghostWidth,
            new Facts(reason, false, reached, capacity, wants, resource, tied, upstream, List.of()));
    }

    private static Foot forcedFoot(final double set, final List<Forced> forced, final ToIntFunction<String> width) {
        final Forced first = forced.get(0);
        final String keep = set == 1 ? "to keep it running" : "to keep all " + Fmt.machines(set) + " running";
        final String word = "FORCED IN";
        final int room = FOOT_TEXT - width.applyAsInt(word) - FOOT_GAP;
        final int more = forced.size() - 1;
        final List<String> lines = forced.size() == 1 ? List
            .of(first.rate() + " of " + first.name() + " " + keep, first.rate() + " of " + first.name(), first.rate())
            : List.of(
                first.rate() + " of " + first.name() + " and " + more + " more",
                first.name() + " and " + more + " more",
                forced.size() + " inputs");
        final List<String> each = new ArrayList<>();
        for (final Forced f : forced) each.add(f.rate() + " " + f.name());
        return new Foot(
            Tone.FORCED,
            word,
            fitLine(lines, room, width),
            "",
            0,
            0,
            0,
            new Facts(null, true, set, set, null, null, List.of(), List.of(), each));
    }

    private static double share(final double value, final double end) {
        return end > 0 ? Math.min(1, Math.max(0, value / end)) : 0;
    }

    @Nullable
    private static String name(@Nullable final PortRef port, final Function<PortRef, String> portName) {
        return port == null ? null : portName.apply(port);
    }

    /** The first line that fits; none fits, nothing (the word and the fraction still say it). */
    static String fitLine(final List<String> lines, final int room, final ToIntFunction<String> width) {
        for (final String line : lines) if (width.applyAsInt(line) <= room) return line;
        return "";
    }

    /**
     * The colour of a card's frame (its 1 px edge) for a problem its bar shows: an Up to reason other than FULL, or
     * FORCED IN; 0 for none (FULL, Any, no read-out keep the normal edge).
     */
    public static int edge(@Nullable final Foot foot) {
        if (foot == null) return 0;
        return switch (foot.tone()) {
            case FULL, PLAIN -> 0;
            default -> foot.tone().colour;
        };
    }

    /** The title of the bar's tip: how many run of how many. */
    public static String footTitle(final Facts f) {
        if (f.forced()) return "Exactly " + Fmt.machines(f.capacity()) + " running";
        if (f.running() <= NOTHING) return "None running";
        if (f.running() >= f.capacity() - 0.000001)
            return f.capacity() == 1 ? "Running flat out" : "All " + Fmt.machines(f.capacity()) + " running";
        return Fmt.machines(f.running()) + " of " + Fmt.machines(f.capacity()) + " running";
    }

    /** How busy an Up to card's machines are, in percent of the count. */
    public static String usage(final double running, final double capacity) {
        return Fmt.compact(capacity > 0 ? Math.min(100, 100 * running / capacity) : 0) + "%";
    }

    // endregion

    // region Words shared by the overview and the notices

    /** A reason's word in a sentence or the overview's tip ("Starved"); FULL is the quiet case. */
    public static String word(final UpToReason reason) {
        return reason.name()
            .charAt(0)
            + reason.name()
                .substring(1)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * The overview's colour for a machine line's count: the card's foot bar tone (the website's up-to-reason.ts); none
     * (the plain ink) when full or not worked out.
     */
    public static int railColour(@Nullable final UpToReason reason, final int ink) {
        return reason == null || reason == UpToReason.FULL ? ink : Tone.of(reason).colour;
    }

    /** A sentence for a tooltip about why an Up to card runs as it does. */
    @Nullable
    public static String sentence(@Nullable final UpToReadout upTo) {
        if (upTo == null || upTo.reason() == null) return null;
        return switch (upTo.reason()) {
            case FULL -> "Every machine runs flat out.";
            case BOTTLENECK -> upTo.wants() != null ? "The plan would use " + Fmt.machines(upTo.wants()) + "."
                : "The plan would use more.";
            case STARVED -> "An input is short.";
            case CLOGGED -> "An output has nowhere to go.";
            case UNUSED -> "Nothing uses what it makes.";
            case LIMITED -> "The plan holds it back.";
        };
    }

    /**
     * What holds a drawer's rate back, as short sentences after it (the website's notices.ts): each limiting card by
     * its rule and reason, each limiting drawer by its rate. {@code rule} and {@code reason} read a card's.
     */
    public static String holders(final List<UUID> cards, final List<UUID> drawers, final Function<UUID, String> name,
        final Function<UUID, PinRule> rule, final Function<UUID, UpToReason> reason,
        final Function<UUID, String> drawerName) {
        final StringBuilder out = new StringBuilder();
        for (final UUID id : cards) {
            final String n = name.apply(id);
            final UpToReason why = reason.apply(id);
            final String sentence;
            if (rule.apply(id) == PinRule.EXACT) sentence = "The " + n + " is set exactly.";
            else if (why != null && why != UpToReason.FULL && why != UpToReason.BOTTLENECK) sentence = "The " + n
                + " is "
                + why.name()
                    .toLowerCase(Locale.ROOT)
                + ".";
            else sentence = "The " + n + " is full.";
            out.append(' ')
                .append(sentence);
        }
        for (final UUID id : drawers) out.append(" The ")
            .append(drawerName.apply(id))
            .append(" drawer's rate holds it back.");
        return out.toString();
    }

    // endregion
}
