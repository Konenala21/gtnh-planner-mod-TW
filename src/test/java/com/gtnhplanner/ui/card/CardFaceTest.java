package com.gtnhplanner.ui.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.data.flowchart.balancer.UpToReason;

/**
 * What a card says about its machine count (docs/design/up-to.md, the website's clean-model tests): what runs and what
 * is set under the machine, the wheel and the rule menu's counts, the bar along an Up to card's foot and its lines
 * shortened to fit, FORCED IN, and the sentences that name what holds a drawer back.
 */
class CardFaceTest {

    private static final UUID CARD = UUID.randomUUID(), OTHER = UUID.randomUUID();
    /** The game's font runs about six pixels a letter. */
    private static final ToIntFunction<String> SIX = s -> 6 * s.length();
    private static final PortRef OXYGEN = new PortRef(CARD, 1, true), WATER = new PortRef(CARD, 2, true),
        ACID = new PortRef(CARD, 0, false);
    private static final Map<PortRef, String> NAMES = Map.of(OXYGEN, "Oxygen", WATER, "Water", ACID, "Sulfuric Acid");
    private static final Function<PortRef, String> PORTS = NAMES::get;
    private static final Function<UUID, String> CARDS = id -> id.equals(OTHER) ? "Electrolyzer" : null;

    private static UpToReadout readout(final double capacity, final double reached, final UpToReason reason,
        final PortRef port, final Double wants) {
        return new UpToReadout(CARD, List.of(CARD), capacity, reached, reason, port, List.of(), wants, List.of());
    }

    // region The count under the machine

    @Test
    void anyShowsWhatThePlanNeedsAndAsksForACountOnlyWhereNothingSetsIt() {
        final CardFace.Count asks = CardFace.count(PinRule.ANY, 0, 3.2, null, true, false, false);
        assertEquals("×3.2", asks.run());
        assertEquals("", asks.ruleWord());
        assertEquals("", asks.setText());
        assertEquals("(set count)", asks.note());
        assertTrue(asks.prompt());
        assertFalse(asks.pinned());

        final CardFace.Count quiet = CardFace.count(PinRule.ANY, 0, 3.2, null, false, false, false);
        assertEquals("", quiet.note());
        assertFalse(quiet.prompt());
        assertTrue(
            CardFace.count(PinRule.ANY, 0, 0, null, false, false, false)
                .zero());
    }

    @Test
    void upToShowsWhatRunsBigAndWhatIsSetUnderIt() {
        final CardFace.Count c = CardFace
            .count(PinRule.UP_TO, 24, 24, readout(24, 18.2, UpToReason.STARVED, OXYGEN, null), true, false, false);
        assertEquals("×18.2", c.run(), "the read-out's reached, not the set count");
        assertEquals(18.2, c.value(), 1e-9);
        assertEquals("up to", c.ruleWord());
        assertEquals("24", c.setText());
        assertEquals("", c.note(), "a set count never asks for one");
        assertFalse(c.prompt());
        assertFalse(c.zero());

        final CardFace.Count idle = CardFace
            .count(PinRule.UP_TO, 3, 3, readout(3, 0, UpToReason.UNUSED, ACID, null), false, false, false);
        assertEquals("×0", idle.run());
        assertTrue(idle.zero());

        // Before the solver reads it out, what runs is the solved count.
        assertEquals(
            "×24",
            CardFace.count(PinRule.UP_TO, 24, 24, null, false, false, false)
                .run());
    }

    @Test
    void exactlyIgnoresALeftOverReadOut() {
        final CardFace.Count c = CardFace
            .count(PinRule.EXACT, 4, 4, readout(4, 2, UpToReason.STARVED, OXYGEN, null), false, false, false);
        assertEquals("×4", c.run());
        assertEquals("exactly", c.ruleWord());
        assertEquals("4", c.setText());
        assertEquals("Runs exactly this many. A gap shows as a forced flow.", c.title());
    }

