package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gtnhplanner.ui.theme.Fmt;

/** The board's view as the player left it comes back when the planner opens again, and after a restart. */
class PlannerSettingsTest {

    @AfterEach
    void backToTheGame() {
        PlannerSettings.useFile(null);
    }

    @Test
    void aFirstOpenShowsTheOverviewWithOnlyInternalFolded(@TempDir final File dir) {
        PlannerSettings.useFile(new File(dir, "settings.properties"));
        assertTrue(PlannerSettings.overviewOpen());
        assertEquals(Set.of("INTERNAL"), PlannerSettings.overviewFolded());
    }

    @Test
    void theOverviewsFoldsOutliveARestart(@TempDir final File dir) {
        final File file = new File(dir, "settings.properties");
        PlannerSettings.useFile(file);
        PlannerSettings.setOverviewFolded(Set.of("INPUTS", "OUTPUTS"));
        PlannerSettings.setOverviewOpen(false);
        PlannerSettings.useFile(file);
        assertEquals(Set.of("INPUTS", "OUTPUTS"), PlannerSettings.overviewFolded());
        assertFalse(PlannerSettings.overviewOpen());
    }

    @Test
    void everySectionOpenStaysOpen(@TempDir final File dir) {
        final File file = new File(dir, "settings.properties");
        PlannerSettings.useFile(file);
        PlannerSettings.setOverviewFolded(Set.of());
        PlannerSettings.useFile(file);
        assertEquals(Set.of(), PlannerSettings.overviewFolded(), "nothing folded is not the first open's Internal");
    }

    @Test
    void aHandEditedListIsReadLeniently(@TempDir final File dir) throws IOException {
        final File file = new File(dir, "settings.properties");
        Files.writeString(file.toPath(), "overview.folded= MACHINES ,,INPUTS\n", StandardCharsets.UTF_8);
        PlannerSettings.useFile(file);
        assertEquals(Set.of("INPUTS", "MACHINES"), PlannerSettings.overviewFolded());
    }

    @Test
    void theBoardsNewSettingsStartAsTheWebsitesAndOutliveARestart(@TempDir final File dir) {
        final File file = new File(dir, "settings.properties");
        PlannerSettings.useFile(file);
        assertEquals(65, PlannerSettings.zoomedOutPercent());
        assertEquals(0.65f, PlannerSettings.zoomedOutAt());
        assertTrue(PlannerSettings.scrollChangesValues());
        assertEquals(RailFit.DEFAULT, PlannerSettings.overviewWidth());
        PlannerSettings.setZoomedOutPercent(40);
        PlannerSettings.setScrollChangesValues(false);
        PlannerSettings.setOverviewWidth(250);
        // Read at once, without a restart: the board changes as the slider moves.
        assertEquals(0.4f, PlannerSettings.zoomedOutAt());
        assertFalse(PlannerSettings.scrollChangesValues());
        PlannerSettings.useFile(file);
        assertEquals(40, PlannerSettings.zoomedOutPercent());
        assertFalse(PlannerSettings.scrollChangesValues());
        assertEquals(250, PlannerSettings.overviewWidth());
    }

    @Test
    void theZoomedOutViewStaysOnTheSlidersSteps(@TempDir final File dir) throws IOException {
        final File file = new File(dir, "settings.properties");
        PlannerSettings.useFile(file);
        PlannerSettings.setZoomedOutPercent(10);
        assertEquals(35, PlannerSettings.zoomedOutPercent());
        PlannerSettings.setZoomedOutPercent(120);
        assertEquals(100, PlannerSettings.zoomedOutPercent());
        PlannerSettings.setZoomedOutPercent(57);
        assertEquals(55, PlannerSettings.zoomedOutPercent());
        // A hand-edited file out of range, or no number at all.
        Files.writeString(file.toPath(), "board.zoomedOutAt=7\noverview.width=9999\n", StandardCharsets.UTF_8);
        PlannerSettings.useFile(file);
        assertEquals(35, PlannerSettings.zoomedOutPercent());
        assertEquals(RailFit.MOST, PlannerSettings.overviewWidth());
        Files.writeString(file.toPath(), "board.zoomedOutAt=lots\n", StandardCharsets.UTF_8);
        PlannerSettings.useFile(file);
        assertEquals(65, PlannerSettings.zoomedOutPercent());
    }

    @Test
    void theTopBarsKeysOutliveARestart(@TempDir final File dir) {
        final File file = new File(dir, "settings.properties");
        PlannerSettings.useFile(file);
        PlannerSettings.setRateUnit(Fmt.RateUnit.MINUTE);
        PlannerSettings.setPowerAsAmps(true);
        PlannerSettings.setPeakPower(true);
        PlannerSettings.useFile(file);
        assertEquals(Fmt.RateUnit.MINUTE, PlannerSettings.rateUnit());
        assertTrue(PlannerSettings.powerAsAmps());
        assertTrue(PlannerSettings.peakPower());
    }
}
