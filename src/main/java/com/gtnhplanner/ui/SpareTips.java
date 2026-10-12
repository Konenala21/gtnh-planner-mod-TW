package com.gtnhplanner.ui;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A forced input or output, told as the player would tell it: anything in a forced block in the overview, a card's
 * forced row and an unwired port the plan runs through tells its whole story, with the real names and icons: what you
 * set, that satisfying it forces this to be made with nothing to take it (or used with nothing to make it), so it
 * leaves (or comes into) the plan, where it does now, and who could take it instead. The words that matter are in
 * gold.
 */
final class SpareTips {

    /** How many pins are named before "and N more". */
    private static final int PINS_SHOWN = 4;
    /** The room an icon line's text has (the tip's width less the icon). */
    private static final int LINE_W = 206;

    private SpareTips() {}

    /**
     * A leftover's whole story; {@code action} what a click does where the mouse is (null for nothing), {@code drag}
     * whether it can be dragged from there.
     */
    static Tip situation(final BoardSession session, final BoardSession.SpareLine line, @Nullable final String action,
        final boolean drag) {
        final boolean in = line.importing();
        final Tip tip = head(in, session.pinsBehind(line));
        final double[] shares = session.spareShares(line);
        for (int i = 0; i < shares.length; i++) {
            if (shares[i] <= SpareBar.SHOWN) continue;
            final BoardSession.SpareBearer b = line.bearers()
                .get(i);
            final String rate = SpareBar.rate(b, shares[i], session.rateUnit());
            final String where = Hyb.fit(b.name() + " at " + b.machine(), LINE_W - Hyb.width(rate + " "));
            tip.icon(b.item(), b.fluid(), Resources.isPower(b.resourceKey()), "*" + rate + "* " + where);
        }
        tip.gap();
        if (line.bearers()
            .size() > 1)
            tip.say(in ? "You choose which machine *takes it in*." : "You choose which machine *ships it out*.");
        else tip.say(
            "Only the " + line.bearers()
                .get(0)
                .machine() + (in ? " can take it in." : " can ship it out."),
            Tip.SUBTLE);
        if (action != null) tip.action(Tip.Input.LEFT, action);
        if (drag) tip.action(Tip.Input.DRAG, "Hand it to another card");
        return tip;
    }

    /**
     * An unwired port the plan runs through anyway (its machine is pinned, or wired to what is): a forced input or
     * output at the only place it can be, on {@code machine}'s card.
     */
    static Tip open(final BoardSession session, final java.util.UUID node, final String machine,
        final com.gtnhplanner.ui.card.CardModel.PortView view) {
        final boolean in = !view.output();
        final Tip tip = head(in, session.pinsBehind(List.of(node)));
        final String rate = com.gtnhplanner.ui.theme.Fmt.rate(view.perSecond(), session.rateUnit(), view.isFluid());
        final String where = Hyb.fit(view.name() + " at " + machine, LINE_W - Hyb.width(rate + " "));
        tip.icon(view.item(), view.fluid(), false, "*" + rate + "* " + where);
        return tip.gap()
            .say(
                in ? "Nothing in the plan makes it here. Wire it to a card that does, or drag it out for a drawer."
                    : "Nothing takes it here. Wire it to a card that uses it, or drag it out for a drawer.",
                Tip.SUBTLE);
    }

    /**
     * The start every forced tip shares: its title, what you set, why it is forced, and the heading over where it
     * leaves or comes in now.
     */
    private static Tip head(final boolean in, final List<BoardSession.Pin> pins) {
        final Tip tip = Tip.of(in ? "Forced input" : "Forced output")
            .titleColor(in ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK);
        if (!pins.isEmpty()) {
            tip.say("You set", Tip.SUBTLE);
            for (int i = 0; i < Math.min(pins.size(), PINS_SHOWN); i++) {
                final BoardSession.Pin pin = pins.get(i);
                tip.icon(pin.item(), pin.fluid(), pin.eu(), pin.text());
            }
            if (pins.size() > PINS_SHOWN) tip.say("and " + (pins.size() - PINS_SHOWN) + " more", Tip.SUBTLE);
            tip.gap();
        }
        tip.say(
            in ? "Satisfying that *forces* the plan to use more of this than it makes, so it *comes into the plan*."
                : "Satisfying that *forces* this to be made, and nothing in the plan takes it, so it *leaves the plan*.");
        return tip.gap()
            .say(in ? "Coming in now" : "Leaving now", Tip.SUBTLE);
    }
}
