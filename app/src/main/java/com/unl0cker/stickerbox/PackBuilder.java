package com.unl0cker.stickerbox;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns a pack into what WhatsApp accepts, only when the user adds or shares it:
 * 512 px WebP stickers, a 96 px PNG tray icon, at most 30 stickers per WhatsApp pack (larger
 * packs are split evenly, in pack order) and a part with any animation sent as an animated pack.
 *
 * Output: files/build/&lt;packId&gt;/&lt;n&gt;/ plus files/build/index.json, which
 * {@link StickerProvider} serves to WhatsApp.
 */
final class PackBuilder {
    static final int MIN = 3;
    static final int MAX = 30;
    static final String TRAY_FILE = "tray.png";

    private static JSONObject cachedIndex;
    private static long cachedStamp = -1;

    private PackBuilder() {}

    static final class Part {
        String id;
        String packId;
        String name;
        String publisher;
        String version;
        boolean animated;
        File dir;
        final List<String> files = new ArrayList<>();
        final List<String> emojis = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONArray st = new JSONArray();
            for (int i = 0; i < files.size(); i++) {
                st.put(new JSONObject().put("file", files.get(i)).put("emojis", emojis.get(i)));
            }
            return new JSONObject().put("id", id).put("pack", packId).put("name", name)
                    .put("publisher", publisher).put("version", version).put("animated", animated)
                    .put("dir", dir.getName()).put("stickers", st);
        }

