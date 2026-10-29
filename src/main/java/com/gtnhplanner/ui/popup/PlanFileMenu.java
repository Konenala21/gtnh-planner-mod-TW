package com.gtnhplanner.ui.popup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.importer.FfImportException;
import com.gtnhplanner.library.PlanFiles;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.UiText;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The + tab's Open plan file (the website's): the game has no file dialog, so it lists the plans Export plan file
 * saved and the pictures that carry a plan (Screenshot... and the website's own), newest first, and opens the one
 * picked as a new plan.
 */
public final class PlanFileMenu {

    private PlanFileMenu() {}

    /** How many files the list shows. */
    private static final int MOST = 20;

    /** Where Export plan file writes, beside the game's other folders. */
    public static File exports() {
        return new File(Minecraft.getMinecraft().mcDataDir, "gtnhplanner/exports");
    }

    private static File screenshots() {
        return new File(Minecraft.getMinecraft().mcDataDir, "screenshots");
    }

    /** The list, at {@code screenX, screenY}; a notice when there is nothing to list. */
    public static void open(final BoardSession session, final ModularPanel panel, final int screenX,
        final int screenY) {
        final List<PlanFiles.Found> found = PlanFiles.list(MOST, exports(), screenshots());
        if (found.isEmpty()) {
            Sfx.DENY.play();
            session.flash(
                Severity.INFO,
                UiText.tr("planfile.no_plan_files_yet_export_a_plan_or_save_a_screenshot_of_one"));
            return;
        }
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final PlanFiles.Found f : found) rows.add(
            new PickList.Entry(
                null,
                f.name(),
                (f.picture() ? UiText.tr("planfile.picture") : UiText.tr("planfile.plan_file"))
                    + " " + com.gtnhplanner.ui.task.TaskLook.ago(
                        java.time.Instant.ofEpochMilli(f.modified())
                            .toString()),
                Hyb.INK,
                false,
                () -> openFile(session, f.file())));
        Popup.open(
            panel,
            PickList.popup("gtnhplanner_plan_files", UiText.tr("planfile.open_plan_file"), rows, false, 200),
            screenX,
            screenY);
    }

    /** Opens a plan file as a new plan, and says how it went. */
    public static void openFile(final BoardSession session, final File file) {
        try {
            final String text = PlanFiles.read(file);
            final com.gtnhplanner.importer.FfConverter.Result result = com.gtnhplanner.importer.game.FactoryFlowImport
                .importAsSlot(text);
            final int missing = result.report()
                .entries(com.gtnhplanner.importer.ImportReport.Kind.UNMATCHED)
                .size();
            session.flash(
                missing == 0 ? Severity.INFO : Severity.WARN,
                "Opened '" + result.graph()
                    .getName()
                    + "': "
                    + result.report()
                        .summary());
            Sfx.SWEEP.play();
        } catch (final IOException | FfImportException e) {
            session.flash(
                Severity.WARN,
                e.getMessage() == null ? UiText.tr("planfile.that_plan_file_could_not_be_opened") : e.getMessage());
            Sfx.DENY.play();
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn(UiText.tr("planfile.could_not_open_the_plan_file"), file, e);
            session.flash(Severity.WARN, UiText.tr("planfile.that_plan_file_could_not_be_opened"));
            Sfx.DENY.play();
        }
    }
}
