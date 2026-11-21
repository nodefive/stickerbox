package com.unl0cker.stickerbox;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/** Writes a pack into a folder the user picked (Storage Access Framework tree). */
final class Export {
    private Export() {}

    /**
     * Creates "&lt;pack name&gt;" inside the picked folder (the system adds " (1)" if it exists)
     * with the original images in pack order: 001.png, 002.webp, ...
     * Returns the name of the created folder.
     */
    static String images(Context c, Store.Pack p, Uri tree) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri dir = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, safeName(p.name));
        if (dir == null) throw new IOException(c.getString(R.string.export_cannot_create));
        int digits = Math.max(3, String.valueOf(p.stickers.size()).length());
        int n = 0;
        for (Store.Sticker s : p.stickers) {
            File f = p.file(s);
            if (!f.isFile()) continue;
            n++;
            boolean webp = s.file.endsWith(".webp");
            String name = String.format(Locale.ROOT, "%0" + digits + "d", n) + (webp ? ".webp" : ".png");
            write(cr, dir, webp ? "image/webp" : "image/png", name, f);
        }
        return displayName(cr, dir, safeName(p.name));
    }

    /** Copies the built .wastickers file(s) into the picked folder. */
    static void files(Context c, List<File> files, Uri tree) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        for (File f : files) write(cr, parent, "application/octet-stream", f.getName(), f);
    }

    private static void write(ContentResolver cr, Uri dir, String mime, String name, File src) throws IOException {
        Uri doc = DocumentsContract.createDocument(cr, dir, mime, name);
        if (doc == null) throw new IOException("cannot create " + name);
        try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(doc, "w")) {
            if (out == null) throw new IOException("cannot write " + name);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    private static String displayName(ContentResolver cr, Uri doc, String fallback) {
        try (android.database.Cursor cur = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the requested name
        }
        return fallback;
    }

    /** A folder or file name without characters storage providers reject. */
    static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "stickers" : s;
    }
}
