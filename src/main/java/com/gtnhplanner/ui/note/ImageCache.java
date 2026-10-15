package com.gtnhplanner.ui.note;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import com.gtnhplanner.GtnhPlanner;

/**
 * The pictures of image annotations (docs/design/annotations.md): fetched in the background from the plan's URL (http
 * or https only), at most {@link #MAX_BYTES} of it, decoded, shrunk to {@link #MAX_SIDE} a side and kept on disk
 * ({@code gtnhplanner/images/<hash>.png}), then made a GL texture on the render thread the first time it is drawn.
 * Until then, and when anything fails, the card shows its placeholder. Nothing here ever blocks a frame.
 */
public final class ImageCache {

    private ImageCache() {}

    /** The most a picture may weigh on the wire, and the longest side it keeps. */
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_SIDE = 512;

    public enum State {
        LOADING,
        READY,
        FAILED
    }

    /** One picture: where it is in its journey, and its texture once there is one. */
    public static final class Entry {

        private volatile State state = State.LOADING;
        private volatile BufferedImage pending;
        private ResourceLocation location;
        private int width, height;

        public State state() {
            return state;
        }

        /** The texture, made now if the picture has just arrived; null until it has. Render thread only. */
        @Nullable
        public ResourceLocation texture() {
            final BufferedImage image = pending;
            if (image != null) {
                pending = null;
                try {
                    final ResourceLocation loc = new ResourceLocation(
                        "gtnhplanner",
                        "image/" + Integer.toHexString(id));
                    Minecraft.getMinecraft()
                        .getTextureManager()
                        .loadTexture(loc, new DynamicTexture(image));
                    location = loc;
                    width = image.getWidth();
                    height = image.getHeight();
                } catch (final RuntimeException | LinkageError e) {
                    GtnhPlanner.LOG.info("Could not make a texture of an annotation's picture", e);
                    state = State.FAILED;
                }
            }
            return location;
        }

        /** The picture's own size; 0 until {@link #texture()} has returned it. */
        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        private int id;
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static ExecutorService pool;
    private static int next;

    /** The picture at {@code url}, started on its way the first time it is asked for. */
    public static Entry of(final String url) {
        return ENTRIES.computeIfAbsent(url, u -> {
            final Entry e = new Entry();
            e.id = ++next;
            executor().submit(() -> load(u, e));
            return e;
        });
    }

    private static synchronized ExecutorService executor() {
        if (pool == null) pool = Executors.newFixedThreadPool(2, r -> {
            final Thread t = new Thread(r, "gtnhplanner-images");
            t.setDaemon(true);
            return t;
        });
        return pool;
    }

    private static void load(final String url, final Entry e) {
        try {
            if (!allowed(url)) throw new IOException("not an http or https address");
            final File file = new File(folder(), hash(url) + ".png");
            BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
            if (image == null) {
                image = shrink(ImageIO.read(new ByteArrayInputStream(download(url))));
                if (image == null) throw new IOException("not a picture");
                try {
                    file.getParentFile()
                        .mkdirs();
                    ImageIO.write(image, "png", file);
                } catch (final IOException cache) {
                    GtnhPlanner.LOG.debug("Could not keep a picture on disk", cache);
                }
            }
            e.pending = image;
            e.state = State.READY;
        } catch (final IOException | RuntimeException ex) {
            GtnhPlanner.LOG.info("An annotation's picture is not shown: " + ex.getMessage());
            e.state = State.FAILED;
        }
    }

    /** Only the web: never a file, a jar or anything else a plan could point the game at. */
    static boolean allowed(final String url) {
        final String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static byte[] download(final String url) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "GTNH-Planner");
        try {
            if (c.getResponseCode() != 200) throw new IOException("answered " + c.getResponseCode());
            if (c.getContentLengthLong() > MAX_BYTES) throw new IOException("over " + MAX_BYTES + " bytes");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("over " + MAX_BYTES + " bytes");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** The picture no longer than {@link #MAX_SIDE} on a side, as ARGB. */
    @Nullable
    static BufferedImage shrink(@Nullable final BufferedImage src) {
        if (src == null) return null;
        final double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(src.getWidth(), src.getHeight()));
        final int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        final int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static File folder() {
        return new File(Minecraft.getMinecraft().mcDataDir, "gtnhplanner/images");
    }

    private static String hash(final String url) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-1")
                .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (final byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