    @Test
    void aCropCountsCropsPlantedWithNoTimesSign() {
        final CardFace.Count many = CardFace.count(PinRule.ANY, 0, 6, null, false, true, false);
        assertEquals("6", many.run());
        assertEquals("crops planted", many.note());
        assertEquals(
            "crop planted",
            CardFace.count(PinRule.ANY, 0, 1, null, false, true, false)
                .note());
        assertEquals(
            "(set count)",
            CardFace.count(PinRule.ANY, 0, 1, null, true, true, false)
                .note());
        final CardFace.Count set = CardFace.count(PinRule.UP_TO, 12, 12, null, false, true, false);
        assertEquals("12", set.run());
        assertEquals("up to", set.ruleWord());
    }

    @Test
    void aCustomRateCardIsExactlyAtItsDial() {
        final CardFace.Count c = CardFace.count(PinRule.EXACT, 1, 1, null, false, false, true);
        assertTrue(c.ruleFixed());
        assertEquals("Runs at its dial.", c.title());
        assertEquals(PinRule.EXACT, CardFace.ruleForCount(PinRule.ANY, true));
    }

    @Test
    void theSetCountPrintsUpToThreeDecimals() {
        assertEquals("24", CardFace.setText(24));
        assertEquals("2.5", CardFace.setText(2.5));
        assertEquals("0.333", CardFace.setText(1 / 3.0));
        assertEquals("0", CardFace.setText(0));
    }

    @Test
    void theWheelStartsUpToAtWhatRunsThenStepsTheSetCount() {
        // From Any: Up to at what runs rounded up, whichever way the wheel turns.
        assertEquals(4L, CardFace.wheel(PinRule.ANY, 0, 3.2, 1));
        assertEquals(4L, CardFace.wheel(PinRule.ANY, 0, 3.2, -1));
        assertEquals(1L, CardFace.wheel(PinRule.ANY, 0, 0, 1), "at least one");
        assertEquals(3L, CardFace.wheel(PinRule.ANY, 0, 3, 1), "a whole count is not rounded past");
        // Set: one more or fewer than set, not than runs.
        assertEquals(25L, CardFace.wheel(PinRule.UP_TO, 24, 18.2, 1));
        assertEquals(23L, CardFace.wheel(PinRule.EXACT, 24, 24, -1));
        assertNull(CardFace.wheel(PinRule.UP_TO, 1, 1, -1), "below one is Any");
    }

    @Test
    void aTypedOrWheeledCountKeepsItsRuleAndAnyBecomesUpTo() {
        assertEquals(PinRule.UP_TO, CardFace.ruleForCount(PinRule.ANY, false));
        assertEquals(PinRule.UP_TO, CardFace.ruleForCount(PinRule.UP_TO, false));
        assertEquals(PinRule.EXACT, CardFace.ruleForCount(PinRule.EXACT, false));
        // The rule menu from Any starts at what runs rounded up; with a count set, at that count.
        assertEquals(4, CardFace.ruleCount(0, 3.2), 1e-9);
        assertEquals(24, CardFace.ruleCount(24, 18.2), 1e-9);
        assertEquals(1, CardFace.ruleCount(0, 0), 1e-9);
    }

    // endregion

    // region The bar along the foot

    private static CardFace.Foot upTo(final UpToReadout r, final ToIntFunction<String> width) {
        return CardFace.foot(PinRule.UP_TO, r.capacity(), r, List.of(), PORTS, CARDS, width);
    }

    @Test
    void fullFillsTheBar() {
        final CardFace.Foot f = upTo(readout(24, 24, UpToReason.FULL, null, null), SIX);
        assertEquals(CardFace.Tone.FULL, f.tone());
        assertEquals("FULL", f.word());
        assertEquals("nothing wants more", f.say());
        assertEquals("24 / 24", f.frac());
        assertEquals(CardFace.FOOT_TRACK, f.fill());
        assertFalse(f.ghost());
    }

    @Test
    void aBottleneckRunsOnStripedToWhatTheLineWants() {
        final CardFace.Foot f = upTo(readout(24, 24, UpToReason.BOTTLENECK, null, 31.0), SIX);
        assertEquals("BOTTLENECK", f.word());
        assertEquals("the line wants 31", f.say());
        assertEquals("24 / 31", f.frac(), "the fraction says what the bar shows: to what the line wants");
        final int left = (int) Math.round(CardFace.FOOT_TRACK * 24 / 31.0);
        assertEquals(left, f.fill());
        assertEquals(left, f.ghostLeft());
        assertEquals(CardFace.FOOT_TRACK - left, f.ghostWidth());
        assertEquals(
            31.0,
            f.facts()
                .wants(),
            1e-9);
        // Wanting no more than it has is no run-on.
        final CardFace.Foot level = upTo(readout(24, 24, UpToReason.BOTTLENECK, null, 24.0), SIX);
        assertFalse(level.ghost());
        assertEquals("the line wants more", level.say());
    }

