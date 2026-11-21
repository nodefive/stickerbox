package com.unl0cker.stickerbox;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A bottom sheet with an optional title, an optional header view and rows of actions. */
final class Sheet {
    private final Activity a;
    private final Dialog dialog;
    private final LinearLayout list;

    Sheet(Activity a) {
        this.a = a;
        dialog = new Dialog(a, R.style.SheetTheme);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.sheet_bg);
        root.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 14));
        View handle = new View(a);
        handle.setBackgroundResource(R.drawable.sheet_handle);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(a, 8);
        root.addView(handle, hp);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        root.addView(scroll);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            v.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 14) + nav);
            return insets;
        });
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDecorFitsSystemWindows(false);
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setNavigationBarColor(a.getColor(R.color.surface));
        }
    }

    Sheet title(String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(a.getColor(R.color.text_secondary));
        t.setPadding(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 24), Ui.dp(a, 10));
        list.addView(t);
        return this;
    }

    Sheet header(View v) {
        list.addView(v);
        return this;
    }

    Sheet item(int icon, String label, Runnable action) {
        return item(icon, null, label, null, false, action);
    }

    Sheet item(int icon, String label, String summary, Runnable action) {
        return item(icon, null, label, summary, false, action);
    }

    Sheet danger(int icon, String label, Runnable action) {
        return item(icon, null, label, null, true, action);
    }

    Sheet item(Drawable image, String label, String summary, Runnable action) {
        return item(0, image, label, summary, false, action);
    }

    private Sheet item(int icon, Drawable image, String label, String summary, boolean danger, Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(a, 56));
        row.setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 8));
        row.setBackgroundResource(resolveSelectable());
        ImageView iv = new ImageView(a);
        int size;
        if (image != null) {
            iv.setImageDrawable(image);
            iv.setBackgroundResource(R.drawable.tile);
            iv.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            size = Ui.dp(a, 44);
        } else {
            iv.setImageResource(icon);
            iv.setImageTintList(a.getColorStateList(danger ? R.color.danger : R.color.text_secondary));
            size = Ui.dp(a, 24);
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.rightMargin = Ui.dp(a, image != null ? 16 : 22);
        row.addView(iv, ip);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(a.getColor(danger ? R.color.danger : R.color.text));
        texts.addView(t);
        if (summary != null) {
            TextView s = new TextView(a);
            s.setText(summary);
            s.setTextSize(13);
            s.setTextColor(a.getColor(R.color.text_secondary));
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        list.addView(row);
        return this;
    }

    private int resolveSelectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    void show() {
        if (!a.isFinishing()) dialog.show();
    }
}
