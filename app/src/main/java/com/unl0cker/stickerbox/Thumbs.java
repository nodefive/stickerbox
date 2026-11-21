package com.unl0cker.stickerbox;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background thumbnail decoding with a memory cache, safe for recycled grid cells. */
final class Thumbs {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(48L * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount() / 1024;
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    private Thumbs() {}

    static void load(ImageView v, File f, int px) {
        String key = f.getPath() + "@" + f.lastModified() + "#" + px;
        load(v, key, () -> Images.decode(f, px));
    }

    static void load(ImageView v, ContentResolver cr, Uri uri, int px) {
        String key = uri + "#" + px;
        load(v, key, () -> Images.decode(cr, uri, px));
    }

    interface Decoder {
        Bitmap decode() throws Exception;
    }

    private static void load(ImageView v, String key, Decoder decoder) {
        v.setTag(R.id.thumb_key, key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        POOL.execute(() -> {
            if (!key.equals(v.getTag(R.id.thumb_key))) return;
            Bitmap b;
            try {
                b = decoder.decode();
            } catch (Exception | OutOfMemoryError e) {
                return;
            }
            CACHE.put(key, b);
            v.post(() -> {
                if (key.equals(v.getTag(R.id.thumb_key))) v.setImageBitmap(b);
            });
        });
    }

    /** Full preview: plays animated stickers. */
    static void preview(ImageView v, File f) {
        v.setTag(R.id.thumb_key, f.getPath());
        POOL.execute(() -> {
            Drawable d;
            try {
                d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f));
            } catch (Exception | OutOfMemoryError e) {
                return;
            }
            v.post(() -> {
                if (!f.getPath().equals(v.getTag(R.id.thumb_key))) return;
                v.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
            });
        });
    }

    static void forget() { CACHE.evictAll(); }
}
