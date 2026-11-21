package com.unl0cker.stickerbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class Ui {
    /** One thread for everything that writes packs, so writes never overlap. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {}

    static void main(Runnable r) { MAIN.post(r); }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /**
     * Draws behind the system bars: the header gets the status bar height as extra top padding
     * (so the green continues under the clock), the bottom view gets the navigation bar height.
     */
    static void edgeToEdge(Activity a, View header, View... bottoms) {
        a.getWindow().setDecorFitsSystemWindows(false);
        View root = a.findViewById(android.R.id.content);
        final int hTop = header == null ? 0 : header.getPaddingTop();
        final int[] base = new int[bottoms.length];
        for (int i = 0; i < bottoms.length; i++) base[i] = bottoms[i].getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            if (header != null) {
                header.setPadding(header.getPaddingLeft(), hTop + bars.top, header.getPaddingRight(), header.getPaddingBottom());
            }
            for (int i = 0; i < bottoms.length; i++) {
                View b = bottoms[i];
                b.setPadding(b.getPaddingLeft(), b.getPaddingTop(), b.getPaddingRight(), base[i] + bars.bottom);
            }
            return WindowInsets.CONSUMED;
        });
    }

    static void toast(Context c, String msg) { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); }

    static void toastLong(Context c, String msg) { Toast.makeText(c, msg, Toast.LENGTH_LONG).show(); }

    static void message(Activity a, String title, String msg) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton(android.R.string.ok, null).show();
    }

    static void confirm(Activity a, String title, String msg, String ok, Runnable onOk) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(ok, (d, w) -> onOk.run()).show();
    }

    /** A dialog with one text field. */
    static void prompt(Activity a, String title, String hint, String initial, int inputType, Consumer<String> onOk) {
        EditText input = new EditText(a);
        input.setHint(hint);
        input.setText(initial);
        input.setSingleLine(true);
        input.setInputType(inputType == 0 ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : inputType);
        input.setSelectAllOnFocus(true);
        input.setBackgroundResource(R.drawable.input_bg);
        int p = dp(a, 14);
        input.setPadding(p, p, p, p);
        input.setTextColor(a.getColor(R.color.text));
        input.setHintTextColor(a.getColor(R.color.text_secondary));
        FrameLayout box = new FrameLayout(a);
        int m = dp(a, 22);
        box.setPadding(m, dp(a, 8), m, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> onOk.accept(input.getText().toString().trim()))
                .create();
        d.setOnShowListener(x -> {
            input.requestFocus();
            input.postDelayed(() -> ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 120);
        });
        d.show();
    }

    /** A small non-cancelable "working" dialog. */
    static Dialog progress(Activity a, String msg) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(a, 24);
        row.setPadding(p, p, p, p);
        ProgressBar bar = new ProgressBar(a);
        bar.setIndeterminateTintList(a.getColorStateList(R.color.accent));
        row.addView(bar, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 36)));
        TextView t = new TextView(a);
        t.setText(msg);
        t.setTextSize(16);
        t.setTextColor(a.getColor(R.color.text));
        t.setPadding(dp(a, 18), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(a).setView(row).setCancelable(false).create();
        d.setCanceledOnTouchOutside(false);
        d.show();
        return d;
    }

    static void dismiss(Dialog d) {
        try {
            if (d != null && d.isShowing()) d.dismiss();
        } catch (IllegalArgumentException ignored) {
            // activity already gone
        }
    }

    static String plural(Context c, int id, int n) { return c.getResources().getQuantityString(id, n, n); }
}
