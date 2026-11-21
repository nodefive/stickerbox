package com.unl0cker.stickerbox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Packs live in files/packs/&lt;id&gt;/: pack.json plus stickers/ with the raw images (PNG from the
 * editor, or the original file for stickers taken from WhatsApp or a .wastickers file).
 * Nothing in here is in WhatsApp's format; {@link PackBuilder} makes that on demand.
 */
final class Store {
    static final Pattern ID = Pattern.compile("[a-z0-9]{6,32}");
    static final Pattern FILE = Pattern.compile("[a-z0-9]{6,32}\\.(png|webp)");
    static final String DEFAULT_EMOJI = "😀";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Store() {}

    static final class Sticker {
        String id;
        String file;
        final List<String> emojis = new ArrayList<>();
        boolean animated;
        String hash;

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("file", file)
                    .put("emojis", new JSONArray(emojis))
                    .put("animated", animated)
                    .put("hash", hash == null ? "" : hash);
        }

        static Sticker fromJson(JSONObject o) throws JSONException {
            Sticker s = new Sticker();
            s.id = o.getString("id");
            s.file = o.getString("file");
            if (!ID.matcher(s.id).matches() || !FILE.matcher(s.file).matches()) {
                throw new JSONException("bad sticker id or file: " + s.file);
            }
            JSONArray e = o.optJSONArray("emojis");
            if (e != null) for (int i = 0; i < e.length() && i < 3; i++) s.emojis.add(e.getString(i));
            s.animated = o.optBoolean("animated");
            s.hash = o.optString("hash", "");
            return s;
        }
    }

    static final class Pack {
        String id;
        String name;
        String author;
        long created;
        long updated;
        /** Bumped on every change; WhatsApp sees it as image_data_version after the next build. */
        int version;
        /** Sticker id used for the tray icon, or null for the first sticker. */
        String tray;
        final List<Sticker> stickers = new ArrayList<>();
        File dir;

        File stickersDir() { return new File(dir, "stickers"); }

        File file(Sticker s) { return new File(stickersDir(), s.file); }

        Sticker find(String stickerId) {
            for (Sticker s : stickers) if (s.id.equals(stickerId)) return s;
            return null;
        }

        Sticker traySticker() {
            Sticker t = tray == null ? null : find(tray);
            return t != null ? t : (stickers.isEmpty() ? null : stickers.get(0));
        }

        boolean hasHash(String hash) {
            for (Sticker s : stickers) if (hash.equals(s.hash)) return true;
            return false;
        }

        void touch() {
            version++;
            updated = System.currentTimeMillis();
        }

        JSONObject toJson() throws JSONException {
            JSONArray arr = new JSONArray();
            for (Sticker s : stickers) arr.put(s.toJson());
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("author", author)
                    .put("created", created)
                    .put("updated", updated)
                    .put("version", version)
                    .put("tray", tray == null ? JSONObject.NULL : tray)
                    .put("stickers", arr);
        }

        static Pack fromJson(JSONObject o, File dir) throws JSONException {
            Pack p = new Pack();
            p.id = o.getString("id");
            if (!ID.matcher(p.id).matches()) throw new JSONException("bad pack id");
            p.name = o.optString("name", "Stickers");
            p.author = o.optString("author", "");
            p.created = o.optLong("created");
            p.updated = o.optLong("updated");
            p.version = o.optInt("version", 1);
            p.tray = o.isNull("tray") ? null : o.optString("tray", null);
            JSONArray arr = o.optJSONArray("stickers");
            if (arr != null) for (int i = 0; i < arr.length(); i++) p.stickers.add(Sticker.fromJson(arr.getJSONObject(i)));
            p.dir = dir;
            return p;
        }
    }

    static String newId() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    static File root(Context c) { return new File(c.getFilesDir(), "packs"); }

    static synchronized List<Pack> all(Context c) {
        List<Pack> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory() || !ID.matcher(d.getName()).matches()) continue;
                Pack p = read(d);
                if (p != null) out.add(p);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    static synchronized Pack load(Context c, String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        return read(new File(root(c), id));
    }

    static Pack read(File dir) {
        try {
            String json = new String(Files.readAllBytes(new File(dir, "pack.json").toPath()), StandardCharsets.UTF_8);
            Pack p = Pack.fromJson(new JSONObject(json), dir);
            return p.id.equals(dir.getName()) ? p : null;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static synchronized void save(Pack p) throws IOException {
        if (!p.stickersDir().isDirectory() && !p.stickersDir().mkdirs()) throw new IOException("cannot create " + p.dir);
        try {
            writeAtomic(new File(p.dir, "pack.json"), p.toJson().toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static Pack create(Context c, String name, String author) throws IOException {
        Pack p = new Pack();
        p.id = newId();
        p.name = name;
        p.author = author;
        p.created = p.updated = System.currentTimeMillis();
        p.version = 1;
        p.dir = new File(root(c), p.id);
        save(p);
        return p;
    }

    static synchronized void delete(Context c, Pack p) {
        deleteTree(p.dir);
        PackBuilder.remove(c, p.id);
    }

    /**
     * Moves (or copies) an image file into the pack. Returns null when the pack already has a
     * sticker with the same content.
     */
    static Sticker addFile(Pack p, File src, boolean move) throws IOException {
        String hash = Images.sha1(src);
        if (p.hasHash(hash)) {
            if (move) src.delete();
            return null;
        }
        Images.Info info = Images.info(src);
        Sticker s = new Sticker();
        s.id = newId();
        if (info.gif) {
            // An animated GIF becomes an animated WebP; a single-frame GIF falls through to PNG.
            byte[] anim = null;
            try {
                anim = AnimatedWebp.fromGif(src);
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                // stored as a still below
            }
            if (anim != null) {
                s.file = s.id + ".webp";
                s.animated = true;
                s.hash = hash;
                if (!p.stickersDir().isDirectory()) p.stickersDir().mkdirs();
                writeAtomic(p.file(s), anim);
                if (move) src.delete();
                p.stickers.add(s);
                return s;
            }
        }
        s.file = s.id + (info.webp ? ".webp" : ".png");
        // Two identical frames are a still in disguise (other sticker apps make these): keep it a still.
        s.animated = info.animated && info.frames > 1 && !(info.frames == 2 && AnimatedWebp.isImposter(src));
        s.hash = hash;
        File dst = p.file(s);
        if (!p.stickersDir().isDirectory()) p.stickersDir().mkdirs();
        if (info.webp || info.png) {
            if (!move || !src.renameTo(dst)) {
                Images.copy(src, dst);
                if (move) src.delete();
            }
        } else {
            // Anything else (JPEG, HEIF, GIF...) is stored as a 512 px PNG with transparent padding.
            android.graphics.Bitmap b = Images.decode(src, 2048);
            Images.writePng(Images.fitSquare(b, Images.SIZE, 0), dst);
            if (move) src.delete();
            s.hash = Images.sha1(dst);
        }
        p.stickers.add(s);
        return s;
    }

    /** Replaces a sticker's image with a new PNG (editor result); the old file is removed. */
    static void replaceImage(Pack p, Sticker s, File png) throws IOException {
        File old = p.file(s);
        String newId = newId();
        File dst = new File(p.stickersDir(), newId + ".png");
        if (!png.renameTo(dst)) {
            Images.copy(png, dst);
            png.delete();
        }
        if (s.id.equals(p.tray)) p.tray = newId;
        s.id = newId;
        s.file = dst.getName();
        s.animated = false;
        s.hash = Images.sha1(dst);
        old.delete();
    }

    static void removeSticker(Pack p, Sticker s) {
        p.stickers.remove(s);
        p.file(s).delete();
        if (s.id.equals(p.tray)) p.tray = null;
    }

    /** Moves a sticker's file and entry to another pack. Returns false when it is already there. */
    static boolean moveSticker(Pack from, Sticker s, Pack to) throws IOException {
        if (s.hash != null && !s.hash.isEmpty() && to.hasHash(s.hash)) {
            removeSticker(from, s);
            return false;
        }
        File src = from.file(s);
        if (!to.stickersDir().isDirectory()) to.stickersDir().mkdirs();
        File dst = to.file(s);
        if (!src.renameTo(dst)) {
            Images.copy(src, dst);
            src.delete();
        }
        from.stickers.remove(s);
        if (s.id.equals(from.tray)) from.tray = null;
        to.stickers.add(s);
        return true;
    }

    static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
