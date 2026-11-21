package com.unl0cker.stickerbox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Imports packs from other WhatsApp sticker apps through the same content provider interface
 * WhatsApp uses. Reading it needs com.whatsapp.sticker.READ, a normal permission that WhatsApp
 * defines and Android grants to any app that requests it.
 */
final class OtherApps {
    static final String READ_PERMISSION = "com.whatsapp.sticker.READ";

    private OtherApps() {}

    static final class Source {
        String pkg;
        String label;
        String authority;
        final List<RemotePack> packs = new ArrayList<>();
    }

    static final class RemotePack {
        String id;
        String name;
        String publisher;
    }

    /** Installed apps (other than this one) that serve sticker packs, with their packs. */
    @SuppressWarnings("deprecation")
    static List<Source> find(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Source> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_PROVIDERS)) {
            if (pi.providers == null || pi.packageName.equals(c.getPackageName())) continue;
            for (ProviderInfo p : pi.providers) {
                if (!p.exported || !READ_PERMISSION.equals(p.readPermission) || p.authority == null) continue;
                String authority = p.authority.split(";")[0];
                Source s = new Source();
                s.pkg = pi.packageName;
                s.authority = authority;
                CharSequence label = pi.applicationInfo == null ? null : pm.getApplicationLabel(pi.applicationInfo);
                s.label = label == null ? pi.packageName : label.toString();
                if (authority.toLowerCase(Locale.ROOT).contains("community")) {
                    s.label += " " + c.getString(R.string.import_community_suffix);
                }
                try {
                    readPacks(c, s);
                } catch (RuntimeException e) {
                    continue; // provider refused or broken
                }
                if (s.packs.isEmpty()) continue;
                // Several providers of one app often serve the same packs (copies for other
                // WhatsApp builds): keep the first.
                StringBuilder key = new StringBuilder(s.pkg);
                for (RemotePack rp : s.packs) key.append('|').append(rp.id);
                if (seen.add(key.toString())) out.add(s);
            }
        }
        return out;
    }

    private static void readPacks(Context c, Source s) {
        Uri uri = Uri.parse("content://" + s.authority + "/metadata");
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur == null) return;
            int id = cur.getColumnIndex("sticker_pack_identifier");
            int name = cur.getColumnIndex("sticker_pack_name");
            int pub = cur.getColumnIndex("sticker_pack_publisher");
            if (id < 0) return;
            while (cur.moveToNext()) {
                RemotePack rp = new RemotePack();
                rp.id = cur.getString(id);
                rp.name = name >= 0 ? cur.getString(name) : null;
                rp.publisher = pub >= 0 ? cur.getString(pub) : null;
                if (rp.id == null || rp.id.isEmpty()) continue;
                if (rp.name == null || rp.name.trim().isEmpty()) rp.name = rp.id;
                s.packs.add(rp);
            }
        }
    }

    /** Sticker Maker pads small packs with placeholder stickers; these are not the user's. */
    private static boolean isFiller(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        return f.startsWith("getstickerpack") || f.startsWith("animated_blanksticker") || f.startsWith("blanksticker");
    }

    /** Copies one pack into a new StickerBox pack. Returns it, or null when it had no stickers. */
    static Store.Pack importPack(Context c, Source s, RemotePack rp) throws IOException {
        List<String[]> rows = new ArrayList<>();
        Uri list = Uri.parse("content://" + s.authority + "/stickers/" + Uri.encode(rp.id));
        try (Cursor cur = c.getContentResolver().query(list, null, null, null, null)) {
            if (cur == null) throw new IOException(c.getString(R.string.import_no_answer, s.label));
            int file = cur.getColumnIndex("sticker_file_name");
            int emoji = cur.getColumnIndex("sticker_emoji");
            if (file < 0) throw new IOException(c.getString(R.string.import_no_answer, s.label));
            while (cur.moveToNext()) {
                String f = cur.getString(file);
                if (f == null || f.isEmpty() || isFiller(f)) continue;
                rows.add(new String[]{f, emoji >= 0 ? cur.getString(emoji) : null});
            }
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (rows.isEmpty()) return null;

        String author = rp.publisher == null || rp.publisher.trim().isEmpty() ? Prefs.author(c) : rp.publisher.trim();
        Store.Pack p = Store.create(c, rp.name.trim(), author);
        File dir = new File(c.getCacheDir(), "import-app");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        for (String[] row : rows) {
            Uri asset = Uri.parse("content://" + s.authority + "/stickers_asset/" + Uri.encode(rp.id) + "/" + Uri.encode(row[0]));
            File tmp = new File(dir, Store.newId());
            try (InputStream in = c.getContentResolver().openInputStream(asset)) {
                if (in == null) continue;
                Images.copy(in, tmp);
            } catch (IOException | RuntimeException e) {
                tmp.delete();
                continue; // skip a sticker the app cannot deliver
            }
            Store.Sticker st;
            try {
                st = Store.addFile(p, tmp, true);
            } catch (IOException | RuntimeException e) {
                tmp.delete();
                continue;
            }
            if (st != null && row[1] != null) {
                for (String e : row[1].split(",")) {
                    String t = e.trim();
                    if (!t.isEmpty() && st.emojis.size() < 3 && !st.emojis.contains(t)) st.emojis.add(t);
                }
            }
        }
        if (p.stickers.isEmpty()) {
            Store.delete(c, p);
            return null;
        }
        Store.save(p);
        return p;
    }
}
