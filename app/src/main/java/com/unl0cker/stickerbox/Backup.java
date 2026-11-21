package com.unl0cker.stickerbox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup = a zip with the raw pack folders, not WhatsApp files:
 *   stickerbox.json                  format, date, settings, pack order
 *   packs/&lt;id&gt;/pack.json            names, author, emojis, order, tray choice
 *   packs/&lt;id&gt;/stickers/&lt;file&gt;    original images
 * Restoring makes every pack fully editable again.
 */
final class Backup {
    static final String FORMAT = "stickerbox-backup-1";
    private static final Pattern ENTRY = Pattern.compile(
            "packs/([a-z0-9]{6,32})/(pack\\.json|stickers/([a-z0-9]{6,32}\\.(?:png|webp)))");
    private static final long MAX_ENTRY = 64L * 1024 * 1024;

    private Backup() {}

    static int export(Context c, OutputStream os) throws IOException {
        List<Store.Pack> packs = Store.all(c);
        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            JSONArray order = new JSONArray();
            for (Store.Pack p : packs) order.put(p.id);
            JSONObject meta = new JSONObject()
                    .put("format", FORMAT)
                    .put("created", System.currentTimeMillis())
                    .put("author", Prefs.author(c))
                    .put("packs", order);
            put(zip, "stickerbox.json", meta.toString(1).getBytes(StandardCharsets.UTF_8));
            for (Store.Pack p : packs) {
                put(zip, "packs/" + p.id + "/pack.json", Files.readAllBytes(new File(p.dir, "pack.json").toPath()));
                for (Store.Sticker s : p.stickers) {
                    File f = p.file(s);
                    if (f.isFile()) put(zip, "packs/" + p.id + "/stickers/" + s.file, Files.readAllBytes(f.toPath()));
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return packs.size();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /**
     * Restores packs from a backup. Packs already on the phone with the same id are replaced by
     * the backup copy; all others are kept. Returns the restored pack names.
     */
    static List<String> restore(Context c, InputStream is) throws IOException {
        File tmp = new File(c.getFilesDir(), "restore.tmp");
        Store.deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        boolean sawMeta = false;
        String author = null;
        try (ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if ("stickerbox.json".equals(name)) {
                    JSONObject meta = new JSONObject(new String(read(zip), StandardCharsets.UTF_8));
                    if (!FORMAT.equals(meta.optString("format"))) throw new IOException(c.getString(R.string.error_not_backup));
                    author = meta.optString("author", null);
                    sawMeta = true;
                    continue;
                }
                Matcher m = ENTRY.matcher(name);
                if (!m.matches()) continue; // ignore anything unexpected, never write outside tmp
                File dst = new File(tmp, name);
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                try (OutputStream out = new FileOutputStream(dst)) {
                    out.write(read(zip));
                }
            }
        } catch (JSONException e) {
            throw new IOException(c.getString(R.string.error_not_backup), e);
        }
        if (!sawMeta) {
            Store.deleteTree(tmp);
            throw new IOException(c.getString(R.string.error_not_backup));
        }

        List<String> names = new ArrayList<>();
        File[] dirs = new File(tmp, "packs").listFiles();
        if (dirs != null) {
            File root = Store.root(c);
            if (!root.isDirectory()) root.mkdirs();
            for (File d : dirs) {
                Store.Pack p = Store.read(d);
                if (p == null) continue;
                // Drop entries whose image is missing from the zip.
                p.stickers.removeIf(s -> !p.file(s).isFile());
                Store.save(p);
                File dst = new File(root, p.id);
                Store.deleteTree(dst);
                if (!d.renameTo(dst)) throw new IOException("cannot restore " + p.name);
                names.add(p.name);
            }
        }
        Store.deleteTree(tmp);
        if (author != null && !author.isEmpty() && Prefs.author(c).isEmpty()) Prefs.setAuthor(c, author);
        return names;
    }

    private static byte[] read(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        long total = 0;
        while ((n = zip.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY) throw new IOException("file in backup too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Imports a .wastickers file as a new, fully editable pack. Returns the pack. */
    static Store.Pack importWastickers(Context c, InputStream is, String fallbackName) throws IOException {
        File tmp = new File(c.getCacheDir(), "import");
        Store.deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        String title = null;
        String author = null;
        List<File> stickers = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry e;
            int n = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
                if (base.equals("title.txt")) {
                    title = new String(read(zip), StandardCharsets.UTF_8).trim();
                } else if (base.equals("author.txt")) {
                    author = new String(read(zip), StandardCharsets.UTF_8).trim();
                } else if (base.endsWith(".webp")) {
                    File f = new File(tmp, String.format(java.util.Locale.ROOT, "%04d", n++));
                    try (OutputStream out = new FileOutputStream(f)) {
                        out.write(read(zip));
                    }
                    stickers.add(f);
                }
                // PNG files in a .wastickers are the tray icon; the tray is rebuilt from a sticker.
            }
        }
        if (stickers.isEmpty()) {
            Store.deleteTree(tmp);
            throw new IOException(c.getString(R.string.error_not_wastickers));
        }
        Store.Pack p = Store.create(c, title == null || title.isEmpty() ? fallbackName : title,
                author == null || author.isEmpty() ? Prefs.author(c) : author);
        for (File f : stickers) Store.addFile(p, f, true);
        Store.save(p);
        Store.deleteTree(tmp);
        return p;
    }
}
