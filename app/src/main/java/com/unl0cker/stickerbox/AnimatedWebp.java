package com.unl0cker.stickerbox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-encodes an animated WebP so WhatsApp accepts it: 512 x 512, at most 500 KB, frames of at
 * least 8 ms, at most 10 s. Android has no animated WebP encoder, so this decodes each frame
 * (rebuilding it from the ANMF chunks with blending and disposal), scales it, encodes it as a
 * still WebP and writes the animation container itself.
 */
final class AnimatedWebp {
    private static final int MAX_DURATION = 10_000;
    private static final int MIN_FRAME = 8;

    private AnimatedWebp() {}

    private static final class SrcFrame {
        int x, y, w, h, duration;
        boolean noBlend, dispose;
        byte[] webp; // a standalone still WebP of this frame's rectangle
    }

    private static final class Source {
        int width, height;
        final List<SrcFrame> frames = new ArrayList<>();
    }

    /** Returns WhatsApp-ready bytes, or null when no setting gets the sticker under the limit. */
    static byte[] fit(File f) throws IOException {
        Source src = parse(Files.readAllBytes(f.toPath()));
        if (src.frames.size() < 2) return null;
        byte[] first = encode(src, 1, 75);
        if (first == null) return null;
        if (first.length <= Images.ANIMATED_MAX) return first;
        // Size grows roughly with the number of frames: start dropping frames as far as the
        // first attempt says is needed, then lower the quality.
        double ratio = Images.ANIMATED_MAX / (double) first.length;
        int startStep = ratio >= 0.55 ? 1 : ratio >= 0.3 ? 2 : ratio >= 0.2 ? 3 : 4;
        for (int step = startStep; step <= 4; step++) {
            if (src.frames.size() / step < 2) break;
            for (int q : step == 1 ? new int[]{60, 45, 30} : new int[]{75, 60, 45, 30}) {
                byte[] out = encode(src, step, q);
                if (out != null && out.length <= Images.ANIMATED_MAX) return out;
            }
        }
        return null;
    }

    /**
     * A still image as a 2-frame animation of the same picture, so it can go into an animated
     * pack (WhatsApp does not mix still and animated stickers in one pack). It looks identical.
     */
    static byte[] stillAsAnimated(Bitmap b) throws IOException {
        Bitmap img = b.getWidth() == Images.SIZE && b.getHeight() == Images.SIZE ? b : Images.fitSquare(b, Images.SIZE, 0);
        byte[] out = null;
        for (int q = 100; q >= 30; q -= q == 100 ? 20 : 10) {
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            img.compress(q == 100 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP_LOSSY, q, enc);
            byte[] data = frameData(enc.toByteArray());
            if (data == null) throw new IOException("encoding failed");
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            for (int i = 0; i < 2; i++) chunk(frames, "ANMF", anmf(data, 500));
            out = container(frames);
            if (out.length <= Images.ANIMATED_MAX) return out;
        }
        throw new IOException("sticker does not fit in 500 KB");
    }

    // ---- reading ----

    private static Source parse(byte[] b) throws IOException {
        if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WEBP")) throw new IOException("not a WebP file");
        Source s = new Source();
        int pos = 12;
        while (pos + 8 <= b.length) {
            String cc = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = (int) le32(b, pos + 4);
            int body = pos + 8;
            if (size < 0 || body + size > b.length) break;
            if (cc.equals("VP8X") && size >= 10) {
                s.width = 1 + le24(b, body + 4);
                s.height = 1 + le24(b, body + 7);
            } else if (cc.equals("ANMF") && size >= 16) {
                SrcFrame fr = new SrcFrame();
                fr.x = 2 * le24(b, body);
                fr.y = 2 * le24(b, body + 3);
                fr.w = 1 + le24(b, body + 6);
                fr.h = 1 + le24(b, body + 9);
                fr.duration = le24(b, body + 12);
                int flags = b[body + 15] & 0xFF;
                fr.noBlend = (flags & 0x02) != 0;
                fr.dispose = (flags & 0x01) != 0;
                fr.webp = standalone(b, body + 16, body + size, fr.w, fr.h);
                if (fr.webp != null) s.frames.add(fr);
            }
            pos = body + size + (size & 1);
        }
        if (s.width <= 0 || s.height <= 0) throw new IOException("no canvas size");
        return s;
    }

