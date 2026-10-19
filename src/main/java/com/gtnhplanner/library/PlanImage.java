package com.gtnhplanner.library;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

/**
 * A picture of a plan that carries the plan, as the website's plan-image.ts writes and reads one, so either opens the
 * other's picture as a plan. Two copies go in: a {@code tEXt} chunk before {@code IEND} (keyword
 * {@value #KEYWORD}, the plan's JSON in base64), and a strip of pixels along the foot (the plan gzipped behind a short
 * header, three bytes a pixel) that outlives a chat or a clipboard dropping the chunk. Reading tries the chunk, then
 * the pixels. Pure: tests run it on plain images.
 */
public final class PlanImage {

    private PlanImage() {}

    /** The {@code tEXt} chunk's keyword (PLAN_METADATA_KEY). */
    public static final String KEYWORD = "gtnh-factory-flow-project";

    private static final byte[] SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
    private static final byte[] MAGIC = "GTNHPLAN".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = MAGIC.length + 9, GZIP = 1;
    /** No side taller than this (Discord's limit, MAX_DISCORD_IMAGE_SIDE). */
    private static final int MAX_SIDE = 4096;

    // region Writing

    /**
     * The PNG of {@code image} carrying {@code json}: the strip of pixels along its foot ({@code background} behind a
     * picture shrunk to make room, as the website letterboxes it) and the {@code tEXt} chunk.
     */
    public static byte[] png(final BufferedImage image, final String json, final int background) throws IOException {
        return withChunk(encode(withStrip(image, json, background)), json);
    }

    /**
     * {@code image} with the plan's strip of pixels under it (embedProjectJsonInPngPixels): its own width, the plan's
     * bytes three a pixel from the strip's top left, the rest black. A plan too big for a strip under the height limit
     * leaves the picture as it is.
     */
    public static BufferedImage withStrip(final BufferedImage image, final String json, final int background) {
        final byte[] payload = payload(json);
        final int w = image.getWidth();
        final int strip = (int) Math.ceil(payload.length / (w * 3.0));
        if (strip >= MAX_SIDE) return image;
        final int height = Math.min(MAX_SIDE, image.getHeight() + strip);
        final int room = height - strip;
        final double scale = Math.min(1, room / (double) image.getHeight());
        final int iw = (int) Math.round(image.getWidth() * scale), ih = (int) Math.round(image.getHeight() * scale);
        final BufferedImage out = new BufferedImage(w, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        g.setColor(new java.awt.Color(background, true));
        g.fillRect(0, 0, w, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, (w - iw) / 2, 0, iw, ih, null);
        g.dispose();
        final int top = height - strip;
        for (int p = 0; p < w * strip; p++) {
            int rgb = 0;
            for (int c = 0; c < 3; c++) {
                final int at = p * 3 + c;
                rgb = rgb << 8 | (at < payload.length ? payload[at] & 0xFF : 0);
            }
            out.setRGB(p % w, top + p / w, 0xFF000000 | rgb);
        }
        return out;
    }

    /** The strip's bytes (createPixelPayload): the magic, a flag for gzip, the length and CRC-32, then the plan. */
    static byte[] payload(final String json) {
        final byte[] plan = gzip(json.getBytes(StandardCharsets.UTF_8));
        final byte[] out = new byte[HEADER + plan.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = GZIP;
        putInt(out, MAGIC.length + 1, plan.length);
        putInt(out, MAGIC.length + 5, (int) crc(plan));
        System.arraycopy(plan, 0, out, HEADER, plan.length);
        return out;
    }

    /** {@code png} with the plan's {@code tEXt} chunk just before {@code IEND}. */
    public static byte[] withChunk(final byte[] png, final String json) throws IOException {
        if (!isPng(png)) throw new IOException("Invalid PNG file.");
        final int iend = chunk(png, "IEND");
        if (iend < 0) throw new IOException("PNG chunk IEND not found.");
        final byte[] key = KEYWORD.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] text = Base64.getEncoder()
            .encode(json.getBytes(StandardCharsets.UTF_8));
        final byte[] data = new byte[key.length + 1 + text.length];
        System.arraycopy(key, 0, data, 0, key.length);
        System.arraycopy(text, 0, data, key.length + 1, text.length);
        final byte[] chunk = new byte[12 + data.length];
        putInt(chunk, 0, data.length);
        System.arraycopy("tEXt".getBytes(StandardCharsets.US_ASCII), 0, chunk, 4, 4);
        System.arraycopy(data, 0, chunk, 8, data.length);
        putInt(chunk, 8 + data.length, (int) crc(Arrays.copyOfRange(chunk, 4, 8 + data.length)));
        final byte[] out = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, out, 0, iend);
        System.arraycopy(chunk, 0, out, iend, chunk.length);
        System.arraycopy(png, iend, out, iend + chunk.length, png.length - iend);
        return out;
    }

    private static byte[] encode(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
        return out.toByteArray();
    }

    // endregion

    // region Reading

    /** Whether {@code bytes} start as a PNG does. */
    public static boolean isPng(@Nullable final byte[] bytes) {
        if (bytes == null || bytes.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) if (bytes[i] != SIGNATURE[i]) return false;
        return true;
    }

