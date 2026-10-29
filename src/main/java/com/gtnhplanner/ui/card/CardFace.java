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
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.data.flowchart.balancer.UpToReason;
import com.gtnhplanner.ui.UiText;
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
     * under it instead ("(set count)" while nothing sets this part of the plan, a crop's
     * UiText.tr("cardface.crops_planted"), or nothing).
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
            : unset ? CardPaint.SET_COUNT
                : crop
                    ? Math.round(value) == 1 ? UiText.tr("cardface.crop_planted") : UiText.tr("cardface.crops_planted")
                    : "";
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
            case UP_TO -> UiText.tr("cardface.runs_as_many_as_the_plan_lets_it_never_more_than_you_set");
            case EXACT -> ruleFixed ? UiText.tr("cardface.runs_at_its_dial")
                : UiText.tr("cardface.runs_exactly_this_many_a_gap_shows_as_a_forced_flow");
            case ANY -> crop ? UiText.tr("cardface.how_many_crops_the_plan_plants")
                : UiText.tr("cardface.how_many_of_this_machine_the_plan_needs");
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
        CLOGGED(0xFFB49CF0),
        UNUSED(0xFF8A8E97),
        LIMITED(0xFFF472B6),
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
     * upstream of a starved one, each forced input, and the ring the card is in (null for none).
     */
    public record Facts(@Nullable UpToReason reason, boolean forced, double running, double capacity,
        @Nullable Double wants, @Nullable String resource, List<String> tied, List<String> upstream,
        List<String> forcedIn, @Nullable UpToLoop.Kind loop) {}

    /**
     * The bar along the foot: its tone, one word ("BOTTLENECK"; empty when the reason was not worked out), one plain
     * line shortened to fit beside them, the fraction the bar shows ("18.2 / 24"; empty for FORCED IN), the fill's
     * width in the track and its share of the bar (0 to 1), and a bottleneck's striped run-on ({@code ghostWidth} 0
     * for none).
     */
    public record Foot(Tone tone, String word, String say, String frac, int fill, double share, int ghostLeft,
        int ghostWidth, Facts facts) {

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
        return foot(rule, set, upTo, forced, portName, cardName, width, null);
    }

    /**
     * As above, for a card in a ring ({@code loop}, null for none): an Up to card's bar says DEAD LOOP or CLOG LOCK in
     * the ring's tone in place of its reason (the website's buildFootView).
     */
    @Nullable
    public static Foot foot(final PinRule rule, final double set, @Nullable final UpToReadout upTo,
        final List<Forced> forced, final Function<PortRef, String> portName, final Function<UUID, String> cardName,
        final ToIntFunction<String> width, @Nullable final UpToLoop.Kind loop) {
        if (rule == PinRule.UP_TO && upTo != null) return upToFoot(set, upTo, portName, cardName, width, loop);
        if (rule == PinRule.EXACT && !forced.isEmpty()) return forcedFoot(set, forced, width);
        return null;
    }

    private static Foot upToFoot(final double set, final UpToReadout upTo, final Function<PortRef, String> portName,
        final Function<UUID, String> cardName, final ToIntFunction<String> width, @Nullable final UpToLoop.Kind loop) {
        final double capacity = upTo.capacity() > 0 ? upTo.capacity() : set;
        final double reached = Math.max(0, upTo.reached());
        final UpToReason reason = upTo.reason();
        final Double wants = reason == UpToReason.BOTTLENECK && upTo.wants() != null && upTo.wants() > capacity
            ? upTo.wants()
            : null;
        // The bar ends at the count, or at what the line wants when that is more.
        final double end = wants != null ? wants : capacity;
        final double share = share(reached, end);
        final int fill = (int) Math.round(FOOT_TRACK * share);
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
            upstream.add(n == null ? UiText.tr("cardface.a_card") : n);
        }
        final String word = loop != null ? loopWord(loop) : reason == null ? "" : reason.name();
        final int room = FOOT_TEXT - (word.isEmpty() ? 0 : width.applyAsInt(word) + FOOT_GAP)
            - width.applyAsInt(frac)
            - FOOT_GAP;
        final String m = wants == null ? "" : Fmt.machines(wants);
        // A clog lock's cards stand still (UNUSED) but say what backs up, as CLOGGED does.
        final UpToReason says = loop == UpToLoop.Kind.CLOG_LOCK ? UpToReason.CLOGGED : reason;
        final List<String> lines = says == null ? List.of() : switch (says) {
            case FULL -> List.of(UiText.tr("cardface.nothing_wants_more"));
            case BOTTLENECK -> wants != null ? List.of("the line wants " + m, "wants " + m)
                : List.of(UiText.tr("cardface.the_line_wants_more"));
            case STARVED -> resource != null ? List.of("short of " + resource, UiText.tr("cardface.short_of_an_input"))
                : List.of(UiText.tr("cardface.short_of_an_input"));
            case CLOGGED -> resource != null ? List.of(
                resource + " has nowhere to go",
                "nowhere for " + resource,
                UiText.tr("cardface.an_output_backs_up")) : List.of(UiText.tr("cardface.an_output_backs_up"));
            case UNUSED -> resource != null
                ? List.of("nothing takes " + resource, UiText.tr("cardface.nothing_takes_it"))
                : List.of(UiText.tr("cardface.nothing_takes_it"));
            case LIMITED -> List.of(UiText.tr("cardface.the_plan_holds_it_back"), UiText.tr("cardface.held_back"));
        };
        return new Foot(
            loop != null ? loopBar(loop) : Tone.of(reason),
            word,
            fitLine(lines, room, width),
            frac,
            fill,
            share,
            ghostLeft,
            ghostWidth,
            new Facts(reason, false, reached, capacity, wants, resource, tied, upstream, List.of(), loop));
    }

    private static Foot forcedFoot(final double set, final List<Forced> forced, final ToIntFunction<String> width) {
        final Forced first = forced.get(0);
        final String keep = set == 1 ? "to keep it running" : "to keep all " + Fmt.machines(set) + " running";
        final String word = UiText.tr("cardface.forced_in");
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
            0,
            new Facts(null, true, set, set, null, null, List.of(), List.of(), each, null));
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

    /**
     * As {@link #edge(Foot)}, with the gear's Problem outlines switch: off, every card keeps the normal edge (its bar,
     * its lit row and a loop's outline still show).
     */
    public static int edge(@Nullable final Foot foot, final boolean outlines) {
        return outlines ? edge(foot) : 0;
    }

    /**
     * The tone a row is lit in when an Up to card's reason names it (the website's withReasonPorts): STARVED's short
     * input, CLOGGED's or UNUSED's output, and the rows tied with it; null for every other row, and on a card that is
     * not Up to or whose reason is FULL, BOTTLENECK or LIMITED. {@code node} is the row's recipe (a shared machine's
     * section), {@code index} its port on that recipe.
     */
    @Nullable
    public static Tone why(final PinRule rule, @Nullable final UpToReadout upTo, final UUID node, final boolean output,
        final int index) {
        if (rule != PinRule.UP_TO || upTo == null) return null;
        final UpToReason reason = upTo.reason();
        if (reason != UpToReason.STARVED && reason != UpToReason.CLOGGED && reason != UpToReason.UNUSED) return null;
        if (names(upTo.port(), node, output, index)) return Tone.of(reason);
        for (final PortRef p : upTo.tied()) if (names(p, node, output, index)) return Tone.of(reason);
        return null;
    }

    private static boolean names(@Nullable final PortRef p, final UUID node, final boolean output, final int index) {
        return p != null && p.input() != output
            && p.portIndex() == index
            && p.nodeId()
                .equals(node);
    }

    /**
     * A lit row's stripes, {body, stripe}: its tone mixed into the card's surface as a free end mixes its own (the
     * website's reasonStripes: 7% and 13%), so each reason has its own.
     */
    public static int[] stripes(final Tone tone) {
        return new int[] { mix(tone.colour, CardPaint.SURFACE, 0.07f), mix(tone.colour, CardPaint.SURFACE, 0.13f) };
    }

    /**
     * The bar a zoomed-out card draws along its foot (the website's glance.ts): its tone, how full (0 to 1: what runs
     * of the count, a bottleneck's of what the line wants, as the card's own bar) and the reason's word (empty when it
     * was not worked out).
     */
    public record Glance(Tone tone, double fill, String word) {}

    /** The zoomed-out bar of a card's foot bar; null without an Up to bar (Any, Exactly, FORCED IN). */
    @Nullable
    public static Glance glance(@Nullable final Foot foot) {
        if (foot == null || foot.facts()
            .forced()) return null;
        return new Glance(foot.tone(), foot.share(), foot.word());
    }

    /**
     * What a ring is called (the website's LOOP_WORD): its notice's chip, and the word on each of its cards' foot bars
     * in place of the reason.
     */
    public static String loopWord(final UpToLoop.Kind kind) {
        return kind == UpToLoop.Kind.DEAD_LOOP ? UiText.tr("cardface.dead_loop") : UiText.tr("cardface.clog_lock");
    }

    /**
     * A ring's bar takes the ring's tone: a dead loop STARVED's amber, a clog lock CLOGGED's purple (never UNUSED's).
     */
    public static Tone loopBar(final UpToLoop.Kind kind) {
        return kind == UpToLoop.Kind.DEAD_LOOP ? Tone.STARVED : Tone.CLOGGED;
    }

    /** The note at the foot of the bar's tip on a card in a ring: that the ring stops itself, and where to act. */
    public static String loopNote(final UpToLoop.Kind kind) {
        return kind == UpToLoop.Kind.DEAD_LOOP
            ? UiText.tr("cardface.this_loop_starves_itself_to_a_stop_bring_this_input_in_from_outside")
            : UiText.tr("cardface.this_loop_clogs_itself_to_a_stop_a_drawer_for_this_output_frees_it");
    }

    /** A dead loop wears STARVED's amber, a clog lock CLOGGED's purple. */
    public static int loopTone(final UpToLoop.Kind kind) {
        return loopBar(kind).colour;
    }

    /**
     * The outline of a card in a ring: its tone in the bright half of the board's prompt clock, dimmed to 30% of it
     * over the card's edge in the other.
     */
    public static int loopFrame(final UpToLoop.Kind kind, final boolean bright) {
        return bright ? loopTone(kind) : mix(loopTone(kind), CardPaint.EDGE, 0.30f);
    }

    /** The ring a card is in (the first when it is in several), by its id; null for none. */
    @Nullable
    public static UpToLoop.Kind loopOf(final List<UpToLoop> loops, final UUID card) {
        for (final UpToLoop loop : loops) if (loop.nodes()
            .contains(card)) return loop.kind();
        return null;
    }

    /** {@code a} at {@code share} over {@code b}, opaque: {@code Hyb.mix}, kept here so this class stays pure. */
    static int mix(final int a, final int b, final float share) {
        final float s = Math.max(0, Math.min(1, share));
        final int r = Math.round((a >> 16 & 0xFF) * s + (b >> 16 & 0xFF) * (1 - s));
        final int g = Math.round((a >> 8 & 0xFF) * s + (b >> 8 & 0xFF) * (1 - s));
        final int bl = Math.round((a & 0xFF) * s + (b & 0xFF) * (1 - s));
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** The title of the bar's tip: how many run of how many. */
    public static String footTitle(final Facts f) {
        if (f.forced()) return "Exactly " + Fmt.machines(f.capacity()) + " running";
        if (f.running() <= NOTHING) return UiText.tr("cardface.none_running");
        if (f.running() >= f.capacity() - 0.000001) return f.capacity() == 1 ? UiText.tr("cardface.running_flat_out")
            : "All " + Fmt.machines(f.capacity()) + " running";
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
            case FULL -> UiText.tr("cardface.every_machine_runs_flat_out");
            case BOTTLENECK -> upTo.wants() != null ? "The plan would use " + Fmt.machines(upTo.wants()) + "."
                : UiText.tr("cardface.the_plan_would_use_more");
            case STARVED -> UiText.tr("cardface.an_input_is_short");
            case CLOGGED -> UiText.tr("cardface.an_output_has_nowhere_to_go");
            case UNUSED -> UiText.tr("cardface.nothing_uses_what_it_makes");
            case LIMITED -> UiText.tr("cardface.the_plan_holds_it_back_sentence");
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
