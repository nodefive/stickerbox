package com.unl0cker.stickerbox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends Activity {
    private static final int REQ_BACKUP = 1;
    private static final int REQ_RESTORE = 2;
    private static final int REQ_TREE_WA = 3;
    private static final int REQ_TREE_W4B = 4;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        Header h = new Header(this).back().title(getString(R.string.settings));
        Ui.edgeToEdge(this, h.root, findViewById(R.id.scroll));

        findViewById(R.id.row_author).setOnClickListener(v ->
                Ui.prompt(this, getString(R.string.default_author), getString(R.string.author_hint), Prefs.author(this), 0, a -> {
                    Prefs.setAuthor(this, a);
                    bind();
                }));
        findViewById(R.id.row_wa).setOnClickListener(v -> folderRow(WhatsApp.WA, REQ_TREE_WA));
        findViewById(R.id.row_w4b).setOnClickListener(v -> folderRow(WhatsApp.W4B, REQ_TREE_W4B));
        findViewById(R.id.row_backup).setOnClickListener(v -> {
            String name = "stickerbox-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date()) + ".zip";
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP);
        });
        findViewById(R.id.row_restore).setOnClickListener(v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}),
                        REQ_RESTORE));

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep empty
        }
        ((TextView) findViewById(R.id.about)).setText(getString(R.string.about_text, version));
        bind();
    }

    private void bind() {
        String author = Prefs.author(this);
        ((TextView) findViewById(R.id.author_value)).setText(author.isEmpty()
                ? getString(R.string.author_default, getString(R.string.app_name)) : author);
        bindFolder(WhatsApp.WA, R.id.row_wa, R.id.wa_value);
        bindFolder(WhatsApp.W4B, R.id.row_w4b, R.id.w4b_value);
    }

    private void bindFolder(String pkg, int row, int value) {
        boolean installed = WhatsApp.installed(this, pkg);
        findViewById(row).setVisibility(installed ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(value)).setText(WaPickerActivity.hasAccess(this, pkg)
                ? getString(R.string.folder_allowed) : getString(R.string.folder_not_allowed));
    }

    private void folderRow(String pkg, int req) {
        if (WaPickerActivity.hasAccess(this, pkg)) {
            new Sheet(this).title(getString(R.string.folder_of, WhatsApp.label(this, pkg)))
                    .item(R.drawable.ic_folder, getString(R.string.choose_again), () ->
                            startActivityForResult(WaPickerActivity.grantIntent(pkg), req))
                    .danger(R.drawable.ic_close, getString(R.string.remove_access), () -> {
                        WaPickerActivity.revoke(this, pkg);
                        bind();
                    })
                    .show();
        } else {
            startActivityForResult(WaPickerActivity.grantIntent(pkg), req);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (req) {
            case REQ_TREE_WA:
            case REQ_TREE_W4B: {
                String err = WaPickerActivity.onGranted(this, req == REQ_TREE_WA ? WhatsApp.WA : WhatsApp.W4B, uri);
                if (err != null) Ui.message(this, getString(R.string.wrong_folder), err);
                bind();
                break;
            }
            case REQ_BACKUP:
                backup(uri);
                break;
            case REQ_RESTORE:
                Ui.confirm(this, getString(R.string.restore_q), getString(R.string.restore_body), getString(R.string.restore),
                        () -> restore(uri));
                break;
            default:
                break;
        }
    }

    private void backup(Uri uri) {
        Dialog d = Ui.progress(this, getString(R.string.backing_up));
        Ui.IO.execute(() -> {
            String msg;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("cannot write the file");
                int n = Backup.export(this, out);
                msg = getString(R.string.backup_done, Ui.plural(this, R.plurals.packs_count, n));
            } catch (IOException | RuntimeException e) {
                msg = getString(R.string.backup_failed, e.getMessage());
            }
            String m = msg;
            Ui.main(() -> {
                Ui.dismiss(d);
                Ui.message(this, getString(R.string.backup), m);
            });
        });
    }

    private void restore(Uri uri) {
        Dialog d = Ui.progress(this, getString(R.string.restoring));
        Ui.IO.execute(() -> {
            String msg;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot read the file");
                List<String> names = Backup.restore(this, in);
                Thumbs.forget();
                msg = names.isEmpty() ? getString(R.string.restore_nothing)
                        : getString(R.string.restore_done, Ui.plural(this, R.plurals.packs_count, names.size()), String.join(", ", names));
            } catch (IOException | RuntimeException e) {
                msg = getString(R.string.restore_failed, e.getMessage());
            }
            String m = msg;
            Ui.main(() -> {
                Ui.dismiss(d);
                Ui.message(this, getString(R.string.restore), m);
                bind();
            });
        });
    }
}
