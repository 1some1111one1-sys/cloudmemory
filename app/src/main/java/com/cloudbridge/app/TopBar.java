package com.cloudbridge.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The bar at the top of every page: a navigation icon, the title, and a few action icons. */
final class TopBar extends LinearLayout {
    private final IconButton nav;
    private final TextView title;
    private final LinearLayout actions;

    TopBar(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        int h = Ui.dp(c, 56);
        nav = new IconButton(c, IconButton.Glyph.MENU);
        addView(nav, new LayoutParams(h, h));
        title = Ui.text(c, "", 20, true, Ui.INK);
        title.setSingleLine(true);
        addView(title, Ui.weight(0, h, 1f));
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actions = new LinearLayout(c);
        actions.setOrientation(HORIZONTAL);
        addView(actions, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h));
        setBackgroundColor(Ui.PAGE);
    }

    void setNav(IconButton.Glyph g, Runnable onClick) {
        nav.setGlyph(g);
        nav.setOnClickListener(v -> onClick.run());
    }

    void setTitle(CharSequence t) {
        title.setText(t);
    }

    /** Replaces the action icons. */
    void setActions(IconButton... buttons) {
        actions.removeAllViews();
        int s = Ui.dp(getContext(), 48);
        for (IconButton b : buttons) actions.addView(b, new LayoutParams(s, Ui.dp(getContext(), 56)));
    }

    IconButton action(IconButton.Glyph g, Runnable onClick) {
        IconButton b = new IconButton(getContext(), g);
        b.setOnClickListener((View v) -> onClick.run());
        return b;
    }
}
