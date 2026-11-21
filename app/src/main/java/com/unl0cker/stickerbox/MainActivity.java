package com.unl0cker.stickerbox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 1;

    private final List<Store.Pack> packs = new ArrayList<>();
    private final Map<String, WhatsApp.Status> statuses = new HashMap<>();
    private Adapter adapter;
    private View empty;
    private int loadToken;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Header h = new Header(this).title(getString(R.string.app_name));
        h.action(R.drawable.ic_import, getString(R.string.import_title), this::importMenu);
        h.action(R.drawable.ic_settings, getString(R.string.settings),
                () -> startActivity(new Intent(this, SettingsActivity.class)));

        ListView list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> openPack(packs.get(pos).id));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            packMenu(packs.get(pos));
            return true;
        });
        findViewById(R.id.fab).setOnClickListener(v -> newPack(null));
        Ui.edgeToEdge(this, h.root, list, findViewById(R.id.fab_area));

        if (state == null) handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        int token = ++loadToken;
        Ui.IO.execute(() -> {
            List<Store.Pack> all = Store.all(this);
            Ui.main(() -> {
                if (token != loadToken) return;
                packs.clear();
                packs.addAll(all);
                adapter.notifyDataSetChanged();
                empty.setVisibility(packs.isEmpty() ? View.VISIBLE : View.GONE);
            });
            // WhatsApp's own provider answers these; slower, so it fills in afterwards.
            Map<String, WhatsApp.Status> st = new HashMap<>();
            for (Store.Pack p : all) st.put(p.id, WhatsApp.status(this, p));
            PackBuilder.pruneConverted(this);
            Ui.main(() -> {
                if (token != loadToken) return;
                statuses.clear();
                statuses.putAll(st);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void openPack(String id) {
        startActivity(new Intent(this, PackActivity.class).putExtra(PackActivity.EXTRA_PACK, id));
    }

    /** Asks for a name, creates the pack and runs then (or opens it). */
    private void newPack(java.util.function.Consumer<Store.Pack> then) {
        Ui.prompt(this, getString(R.string.new_pack), getString(R.string.pack_name_hint), "", 0, name -> {
            if (name.isEmpty()) return;
            Ui.IO.execute(() -> {
                try {
                    Store.Pack p = Store.create(this, name, Prefs.author(this));
                    Ui.main(() -> {
                        if (then != null) then.accept(p);
                        else openPack(p.id);
                    });
                } catch (IOException e) {
                    Ui.main(() -> Ui.message(this, getString(R.string.error), e.getMessage()));
                }
            });
        });
    }

    private void packMenu(Store.Pack p) {
        new Sheet(this).title(p.name)
                .item(R.drawable.ic_edit, getString(R.string.rename), () ->
                        Ui.prompt(this, getString(R.string.rename), getString(R.string.pack_name_hint), p.name, 0, name -> {
                            if (name.isEmpty()) return;
                            Ui.IO.execute(() -> {
                                Store.Pack fresh = Store.load(this, p.id);
                                if (fresh == null) return;
                                fresh.name = name;
                                fresh.touch();
                                try {
                                    Store.save(fresh);
                                } catch (IOException ignored) {
                                    // shown as unchanged after reload
                                }
                                Ui.main(this::reload);
                            });
                        }))
                .danger(R.drawable.ic_delete, getString(R.string.delete_pack), () ->
                        Ui.confirm(this, getString(R.string.delete_pack_q, p.name), getString(R.string.delete_pack_body),
                                getString(R.string.delete), () -> Ui.IO.execute(() -> {
                                    Store.delete(this, p);
                                    getContentResolver().notifyChange(StickerProvider.METADATA_URI, null);
                                    Ui.main(this::reload);
                                })))
                .show();
    }

    // ---- import ----

    private void importMenu() {
        new Sheet(this).title(getString(R.string.import_title))
                .item(R.drawable.ic_chat, getString(R.string.import_from_app), getString(R.string.import_from_app_summary), this::importFromApps)
                .item(R.drawable.ic_folder, getString(R.string.import_wastickers), getString(R.string.import_file_summary), this::pickImport)
                .show();
    }

    private void importFromApps() {
        Dialog d = Ui.progress(this, getString(R.string.import_looking));
        Ui.IO.execute(() -> {
            List<OtherApps.Source> sources = OtherApps.find(this);
            Ui.main(() -> {
                Ui.dismiss(d);
                if (sources.isEmpty()) {
                    Ui.message(this, getString(R.string.import_none_title), getString(R.string.import_none_body));
                    return;
                }
                Sheet s = new Sheet(this).title(getString(R.string.import_from_app));
                for (OtherApps.Source src : sources) {
                    s.item(R.drawable.ic_chat, src.label, Ui.plural(this, R.plurals.packs_count, src.packs.size()), () -> choosePacks(src));
                }
                s.show();
            });
        });
    }

    private void choosePacks(OtherApps.Source src) {
        String[] names = new String[src.packs.size()];
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            OtherApps.RemotePack rp = src.packs.get(i);
            names[i] = rp.publisher == null || rp.publisher.isEmpty() ? rp.name : rp.name + "  ·  " + rp.publisher;
            checked[i] = true;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_choose, src.label))
                .setMultiChoiceItems(names, checked, (dlg, which, on) -> checked[which] = on)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_button, (dlg, w) -> {
                    List<OtherApps.RemotePack> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(src.packs.get(i));
                    if (!chosen.isEmpty()) importPacks(src, chosen);
                })
                .show();
    }

    private void importPacks(OtherApps.Source src, List<OtherApps.RemotePack> chosen) {
        Dialog d = Ui.progress(this, Ui.plural(this, R.plurals.importing_packs, chosen.size()));
        Ui.IO.execute(() -> {
            int packs = 0;
            int stickers = 0;
            List<String> failed = new ArrayList<>();
            for (OtherApps.RemotePack rp : chosen) {
                try {
                    Store.Pack p = OtherApps.importPack(this, src, rp);
                    if (p == null) {
                        failed.add(getString(R.string.import_pack_empty, rp.name));
                    } else {
                        packs++;
                        stickers += p.stickers.size();
                    }
                } catch (IOException | RuntimeException e) {
                    failed.add(rp.name + ": " + e.getMessage());
                }
            }
            String msg = getString(R.string.import_done, Ui.plural(this, R.plurals.packs_count, packs),
                    Ui.plural(this, R.plurals.stickers_count, stickers));
            if (!failed.isEmpty()) msg += getString(R.string.import_failed_list, String.join("\n", failed));
            String m = msg;
            Ui.main(() -> {
                Ui.dismiss(d);
                Ui.message(this, getString(R.string.import_title), m);
                reload();
            });
        });
    }

    // ---- .wastickers import ----

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Dialog d = Ui.progress(this, getString(R.string.importing));
            Ui.IO.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    Store.Pack p = Backup.importWastickers(this, in, getString(R.string.imported_pack));
                    Ui.main(() -> {
                        Ui.dismiss(d);
                        openPack(p.id);
                    });
                } catch (IOException | RuntimeException e) {
                    Ui.main(() -> {
                        Ui.dismiss(d);
                        Ui.message(this, getString(R.string.import_failed), e.getMessage());
                    });
                }
            });
        }
    }

    // ---- images shared from other apps ----

    private void handleShare(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        setIntent(new Intent(this, MainActivity.class));
        if (uris.isEmpty()) return;

        Dialog d = Ui.progress(this, getString(R.string.loading));
        Ui.IO.execute(() -> {
            List<File> files = new ArrayList<>();
            File dir = new File(getCacheDir(), "incoming");
            for (Uri u : uris) {
                try {
                    files.add(Images.copyIn(getContentResolver(), u, dir));
                } catch (IOException | SecurityException ignored) {
                    // skip what cannot be read
                }
            }
            List<Store.Pack> all = Store.all(this);
            Ui.main(() -> {
                Ui.dismiss(d);
                if (files.isEmpty()) {
                    Ui.toast(this, getString(R.string.nothing_readable));
                    return;
                }
                choosePackFor(files, all);
            });
        });
    }

    private void choosePackFor(List<File> files, List<Store.Pack> all) {
        Sheet s = new Sheet(this).title(Ui.plural(this, R.plurals.add_images_to, files.size()));
        s.item(R.drawable.ic_add, getString(R.string.new_pack), () -> newPack(p -> addShared(p, files)));
        for (Store.Pack p : all) {
            s.item(R.drawable.ic_folder, p.name, Ui.plural(this, R.plurals.stickers_count, p.stickers.size()), () -> addShared(p, files));
        }
        s.show();
    }

    private void addShared(Store.Pack pack, List<File> files) {
        if (files.size() == 1) {
            try {
                if (!Images.info(files.get(0)).webp) {
                    startActivity(new Intent(this, EditorActivity.class)
                            .putExtra(EditorActivity.EXTRA_PACK, pack.id)
                            .putExtra(EditorActivity.EXTRA_SOURCE, files.get(0).getPath()));
                    return;
                }
            } catch (IOException ignored) {
                // fall through and add it as it is
            }
        }
        PackActivity.addFiles(this, pack.id, files, () -> openPack(pack.id));
    }

    // ---- list ----

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return packs.size(); }

        @Override
        public Object getItem(int i) { return packs.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_pack, parent, false);
            Store.Pack p = packs.get(pos);
            ((TextView) v.findViewById(R.id.name)).setText(p.name);
            String info = Ui.plural(MainActivity.this, R.plurals.stickers_count, p.stickers.size());
            if (p.author != null && !p.author.isEmpty()) info += " · " + p.author;
            ((TextView) v.findViewById(R.id.info)).setText(info);

            ImageView tray = v.findViewById(R.id.tray);
            Store.Sticker t = p.traySticker();
            if (t != null) Thumbs.load(tray, p.file(t), Ui.dp(MainActivity.this, 58));
            else {
                tray.setTag(R.id.thumb_key, null);
                tray.setImageResource(R.drawable.ic_image);
                tray.setImageTintList(getColorStateList(R.color.text_secondary));
            }
            if (t != null) tray.setImageTintList(null);

            LinearLayout strip = v.findViewById(R.id.strip);
            strip.removeAllViews();
            int px = Ui.dp(MainActivity.this, 34);
            for (int i = 0; i < Math.min(6, p.stickers.size()); i++) {
                ImageView iv = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
                lp.setMarginEnd(Ui.dp(MainActivity.this, 6));
                strip.addView(iv, lp);
                Thumbs.load(iv, p.file(p.stickers.get(i)), px);
            }
            strip.setVisibility(p.stickers.isEmpty() ? View.GONE : View.VISIBLE);

            TextView status = v.findViewById(R.id.status);
            WhatsApp.Status s = statuses.get(p.id);
            if (s != null && s.added()) {
                status.setVisibility(View.VISIBLE);
                if (s.outdated) {
                    status.setText(R.string.chip_update);
                    status.setBackgroundResource(R.drawable.chip_warn);
                    status.setTextColor(getColor(R.color.warn));
                } else {
                    status.setText(R.string.chip_added);
                    status.setBackgroundResource(R.drawable.chip_ok);
                    status.setTextColor(getColor(R.color.chip_ok_text));
                }
            } else {
                status.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