        static Part fromJson(JSONObject o, File packBuildDir) throws JSONException {
            Part p = new Part();
            p.id = o.getString("id");
            p.packId = o.getString("pack");
            p.name = o.getString("name");
            p.publisher = o.getString("publisher");
            p.version = o.getString("version");
            p.animated = o.getBoolean("animated");
            p.dir = new File(packBuildDir, o.getString("dir"));
            JSONArray st = o.getJSONArray("stickers");
            for (int i = 0; i < st.length(); i++) {
                p.files.add(st.getJSONObject(i).getString("file"));
                p.emojis.add(st.getJSONObject(i).getString("emojis"));
            }
            return p;
        }
    }

    static final class Result {
        final List<Part> parts = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    static File root(Context c) { return new File(c.getFilesDir(), "build"); }

    private static File indexFile(Context c) { return new File(root(c), "index.json"); }

    /** How the pack would be split, without building anything (for the UI). */
    static int[] plan(Store.Pack p) {
        int animated = 0;
        for (Store.Sticker s : p.stickers) if (s.animated) animated++;
        int still = p.stickers.size() - animated;
        int n = p.stickers.size();
        int parts = n < MIN ? 0 : (n + MAX - 1) / MAX;
        return new int[]{parts, still, animated};
    }

    static synchronized Result build(Context c, Store.Pack pack) throws IOException {
        Result r = new Result();
        File packDir = new File(root(c), pack.id);
        File tmp = new File(root(c), pack.id + ".tmp");
        Store.deleteTree(tmp);
        File prep = new File(tmp, "prep");
        if (!prep.mkdirs()) throw new IOException("cannot create " + prep);

        // Animated stickers outside WhatsApp's limits (common for stickers received in chats:
        // over 500 KB, not 512 px) are re-encoded; only when that fails do they become stills.
        // prepared: sticker -> WhatsApp-ready animated file; stickers not in it are stills.
        List<Store.Sticker> usable = new ArrayList<>();
        java.util.Map<Store.Sticker, File> prepared = new java.util.HashMap<>();
        int badAnim = 0;
        for (Store.Sticker s : pack.stickers) {
            File f = pack.file(s);
            if (!f.isFile()) continue;
            usable.add(s);
            if (!s.animated) continue;
            Images.Info info = Images.info(f);
            if (info.validAnimated()) {
                prepared.put(s, f);
            } else if (info.animated && info.frames > 1) {
                File converted = converted(c, s, f);
                if (converted != null) prepared.put(s, converted);
                else badAnim++;
            }
            // else: flagged animated but a single frame, so a still
        }
        if (badAnim > 0) r.notes.add(c.getResources().getQuantityString(R.plurals.note_bad_animated, badAnim, badAnim));

        // WhatsApp wants each pack all-animated or all-still. Like other sticker makers, a part
        // with any real animation goes in as animated, and its still stickers are sent as
        // 2-frame animations of the same image, which look exactly like the still.
        // The pack order is kept; parts only split at WhatsApp's 30.
        List<List<Store.Sticker>> groups = new ArrayList<>();
        split(usable, groups);
        if (groups.isEmpty()) {
            Store.deleteTree(tmp);
            throw new IOException(c.getString(R.string.error_min_stickers));
        }

        String publisher = clip(pack.author == null || pack.author.trim().isEmpty()
                ? c.getString(R.string.app_name) : pack.author.trim());
        Store.Sticker traySticker = pack.traySticker();
        for (int i = 0; i < groups.size(); i++) {
            List<Store.Sticker> group = groups.get(i);
            Part part = new Part();
            part.packId = pack.id;
            part.animated = false;
            for (Store.Sticker s : group) part.animated |= prepared.containsKey(s);
            // Animated and still parts get different identifiers: when a part changes type,
            // WhatsApp gets a new pack instead of a known pack switching type.
            part.id = pack.id + "_" + (part.animated ? "a" : "") + (i + 1);
            part.name = clip(groups.size() == 1 ? pack.name : pack.name + " " + (i + 1) + "/" + groups.size());
            part.publisher = publisher;
            part.version = String.valueOf(pack.version);
            part.dir = new File(tmp, String.valueOf(i + 1));
            if (!part.dir.mkdirs()) throw new IOException("cannot create " + part.dir);

            Store.Sticker trayFrom = group.contains(traySticker) ? traySticker : group.get(0);
            for (int k = 0; k < group.size(); k++) {
                Store.Sticker s = group.get(k);
                File src = pack.file(s);
                String name = String.format(java.util.Locale.ROOT, "s%03d.webp", k + 1);
                File dst = new File(part.dir, name);
                File ready = prepared.get(s);
                if (part.animated && ready != null) {
                    Images.copy(ready, dst);
                } else if (part.animated) {
                    Store.writeAtomic(dst, AnimatedWebp.stillAsAnimated(Images.decode(src, 2048)));
                } else {
                    Images.Info info = Images.info(src);
                    if (info.validStatic()) {
                        Images.copy(src, dst);
                    } else {
                        Store.writeAtomic(dst, Images.staticWebp(Images.decode(src, 2048)));
                    }
                }
                part.files.add(name);
                part.emojis.add(String.join(",", s.emojis.isEmpty()
                        ? java.util.Collections.singletonList(Store.DEFAULT_EMOJI) : s.emojis));
            }
            Bitmap trayBitmap = Images.decode(pack.file(trayFrom), 512);
            Store.writeAtomic(new File(part.dir, TRAY_FILE), Images.trayPng(trayBitmap));
            r.parts.add(part);
        }

        // Swap the new build in, then record it in the index WhatsApp reads through the provider.
        Store.deleteTree(prep);
        Store.deleteTree(packDir);
        if (!tmp.renameTo(packDir)) throw new IOException("cannot replace " + packDir);
        for (Part part : r.parts) part.dir = new File(packDir, part.dir.getName());

        JSONObject index = readIndex(c);
        try {
            JSONArray parts = new JSONArray();
            for (Part part : r.parts) parts.put(part.toJson());
            index.getJSONObject("packs").put(pack.id, new JSONObject()
                    .put("version", pack.version).put("parts", parts));
            writeIndex(c, index);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return r;
    }

    /**
     * The re-encoded version of an animated sticker outside WhatsApp's limits, kept in
     * files/converted/&lt;content hash&gt;.webp so the slow conversion runs once per sticker.
     * Null when it cannot be brought under the limits.
     */
    private static File converted(Context c, Store.Sticker s, File src) throws IOException {
        File dir = new File(c.getFilesDir(), "converted");
        String key = s.hash == null || s.hash.isEmpty() ? Images.sha1(src) : s.hash;
        File out = new File(dir, key + ".webp");
        File failed = new File(dir, key + ".failed");
        if (out.isFile()) return out;
        if (failed.isFile()) return null;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        byte[] data;
        try {
            data = AnimatedWebp.fit(src);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            data = null;
        }
        if (data == null) {
            Store.writeAtomic(failed, new byte[0]);
            return null;
        }
        Store.writeAtomic(out, data);
        return out;
    }

    /** Removes conversions no pack uses any more. */
    static synchronized void pruneConverted(Context c) {
        File[] files = new File(c.getFilesDir(), "converted").listFiles();
        if (files == null) return;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Store.Pack p : Store.all(c)) for (Store.Sticker s : p.stickers) if (s.hash != null) used.add(s.hash);
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0 && !used.contains(name.substring(0, dot))) f.delete();
        }
    }

    /** Splits the stickers into the fewest parts of at most 30, with sizes as even as possible. */
    private static void split(List<Store.Sticker> list, List<List<Store.Sticker>> groups) {
        int n = list.size();
        if (n < MIN) return;
        int k = (n + MAX - 1) / MAX;
        int base = n / k;
        int extra = n % k;
        int pos = 0;
        for (int i = 0; i < k; i++) {
            int size = base + (i < extra ? 1 : 0);
            groups.add(new ArrayList<>(list.subList(pos, pos + size)));
            pos += size;
        }
    }

    private static String clip(String s) { return s.length() <= 128 ? s : s.substring(0, 128); }

    static synchronized JSONObject readIndex(Context c) {
        File f = indexFile(c);
        long stamp = f.lastModified();
        if (cachedIndex != null && stamp == cachedStamp) return cachedIndex;
        JSONObject o;
        try {
            o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!o.has("packs")) o.put("packs", new JSONObject());
        } catch (IOException | JSONException e) {
            o = new JSONObject();
            try {
                o.put("packs", new JSONObject());
            } catch (JSONException ignored) {
                // cannot happen
            }
        }
        cachedIndex = o;
        cachedStamp = stamp;
        return o;
    }

    private static void writeIndex(Context c, JSONObject index) throws IOException {
        File f = indexFile(c);
        if (!root(c).isDirectory()) root(c).mkdirs();
        Store.writeAtomic(f, index.toString().getBytes(StandardCharsets.UTF_8));
        cachedIndex = index;
        cachedStamp = f.lastModified();
    }

    /** Every built part, for WhatsApp's metadata query. */
    static synchronized List<Part> allParts(Context c) {
        List<Part> out = new ArrayList<>();
        JSONObject packs = readIndex(c).optJSONObject("packs");
        if (packs == null) return out;
        for (java.util.Iterator<String> it = packs.keys(); it.hasNext(); ) {
            String packId = it.next();
            out.addAll(parts(c, packId));
        }
        return out;
    }

    static synchronized List<Part> parts(Context c, String packId) {
        List<Part> out = new ArrayList<>();
        JSONObject entry = readIndex(c).optJSONObject("packs");
        entry = entry == null ? null : entry.optJSONObject(packId);
        if (entry == null) return out;
        JSONArray arr = entry.optJSONArray("parts");
        if (arr == null) return out;
        File packDir = new File(root(c), packId);
        for (int i = 0; i < arr.length(); i++) {
            try {
                out.add(Part.fromJson(arr.getJSONObject(i), packDir));
            } catch (JSONException ignored) {
                // skip a damaged entry
            }
        }
        return out;
    }

    static Part part(Context c, String identifier) {
        int us = identifier.lastIndexOf('_');
        if (us <= 0) return null;
        for (Part p : parts(c, identifier.substring(0, us))) if (p.id.equals(identifier)) return p;
        return null;
    }

    /** The pack version WhatsApp currently gets, or -1 when it was never built. */
    static synchronized int builtVersion(Context c, String packId) {
        JSONObject packs = readIndex(c).optJSONObject("packs");
        JSONObject entry = packs == null ? null : packs.optJSONObject(packId);
        return entry == null ? -1 : entry.optInt("version", -1);
    }

    static synchronized void remove(Context c, String packId) {
        Store.deleteTree(new File(root(c), packId));
        JSONObject index = readIndex(c);
        JSONObject packs = index.optJSONObject("packs");
        if (packs != null && packs.has(packId)) {
            packs.remove(packId);
            try {
                writeIndex(c, index);
            } catch (IOException ignored) {
                // the provider ignores parts whose files are gone
            }
        }
    }

    /** Writes each part as a .wastickers file (zip: stickers, tray icon, title.txt, author.txt). */
    static List<File> wastickers(Context c, Result r) throws IOException {
        File dir = new File(c.getCacheDir(), "share");
        Store.deleteTree(dir);
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        List<File> out = new ArrayList<>();
        for (Part part : r.parts) {
            String base = part.name.replaceAll("[^\\p{L}\\p{N} ._-]", "_").replace('/', '-').trim();
            if (base.isEmpty()) base = "stickers";
            File f = new File(dir, base + ".wastickers");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(f))) {
                putText(zip, "title.txt", part.name);
                putText(zip, "author.txt", part.publisher);
                putFile(zip, TRAY_FILE, new File(part.dir, TRAY_FILE));
                for (String name : part.files) putFile(zip, name, new File(part.dir, name));
            }
            out.add(f);
        }
        return out;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(Files.readAllBytes(f.toPath()));
        zip.closeEntry();
    }
}
