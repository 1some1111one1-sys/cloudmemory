package com.cloudbridge.app;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudQuota;
import com.cloudbridge.app.core.Format;
import java.util.List;

/** The home page: how full the phone and each drive are, how the photo backup is doing, and shortcuts. */
final class HomePage extends Page {
    private final Host host;
    private final Activity act;
    private final LinearLayout root;
    private final LinearLayout content;

    HomePage(Host host) {
        this.host = host;
        this.act = host.activity();
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.PAGE);
        TopBar bar = new TopBar(act);
        bar.setTitle("CloudBridge");
        bar.setNav(IconButton.Glyph.MENU, host::openDrawer);
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP));
        ScrollView scroll = new ScrollView(act);
        content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 32));
        scroll.addView(content);
        root.addView(scroll, Ui.weight(Ui.MATCH, 0, 1f));
        refresh();
    }

    @Override
    View view() {
        return root;
    }

    @Override
    void onShow() {
        refresh();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 14));
        Ui.round(c, Ui.CARD, 16);
        c.setLayoutParams(Ui.lpMargin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(act, 6), 0, Ui.dp(act, 6)));
        return c;
    }

    private void refresh() {
        content.removeAllViews();

        // the phone
        LinearLayout phone = card();
        phone.setOnClickListener(v -> host.goFiles(null));
        phone.addView(Ui.text(act, "Internal storage", 17, true, Ui.INK));
        CloudQuota q = Clients.phone().quota();
        final TextView phoneText = Ui.text(act, Ui.quotaText(q), 13, false, Ui.INK);
        phoneText.setPadding(0, Ui.dp(act, 6), 0, 0);
        phone.addView(phoneText);
        double f = Format.fraction(q);
        if (f >= 0) phone.addView(Ui.usageBar(act, f));
        content.addView(phone);

        // the drives
        List<Account> accounts = AccountStore.load(act);
        content.addView(heading("Connected drives"));
        if (accounts.isEmpty()) {
            LinearLayout none = card();
            none.addView(Ui.text(act, "No drive is connected yet.", 14, false, Ui.SUB));
            none.addView(link("Add a drive", host::addDrive));
            content.addView(none);
        }
        for (final Account a : accounts) {
            LinearLayout c = card();
            c.setOnClickListener(v -> host.goFiles(a));
            c.addView(Ui.text(act, a.name, 16, true, Ui.INK));
            final TextView space = Ui.text(act, "Checking storage...", 13, false, Ui.INK);
            space.setPadding(0, Ui.dp(act, 4), 0, 0);
            final LinearLayout holder = new LinearLayout(act);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.addView(space);
            c.addView(holder);
            Clients.quota(act, a, false, (cq, err) -> {
                holder.removeAllViews();
                space.setText(err != null && cq == null ? "Storage size unavailable" : Ui.quotaText(cq));
                holder.addView(space);
                double fr = Format.fraction(cq);
                if (fr >= 0) holder.addView(Ui.usageBar(act, fr));
            });
            content.addView(c);
        }

        // the backup
        content.addView(heading("Photo and video backup"));
        LinearLayout backup = card();
        boolean any = false;
        for (Account a : accounts) {
            if (!BackupSettings.enabled(act, a.id)) continue;
            any = true;
            backup.addView(Ui.text(act, a.name, 15, true, Ui.INK));
            TextView s = Ui.text(act, BackupSettings.status(act, a.id)
                    + (BackupSettings.wifiOnly(act, a.id) ? "\nWi-Fi only" : "\nWi-Fi and mobile data"), 13, false, Ui.SUB);
            s.setPadding(0, 0, 0, Ui.dp(act, 8));
            backup.addView(s);
        }
        if (!any) {
            backup.addView(Ui.text(act, "Off. Choose a drive and switch on \"Back up all photos and videos\" to keep "
                    + "your pictures safe in the cloud.", 14, false, Ui.SUB));
            backup.addView(link("Set up backup", host::goDrives));
        }
        content.addView(backup);

        // shortcuts
        content.addView(heading("Shortcuts"));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(shortcut(IconButton.Glyph.FOLDER, "Files", () -> host.goFiles(null)), Ui.weight(0, Ui.WRAP, 1f));
        row.addView(shortcut(IconButton.Glyph.CLOUD, "Drives", host::goDrives), Ui.weight(0, Ui.WRAP, 1f));
        row.addView(shortcut(IconButton.Glyph.PLUS, "Add drive", host::addDrive), Ui.weight(0, Ui.WRAP, 1f));
        content.addView(row);
    }

    private TextView heading(String text) {
        TextView t = Ui.text(act, text, 14, true, Ui.SUB);
        t.setPadding(Ui.dp(act, 4), Ui.dp(act, 16), 0, Ui.dp(act, 2));
        return t;
    }

    private TextView link(String text, Runnable r) {
        TextView t = Ui.text(act, text, 14, true, Ui.ACCENT);
        t.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 2));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private View shortcut(IconButton.Glyph g, String label, final Runnable r) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        IconButton b = new IconButton(act, g);
        b.setColor(Ui.ACCENT);
        b.setClickable(false);
        c.addView(b, Ui.lp(Ui.dp(act, 44), Ui.dp(act, 44)));
        TextView t = Ui.text(act, label, 13, false, Ui.INK);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        c.setOnClickListener(v -> r.run());
        return c;
    }
}
