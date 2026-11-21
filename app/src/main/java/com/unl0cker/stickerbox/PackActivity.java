package com.unl0cker.stickerbox;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class PackActivity extends Activity {
    static final String EXTRA_PACK = "pack";
    private static final int REQ_GALLERY = 1;
    private static final int REQ_FILES = 2;
    private static final int REQ_WHATSAPP = 3;
    private static final int REQ_ADD_TO_WA = 200;
    private static final int REQ_EXPORT_IMAGES = 4;
    private static final int REQ_EXPORT_WASTICKERS = 5;

    private String packId;
    private Store.Pack pack;
    private WhatsApp.Status status;
    private Header header;
    private GridView grid;
    private TextView banner;
    private TextView plan;
    private Button install;
    private final Adapter adapter = new Adapter();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;
    private int loadToken;

    /** WhatsApp "add pack" prompts still to show: one per part and app. */
    private final Deque<String[]> pending = new ArrayDeque<>();
    private int addedCount;
    private List<String> buildNotes = new ArrayList<>();
    private List<String> staleParts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_pack);
        packId = getIntent().getStringExtra(EXTRA_PACK);
        header = new Header(this);
        grid = findViewById(R.id.grid);
        banner = findViewById(R.id.banner);
        plan = findViewById(R.id.plan);
        install = findViewById(R.id.install);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onTap(pos));
        grid.setOnItemLongClickListener((p, v, pos, id) -> onLongPress(pos));
        install.setOnClickListener(v -> install());
        findViewById(R.id.share).setOnClickListener(v -> share());
        header.title.setOnClickListener(v -> { if (!selecting) rename(); });
        Ui.edgeToEdge(this, header.root, findViewById(R.id.bottom_bar));
        setupHeader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onBackPressed() {
        if (selecting) endSelection();
        else super.onBackPressed();
    }

    private void reload() {
        int token = ++loadToken;
        Ui.IO.execute(() -> {
            Store.Pack p = Store.load(this, packId);
            WhatsApp.Status s = p == null ? null : WhatsApp.status(this, p);
            Ui.main(() -> {
                if (token != loadToken) return;
                if (p == null) {
                    finish();
                    return;
                }
                pack = p;
                status = s;
                selected.retainAll(ids(p));
                if (selecting && selected.isEmpty()) selecting = false;
                bind();
            });
        });
    }

    private static Set<String> ids(Store.Pack p) {
        Set<String> out = new LinkedHashSet<>();
        for (Store.Sticker s : p.stickers) out.add(s.id);
        return out;
    }

    private void bind() {
        setupHeader();
        adapter.notifyDataSetChanged();

        int n = pack.stickers.size();
        int[] pl = PackBuilder.plan(pack);
        if (n < PackBuilder.MIN) {
            plan.setText(getString(R.string.plan_need_more, PackBuilder.MIN - n));
        } else if (pl[0] > 1) {
            plan.setText(getString(R.string.plan_split, Ui.plural(this, R.plurals.stickers_count, n), pl[0]));
        } else if (pl[1] > 0 && pl[2] > 0) {
            plan.setText(R.string.plan_mixed);
        } else {
            plan.setText(R.string.plan_one);
        }
        install.setEnabled(pl[0] > 0);
        View share = findViewById(R.id.share);
        share.setEnabled(pl[0] > 0);
        share.setAlpha(pl[0] > 0 ? 1f : 0.4f);

        if (status != null && status.added()) {
            banner.setVisibility(View.VISIBLE);
            banner.setText(WhatsApp.describe(this, status));
            if (status.outdated) {
                banner.setBackgroundColor(getColor(R.color.warn_bg));
                banner.setTextColor(getColor(R.color.warn));
                setBannerIcon(R.drawable.ic_undo, R.color.warn);
                install.setText(R.string.update_whatsapp);
            } else {
                banner.setBackgroundColor(getColor(R.color.chip_ok_bg));
                banner.setTextColor(getColor(R.color.chip_ok_text));
                setBannerIcon(R.drawable.ic_check, R.color.chip_ok_text);
                install.setText(R.string.add_again);
            }
        } else {
            banner.setVisibility(View.GONE);
            install.setText(R.string.add_to_whatsapp);
        }
    }

    private void setBannerIcon(int icon, int color) {
        android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        d.setTint(getColor(color));
        int s = Ui.dp(this, 20);
        d.setBounds(0, 0, s, s);
        banner.setCompoundDrawablesRelative(d, null, null, null);
    }

    private void setupHeader() {
        header.clearActions();
        if (selecting) {
            header.nav(R.drawable.ic_close, getString(R.string.cancel), this::endSelection);
            header.title(getString(R.string.n_selected, selected.size())).subtitle(null);
            if (selected.size() == 1) header.action(R.drawable.ic_star, getString(R.string.set_tray), () -> setTray(first()));
            header.action(R.drawable.ic_emoji, getString(R.string.emojis), () -> editEmojis(new ArrayList<>(selected)));
            header.action(R.drawable.ic_first, getString(R.string.move_to_front), () -> moveToFront(new ArrayList<>(selected)));
            header.action(R.drawable.ic_move_pack, getString(R.string.move_to_pack), () -> moveToPack(new ArrayList<>(selected)));
            header.action(R.drawable.ic_delete, getString(R.string.delete), () -> deleteStickers(new ArrayList<>(selected)));
        } else {
            header.back();
            if (pack != null) {
                String sub = Ui.plural(this, R.plurals.stickers_count, pack.stickers.size());
                if (pack.author != null && !pack.author.isEmpty()) sub += " · " + pack.author;
                header.title(pack.name).subtitle(sub);
            }
            header.action(R.drawable.ic_more, getString(R.string.more), this::packMenu);
        }
    }

    private String first() { return selected.iterator().next(); }

    // ---- grid ----

    private boolean hasAddTile() { return !selecting; }

    private Store.Sticker stickerAt(int pos) {
        int i = pos - (hasAddTile() ? 1 : 0);
        return pack == null || i < 0 || i >= pack.stickers.size() ? null : pack.stickers.get(i);
    }

    private void onTap(int pos) {
        if (hasAddTile() && pos == 0) {
            addMenu();
            return;
        }
        Store.Sticker s = stickerAt(pos);
        if (s == null) return;
        if (selecting) {
            if (!selected.remove(s.id)) selected.add(s.id);
            if (selected.isEmpty()) endSelection();
            else {
                setupHeader();
                adapter.notifyDataSetChanged();
            }
        } else {
            stickerMenu(s);
        }
    }

    private boolean onLongPress(int pos) {
        Store.Sticker s = stickerAt(pos);
        if (s == null) return false;
        if (!selecting) {
            selecting = true;
            selected.clear();
        }
        selected.add(s.id);
        setupHeader();
        adapter.notifyDataSetChanged();
        return true;
    }

    private void endSelection() {
        selecting = false;
        selected.clear();
        setupHeader();
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pack == null ? 0 : pack.stickers.size() + (hasAddTile() ? 1 : 0);
        }

        @Override
        public Object getItem(int i) { return null; }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public int getViewTypeCount() { return 2; }

        @Override
        public int getItemViewType(int pos) { return hasAddTile() && pos == 0 ? 0 : 1; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (getItemViewType(pos) == 0) {
                return v != null ? v : getLayoutInflater().inflate(R.layout.item_add, parent, false);
            }
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_sticker, parent, false);
            Store.Sticker s = stickerAt(pos);
            ImageView image = v.findViewById(R.id.image);
            Thumbs.load(image, pack.file(s), Ui.dp(PackActivity.this, 96));
            v.findViewById(R.id.anim_badge).setVisibility(s.animated ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.tray_badge).setVisibility(s == pack.traySticker() ? View.VISIBLE : View.GONE);
            boolean sel = selected.contains(s.id);
            v.findViewById(R.id.selected).setVisibility(sel ? View.VISIBLE : View.GONE);
            ImageView check = v.findViewById(R.id.check);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setImageResource(sel ? R.drawable.check_on : R.drawable.check_off);
            return v;
        }
    }

    // ---- adding stickers ----

    private void addMenu() {
        new Sheet(this).title(getString(R.string.add_stickers))
                .item(R.drawable.ic_image, getString(R.string.from_gallery), getString(R.string.from_gallery_summary), this::pickGallery)
                .item(R.drawable.ic_folder, getString(R.string.from_files), getString(R.string.from_files_summary), this::pickFiles)
                .item(R.drawable.ic_chat, getString(R.string.from_whatsapp), getString(R.string.from_whatsapp_summary), () ->
                        startActivityForResult(new Intent(this, WaPickerActivity.class).putExtra(EXTRA_PACK, packId), REQ_WHATSAPP))
                .show();
    }

    private void pickGallery() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES)
                    .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(100, MediaStore.getPickImagesMaxLimit()));
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            pickFiles();
        }
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILES);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ADD_TO_WA) {
            onWhatsAppResult(res, data);
            return;
        }
        if ((req == REQ_EXPORT_IMAGES || req == REQ_EXPORT_WASTICKERS) && res == RESULT_OK && data != null && data.getData() != null) {
            Prefs.setExportTree(this, data.getData());
            exportTo(data.getData(), req == REQ_EXPORT_IMAGES);
            return;
        }
        if ((req == REQ_GALLERY || req == REQ_FILES) && res == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importUris(uris);
        }
    }

    private void importUris(List<Uri> uris) {
        Dialog d = Ui.progress(this, getString(R.string.loading));
        Ui.IO.execute(() -> {
            List<File> files = new ArrayList<>();
            File dir = new File(getCacheDir(), "incoming");
            for (Uri u : uris) {
                try {
                    files.add(Images.copyIn(getContentResolver(), u, dir));
                } catch (IOException | SecurityException ignored) {
                    // skip unreadable items
                }
            }
            boolean editOne = false;
            if (files.size() == 1) {
                try {
                    editOne = !Images.info(files.get(0)).webp;
                } catch (IOException ignored) {
                    // add as it is
                }
            }
            boolean edit = editOne;
            Ui.main(() -> {
                Ui.dismiss(d);
                if (files.isEmpty()) Ui.toast(this, getString(R.string.nothing_readable));
                else if (edit) {
                    startActivity(new Intent(this, EditorActivity.class)
                            .putExtra(EditorActivity.EXTRA_PACK, packId)
                            .putExtra(EditorActivity.EXTRA_SOURCE, files.get(0).getPath()));
                } else {
                    addFiles(this, packId, files, this::reload);
                }
            });
        });
    }

    /** Adds image files (moved, not copied) to a pack and reports duplicates. */
    static void addFiles(Activity a, String packId, List<File> files, Runnable done) {
        Dialog d = Ui.progress(a, a.getString(R.string.adding));
        Ui.IO.execute(() -> {
            int added = 0;
            int dupes = 0;
            int failed = 0;
            String error = null;
            Store.Pack p = Store.load(a, packId);
            if (p != null) {
                for (File f : files) {
                    try {
                        if (Store.addFile(p, f, true) != null) added++;
                        else dupes++;
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        f.delete();
                    }
                }
                if (added > 0) {
                    p.touch();
                    try {
                        Store.save(p);
                    } catch (IOException e) {
                        error = e.getMessage();
                    }
                }
            }
            StringBuilder msg = new StringBuilder(Ui.plural(a, R.plurals.added_n, added));
            if (dupes > 0) msg.append(". ").append(Ui.plural(a, R.plurals.dupes_n, dupes));
            if (failed > 0) msg.append(". ").append(Ui.plural(a, R.plurals.failed_n, failed));
            String text = error != null ? error : msg.toString();
            Ui.main(() -> {
                Ui.dismiss(d);
                Ui.toastLong(a, text);
                if (done != null) done.run();
            });
        });
    }

    // ---- sticker actions ----

    private void stickerMenu(Store.Sticker s) {
        ImageView preview = new ImageView(this);
        preview.setBackgroundResource(R.drawable.tile);
        int p = Ui.dp(this, 10);
        preview.setPadding(p, p, p, p);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int size = Ui.dp(this, 180);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.dp(this, 8);
        box.addView(preview, lp);
        Thumbs.preview(preview, pack.file(s));

        String emojis = s.emojis.isEmpty() ? getString(R.string.emojis_none) : String.join(" ", s.emojis);
        Sheet sheet = new Sheet(this).header(box);
        if (!s.animated) {
            sheet.item(R.drawable.ic_edit, getString(R.string.edit), () -> startActivity(new Intent(this, EditorActivity.class)
                    .putExtra(EditorActivity.EXTRA_PACK, packId).putExtra(EditorActivity.EXTRA_STICKER, s.id)));
        }
        List<String> one = java.util.Collections.singletonList(s.id);
        sheet.item(R.drawable.ic_emoji, getString(R.string.emojis), emojis, () -> editEmojis(one))
                .item(R.drawable.ic_star, getString(R.string.set_tray), getString(R.string.set_tray_summary), () -> setTray(s.id))
                .item(R.drawable.ic_first, getString(R.string.move_to_front), () -> moveToFront(one))
                .item(R.drawable.ic_move_pack, getString(R.string.move_to_pack), () -> moveToPack(one))
                .danger(R.drawable.ic_delete, getString(R.string.delete), () -> deleteStickers(one))
                .show();
    }

    /** Loads the pack fresh, applies op, bumps the version, saves, refreshes the screen. */
    private void mutate(Consumer<Store.Pack> op) {
        Ui.IO.execute(() -> {
            Store.Pack p = Store.load(this, packId);
            if (p == null) return;
            String err = null;
            try {
                op.accept(p);
                p.touch();
                Store.save(p);
            } catch (RuntimeException | IOException e) {
                err = e.getMessage();
            }
            String e = err;
            Ui.main(() -> {
                if (e != null) Ui.message(this, getString(R.string.error), e);
                reload();
            });
        });
    }

    private void setTray(String id) {
        mutate(p -> p.tray = id);
        if (selecting) endSelection();
    }

    private void moveToFront(List<String> ids) {
        mutate(p -> {
            List<Store.Sticker> moved = new ArrayList<>();
            for (String id : ids) {
                Store.Sticker s = p.find(id);
                if (s != null) moved.add(s);
            }
            p.stickers.removeAll(moved);
            p.stickers.addAll(0, moved);
        });
        if (selecting) endSelection();
    }

    private void deleteStickers(List<String> ids) {
        Ui.confirm(this, Ui.plural(this, R.plurals.delete_n_q, ids.size()), getString(R.string.delete_stickers_body),
                getString(R.string.delete), () -> {
                    mutate(p -> {
                        for (String id : ids) {
                            Store.Sticker s = p.find(id);
                            if (s != null) Store.removeSticker(p, s);
                        }
                    });
                    if (selecting) endSelection();
                });
    }

    private void editEmojis(List<String> ids) {
        Store.Sticker firstSticker = pack.find(ids.get(0));
        String current = firstSticker == null ? "" : String.join("", firstSticker.emojis);
        Ui.prompt(this, getString(R.string.emojis), getString(R.string.emojis_hint), current,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, text -> {
                    List<String> emojis = parseEmojis(text);
                    mutate(p -> {
                        for (String id : ids) {
                            Store.Sticker s = p.find(id);
                            if (s == null) continue;
                            s.emojis.clear();
                            s.emojis.addAll(emojis);
                        }
                    });
                    if (selecting) endSelection();
                });
    }

    /** Up to three emoji (grapheme clusters that are not plain letters, digits or spaces). */
    static List<String> parseEmojis(String text) {
        List<String> out = new ArrayList<>();
        android.icu.text.BreakIterator it = android.icu.text.BreakIterator.getCharacterInstance();
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != android.icu.text.BreakIterator.DONE && out.size() < 3; start = end, end = it.next()) {
            String g = text.substring(start, end);
            int cp = g.codePointAt(0);
            if (Character.isWhitespace(cp) || Character.isLetterOrDigit(cp) || cp < 0xA9 || ",.;".contains(g)) continue;
            if (!out.contains(g)) out.add(g);
        }
        return out;
    }

    private void moveToPack(List<String> ids) {
        Ui.IO.execute(() -> {
            List<Store.Pack> all = Store.all(this);
            Ui.main(() -> {
                Sheet sheet = new Sheet(this).title(getString(R.string.move_to_pack));
                sheet.item(R.drawable.ic_add, getString(R.string.new_pack), () ->
                        Ui.prompt(this, getString(R.string.new_pack), getString(R.string.pack_name_hint), "", 0, name -> {
                            if (!name.isEmpty()) doMove(ids, null, name);
                        }));
                for (Store.Pack other : all) {
                    if (other.id.equals(packId)) continue;
                    sheet.item(R.drawable.ic_folder, other.name, Ui.plural(this, R.plurals.stickers_count, other.stickers.size()),
                            () -> doMove(ids, other.id, null));
                }
                sheet.show();
            });
        });
    }

    private void doMove(List<String> ids, String targetId, String newName) {
        Ui.IO.execute(() -> {
            String msg;
            try {
                Store.Pack from = Store.load(this, packId);
                Store.Pack to = targetId != null ? Store.load(this, targetId) : Store.create(this, newName, Prefs.author(this));
                if (from == null || to == null) return;
                int moved = 0;
                for (String id : ids) {
                    Store.Sticker s = from.find(id);
                    if (s != null && Store.moveSticker(from, s, to)) moved++;
                }
                from.touch();
                to.touch();
                Store.save(to);
                Store.save(from);
                msg = getString(R.string.moved_to, Ui.plural(this, R.plurals.stickers_count, moved), to.name);
            } catch (IOException e) {
                msg = e.getMessage();
            }
            String m = msg;
            Ui.main(() -> {
                Ui.toast(this, m);
                if (selecting) endSelection();
                reload();
            });
        });
    }

    // ---- pack actions ----

    private void packMenu() {
        if (pack == null) return;
        new Sheet(this).title(pack.name)
                .item(R.drawable.ic_edit, getString(R.string.rename), this::rename)
                .item(R.drawable.ic_person, getString(R.string.author), pack.author == null || pack.author.isEmpty()
                        ? getString(R.string.author_default, getString(R.string.app_name)) : pack.author, this::editAuthor)
                .item(R.drawable.ic_move_pack, getString(R.string.merge), getString(R.string.merge_summary), this::mergeMenu)
                .item(R.drawable.ic_export, getString(R.string.export), getString(R.string.export_summary), this::exportMenu)
                .item(R.drawable.ic_share, getString(R.string.share_wastickers), getString(R.string.share_summary), this::share)
                .danger(R.drawable.ic_delete, getString(R.string.delete_pack), () ->
                        Ui.confirm(this, getString(R.string.delete_pack_q, pack.name), getString(R.string.delete_pack_body),
                                getString(R.string.delete), () -> Ui.IO.execute(() -> {
                                    Store.delete(this, pack);
                                    getContentResolver().notifyChange(StickerProvider.METADATA_URI, null);
                                    Ui.main(this::finish);
                                })))
                .show();
    }

    /** Moves every sticker of another pack into this one (after this pack's), then deletes it. */
    private void mergeMenu() {
        Ui.IO.execute(() -> {
            List<Store.Pack> all = Store.all(this);
            Ui.main(() -> {
                Sheet sheet = new Sheet(this).title(getString(R.string.merge_into, pack.name));
                int others = 0;
                for (Store.Pack other : all) {
                    if (other.id.equals(packId)) continue;
                    others++;
                    sheet.item(R.drawable.ic_folder, other.name, Ui.plural(this, R.plurals.stickers_count, other.stickers.size()), () ->
                            Ui.confirm(this, getString(R.string.merge_q, other.name, pack.name),
                                    getString(R.string.merge_body, other.name), getString(R.string.merge_button),
                                    () -> merge(other.id)));
                }
                if (others == 0) Ui.toast(this, getString(R.string.merge_none));
                else sheet.show();
            });
        });
    }

    private void merge(String otherId) {
        Dialog d = Ui.progress(this, getString(R.string.merging));
        Ui.IO.execute(() -> {
            String msg;
            try {
                Store.Pack to = Store.load(this, packId);
                Store.Pack from = Store.load(this, otherId);
                if (to == null || from == null) throw new IOException(getString(R.string.pack_gone));
                int moved = 0;
                int dupes = 0;
                for (Store.Sticker s : new ArrayList<>(from.stickers)) {
                    if (Store.moveSticker(from, s, to)) moved++;
                    else dupes++;
                }
                to.touch();
                Store.save(to);
                Store.delete(this, from);
                getContentResolver().notifyChange(StickerProvider.METADATA_URI, null);
                msg = getString(R.string.merge_done, Ui.plural(this, R.plurals.stickers_count, moved), from.name);
                if (dupes > 0) msg += " " + Ui.plural(this, R.plurals.dupes_n, dupes) + ".";
            } catch (IOException | RuntimeException e) {
                msg = e.getMessage();
            }
            String m = msg;
            Ui.main(() -> {
                Ui.dismiss(d);
                Ui.toastLong(this, m);
                reload();
            });
        });
    }

    private void rename() {
        if (pack == null) return;
        Ui.prompt(this, getString(R.string.rename), getString(R.string.pack_name_hint), pack.name, 0, name -> {
            if (!name.isEmpty()) mutate(p -> p.name = name);
        });
    }

    private void editAuthor() {
        Ui.prompt(this, getString(R.string.author), getString(R.string.author_hint), pack.author, 0, a -> mutate(p -> p.author = a));
    }

    // ---- add to WhatsApp ----

    private void install() {
        List<String> apps = WhatsApp.installedApps(this);
        if (apps.isEmpty()) {
            Ui.message(this, getString(R.string.no_whatsapp), getString(R.string.no_whatsapp_body));
        } else if (apps.size() == 1) {
            startInstall(apps);
        } else {
            new Sheet(this).title(getString(R.string.add_to_which))
                    .item(R.drawable.ic_chat, getString(R.string.whatsapp), () -> startInstall(java.util.Collections.singletonList(WhatsApp.WA)))
                    .item(R.drawable.ic_chat, getString(R.string.whatsapp_business), () -> startInstall(java.util.Collections.singletonList(WhatsApp.W4B)))
                    .item(R.drawable.ic_check, getString(R.string.both), () -> startInstall(apps))
                    .show();
        }
    }

    private void startInstall(List<String> targets) {
        Dialog d = Ui.progress(this, getString(R.string.preparing));
        Ui.IO.execute(() -> {
            try {
                Store.Pack p = Store.load(this, packId);
                List<PackBuilder.Part> before = PackBuilder.parts(this, packId);
                PackBuilder.Result r = PackBuilder.build(this, p);
                getContentResolver().notifyChange(StickerProvider.METADATA_URI, null);
                // Parts WhatsApp still has from an earlier build that this build no longer makes
                // (the pack got smaller, or a part changed between still and animated).
                Set<String> now = new java.util.HashSet<>();
                for (PackBuilder.Part part : r.parts) now.add(part.id);
                List<String> stale = new ArrayList<>();
                for (PackBuilder.Part old : before) {
                    if (now.contains(old.id)) continue;
                    for (String pkg : WhatsApp.installedApps(this)) {
                        if (Boolean.TRUE.equals(WhatsApp.isAdded(this, pkg, old.id))) {
                            stale.add(old.name + " (" + WhatsApp.label(this, pkg) + ")");
                        }
                    }
                }
                Deque<String[]> queue = new ArrayDeque<>();
                for (String pkg : targets) {
                    for (PackBuilder.Part part : r.parts) {
                        if (!Boolean.TRUE.equals(WhatsApp.isAdded(this, pkg, part.id))) {
                            queue.add(new String[]{pkg, part.id, part.name});
                        }
                    }
                }
                Ui.main(() -> {
                    Ui.dismiss(d);
                    pending.clear();
                    pending.addAll(queue);
                    addedCount = 0;
                    buildNotes = r.notes;
                    staleParts = stale;
                    if (!r.notes.isEmpty()) {
                        new android.app.AlertDialog.Builder(this).setTitle(R.string.note)
                                .setMessage(String.join("\n\n", r.notes))
                                .setPositiveButton(R.string.continue_, (x, y) -> nextPrompt())
                                .setNegativeButton(android.R.string.cancel, (x, y) -> reload())
                                .show();
                    } else {
                        nextPrompt();
                    }
                });
            } catch (IOException | RuntimeException e) {
                Ui.main(() -> {
                    Ui.dismiss(d);
                    Ui.message(this, getString(R.string.cannot_add), e.getMessage());
                    reload();
                });
            }
        });
    }

    private void nextPrompt() {
        String[] next = pending.peek();
        if (next == null) {
            if (!staleParts.isEmpty()) {
                Ui.message(this, getString(R.string.stale_title), getString(R.string.stale_body, String.join("\n", staleParts)));
                staleParts = new ArrayList<>();
            } else {
                Ui.toastLong(this, getString(addedCount > 0 ? R.string.added_done : R.string.updated_done));
            }
            reload();
            return;
        }
        PackBuilder.Part part = new PackBuilder.Part();
        part.id = next[1];
        part.name = next[2];
        try {
            startActivityForResult(WhatsApp.addIntent(next[0], part), REQ_ADD_TO_WA);
        } catch (ActivityNotFoundException e) {
            pending.clear();
            Ui.message(this, getString(R.string.cannot_add), getString(R.string.whatsapp_too_old, WhatsApp.label(this, next[0])));
        }
    }

    private void onWhatsAppResult(int res, Intent data) {
        String[] cur = pending.poll();
        if (cur == null) return;
        if (res == RESULT_OK) {
            addedCount++;
            nextPrompt();
            return;
        }
        pending.clear();
        String error = data == null ? null : data.getStringExtra("validation_error");
        if (error != null) {
            Ui.message(this, getString(R.string.whatsapp_refused, WhatsApp.label(this, cur[0])), error);
        } else {
            Ui.toast(this, getString(R.string.not_added, cur[2]));
        }
        reload();
    }

    // ---- export to a folder ----

    private void exportMenu() {
        if (pack == null) return;
        boolean canBuild = PackBuilder.plan(pack)[0] > 0;
        Sheet s = new Sheet(this).title(getString(R.string.export_as))
                .item(R.drawable.ic_image, getString(R.string.export_images), getString(R.string.export_images_summary),
                        () -> pickExportFolder(REQ_EXPORT_IMAGES));
        if (canBuild) {
            s.item(R.drawable.ic_folder, getString(R.string.export_wastickers), getString(R.string.export_wastickers_summary),
                    () -> pickExportFolder(REQ_EXPORT_WASTICKERS));
        } else {
            s.item(R.drawable.ic_folder, getString(R.string.export_wastickers), getString(R.string.error_min_stickers), () ->
                    Ui.toast(this, getString(R.string.error_min_stickers)));
        }
        s.show();
    }

    private void pickExportFolder(int req) {
        if (pack == null || pack.stickers.isEmpty()) {
            Ui.toast(this, getString(R.string.export_empty));
            return;
        }
        Ui.toastLong(this, getString(R.string.export_pick_hint));
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Uri last = Prefs.exportTree(this);
        if (last != null) {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    last, android.provider.DocumentsContract.getTreeDocumentId(last)));
        }
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            Ui.message(this, getString(R.string.error), e.getMessage());
        }
    }

    private void exportTo(Uri tree, boolean images) {
        Dialog d = Ui.progress(this, getString(R.string.exporting));
        Ui.IO.execute(() -> {
            String msg;
            try {
                Store.Pack p = Store.load(this, packId);
                if (p == null) throw new IOException(getString(R.string.pack_gone));
                if (images) {
                    String folder = Export.images(this, p, tree);
                    msg = getString(R.string.export_images_done, Ui.plural(this, R.plurals.stickers_count, p.stickers.size()), folder);
                } else {
                    PackBuilder.Result r = PackBuilder.build(this, p);
                    getContentResolver().notifyChange(StickerProvider.METADATA_URI, null);
                    List<File> files = PackBuilder.wastickers(this, r);
                    Export.files(this, files, tree);
                    List<String> names = new ArrayList<>();
                    for (File f : files) names.add(f.getName());
                    msg = getString(R.string.export_files_done, String.join("\n", names));
                }
            } catch (IOException | RuntimeException e) {
                msg = getString(R.string.export_failed, e.getMessage());
            }
            String m = msg;
            Ui.main(() -> {
                Ui.dismiss(d);
                Ui.message(this, getString(R.string.export), m);
                reload();
            });
        });
    }

    // ---- share as .wastickers ----

    private void share() {
        Dialog d = Ui.progress(this, getString(R.string.preparing));
        Ui.IO.execute(() -> {
            try {
                Store.Pack p = Store.load(this, packId);
                PackBuilder.Result r = PackBuilder.build(this, p);
                getContentResolver().notifyChange(StickerProvider.METADATA_URI, null);
                List<File> files = PackBuilder.wastickers(this, r);
                Ui.main(() -> {
                    Ui.dismiss(d);
                    ArrayList<Uri> uris = new ArrayList<>();
                    for (File f : files) uris.add(ShareProvider.uriFor(f));
                    Intent send;
                    if (uris.size() == 1) {
                        send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    } else {
                        send = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    }
                    send.setType("application/octet-stream");
                    ClipData clip = ClipData.newRawUri(files.get(0).getName(), uris.get(0));
                    for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                    send.setClipData(clip);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, getString(R.string.share_wastickers)));
                    reload();
                });
            } catch (IOException | RuntimeException e) {
                Ui.main(() -> {
                    Ui.dismiss(d);
                    Ui.message(this, getString(R.string.cannot_share), e.getMessage());
                });
            }
        });
    }
}
