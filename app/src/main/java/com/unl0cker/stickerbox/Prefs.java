package com.unl0cker.stickerbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String author(Context c) {
        return sp(c).getString("author", "");
    }

    static void setAuthor(Context c, String v) {
        sp(c).edit().putString("author", v).apply();
    }

    /** The approved folder (SAF tree) holding a WhatsApp app's sticker folders. */
    static Uri tree(Context c, String pkg) {
        String s = sp(c).getString("tree_" + pkg, null);
        return s == null ? null : Uri.parse(s);
    }

    /** The folder last picked for an export, to open the picker there next time. */
    static Uri exportTree(Context c) {
        String s = sp(c).getString("export_tree", null);
        return s == null ? null : Uri.parse(s);
    }

    static void setExportTree(Context c, Uri uri) {
        sp(c).edit().putString("export_tree", uri.toString()).apply();
    }

    static void setTree(Context c, String pkg, Uri uri) {
        sp(c).edit().putString("tree_" + pkg, uri == null ? null : uri.toString()).apply();
    }
}
