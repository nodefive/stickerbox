package com.unl0cker.stickerbox;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Talks to WhatsApp and WhatsApp Business through their public sticker interfaces. */
final class WhatsApp {
    static final String WA = "com.whatsapp";
    static final String W4B = "com.whatsapp.w4b";
    static final String[] APPS = {WA, W4B};

    private WhatsApp() {}

    static boolean installed(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static List<String> installedApps(Context c) {
        List<String> out = new ArrayList<>();
        for (String pkg : APPS) if (installed(c, pkg)) out.add(pkg);
        return out;
    }

    static String label(Context c, String pkg) {
        return c.getString(W4B.equals(pkg) ? R.string.whatsapp_business : R.string.whatsapp);
    }

    /** Whether WhatsApp has added this part. Null when WhatsApp cannot be asked. */
    static Boolean isAdded(Context c, String pkg, String identifier) {
        String authority = pkg + ".provider.sticker_whitelist_check";
        if (c.getPackageManager().resolveContentProvider(authority, 0) == null) return null;
        Uri uri = new Uri.Builder().scheme("content").authority(authority).appendPath("is_whitelisted")
                .appendQueryParameter("authority", StickerProvider.AUTHORITY)
                .appendQueryParameter("identifier", identifier).build();
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getInt(cur.getColumnIndexOrThrow("result")) == 1;
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    static Intent addIntent(String pkg, PackBuilder.Part part) {
        Intent i = new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
        i.setPackage(pkg);
        i.putExtra("sticker_pack_id", part.id);
        i.putExtra("sticker_pack_authority", StickerProvider.AUTHORITY);
        i.putExtra("sticker_pack_name", part.name);
        return i;
    }

    /** Where a pack stands with WhatsApp, for the lists. */
    static final class Status {
        /** Apps (package names) that have every built part of this pack. */
        final List<String> addedTo = new ArrayList<>();
        boolean built;
        boolean outdated;

        boolean added() { return !addedTo.isEmpty(); }
    }

    static Status status(Context c, Store.Pack p) {
        Status s = new Status();
        int built = PackBuilder.builtVersion(c, p.id);
        s.built = built >= 0;
        if (!s.built) return s;
        s.outdated = built != p.version;
        List<PackBuilder.Part> parts = PackBuilder.parts(c, p.id);
        for (String pkg : installedApps(c)) {
            boolean all = !parts.isEmpty();
            for (PackBuilder.Part part : parts) {
                if (!Boolean.TRUE.equals(isAdded(c, pkg, part.id))) {
                    all = false;
                    break;
                }
            }
            if (all) s.addedTo.add(pkg);
        }
        return s;
    }

    static String describe(Context c, Status s) {
        if (!s.added()) return c.getString(R.string.status_not_added);
        String where = s.addedTo.size() == 2 ? c.getString(R.string.status_both)
                : label(c, s.addedTo.get(0));
        return s.outdated ? c.getString(R.string.status_outdated, where) : c.getString(R.string.status_added, where);
    }
}
