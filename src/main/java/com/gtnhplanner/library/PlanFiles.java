package com.gtnhplanner.library;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The plan files a player has (the website's Open plan file, open-plan-file.ts, where the game has no file dialog):
 * the plans Export plan file wrote, and the pictures that carry a plan ({@link PlanImage}), newest first. Pure: a
 * folder in, files out.
 */
public final class PlanFiles {

    private PlanFiles() {}

    /** One file: a plan's JSON, or a picture carrying one; when it was written. */
    public record Found(File file, boolean picture, long modified) {

        /** Its name without the extension, as the list shows it. */
        public String name() {
            final String n = file.getName();
            final int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
    }

    /** How many of a folder's newest pictures are looked into: a screenshots folder can hold thousands. */
    private static final int PICTURES_LOOKED_AT = 200;

    /**
     * Every plan file in {@code folders}, newest first, at most {@code most}: each folder's {@code .json} files and its
     * {@code .png} files that carry a plan (their chunk looked for, the newest {@value #PICTURES_LOOKED_AT} of them).
     */
    public static List<Found> list(final int most, final File... folders) {
        final List<Found> out = new ArrayList<>();
        for (final File folder : folders) {
            final File[] files = folder == null ? null : folder.listFiles(File::isFile);
            if (files == null) continue;
            final List<File> pictures = new ArrayList<>();
            for (final File f : files) {
                final String n = f.getName()
                    .toLowerCase(Locale.ROOT);
                if (n.endsWith(".json")) out.add(new Found(f, false, f.lastModified()));
                else if (n.endsWith(".png")) pictures.add(f);
            }
            pictures.sort(
                Comparator.comparingLong(File::lastModified)
                    .reversed());
            for (final File f : pictures.subList(0, Math.min(PICTURES_LOOKED_AT, pictures.size())))
                if (PlanImage.hasChunk(f)) out.add(new Found(f, true, f.lastModified()));
        }
        out.sort(
            Comparator.comparingLong(Found::modified)
                .reversed());
        return out.size() > most ? new ArrayList<>(out.subList(0, most)) : out;
    }

    /**
     * The plan in a file, as the importer reads it: a JSON file's text, a picture's carried plan.
     *
     * @throws IOException when it cannot be read, or a picture carries no plan (the message is for the player)
     */
    public static String read(final File file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file.toPath());
        if (!file.getName()
            .toLowerCase(Locale.ROOT)
            .endsWith(".png") && !PlanImage.isPng(bytes)) return new String(bytes, StandardCharsets.UTF_8);
        final String plan;
        try {
            plan = PlanImage.extract(bytes);
        } catch (final IOException e) {
            throw new IOException("That picture does not read", e);
        }
        if (plan == null) throw new IOException("That picture has no plan in it");
        return plan;
    }
}
