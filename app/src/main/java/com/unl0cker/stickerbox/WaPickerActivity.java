package com.unl0cker.stickerbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Browses the stickers WhatsApp keeps in shared storage:
 *   Android/media/&lt;pkg&gt;/&lt;app&gt;/Media/&lt;app&gt; Stickers                  stickers from chats
 *   Android/media/&lt;pkg&gt;/&lt;app&gt;/Media/&lt;app&gt; Backup Excluded Stickers  stickers WhatsApp saved
 * Access is one folder the user approves in the system file picker; no storage permission.
 */
public class WaPickerActivity extends Activity {
    private static final int REQ_TREE = 1;
    private static final String EXTERNAL = "com.android.externalstorage.documents";

    private String packId;
    private final List<String> apps = new ArrayList<>();
    private String app;
    private boolean saved;
    private final List<Doc> docs = new ArrayList<>();
    private final Set<Uri> selected = new LinkedHashSet<>();
    private final Map<String, List<Doc>> cache = new HashMap<>();
    private final Adapter adapter = new Adapter();
    private GridView grid;
    private View access;
    private TextView empty;
    private View loading;
    private Button add;
    private TextView chipChats;
    private TextView chipSaved;
    private Header header;
    private int loadToken;

    static final class Doc {
        Uri uri;
        String name;
        long modified;
        boolean animated;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_picker);
        packId = getIntent().getStringExtra(PackActivity.EXTRA_PACK);
        header = new Header(this).back().title(getString(R.string.whatsapp_stickers));
        grid = findViewById(R.id.grid);
        access = findViewById(R.id.access);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        add = findViewById(R.id.add);
        chipChats = findViewById(R.id.chip_chats);
        chipSaved = findViewById(R.id.chip_saved);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Uri u = docs.get(pos).uri;
            if (!selected.remove(u)) selected.add(u);
            adapter.notifyDataSetChanged();
            updateButton();
        });
        chipChats.setOnClickListener(v -> setSource(false));
        chipSaved.setOnClickListener(v -> setSource(true));
        findViewById(R.id.grant).setOnClickListener(v -> startActivityForResult(grantIntent(app), REQ_TREE));
        add.setOnClickListener(v -> addSelected());
        Ui.edgeToEdge(this, header.root, findViewById(R.id.bottom_bar));

        apps.addAll(WhatsApp.installedApps(this));
        if (apps.isEmpty()) {
            Ui.toast(this, getString(R.string.no_whatsapp));
            finish();
            return;
        }
        if (apps.size() > 1) {
            header.tabs.setVisibility(View.VISIBLE);
            for (String pkg : apps) {
                View tab = getLayoutInflater().inflate(R.layout.header_tab, header.tabs, false);
                ((TextView) tab.findViewById(R.id.tab_label)).setText(WhatsApp.label(this, pkg));
                tab.setTag(pkg);
                tab.setOnClickListener(v -> setApp(pkg));
                header.tabs.addView(tab);
            }
        }
        setApp(apps.get(0));
        updateButton();
    }

    private void setApp(String pkg) {
        app = pkg;
        for (int i = 0; i < header.tabs.getChildCount(); i++) {
            View tab = header.tabs.getChildAt(i);
            boolean on = pkg.equals(tab.getTag());
            tab.findViewById(R.id.tab_line).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            ((TextView) tab.findViewById(R.id.tab_label)).setTextColor(getColor(on ? R.color.on_header : R.color.on_header_dim));
        }
        if (apps.size() == 1) header.subtitle(WhatsApp.label(this, pkg));
        load();
    }

    private void setSource(boolean savedFolder) {
        saved = savedFolder;
        load();
    }

    private void updateButton() {
        add.setEnabled(!selected.isEmpty());
        add.setText(selected.isEmpty() ? getString(R.string.select_stickers)
                : getString(R.string.add_n, Ui.plural(this, R.plurals.stickers_count, selected.size())));
    }

    private void load() {
        chipChats.setSelected(!saved);
        chipSaved.setSelected(saved);
        docs.clear();
        adapter.notifyDataSetChanged();
        empty.setVisibility(View.GONE);
        Uri tree = Prefs.tree(this, app);
        if (tree == null || !hasAccess(this, tree)) {
            access.setVisibility(View.VISIBLE);
            grid.setVisibility(View.GONE);
            findViewById(R.id.chips).setVisibility(View.GONE);
            ((TextView) findViewById(R.id.access_title)).setText(getString(R.string.access_title, WhatsApp.label(this, app)));
            ((TextView) findViewById(R.id.access_body)).setText(getString(R.string.access_body, folderPath(app)));
            return;
        }
        access.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        findViewById(R.id.chips).setVisibility(View.VISIBLE);
        String key = app + (saved ? ":saved" : ":chats");
        List<Doc> hit = cache.get(key);
        if (hit != null) {
            show(hit);
            return;
        }
        loading.setVisibility(View.VISIBLE);
        int token = ++loadToken;
        String pkg = app;
        boolean sv = saved;
        new Thread(() -> {
            List<Doc> list = list(this, tree, pkg, sv);
            Ui.main(() -> {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                if (list == null) {
                    empty.setVisibility(View.VISIBLE);
                    empty.setText(getString(R.string.folder_not_found, folderPath(pkg)));
                    return;
                }
                cache.put(key, list);
                show(list);
            });
        }).start();
    }

    private void show(List<Doc> list) {
        docs.clear();
        docs.addAll(list);
        adapter.notifyDataSetChanged();
        if (list.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(saved ? R.string.no_saved_stickers : R.string.no_chat_stickers);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            String err = onGranted(this, app, data.getData());
            if (err != null) Ui.message(this, getString(R.string.wrong_folder), err);
            cache.clear();
            load();
        }
    }

    private void addSelected() {
        List<Uri> uris = new ArrayList<>(selected);
        android.app.Dialog d = Ui.progress(this, getString(R.string.loading));
        Ui.IO.execute(() -> {
            List<File> files = new ArrayList<>();
            File dir = new File(getCacheDir(), "incoming");
            for (Uri u : uris) {
                try {
                    files.add(Images.copyIn(getContentResolver(), u, dir));
                } catch (IOException | SecurityException ignored) {
                    // skip
                }
            }
            Ui.main(() -> {
                Ui.dismiss(d);
                PackActivity.addFiles(this, packId, files, () -> {
                    setResult(RESULT_OK);
                    finish();
                });
            });
        });
    }

    // ---- folder access (also used by Settings) ----

    static String baseName(String pkg) { return WhatsApp.W4B.equals(pkg) ? "WhatsApp Business" : "WhatsApp"; }

    static String folderPath(String pkg) { return "Android/media/" + pkg + "/" + baseName(pkg) + "/Media"; }

    static Intent grantIntent(String pkg) {
        Uri initial = DocumentsContract.buildDocumentUri(EXTERNAL, "primary:" + folderPath(pkg));
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
    }

    static boolean hasAccess(Context c, Uri tree) {
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) return true;
        }
        return false;
    }

    static boolean hasAccess(Context c, String pkg) {
        Uri t = Prefs.tree(c, pkg);
        return t != null && hasAccess(c, t);
    }

    /** Keeps the approval if the folder leads to the sticker folders; returns an error otherwise. */
    static String onGranted(Context c, String pkg, Uri tree) {
        try {
            c.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            return e.getMessage();
        }
        if (resolve(c, tree, pkg) == null) {
            c.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return c.getString(R.string.wrong_folder_body, folderPath(pkg));
        }
        Uri old = Prefs.tree(c, pkg);
        if (old != null && !old.equals(tree)) {
            try {
                c.getContentResolver().releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // already gone
            }
        }
        Prefs.setTree(c, pkg, tree);
        return null;
    }

    static void revoke(Context c, String pkg) {
        Uri t = Prefs.tree(c, pkg);
        if (t != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // already gone
            }
        }
        Prefs.setTree(c, pkg, null);
    }

    /** Document id of the folder that holds the sticker folders, searching down from the approved one. */
    private static String resolve(Context c, Uri tree, String pkg) {
        String base = baseName(pkg);
        String docId = DocumentsContract.getTreeDocumentId(tree);
        for (int depth = 0; depth < 5 && docId != null; depth++) {
            Map<String, String> kids = children(c, tree, docId, true);
            if (kids == null) return null;
            if (kids.containsKey(base + " Stickers") || kids.containsKey(base + " Backup Excluded Stickers")) return docId;
            String next = null;
            for (String name : new String[]{"media", pkg, base, "Media"}) {
                if (kids.containsKey(name)) {
                    next = kids.get(name);
                    break;
                }
            }
            docId = next;
        }
        return null;
    }

    /** name to document id of the child folders (dirsOnly) of docId. */
    private static Map<String, String> children(Context c, Uri tree, String docId, boolean dirsOnly) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Map<String, String> out = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2));
                if (!dirsOnly || dir) out.put(cur.getString(1), cur.getString(0));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** WebP stickers in the chats or saved folder, newest first; null when the folder is missing. */
    static List<Doc> list(Context c, Uri tree, String pkg, boolean saved) {
        String media = resolve(c, tree, pkg);
        if (media == null) return null;
        Map<String, String> dirs = children(c, tree, media, true);
        String base = baseName(pkg);
        String folder = dirs == null ? null : dirs.get(base + (saved ? " Backup Excluded Stickers" : " Stickers"));
        List<Doc> out = new ArrayList<>();
        if (folder == null) return out;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder);
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String name = cur.getString(1);
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".webp")) continue;
                Doc d = new Doc();
                d.uri = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                d.name = name;
                d.modified = cur.getLong(2);
                d.animated = isAnimated(c, d.uri);
                out.add(d);
            }
        } catch (RuntimeException e) {
            return out;
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    private static boolean isAnimated(Context c, Uri uri) {
        byte[] h = new byte[21];
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            return n == h.length && h[12] == 'V' && h[13] == 'P' && h[14] == '8' && h[15] == 'X' && (h[20] & 0x02) != 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return docs.size(); }

        @Override
        public Object getItem(int i) { return docs.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_sticker, parent, false);
            Doc d = docs.get(pos);
            Thumbs.load(v.findViewById(R.id.image), getContentResolver(), d.uri, Ui.dp(WaPickerActivity.this, 96));
            v.findViewById(R.id.anim_badge).setVisibility(d.animated ? View.VISIBLE : View.GONE);
            boolean sel = selected.contains(d.uri);
            v.findViewById(R.id.selected).setVisibility(sel ? View.VISIBLE : View.GONE);
            ImageView check = v.findViewById(R.id.check);
            check.setVisibility(View.VISIBLE);
            check.setImageResource(sel ? R.drawable.check_on : R.drawable.check_off);
            return v;
        }
    }
}
