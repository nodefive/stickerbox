package com.unl0cker.stickerbox;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The green WhatsApp-style top bar (layout/header.xml). */
final class Header {
    final View root;
    final ImageView nav;
    final TextView title;
    final TextView subtitle;
    final LinearLayout actions;
    final LinearLayout tabs;
    private final Activity a;

    Header(Activity a) {
        this.a = a;
        root = a.findViewById(R.id.header);
        nav = a.findViewById(R.id.header_nav);
        title = a.findViewById(R.id.header_title);
        subtitle = a.findViewById(R.id.header_subtitle);
        actions = a.findViewById(R.id.header_actions);
        tabs = a.findViewById(R.id.header_tabs);
    }

    Header nav(int icon, String desc, Runnable r) {
        nav.setVisibility(View.VISIBLE);
        nav.setImageResource(icon);
        nav.setContentDescription(desc);
        nav.setOnClickListener(v -> r.run());
        ((LinearLayout.LayoutParams) ((View) title.getParent()).getLayoutParams()).setMarginStart(Ui.dp(a, 8));
        return this;
    }

    Header back() {
        return nav(R.drawable.ic_back, a.getString(R.string.back), a::finish);
    }

    Header title(CharSequence t) {
        title.setText(t);
        return this;
    }

    Header subtitle(CharSequence s) {
        subtitle.setText(s);
        subtitle.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        return this;
    }

    ImageView action(int icon, String desc, Runnable r) {
        ImageView iv = (ImageView) a.getLayoutInflater().inflate(R.layout.header_action, actions, false);
        iv.setImageResource(icon);
        iv.setContentDescription(desc);
        iv.setTooltipText(desc);
        iv.setOnClickListener(v -> r.run());
        actions.addView(iv);
        return iv;
    }

    void clearActions() { actions.removeAllViews(); }
}