    @Test
    void starvedNamesTheShortInputAndTheFullCardsUpstream() {
        final UpToReadout r = new UpToReadout(
            CARD,
            List.of(CARD),
            24,
            18.2,
            UpToReason.STARVED,
            OXYGEN,
            List.of(WATER),
            null,
            List.of(OTHER, UUID.randomUUID()));
        final CardFace.Foot f = upTo(r, SIX);
        assertEquals(CardFace.Tone.STARVED, f.tone());
        assertEquals("short of Oxygen", f.say());
        assertEquals("18.2 / 24", f.frac());
        assertEquals((int) Math.round(CardFace.FOOT_TRACK * 18.2 / 24), f.fill());
        assertEquals(
            "Oxygen",
            f.facts()
                .resource());
        assertEquals(
            List.of("Water"),
            f.facts()
                .tied());
        assertEquals(
            List.of("Electrolyzer", "a card"),
            f.facts()
                .upstream());
    }

    @Test
    void cloggedAndUnusedNameTheOutputAndShortenToFit() {
        final CardFace.Foot clogged = upTo(readout(24, 12, UpToReason.CLOGGED, ACID, null), SIX);
        assertEquals("Sulfuric Acid has nowhere to go", clogged.say());
        // A wider font leaves less room: the next line that fits, never a cut word.
        final CardFace.Foot narrow = upTo(readout(24, 12, UpToReason.CLOGGED, ACID, null), s -> 7 * s.length());
        assertEquals("nowhere for Sulfuric Acid", narrow.say());
        final CardFace.Foot unused = upTo(readout(24, 0, UpToReason.UNUSED, ACID, null), SIX);
        assertEquals("nothing takes Sulfuric Acid", unused.say());
        assertEquals("0 / 24", unused.frac());
        assertEquals(0, unused.fill());
    }

    @Test
    void aLineThatCannotFitIsLeftOutForTheWordAndFraction() {
        assertEquals("wants 31", upTo(readout(24, 24, UpToReason.BOTTLENECK, null, 31.0), s -> 10 * s.length()).say());
        final CardFace.Foot none = upTo(readout(24, 24, UpToReason.BOTTLENECK, null, 31.0), s -> 12 * s.length());
        assertEquals("", none.say());
        assertEquals("BOTTLENECK", none.word());
        assertEquals("24 / 31", none.frac());
    }

    @Test
    void limitedAndUnworkedReasons() {
        assertEquals("the plan holds it back", upTo(readout(5, 3, UpToReason.LIMITED, null, null), SIX).say());
        final CardFace.Foot plain = upTo(readout(5, 3, null, null, null), SIX);
        assertEquals(CardFace.Tone.PLAIN, plain.tone());
        assertEquals("", plain.word());
        assertEquals("", plain.say());
        assertEquals("3 / 5", plain.frac(), "fill and fraction still say what runs");
    }

    @Test
    void exactlyWithAForcedInputSaysForcedIn() {
        final List<CardFace.Forced> oxygen = List.of(new CardFace.Forced("Oxygen", "720 L/s"));
        final CardFace.Foot one = CardFace.foot(PinRule.EXACT, 24, null, oxygen, PORTS, CARDS, s -> 5 * s.length());
        assertEquals(CardFace.Tone.FORCED, one.tone());
        assertEquals("FORCED IN", one.word());
        assertEquals("720 L/s of Oxygen to keep all 24 running", one.say());
        assertEquals("", one.frac());
        assertEquals(0, one.fill());
        assertEquals(
            List.of("720 L/s Oxygen"),
            one.facts()
                .forcedIn());
        assertEquals(
            "720 L/s of Oxygen",
            CardFace.foot(PinRule.EXACT, 24, null, oxygen, PORTS, CARDS, SIX)
                .say(),
            "shortened to what fits");
        assertEquals(
            "1/s of Oxygen to keep it running",
            CardFace.foot(PinRule.EXACT, 1, null, List.of(new CardFace.Forced("Oxygen", "1/s")), PORTS, CARDS, SIX)
                .say());
        assertEquals(
            "1/s of Oxygen and 1 more",
            CardFace
                .foot(
                    PinRule.EXACT,
                    4,
                    null,
                    List.of(new CardFace.Forced("Oxygen", "1/s"), new CardFace.Forced("Water", "2 L/s")),
                    PORTS,
                    CARDS,
                    SIX)
                .say());
    }