    /**
     * The plan a PNG carries (extractProjectJsonFromPng): its {@code tEXt} chunk's, else its strip of pixels'; null
     * for a picture with none.
     *
     * @throws IOException when it is no PNG, or a chunk runs past its end
     */
    @Nullable
    public static String extract(final byte[] png) throws IOException {
        final String text = chunkText(png);
        return text != null ? text : pixels(png);
    }

    /** The plan in a PNG's {@code tEXt} chunk only: cheap, nothing decoded (a list of files asks it of each). */
    @Nullable
    public static String chunkText(final byte[] png) throws IOException {
        if (!isPng(png)) throw new IOException("Invalid PNG file.");
        int at = SIGNATURE.length;
        while (at + 8 <= png.length) {
            final long length = getInt(png, at) & 0xFFFFFFFFL;
            final String type = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            final long start = at + 8L, end = start + length;
            if (end + 4 > png.length) throw new IOException("Invalid PNG chunk.");
            if ("tEXt".equals(type)) {
                int zero = -1;
                for (int i = (int) start; i < end; i++) if (png[i] == 0) {
                    zero = i;
                    break;
                }
                if (zero > start
                    && KEYWORD.equals(new String(png, (int) start, zero - (int) start, StandardCharsets.ISO_8859_1))) {
                    final byte[] text = Arrays.copyOfRange(png, zero + 1, (int) end);
                    return new String(
                        Base64.getMimeDecoder()
                            .decode(text),
                        StandardCharsets.UTF_8);
                }
            }
            if ("IEND".equals(type)) break;
            at = (int) end + 4;
        }
        return null;
    }

    /**
     * Whether the PNG file {@code file} has the plan's {@code tEXt} chunk: its chunks walked from the disk, each one's
     * data skipped, so a folder of large screenshots is looked through quickly. False for any file that is no PNG.
     */
    public static boolean hasChunk(final java.io.File file) {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
            final byte[] head = new byte[8];
            in.readFully(head);
            if (!isPng(head)) return false;
            final byte[] key = (KEYWORD + '\0').getBytes(StandardCharsets.ISO_8859_1);
            final long size = in.length();
            long at = 8;
            while (at + 8 <= size) {
                in.seek(at);
                in.readFully(head);
                final long length = getInt(head, 0) & 0xFFFFFFFFL;
                final String type = new String(head, 4, 4, StandardCharsets.US_ASCII);
                if ("tEXt".equals(type) && length > key.length) {
                    final byte[] word = new byte[key.length];
                    in.readFully(word);
                    if (Arrays.equals(word, key)) return true;
                }
                if ("IEND".equals(type)) return false;
                at += 12 + length;
            }
        } catch (final IOException e) {
            return false;
        }
        return false;
    }

    /** The plan in a PNG's pixels (extractProjectJsonFromPngPixels): the whole picture searched, the CRC checked. */
    @Nullable
    static String pixels(final byte[] png) throws IOException {
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) return null;
        final int w = image.getWidth(), h = image.getHeight();
        final int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        final byte[] rgb = new byte[argb.length * 3];
        for (int i = 0; i < argb.length; i++) {
            rgb[i * 3] = (byte) (argb[i] >> 16);
            rgb[i * 3 + 1] = (byte) (argb[i] >> 8);
            rgb[i * 3 + 2] = (byte) argb[i];
        }
        final int at = find(rgb, MAGIC);
        if (at < 0 || at + HEADER > rgb.length) return null;
        final int flags = rgb[at + MAGIC.length];
        final long length = getInt(rgb, at + MAGIC.length + 1) & 0xFFFFFFFFL;
        final long want = getInt(rgb, at + MAGIC.length + 5) & 0xFFFFFFFFL;
        if (length <= 0 || at + HEADER + length > rgb.length) return null;
        final byte[] plan = Arrays.copyOfRange(rgb, at + HEADER, at + HEADER + (int) length);
        if (crc(plan) != want) return null;
        return new String((flags & GZIP) != 0 ? gunzip(plan) : plan, StandardCharsets.UTF_8);
    }

    // endregion

    /** Where chunk {@code type} starts in {@code png}, or -1. */
    private static int chunk(final byte[] png, final String type) throws IOException {
        int at = SIGNATURE.length;
        while (at + 8 <= png.length) {
            final long end = at + 8L + (getInt(png, at) & 0xFFFFFFFFL);
            if (end + 4 > png.length) throw new IOException("Invalid PNG chunk.");
            if (type.equals(new String(png, at + 4, 4, StandardCharsets.US_ASCII))) return at;
            at = (int) end + 4;
        }
        return -1;
    }

    private static int find(final byte[] bytes, final byte[] what) {
        outer: for (int i = 0; i <= bytes.length - what.length; i++) {
            for (int k = 0; k < what.length; k++) if (bytes[i + k] != what[k]) continue outer;
            return i;
        }
        return -1;
    }

    private static long crc(final byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static byte[] gzip(final byte[] bytes) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(bytes);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(final byte[] bytes) throws IOException {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            for (int n; (n = z.read(buffer)) > 0;) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void putInt(final byte[] b, final int at, final int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static int getInt(final byte[] b, final int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}
