package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.CropsNhProvider;
import com.gtnhplanner.data.provider.MobDropsProvider;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.machines.game.EecCards;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The menus that put another recipe on a card, each a list with a filter: an Extreme Entity Crusher's mob (the
 * website's MobPickerMenu: every mob a Powered Spawner can hold, by name or by what it drops, its biggest drop as its
 * face) and a crop (its CropPickerMenu: by tier, then name), for a crop card or an empty crop farm. A pick keeps the
 * card's machine, settings and wires on what the new recipe also makes (BoardSession.swapRecipe, pickCrop).
 */
final class RecipePicks {

    private RecipePicks() {}

    /** The mobs a crusher card can hold, at a screen point. */
    static void mobs(final BoardSession session, final Node node, final int sx, final int sy) {
        final String current = EecCards.mob(node);
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final MobDropsProvider.Choice c : MobDropsProvider.choices()) {
            final boolean now = c.key()
                .equals(current);
            rows.add(
                new PickList.Entry(
                    c.icon(),
                    c.name(),
                    c.alwaysInfernal() ? "Always infernal" : Fmt.compact(c.health()) + " health",
                    Hyb.INK,
                    now,
                    () -> { if (!now) session.swapRecipe(node, c.ref()); }).searching(String.join(" ", c.drops())));
        }
        if (rows.isEmpty()) rows.add(PickList.Entry.of("No mobs in this game", () -> {}));
        Popup.open(session.panel(), PickList.popup("gtnhplanner_mobs", "MOB IN THE SPAWNER", rows, true, 190), sx, sy);
    }

    /** The crops a crop card or an empty crop farm can plant, at a screen point. */
    static void crops(final BoardSession session, final Node node, final int sx, final int sy) {
        final String current = node.properties.get(CropCards.CROP_ID) instanceof final String s ? s : null;
        final boolean empty = com.gtnhplanner.power.CropFarm.is(node);
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final CropsNhProvider.Choice c : CropsNhProvider.choices()) {
            final boolean now = c.id()
                .equals(current);
            rows.add(
                new PickList.Entry(c.icon(), c.name(), "Tier " + c.tier(), Hyb.INK, now, () -> {
                    if (now) return;
                    if (empty) session.pickCrop(node, c.ref());
                    else session.swapRecipe(node, c.ref());
                }).searching(String.join(" ", c.drops())));
        }
        if (rows.isEmpty()) rows.add(PickList.Entry.of("No crops in this game", () -> {}));
        Popup.open(session.panel(), PickList.popup("gtnhplanner_crops", "CROPS TO PLANT", rows, true, 190), sx, sy);
    }
}