    @Test
    void noBarWhenThereIsNothingToSay() {
        final List<CardFace.Forced> forced = List.of(new CardFace.Forced("Oxygen", "1/s"));
        assertNull(CardFace.foot(PinRule.ANY, 0, null, forced, PORTS, CARDS, SIX), "Any");
        assertNull(CardFace.foot(PinRule.EXACT, 4, null, List.of(), PORTS, CARDS, SIX), "Exactly with nothing forced");
        assertNull(CardFace.foot(PinRule.UP_TO, 4, null, forced, PORTS, CARDS, SIX), "Up to before the read-out");
        // A read-out left over from before the rule changed is not this card's.
        assertNull(
            CardFace
                .foot(PinRule.ANY, 0, readout(4, 2, UpToReason.STARVED, OXYGEN, null), List.of(), PORTS, CARDS, SIX));
    }

    @Test
    void theBarsTipTitleSaysHowManyRun() {
        assertEquals(
            "18.2 of 24 running",
            CardFace.footTitle(upTo(readout(24, 18.2, UpToReason.STARVED, OXYGEN, null), SIX).facts()));
        assertEquals(
            "All 24 running",
            CardFace.footTitle(upTo(readout(24, 24, UpToReason.FULL, null, null), SIX).facts()));
        assertEquals(
            "Running flat out",
            CardFace.footTitle(upTo(readout(1, 1, UpToReason.FULL, null, null), SIX).facts()));
        assertEquals(
            "None running",
            CardFace.footTitle(upTo(readout(3, 0, UpToReason.UNUSED, ACID, null), SIX).facts()));
        assertEquals("75%", CardFace.usage(18, 24));
        assertEquals("100%", CardFace.usage(30, 24), "never past a hundred");
    }

    // endregion

    // region The overview and the notices

    @Test
    void aShortfallNamesWhatHoldsItBack() {
        final UUID tower = UUID.randomUUID(), electrolyzer = UUID.randomUUID(), mixer = UUID.randomUUID(),
            other = UUID.randomUUID();
        final Map<UUID, String> names = Map
            .of(tower, "Distillation Tower", electrolyzer, "Electrolyzer", mixer, "Mixer");
        final Map<UUID, PinRule> rules = Map
            .of(tower, PinRule.UP_TO, electrolyzer, PinRule.EXACT, mixer, PinRule.UP_TO);
        final Map<UUID, UpToReason> reasons = Map.of(tower, UpToReason.BOTTLENECK, mixer, UpToReason.STARVED);
        assertEquals(
            " The Distillation Tower is full. The Electrolyzer is set exactly. The Mixer is starved."
                + " The Benzene drawer's rate holds it back.",
            CardFace.holders(
                List.of(tower, electrolyzer, mixer),
                List.of(other),
                names::get,
                rules::get,
                reasons::get,
                id -> "Benzene"));
        assertEquals("", CardFace.holders(List.of(), List.of(), names::get, rules::get, reasons::get, id -> ""));
    }

    @Test
    void reasonsReadInTheOverview() {
        assertEquals("Starved", CardFace.word(UpToReason.STARVED));
        assertEquals(0xFFE8E9EE, CardFace.railColour(UpToReason.FULL, 0xFFE8E9EE), "full is the quiet case");
        assertEquals(0xFFE8E9EE, CardFace.railColour(null, 0xFFE8E9EE));
        assertEquals(0xFFE0A63A, CardFace.railColour(UpToReason.CLOGGED, 0xFFE8E9EE));
        assertEquals("The plan would use 31.", CardFace.sentence(readout(24, 24, UpToReason.BOTTLENECK, null, 31.0)));
        assertNull(CardFace.sentence(readout(24, 24, null, null, null)));
    }

    // endregion
}