    /** Wraps a frame's ALPH / VP8 / VP8L sub-chunks into a WebP file BitmapFactory can decode. */
    private static byte[] standalone(byte[] b, int from, int to, int w, int h) throws IOException {
        byte[] alph = null, vp8 = null, vp8l = null;
        int pos = from;
        while (pos + 8 <= to) {
            String cc = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = (int) le32(b, pos + 4);
            if (size < 0 || pos + 8 + size > to) break;
            byte[] data = java.util.Arrays.copyOfRange(b, pos + 8, pos + 8 + size);
            if (cc.equals("ALPH")) alph = data;
            else if (cc.equals("VP8 ")) vp8 = data;
            else if (cc.equals("VP8L")) vp8l = data;
            pos += 8 + size + (size & 1);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        if (vp8l != null) {
            chunk(body, "VP8L", vp8l);
        } else if (vp8 != null && alph != null) {
            byte[] x = new byte[10];
            x[0] = 0x10; // alpha
            put24(x, 4, w - 1);
            put24(x, 7, h - 1);
            chunk(body, "VP8X", x);
            chunk(body, "ALPH", alph);
            chunk(body, "VP8 ", vp8);
        } else if (vp8 != null) {
            chunk(body, "VP8 ", vp8);
        } else {
            return null;
        }
        return riff(body.toByteArray());
    }

    // ---- writing ----

    /** Renders every frame, keeps every step-th one (adding up the skipped durations), encodes. */
    private static byte[] encode(Source s, int step, int quality) throws IOException {
        Bitmap canvasBmp = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvasBmp);
        Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint replace = new Paint(Paint.FILTER_BITMAP_FLAG);
        replace.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        SrcFrame prev = null;
        int pending = 0;
        int total = 0;
        int written = 0;
        for (int i = 0; i < s.frames.size() && total < MAX_DURATION; i++) {
            SrcFrame fr = s.frames.get(i);
            if (prev != null && prev.dispose) {
                c.save();
                c.clipRect(prev.x, prev.y, prev.x + prev.w, prev.y + prev.h);
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                c.restore();
            }
            Bitmap fb = BitmapFactory.decodeByteArray(fr.webp, 0, fr.webp.length);
            if (fb != null) {
                c.drawBitmap(fb, fr.x, fr.y, fr.noBlend ? replace : over);
                fb.recycle();
            }
            prev = fr;
            pending += Math.max(fr.duration, 0);
            boolean last = i == s.frames.size() - 1;
            if (i % step != step - 1 && !last) continue;

            int duration = Math.max(MIN_FRAME, Math.min(pending, MAX_DURATION - total));
            pending = 0;
            total += duration;
            Bitmap out = Images.fitSquare(canvasBmp, Images.SIZE, 0);
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, enc);
            out.recycle();
            byte[] data = frameData(enc.toByteArray());
            if (data == null) return null;

            chunk(frames, "ANMF", anmf(data, duration));
            written++;
        }
        canvasBmp.recycle();
        if (written < 2) return null;
        return container(frames);
    }

    /** ANMF payload for a full-canvas 512 px frame: no blending, no disposal. */
    private static byte[] anmf(byte[] data, int duration) {
        byte[] out = new byte[16 + data.length];
        put24(out, 6, Images.SIZE - 1);
        put24(out, 9, Images.SIZE - 1);
        put24(out, 12, duration);
        out[15] = 0x02;
        System.arraycopy(data, 0, out, 16, data.length);
        return out;
    }

    /** RIFF / VP8X / ANIM around the given ANMF chunks. */
    private static byte[] container(ByteArrayOutputStream frames) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        byte[] x = new byte[10];
        x[0] = 0x02 | 0x10; // animation, alpha
        put24(x, 4, Images.SIZE - 1);
        put24(x, 7, Images.SIZE - 1);
        chunk(body, "VP8X", x);
        chunk(body, "ANIM", new byte[6]); // transparent background, loop forever
        frames.writeTo(body);
        return riff(body.toByteArray());
    }

    /** The ALPH and VP8 / VP8L chunks (with headers) of a still WebP made by Bitmap.compress. */
    private static byte[] frameData(byte[] webp) {
        if (webp.length < 12 || !tag(webp, 0, "RIFF")) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 12;
        while (pos + 8 <= webp.length) {
            String cc = new String(webp, pos, 4, StandardCharsets.US_ASCII);
            int size = (int) le32(webp, pos + 4);
            if (size < 0 || pos + 8 + size > webp.length) break;
            if (cc.equals("ALPH") || cc.equals("VP8 ") || cc.equals("VP8L")) {
                out.write(webp, pos, 8 + size);
                if ((size & 1) == 1) out.write(0); // chunks are padded to an even length
            }
            pos += 8 + size + (size & 1);
        }
        return out.size() == 0 ? null : out.toByteArray();
    }

    // ---- bytes ----

    private static void chunk(ByteArrayOutputStream out, String cc, byte[] data) {
        out.write(cc.getBytes(StandardCharsets.US_ASCII), 0, 4);
        byte[] len = new byte[4];
        put32(len, 0, data.length);
        out.write(len, 0, 4);
        out.write(data, 0, data.length);
        if ((data.length & 1) == 1) out.write(0);
    }

    private static byte[] riff(byte[] body) {
        byte[] out = new byte[8 + body.length];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        put32(out, 4, body.length);
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static boolean tag(byte[] b, int o, String t) {
        for (int i = 0; i < 4; i++) if (b[o + i] != t.charAt(i)) return false;
        return true;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static int le24(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16;
    }

    private static void put24(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
    }

    private static void put32(byte[] b, int o, int v) {
        put24(b, o, v);
        b[o + 3] = (byte) (v >> 24);
    }
}
