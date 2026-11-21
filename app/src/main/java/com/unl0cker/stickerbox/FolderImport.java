package com.unl0cker.stickerbox;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Packs from a folder chosen in the system picker: each subfolder becomes a pack named after
 * it (a leading number such as "01 " only sets the order), with its images in file name order.
 * A folder without subfolders becomes one pack.
 */
final class FolderImport {
    private FolderImport() {}

    static final class Folder {
        String name;
        final List<Uri> files = new ArrayList<>();
    }

    private static final class Child {
        String id, name, mime;
    }

    /** The folders to import, in name order ("2" before "10"). */
    static List<Folder> scan(Context c, Uri tree) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        List<Child> top = children(c, tree, rootId);
        List<Folder> out = new ArrayList<>();
        for (Child ch : top) {
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(ch.mime)) continue;
            Folder f = folder(c, tree, ch.id, ch.name);
            if (!f.files.isEmpty()) out.add(f);
        }
        if (out.isEmpty()) {
            Folder f = folder(c, tree, rootId, displayName(c, tree, rootId));
            if (!f.files.isEmpty()) out.add(f);
        }
        return out;
    }

    /** Imports one folder as a new pack; null when none of its files is a usable image. */
    static Store.Pack importFolder(Context c, Folder f) throws IOException {
        Store.Pack p = Store.create(c, packName(f.name), Prefs.author(c));
        File tmp = new File(c.getCacheDir(), "folder-import");
        tmp.mkdirs();
        for (Uri u : f.files) {
            File t = new File(tmp, "in");
            try (InputStream in = c.getContentResolver().openInputStream(u);
                 OutputStream out = new FileOutputStream(t)) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            try {
                Store.addFile(p, t, true);
            } catch (IOException | RuntimeException e) {
                t.delete(); // not an image Android can read: skip it
            }
        }
        Store.deleteTree(tmp);
        if (p.stickers.isEmpty()) {
            Store.delete(c, p);
            return null;
        }
        Store.save(p);
        return p;
    }

    /** "01 BAD" -> "BAD": the number orders the folders, the rest is the name. */
    static String packName(String folder) {
        String n = folder == null ? "" : folder.trim().replaceFirst("^\\d+[\\s._-]+(?=\\S)", "");
        return n.isEmpty() ? (folder == null || folder.trim().isEmpty() ? "Pack" : folder.trim()) : n;
    }

    private static Folder folder(Context c, Uri tree, String id, String name) {
        Folder f = new Folder();
        f.name = name;
        for (Child ch : children(c, tree, id)) {
            if (isImage(ch)) f.files.add(DocumentsContract.buildDocumentUriUsingTree(tree, ch.id));
        }
        return f;
    }

    private static boolean isImage(Child ch) {
        if (ch.name == null || ch.name.startsWith(".")) return false;
        if (ch.mime != null && ch.mime.startsWith("image/")) return true;
        String n = ch.name.toLowerCase(Locale.ROOT);
        return n.matches(".*\\.(webp|png|jpe?g|gif|heic|heif|bmp)$");
    }

    private static List<Child> children(Context c, Uri tree, String parentId) {
        List<Child> out = new ArrayList<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                Child ch = new Child();
                ch.id = cur.getString(0);
                ch.name = cur.getString(1);
                ch.mime = cur.getString(2);
                if (ch.id != null && ch.name != null) out.add(ch);
            }
        } catch (RuntimeException ignored) {
            // unreadable folder: nothing to import from it
        }
        out.sort((a, b) -> naturalCompare(a.name, b.name));
        return out;
    }

    private static String displayName(Context c, Uri tree, String id) {
        try (Cursor cur = c.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "Pack";
    }

    /** Compares names with digit runs as numbers, ignoring case: "2.webp" < "10.webp". */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
