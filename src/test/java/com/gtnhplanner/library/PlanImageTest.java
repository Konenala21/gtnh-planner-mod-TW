package com.gtnhplanner.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A picture of a plan carries the plan as the website's plan-image.ts writes and reads one: a {@code tEXt} chunk
 * before {@code IEND}, and a strip of pixels along the foot for when a chat drops the chunk.
 */
class PlanImageTest {

    private static final String PLAN = "{\"name\":\"Ünïcode ☕ plan\",\"recipes\":[],\"nodes\":[],\"note\":\""
        + "x".repeat(3000)
        + "\"}";

    private static BufferedImage picture(final int w, final int h) {
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image.setRGB(x, y, 0xFF000000 | x * 3 << 16 | y * 5);
        return image;
    }

    private static byte[] encode(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aPictureCarriesItsPlanBothWays() throws IOException {
        final byte[] png = PlanImage.png(picture(80, 40), PLAN, 0xFF141414);
        assertEquals(PLAN, PlanImage.extract(png));
        assertEquals(PLAN, PlanImage.chunkText(png));
        assertEquals(PLAN, PlanImage.pixels(png), "the strip alone carries it too");
        final BufferedImage shown = ImageIO.read(new ByteArrayInputStream(png));
        final int strip = (int) Math.ceil(PlanImage.payload(PLAN).length / (80 * 3.0));
        assertEquals(80, shown.getWidth());
        assertEquals(40 + strip, shown.getHeight(), "the strip goes under the picture");
        assertEquals(picture(80, 40).getRGB(17, 9), shown.getRGB(17, 9), "the picture itself is untouched");
        // For the website's own reader to try (tools: its plan-image.ts under node): the picture, and it with no chunk.
        final Path out = Path.of("build", "tmp");
        Files.createDirectories(out);
        Files.write(out.resolve("gtnhplanner-plan-picture.png"), png);
        Files.write(out.resolve("gtnhplanner-plan-picture-stripped.png"), encode(shown));
    }

    @Test
    void theChunkIsTheWebsitesTextChunkJustBeforeTheEnd() throws IOException {
        final byte[] png = PlanImage.png(picture(16, 16), PLAN, 0xFF141414);
        // The last chunk is IEND; the one before it the plan's tEXt.
        final int iend = png.length - 12;
        assertEquals("IEND", new String(png, iend + 4, 4, StandardCharsets.US_ASCII));
        final byte[] key = (PlanImage.KEYWORD + '\0').getBytes(StandardCharsets.ISO_8859_1);
        final byte[] text = Base64.getEncoder()
            .encode(PLAN.getBytes(StandardCharsets.UTF_8));
        final int length = key.length + text.length, at = iend - 12 - length;
        assertEquals(length, readInt(png, at));
        assertEquals("tEXt", new String(png, at + 4, 4, StandardCharsets.US_ASCII));
        assertArrayEquals(key, Arrays.copyOfRange(png, at + 8, at + 8 + key.length));
        assertArrayEquals(text, Arrays.copyOfRange(png, at + 8 + key.length, at + 8 + length), "standard base64");
        final CRC32 crc = new CRC32();
        crc.update(png, at + 4, 4 + length);
        assertEquals(crc.getValue(), readInt(png, at + 8 + length) & 0xFFFFFFFFL, "over the type and the data");
    }

    @Test
    void theStripIsTheWebsitesPayload() {
        final byte[] p = PlanImage.payload(PLAN);
        assertEquals("GTNHPLAN", new String(p, 0, 8, StandardCharsets.US_ASCII));
        assertEquals(1, p[8], "gzipped");
        final int length = readInt(p, 9);
        assertEquals(p.length - 17, length);
        assertEquals(0x1f, p[17] & 0xFF, "a gzip stream");
        assertEquals(0x8b, p[18] & 0xFF);
        final CRC32 crc = new CRC32();
        crc.update(p, 17, length);
        assertEquals(crc.getValue(), readInt(p, 13) & 0xFFFFFFFFL);
    }

    @Test
    void aChatThatDropsTheChunkStillLeavesThePlanInThePixels() throws IOException {
        final byte[] stripped = encode(PlanImage.withStrip(picture(64, 30), PLAN, 0xFF141414));
        assertNull(PlanImage.chunkText(stripped));
        assertEquals(PLAN, PlanImage.extract(stripped));
    }

    @Test
    void aPictureTheWebsiteMadeOpensHere() throws IOException {
        // Written by the website's own embedProjectJsonInPng (in a browser) round a 40 by 24 canvas.
        final byte[] png;
        try (java.io.InputStream in = PlanImageTest.class.getResourceAsStream("/plan-pictures/website-made.png")) {
            png = in.readAllBytes();
        }
        final String want = "{\"name\":\"Website plan ✓\",\"recipes\":[],\"nodes\":[],\"view\":{\"rateUnit\":\"hour\"}}";
        assertEquals(want, PlanImage.chunkText(png));
        assertEquals(want, PlanImage.pixels(png), "its strip of pixels too");
        assertEquals(
            want,
            PlanImage.extract(encode(ImageIO.read(new ByteArrayInputStream(png)))),
            "with the chunk dropped");
    }

    @Test
    void aPlainPictureHasNoPlanAndAnythingElseIsNoPicture() throws IOException {
        assertNull(PlanImage.extract(encode(picture(20, 20))));
        assertThrows(IOException.class, () -> PlanImage.extract("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aPlanTooTallForAStripStillGoesInTheChunk() throws IOException {
        final StringBuilder big = new StringBuilder("{\"n\":\"");
        final java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 120_000; i++) big.append((char) ('a' + r.nextInt(26)));
        final String plan = big.append("\"}")
            .toString();
        final byte[] png = PlanImage.png(picture(4, 4), plan, 0xFF141414);
        assertEquals(
            4,
            ImageIO.read(new ByteArrayInputStream(png))
                .getHeight(),
            "no strip over the height limit");
        assertEquals(plan, PlanImage.extract(png));
    }

    @Test
    void theFileListShowsPlansAndPicturesThatCarryOneNewestFirst(@TempDir final Path dir) throws IOException {
        final File exports = dir.resolve("exports")
            .toFile(),
            shots = dir.resolve("screenshots")
                .toFile();
        assertTrue(exports.mkdirs() && shots.mkdirs());
        final File json = new File(exports, "Steel line.json"), shot = new File(shots, "Steel_line_2026.png"),
            plain = new File(shots, "2026-10-18_12.00.00.png");
        Files.writeString(json.toPath(), PLAN);
        Files.write(shot.toPath(), PlanImage.png(picture(30, 20), PLAN, 0xFF141414));
        Files.write(plain.toPath(), encode(picture(30, 20)));
        assertTrue(json.setLastModified(1_000_000L) && shot.setLastModified(2_000_000L));
        assertTrue(PlanImage.hasChunk(shot));
        assertFalse(PlanImage.hasChunk(plain));
        assertFalse(PlanImage.hasChunk(json));

        final List<PlanFiles.Found> found = PlanFiles.list(
            20,
            exports,
            shots,
            dir.resolve("missing")
                .toFile());
        assertEquals(
            List.of("Steel_line_2026", "Steel line"),
            found.stream()
                .map(PlanFiles.Found::name)
                .toList());
        assertTrue(
            found.get(0)
                .picture());
        assertEquals(PLAN, PlanFiles.read(shot));
        assertEquals(PLAN, PlanFiles.read(json));
        assertEquals(
            "That picture has no plan in it",
            assertThrows(IOException.class, () -> PlanFiles.read(plain)).getMessage());
        assertEquals(
            1,
            PlanFiles.list(1, exports, shots)
                .size());
    }

    private static int readInt(final byte[] b, final int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}
