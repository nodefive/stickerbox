package com.unl0cker.stickerbox;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Images {
    static final int SIZE = 512;
    static final int TRAY = 96;
    static final int STATIC_MAX = 100 * 1024;
    static final int ANIMATED_MAX = 500 * 1024;
    static final int TRAY_MAX = 50 * 1024;

    private Images() {}

    /** What the file header says; no pixels are decoded. */
    static final class Info {
        boolean webp;
        boolean png;
        boolean animated;
        int width;
        int height;
        int frames;
        int durationMs;
        int minFrameMs = Integer.MAX_VALUE;
        long length;

        /** Whether WhatsApp accepts this file as-is in an animated pack. */
        boolean validAnimated() {
            return webp && animated && width == SIZE && height == SIZE && frames > 1
                    && length <= ANIMATED_MAX && minFrameMs >= 8 && durationMs <= 10_000;
        }

        /** Whether WhatsApp accepts this file as-is in a still pack. */
        boolean validStatic() {
            return webp && !animated && width == SIZE && height == SIZE && length <= STATIC_MAX;
        }
    }

    static Info info(File f) throws IOException {
        Info info = new Info();
        info.length = f.length();
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            if (in.read(h) < 12) return info;
            if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
                info.png = true;
                return info;
            }
            if (!(h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P')) return info;
            info.webp = true;
            long pos = 12;
            long end = Math.min(in.length(), 8 + le32(h, 4));
            byte[] head = new byte[8];
            byte[] payload = new byte[16];
            while (pos + 8 <= end) {
                in.seek(pos);
                if (in.read(head) < 8) break;
                String cc = new String(head, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = le32(head, 4);
                int n = in.read(payload, 0, (int) Math.min(payload.length, size));
                switch (cc) {
                    case "VP8X":
                        if (n >= 10) {
                            info.animated = (payload[0] & 0x02) != 0;
                            info.width = 1 + le24(payload, 4);
                            info.height = 1 + le24(payload, 7);
                        }
                        break;
                    case "VP8 ":
                        if (n >= 10 && info.width == 0) {
                            info.width = ((payload[6] & 0xFF) | (payload[7] & 0xFF) << 8) & 0x3FFF;
                            info.height = ((payload[8] & 0xFF) | (payload[9] & 0xFF) << 8) & 0x3FFF;
                        }
                        break;
                    case "VP8L":
                        if (n >= 5 && info.width == 0) {
                            int bits = (payload[1] & 0xFF) | (payload[2] & 0xFF) << 8
                                    | (payload[3] & 0xFF) << 16 | (payload[4] & 0xFF) << 24;
                            info.width = 1 + (bits & 0x3FFF);
                            info.height = 1 + ((bits >> 14) & 0x3FFF);
                        }
                        break;
                    case "ANMF":
                        if (n >= 15) {
                            int d = le24(payload, 12);
                            info.frames++;
                            info.durationMs += d;
                            info.minFrameMs = Math.min(info.minFrameMs, d);
                        }
                        break;
                    default:
                        break;
                }
                pos += 8 + size + (size & 1);
            }
        }
        if (!info.animated) info.frames = 1;
        return info;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static int le24(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16;
    }

    /** Decodes the first frame, scaled down so the longer side is at most maxDim. */
    static Bitmap decode(ImageDecoder.Source source, int maxDim) throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            int longer = Math.max(w, h);
            if (longer > maxDim) {
                float k = maxDim / (float) longer;
                decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            }
        });
    }

    static Bitmap decode(File f, int maxDim) throws IOException {
        return decode(ImageDecoder.createSource(f), maxDim);
    }

    static Bitmap decode(ContentResolver cr, Uri uri, int maxDim) throws IOException {
        return decode(ImageDecoder.createSource(cr, uri), maxDim);
    }

    /** Fits b inside a size x size transparent square, centred, keeping the aspect ratio. */
    static Bitmap fitSquare(Bitmap b, int size, int margin) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float avail = size - 2f * margin;
        float k = Math.min(avail / b.getWidth(), avail / b.getHeight());
        float w = b.getWidth() * k;
        float h = b.getHeight() * k;
        RectF dst = new RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f);
        new Canvas(out).drawBitmap(b, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    static void writePng(Bitmap b, File f) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encoding failed");
        }
    }

    /** 512 x 512 still WebP within WhatsApp's 100 KB: lossless when it fits, else lossy. */
    static byte[] staticWebp(Bitmap b) throws IOException {
        if (b.getWidth() != SIZE || b.getHeight() != SIZE) b = fitSquare(b, SIZE, 0);
        byte[] data = compress(b, Bitmap.CompressFormat.WEBP_LOSSLESS, 100);
        for (int q = 95; data.length > STATIC_MAX && q >= 10; q -= q > 50 ? 5 : 10) {
            data = compress(b, Bitmap.CompressFormat.WEBP_LOSSY, q);
        }
        if (data.length > STATIC_MAX) throw new IOException("sticker does not fit in 100 KB");
        return data;
    }

    /** 96 x 96 PNG tray icon. */
    static byte[] trayPng(Bitmap b) throws IOException {
        byte[] data = compress(fitSquare(b, TRAY, 0), Bitmap.CompressFormat.PNG, 100);
        if (data.length > TRAY_MAX) throw new IOException("tray icon too large");
        return data;
    }

    private static byte[] compress(Bitmap b, Bitmap.CompressFormat fmt, int q) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!b.compress(fmt, q, out)) throw new IOException("encoding failed");
        return out.toByteArray();
    }

    static String sha1(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static void copy(InputStream in, File dst) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            copy(in, dst);
        }
    }

    /** Copies a content Uri into a new file in dir (for picker results). */
    static File copyIn(ContentResolver cr, Uri uri, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File f = new File(dir, Store.newId());
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open " + uri);
            copy(in, f);
        }
        return f;
    }
}
