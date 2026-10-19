package com.gtnhplanner.power;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;

/**
 * The empty crop farm (the website's Crop Farm in its Non-recipe picker's Other column, its
 * {@code factoryflow:crop-farm:empty} recipe): a card dropped in with no crop, which makes nothing and says "Pick a
 * crop". Picking one puts that crop's card in its place ({@code BoardSession.pickCrop}); a crop card is CropsNH's, read
 * from NEI (machines/game/CropCards). A planning card like the custom rate card: never in the generator catalog.
 */
public final class CropFarm {

    /** Its source id, stored in plans: never rename. */
    public static final String ID = "crop-farm";

    /** Its entry for {@link PowerRegistry#get}. */
    public static final PowerSource SOURCE = new PowerSource(
        ID,
        "Crop Farm",
        PowerGroup.OTHER,
        null,
        "Crops planted and harvested. Pick the crop on the card.",
        List.of(),
        read -> new PowerModel(0, List.of(), List.of(), List.of()));

    private CropFarm() {}

    public static boolean is(@Nullable final Node node) {
        return node != null && ID.equals(node.powerSource);
    }
}
