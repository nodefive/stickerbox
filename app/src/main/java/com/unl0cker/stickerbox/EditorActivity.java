package com.unl0cker.stickerbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

public class EditorActivity extends Activity implements EditorView.Listener {
    static final String EXTRA_PACK = "pack";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_STICKER = "sticker";

    private static final int[] TEXT_COLORS = {Color.WHITE, Color.BLACK, 0xFFFFD54F, 0xFF25D366, 0xFFFF5252, 0xFF40C4FF, 0xFFE040FB};
    private static final int[] OUTLINES = {0, 6, 12, 20};

    private EditorView editor;
    private LinearLayout options;
    private LinearLayout tools;
    private TextView hint;
    private String packId;
    private String stickerId;
    private String sourcePath;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_editor);
        packId = getIntent().getStringExtra(EXTRA_PACK);
        stickerId = getIntent().getStringExtra(EXTRA_STICKER);
        sourcePath = getIntent().getStringExtra(EXTRA_SOURCE);

        Header h = new Header(this)
                .nav(R.drawable.ic_close, getString(R.string.cancel), this::onBackPressed)
                .title(getString(stickerId != null ? R.string.edit_sticker : R.string.new_sticker));
        h.action(R.drawable.ic_check, getString(R.string.save), this::save);
        editor = findViewById(R.id.editor);
        options = findViewById(R.id.options);
        tools = findViewById(R.id.tools);
        hint = findViewById(R.id.hint);
        editor.setListener(this);
        Ui.edgeToEdge(this, h.root, tools);

        addTool(R.drawable.ic_move, R.string.tool_move, EditorView.TOOL_MOVE);
        addTool(R.drawable.ic_cut, R.string.tool_cut, EditorView.TOOL_CUT);
        addTool(R.drawable.ic_text, R.string.tool_text, EditorView.TOOL_TEXT);
        addTool(R.drawable.ic_outline, R.string.tool_outline, EditorView.TOOL_OUTLINE);
        selectTool(EditorView.TOOL_MOVE);
        load();
    }

    private void load() {
        Dialog d = Ui.progress(this, getString(R.string.loading));
        Ui.IO.execute(() -> {
            Bitmap b = null;
            String err = null;
            try {
                if (stickerId != null) {
                    Store.Pack p = Store.load(this, packId);
                    Store.Sticker s = p == null ? null : p.find(stickerId);
                    if (s == null) throw new IOException(getString(R.string.sticker_gone));
                    b = Images.decode(p.file(s), 2048);
                } else {
                    b = Images.decode(new File(sourcePath), 2048);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                err = e.getMessage();
            }
            Bitmap bitmap = b;
            String error = err;
            Ui.main(() -> {
                Ui.dismiss(d);
                if (bitmap == null) {
                    Ui.toastLong(this, getString(R.string.cannot_open_image) + (error == null ? "" : ": " + error));
                    finish();
                    return;
                }
                boolean exact = stickerId != null && bitmap.getWidth() == bitmap.getHeight();
                editor.setSource(bitmap, exact);
            });
        });
    }

    @Override
    public void onBackPressed() {
        if (editor.isChanged()) {
            Ui.confirm(this, getString(R.string.discard_q), getString(R.string.discard_body), getString(R.string.discard), this::finish);
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A picked image copied into the cache is not needed once the editor is closed.
        if (isFinishing() && sourcePath != null && sourcePath.startsWith(getCacheDir().getPath())) {
            new File(sourcePath).delete();
        }
    }

    // ---- tools ----

    private void addTool(int icon, int label, int tool) {
        View v = getLayoutInflater().inflate(R.layout.tool_button, tools, false);
        ((ImageView) v.findViewById(R.id.tool_icon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.tool_label)).setText(label);
        v.setTag(tool);
        v.setOnClickListener(x -> selectTool(tool));
        tools.addView(v);
    }

    private void selectTool(int tool) {
        editor.setTool(tool);
        for (int i = 0; i < tools.getChildCount(); i++) {
            View v = tools.getChildAt(i);
            boolean on = ((int) v.getTag()) == tool;
            int color = getColor(on ? R.color.accent : R.color.text_secondary);
            ((ImageView) v.findViewById(R.id.tool_icon)).setImageTintList(android.content.res.ColorStateList.valueOf(color));
            ((TextView) v.findViewById(R.id.tool_label)).setTextColor(color);
        }
        buildOptions();
    }

    private void buildOptions() {
        options.removeAllViews();
        switch (editor.tool()) {
            case EditorView.TOOL_MOVE:
                hint.setText(R.string.hint_move);
                chip(getString(R.string.fit), false, () -> editor.fitImage(false));
                chip(getString(R.string.fill), false, () -> editor.fitImage(true));
                chip(getString(R.string.rotate), false, editor::rotate90);
                break;
            case EditorView.TOOL_CUT:
                hint.setText(editor.waitingForDrawing() ? R.string.hint_cut_draw : R.string.hint_cut);
                chip(getString(R.string.cut_none), editor.cut() == EditorView.CUT_NONE && !editor.waitingForDrawing(), () -> {
                    editor.setCut(EditorView.CUT_NONE);
                    buildOptions();
                });
                chip(getString(R.string.cut_circle), editor.cut() == EditorView.CUT_CIRCLE && !editor.waitingForDrawing(), () -> {
                    editor.setCut(EditorView.CUT_CIRCLE);
                    buildOptions();
                });
                chip(getString(R.string.cut_free), editor.cut() == EditorView.CUT_FREE || editor.waitingForDrawing(), () -> {
                    editor.setCut(EditorView.CUT_FREE);
                    buildOptions();
                });
                break;
            case EditorView.TOOL_TEXT: {
                EditorView.Caption c = editor.caption();
                boolean has = c != null && c.text != null && !c.text.isEmpty();
                hint.setText(has ? R.string.hint_text_edit : R.string.hint_text_add);
                chip(getString(has ? R.string.edit_text : R.string.add_text), false, this::editText);
                if (has) {
                    for (int color : TEXT_COLORS) {
                        swatch(color, c.color == color, () -> {
                            c.color = color;
                            editor.captionChanged();
                            buildOptions();
                        });
                    }
                    chip(getString(R.string.remove), false, () -> {
                        editor.setCaption(null);
                        buildOptions();
                    });
                }
                break;
            }
            case EditorView.TOOL_OUTLINE: {
                hint.setText(R.string.hint_outline);
                int[] labels = {R.string.outline_none, R.string.outline_thin, R.string.outline_medium, R.string.outline_thick};
                for (int i = 0; i < OUTLINES.length; i++) {
                    int px = OUTLINES[i];
                    chip(getString(labels[i]), editor.outline() == px, () -> {
                        editor.setOutline(px, editor.outlineColor());
                        buildOptions();
                    });
                }
                if (editor.outline() > 0) {
                    for (int color : new int[]{Color.WHITE, Color.BLACK, 0xFF25D366}) {
                        swatch(color, editor.outlineColor() == color, () -> {
                            editor.setOutline(editor.outline(), color);
                            buildOptions();
                        });
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void chip(String label, boolean selected, Runnable r) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(getColor(R.color.text));
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(R.drawable.chip_toggle);
        t.setSelected(selected);
        int p = Ui.dp(this, 16);
        t.setPadding(p, 0, p, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        lp.setMarginEnd(Ui.dp(this, 8));
        t.setOnClickListener(v -> r.run());
        options.addView(t, lp);
    }

    private void swatch(int color, boolean selected, Runnable r) {
        FrameLayout box = new FrameLayout(this);
        int size = Ui.dp(this, 36);
        if (selected) box.setBackgroundResource(R.drawable.swatch_ring);
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(Ui.dp(this, 1), getColor(R.color.divider));
        dot.setBackground(g);
        int inner = Ui.dp(this, 26);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(inner, inner);
        dp.gravity = android.view.Gravity.CENTER;
        box.addView(dot, dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Ui.dp(this, 6));
        box.setOnClickListener(v -> r.run());
        options.addView(box, lp);
    }

    private void editText() {
        EditorView.Caption current = editor.caption();
        EditText input = new EditText(this);
        input.setText(current == null ? "" : current.text);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(3);
        input.setBackgroundResource(R.drawable.input_bg);
        int p = Ui.dp(this, 14);
        input.setPadding(p, p, p, p);
        input.setTextColor(getColor(R.color.text));
        input.setHint(R.string.text_hint);
        input.setHintTextColor(getColor(R.color.text_secondary));
        FrameLayout box = new FrameLayout(this);
        int m = Ui.dp(this, 22);
        box.setPadding(m, Ui.dp(this, 8), m, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(R.string.tool_text).setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (x, y) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        editor.setCaption(null);
                    } else {
                        EditorView.Caption c = current != null ? current : new EditorView.Caption();
                        c.text = text;
                        editor.setCaption(c);
                    }
                    buildOptions();
                }).create();
        d.setOnShowListener(x -> {
            input.requestFocus();
            input.postDelayed(() -> ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(input, 0), 120);
        });
        d.show();
    }

    @Override
    public void onTextTap(boolean hasText) {
        editText();
    }

    @Override
    public void onChanged() {
        if (editor.tool() == EditorView.TOOL_CUT && !editor.waitingForDrawing()) {
            // a finished freehand cut: show it as the selected option
            hint.setText(R.string.hint_cut);
            if (options.getChildCount() == 3) {
                options.getChildAt(0).setSelected(editor.cut() == EditorView.CUT_NONE);
                options.getChildAt(1).setSelected(editor.cut() == EditorView.CUT_CIRCLE);
                options.getChildAt(2).setSelected(editor.cut() == EditorView.CUT_FREE);
            }
        }
    }

    // ---- save ----

    private void save() {
        if (!editor.hasImage()) return;
        Bitmap out = editor.render();
        Dialog d = Ui.progress(this, getString(R.string.saving));
        Ui.IO.execute(() -> {
            String error = null;
            boolean duplicate = false;
            try {
                Store.Pack p = Store.load(this, packId);
                if (p == null) throw new IOException(getString(R.string.pack_gone));
                File tmp = new File(getCacheDir(), Store.newId() + ".png");
                Images.writePng(out, tmp);
                if (stickerId != null) {
                    Store.Sticker s = p.find(stickerId);
                    if (s == null) throw new IOException(getString(R.string.sticker_gone));
                    Store.replaceImage(p, s, tmp);
                } else {
                    duplicate = Store.addFile(p, tmp, true) == null;
                }
                p.touch();
                Store.save(p);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            String err = error;
            boolean dup = duplicate;
            Ui.main(() -> {
                Ui.dismiss(d);
                if (err != null) {
                    Ui.message(this, getString(R.string.error), err);
                    return;
                }
                if (dup) Ui.toast(this, getString(R.string.already_in_pack));
                finish();
            });
        });
    }
}
