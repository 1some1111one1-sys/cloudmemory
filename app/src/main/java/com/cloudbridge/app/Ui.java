package com.cloudbridge.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small view helpers shared by every screen. */
final class Ui {
    static final int INK = Color.parseColor("#1C1C1E");
    static final int SUB = Color.parseColor("#8E8E93");
    static final int ICON = Color.parseColor("#3C4043");
    static final int ACCENT = Color.parseColor("#1E88E5");
    static final int PAGE = Color.parseColor("#FFFFFF");
    static final int CARD = Color.parseColor("#F4F5F7");
    static final int LINE = Color.parseColor("#E6E8EB");
    static final int CHIP = Color.parseColor("#E8ECF3");
    static final int CHIP_TEXT = Color.parseColor("#5A6B85");
    static final int CURRENT = Color.parseColor("#FFF1CC");
    static final int CURRENT_TEXT = Color.parseColor("#F57C00");
    static final int SELECTED = Color.parseColor("#E3F0FD");
    static final int DANGER = Color.parseColor("#B3261E");

    private Ui() {
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, int sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A rounded filled background. */
    static void round(View v, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(v.getContext(), radiusDp));
        v.setBackground(d);
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpMargin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static LinearLayout.LayoutParams weight(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** A thin usage bar; {@code fraction} < 0 draws it empty. */
    static View usageBar(Context c, double fraction) {
        LinearLayout track = new LinearLayout(c);
        round(track, LINE, 4);
        float f = (float) Math.max(0, Math.min(1, fraction));
        if (f > 0) {
            View fill = new View(c);
            round(fill, f > 0.9f ? DANGER : ACCENT, 4);
            track.addView(fill, weight(0, MATCH, f));
        }
        if (f < 1) track.addView(new View(c), weight(0, MATCH, 1f - f));
        track.setLayoutParams(lpMargin(MATCH, dp(c, 8), 0, dp(c, 6), 0, dp(c, 4)));
        return track;
    }

    /** "12.3 GB free of 15 GB" and the like. */
    static String quotaText(com.cloudbridge.app.core.CloudQuota q) {
        if (q == null) return "This service does not report its storage size.";
        if (q.free >= 0 && q.total > 0) {
            return com.cloudbridge.app.core.Format.storage(q.free) + " free of " + com.cloudbridge.app.core.Format.storage(q.total);
        }
        if (q.free >= 0) return com.cloudbridge.app.core.Format.storage(q.free) + " free";
        if (q.used >= 0) return com.cloudbridge.app.core.Format.storage(q.used) + " used";
        return "This service does not report its storage size.";
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
