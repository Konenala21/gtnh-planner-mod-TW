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
            tip.say(
                in ? UiText.tr("spare.you_choose_which_machine_takes_it_in")
                    : UiText.tr("spare.you_choose_which_machine_ships_it_out"));
        else tip.say(
            "Only the " + line.bearers()
                .get(0)
                .machine() + (in ? " can take it in." : " can ship it out."),
            Tip.SUBTLE);
        if (action != null) tip.action(Tip.Input.LEFT, action);
        if (drag) tip.action(Tip.Input.DRAG, UiText.tr("spare.hand_it_to_another_card"));
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
                in ? UiText.tr(
                    "spare.nothing_in_the_plan_makes_it_here_wire_it_to_a_card_that_does_or_drag_it_out_for_a_drawer")
                    : UiText
                        .tr("spare.nothing_takes_it_here_wire_it_to_a_card_that_uses_it_or_drag_it_out_for_a_drawer"),
                Tip.SUBTLE);
    }

    /**
     * The start every forced tip shares: its title, what you set, why it is forced, and the heading over where it
     * leaves or comes in now.
     */
    private static Tip head(final boolean in, final List<BoardSession.Pin> pins) {
        final Tip tip = Tip.of(in ? UiText.tr("spare.forced_input") : UiText.tr("spare.forced_output"))
            .titleColor(in ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK);
        if (!pins.isEmpty()) {
            tip.say(UiText.tr("spare.you_set"), Tip.SUBTLE);
            for (int i = 0; i < Math.min(pins.size(), PINS_SHOWN); i++) {
                final BoardSession.Pin pin = pins.get(i);
                tip.icon(pin.item(), pin.fluid(), pin.eu(), pin.text());
            }
            if (pins.size() > PINS_SHOWN) tip.say("and " + (pins.size() - PINS_SHOWN) + " more", Tip.SUBTLE);
            tip.gap();
        }
        tip.say(
            in ? UiText
                .tr("spare.satisfying_that_forces_the_plan_to_use_more_of_this_than_it_makes_so_it_comes_into_the_plan")
                : UiText.tr(
                    "spare.satisfying_that_forces_this_to_be_made_and_nothing_in_the_plan_takes_it_so_it_leaves_the_plan"));
        return tip.gap()
            .say(in ? UiText.tr("spare.coming_in_now") : UiText.tr("spare.leaving_now"), Tip.SUBTLE);
    }
}
