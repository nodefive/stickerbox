package com.unl0cker.stickerbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.List;

/**
 * WhatsApp's sticker app interface (see WhatsApp/stickers on GitHub). Readable only by holders of
 * com.whatsapp.sticker.READ. It serves the parts last built by {@link PackBuilder}, never the
 * editable originals.
 *
 *   metadata                         all parts
 *   metadata/&lt;id&gt;                    one part
 *   stickers/&lt;id&gt;                    sticker files and emojis of a part
 *   stickers_asset/&lt;id&gt;/&lt;file&gt;      a sticker or the tray icon
 */
public final class StickerProvider extends ContentProvider {
    static final String AUTHORITY = "com.unl0cker.stickerbox.stickercontentprovider";
    static final Uri METADATA_URI = Uri.parse("content://" + AUTHORITY + "/metadata");

    private static final String[] PACK_COLUMNS = {
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
    };
    private static final String[] STICKER_COLUMNS = {
            "sticker_file_name", "sticker_emoji", "sticker_accessibility_text"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 1 && "metadata".equals(seg.get(0))) {
            return packCursor(uri, PackBuilder.allParts(getContext()));
        }
        if (seg.size() == 2 && "metadata".equals(seg.get(0))) {
            PackBuilder.Part p = PackBuilder.part(getContext(), seg.get(1));
            return packCursor(uri, p == null ? Collections.emptyList() : Collections.singletonList(p));
        }
        if (seg.size() == 2 && "stickers".equals(seg.get(0))) {
            MatrixCursor c = new MatrixCursor(STICKER_COLUMNS);
            PackBuilder.Part p = PackBuilder.part(getContext(), seg.get(1));
            if (p != null) {
                for (int i = 0; i < p.files.size(); i++) c.addRow(new Object[]{p.files.get(i), p.emojis.get(i), null});
            }
            c.setNotificationUri(getContext().getContentResolver(), uri);
            return c;
        }
        throw new IllegalArgumentException("Unknown URI: " + uri);
    }

    private Cursor packCursor(Uri uri, List<PackBuilder.Part> parts) {
        MatrixCursor c = new MatrixCursor(PACK_COLUMNS);
        for (PackBuilder.Part p : parts) {
            if (!p.dir.isDirectory()) continue;
            c.addRow(new Object[]{p.id, p.name, p.publisher, PackBuilder.TRAY_FILE,
                    "", "", "", "", "", "", p.version, 0, p.animated ? 1 : 0});
        }
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 3 || !"stickers_asset".equals(seg.get(0))) throw new FileNotFoundException(uri.toString());
        PackBuilder.Part p = PackBuilder.part(getContext(), seg.get(1));
        String name = seg.get(2);
        // Only names listed in the build index, so nothing else in the app's storage is reachable.
        if (p == null || !(PackBuilder.TRAY_FILE.equals(name) || p.files.contains(name))) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = new File(p.dir, name);
        return new AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length());
    }

    @Override
    public String getType(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg.isEmpty()) return null;
        switch (seg.get(0)) {
            case "metadata":
                return (seg.size() == 1 ? "vnd.android.cursor.dir/vnd." : "vnd.android.cursor.item/vnd.") + AUTHORITY + ".metadata";
            case "stickers":
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".stickers";
            case "stickers_asset":
                return seg.size() == 3 && PackBuilder.TRAY_FILE.equals(seg.get(2)) ? "image/png" : "image/webp";
            default:
                return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
