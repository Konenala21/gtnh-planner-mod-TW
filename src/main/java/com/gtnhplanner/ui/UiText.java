package com.gtnhplanner.ui;

import net.minecraft.util.StatCollector;

/** Localized text for the planner's fixed interface copy. */
public final class UiText {

    private UiText() {}

    public static String tr(final String key) {
        return StatCollector.translateToLocal("gtnhplanner.ui." + key);
    }
}
