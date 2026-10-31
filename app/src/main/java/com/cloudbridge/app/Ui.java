package com.cloudbridge.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small view helpers shared by every screen. */
final class Ui {
    // Colours; filled in by apply() for the light or the dark look.
    static int INK, SUB, ICON, ACCENT, PAGE, CARD, LINE, CHIP, CHIP_TEXT, CURRENT, CURRENT_TEXT, SELECTED, DANGER, INFO, HEADER;
    static boolean dark;

    static {
        apply(false);
    }

    /** Reads the saved choice (the phone's own setting until the person chooses) and sets the colours. */
    static void init(Context c) {
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        apply(c.getSharedPreferences("cloudbridge_ui", 0).getBoolean("dark", night));
    }

    static void setDark(Context c, boolean on) {
        c.getSharedPreferences("cloudbridge_ui", 0).edit().putBoolean("dark", on).commit();
        apply(on);
    }

    static void apply(boolean on) {
        dark = on;
        INK = Color.parseColor(on ? "#ECECEF" : "#1C1C1E");
        SUB = Color.parseColor(on ? "#A0A0A8" : "#8E8E93");
        ICON = Color.parseColor(on ? "#DADCE0" : "#3C4043");
        ACCENT = Color.parseColor(on ? "#64B5F6" : "#1E88E5");
        PAGE = Color.parseColor(on ? "#121212" : "#FFFFFF");
        CARD = Color.parseColor(on ? "#1F2023" : "#F4F5F7");
        LINE = Color.parseColor(on ? "#34363A" : "#E6E8EB");
        CHIP = Color.parseColor(on ? "#2B303B" : "#E8ECF3");
        CHIP_TEXT = Color.parseColor(on ? "#AEBBD3" : "#5A6B85");
        CURRENT = Color.parseColor(on ? "#4A3B14" : "#FFF1CC");
        CURRENT_TEXT = Color.parseColor(on ? "#FFB74D" : "#F57C00");
        SELECTED = Color.parseColor(on ? "#1D3A57" : "#E3F0FD");
        DANGER = Color.parseColor(on ? "#F2B8B5" : "#B3261E");
        INFO = Color.parseColor(on ? "#1B2A3F" : "#E8F0FE");
        HEADER = Color.parseColor(on ? "#1565C0" : "#1E88E5");
    }

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
