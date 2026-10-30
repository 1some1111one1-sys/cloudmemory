package com.cloudbridge.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudQuota;
import com.cloudbridge.app.core.Format;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Connected drives: each cloud with its free and total space, its photo backup settings, and the bridge for other apps. */
final class DrivesPage extends Page {
    private final Host host;
    private final Activity act;
    private final LinearLayout root;
    private final TopBar bar;
    private final LinearLayout list;
    private final LinearLayout bridgeBox;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, TextView> statusViews = new HashMap<>();
    private boolean ticking;

    DrivesPage(Host host) {
        this.host = host;
        this.act = host.activity();
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.PAGE);
        bar = new TopBar(act);
        bar.setTitle("Connected drives");
        bar.setNav(IconButton.Glyph.MENU, host::openDrawer);
        bar.setActions(bar.action(IconButton.Glyph.PLUS, host::addDrive));
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP));

        ScrollView scroll = new ScrollView(act);
        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 32));
        scroll.addView(page);
        root.addView(scroll, Ui.weight(Ui.MATCH, 0, 1f));

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list);

        Button add = new Button(act);
        add.setText("Add a drive");
        add.setAllCaps(false);
        add.setOnClickListener(v -> host.addDrive());
        page.addView(add, Ui.lpMargin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(act, 4), 0, 0));

        bridgeBox = new LinearLayout(act);
        bridgeBox.setOrientation(LinearLayout.VERTICAL);
        bridgeBox.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));
        Ui.round(bridgeBox, Color.parseColor("#E8F0FE"), 12);
        page.addView(bridgeBox, Ui.lpMargin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(act, 20), 0, 0));

        TextView notes = Ui.text(act, "Good to know\n"
                + "• Passwords and keys are stored encrypted with the Android Keystore.\n"
                + "• Google Drive needs a free client ID from your own Google Cloud project (the Add screen "
                + "explains how).\n"
                + "• MEGA signs in with your e-mail and password; its files are decrypted on your phone.\n"
                + "• TeraBox is unofficial and experimental: it has no public API, so it may stop working "
                + "whenever TeraBox changes its website.\n"
                + "• Your drives also appear in the system Files app and in every app's \"choose file\" screen.",
                13, false, Ui.SUB);
        notes.setPadding(0, Ui.dp(act, 24), 0, 0);
        page.addView(notes);
        refresh();
    }

    @Override
    View view() {
        return root;
    }

    @Override
    void onShow() {
        refresh();
        if (!ticking) {
            ticking = true;
            handler.postDelayed(tick, 2500);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!root.isShown()) {
                ticking = false;
                return;
            }
            updateStatuses();
            handler.postDelayed(this, 2500);
        }
    };

    // ------------------------------------------------------------------ the drives

    void refresh() {
        list.removeAllViews();
        statusViews.clear();
        List<Account> accounts = AccountStore.load(act);
        if (accounts.isEmpty()) {
            TextView none = Ui.text(act, "No drive is connected yet. Add Google Drive, MEGA, TeraBox, a WebDAV "
                    + "server (Nextcloud, ownCloud, Synology ...) or an S3 bucket, and it shows up in Files like a folder "
                    + "on your phone.", 14, false, Ui.SUB);
            none.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 16));
            list.addView(none);
        }
        for (Account a : accounts) list.addView(card(a));
        refreshBridge();
    }

    private View card(final Account a) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 10));
        Ui.round(card, Ui.CARD, 16);
        card.setLayoutParams(Ui.lpMargin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(act, 8), 0, Ui.dp(act, 8)));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        IconButton cloud = new IconButton(act, IconButton.Glyph.CLOUD);
        cloud.setColor(Ui.ACCENT);
        cloud.setClickable(false);
        head.addView(cloud, Ui.lp(Ui.dp(act, 40), Ui.dp(act, 40)));
        LinearLayout names = new LinearLayout(act);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(act, a.name, 17, true, Ui.INK));
        names.addView(Ui.text(act, a.summary(), 12, false, Ui.SUB));
        head.addView(names, Ui.weight(0, Ui.WRAP, 1f));
        card.addView(head);

        // storage: free and total
        final TextView space = Ui.text(act, "Checking storage...", 13, false, Ui.INK);
        final LinearLayout barHolder = new LinearLayout(act);
        barHolder.setOrientation(LinearLayout.VERTICAL);
        card.addView(barHolder, Ui.lpMargin(Ui.MATCH, Ui.WRAP, 0, Ui.dp(act, 10), 0, 0));
        barHolder.addView(space);
        showCached(a, space, barHolder);
        Clients.quota(act, a, false, (q, err) -> {
            barHolder.removeAllViews();
            if (err != null && q == null) {
                space.setText("Storage size unavailable: " + err);
                barHolder.addView(space);
                return;
            }
            space.setText(Ui.quotaText(q));
            barHolder.addView(space);
            double f = Format.fraction(q);
            if (f >= 0) barHolder.addView(Ui.usageBar(act, f));
        });

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(textButton("Browse", () -> host.goFiles(a)));
        buttons.addView(textButton("Edit", () -> host.editDrive(a)));
        buttons.addView(textButton("Remove", () -> host.removeDrive(a)));
        card.addView(buttons);

        card.addView(backupSection(a));
        return card;
    }

    private void showCached(Account a, TextView space, LinearLayout holder) {
        CloudQuota q = Clients.cachedQuota(a.id);
        if (q == null) return;
        space.setText(Ui.quotaText(q));
        double f = Format.fraction(q);
        if (f >= 0) holder.addView(Ui.usageBar(act, f));
    }

    private View textButton(String label, Runnable r) {
        TextView t = Ui.text(act, label, 14, true, Ui.ACCENT);
        t.setPadding(0, Ui.dp(act, 10), Ui.dp(act, 20), Ui.dp(act, 10));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    // ------------------------------------------------------------------ photo and video backup

    private View backupSection(final Account a) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        View line = new View(act);
        line.setBackgroundColor(Ui.LINE);
        box.addView(line, Ui.lpMargin(Ui.MATCH, 2, 0, Ui.dp(act, 4), 0, Ui.dp(act, 6)));

        Switch on = new Switch(act);
        on.setText("Back up all photos and videos");
        on.setTextSize(15);
        on.setTextColor(Ui.INK);
        on.setChecked(BackupSettings.enabled(act, a.id));
        box.addView(on, Ui.lp(Ui.MATCH, Ui.WRAP));

        final LinearLayout details = new LinearLayout(act);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(BackupSettings.enabled(act, a.id) ? View.VISIBLE : View.GONE);
        box.addView(details);

        Switch wifi = new Switch(act);
        wifi.setText("Wi-Fi only (no mobile data)");
        wifi.setTextSize(14);
        wifi.setTextColor(Ui.INK);
        wifi.setChecked(BackupSettings.wifiOnly(act, a.id));
        details.addView(wifi, Ui.lp(Ui.MATCH, Ui.WRAP));

        final TextView folder = Ui.text(act, "", 13, false, Ui.SUB);
        folder.setPadding(0, Ui.dp(act, 6), 0, 0);
        folder.setText("Saved in \"" + BackupSettings.folder(act, a.id) + "\" (tap to change)");
        folder.setOnClickListener(v -> chooseFolder(a, folder));
        details.addView(folder);

        TextView status = Ui.text(act, BackupSettings.status(act, a.id), 13, false, Ui.INK);
        status.setPadding(0, Ui.dp(act, 6), 0, 0);
        details.addView(status);
        statusViews.put(a.id, status);

        details.addView(textButton("Back up now", () -> host.askMediaAccess(() -> {
            BackupService.start(act, a.id);
            updateStatuses();
        })));
        TextView rule = Ui.text(act, "A photo that you delete from the cloud is not uploaded again. New photos are "
                + "uploaded every few hours and when you tap \"Back up now\".", 12, false, Ui.SUB);
        details.addView(rule);

        on.setOnCheckedChangeListener((b, checked) -> {
            BackupSettings.setEnabled(act, a.id, checked);
            details.setVisibility(checked ? View.VISIBLE : View.GONE);
            BackupJobService.update(act);
            if (checked) {
                host.askMediaAccess(() -> {
                    BackupService.start(act, a.id);
                    updateStatuses();
                });
            }
        });
        wifi.setOnCheckedChangeListener((b, checked) -> {
            BackupSettings.setWifiOnly(act, a.id, checked);
            BackupJobService.update(act);
        });
        return box;
    }

    private void updateStatuses() {
        for (Map.Entry<String, TextView> e : statusViews.entrySet()) {
            e.getValue().setText(BackupSettings.status(act, e.getKey()));
        }
    }

    private void chooseFolder(final Account a, final TextView label) {
        final android.widget.EditText field = new android.widget.EditText(act);
        field.setText(BackupSettings.folder(act, a.id));
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout holder = new LinearLayout(act);
        holder.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        holder.addView(field, Ui.lp(Ui.MATCH, Ui.WRAP));
        new android.app.AlertDialog.Builder(act)
                .setTitle("Backup folder in " + a.name)
                .setMessage("Photos go into this folder in the cloud, one sub-folder per album, apart from your other files.")
                .setView(holder)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String f = com.cloudbridge.app.core.Paths.normalize("/" + field.getText().toString().trim());
                    if (f.equals("/")) {
                        toast("Choose a folder name, not the whole cloud.");
                        return;
                    }
                    BackupSettings.setFolder(act, a.id, f);
                    label.setText("Saved in \"" + f + "\" (tap to change)");
                }).show();
    }

    // ------------------------------------------------------------------ the bridge for other file managers

    private void refreshBridge() {
        bridgeBox.removeAllViews();
        boolean on = BridgeSettings.enabled(act);
        final int port = BridgeService.port();
        bridgeBox.addView(Ui.text(act, "Use your clouds in other file managers", 17, true, Ui.INK));
        TextView what = Ui.text(act, "Many file managers can connect to an FTP or WebDAV server. Switch this on and "
                + "add the address below in your file manager: every cloud then shows up as a folder. It works only on "
                + "this phone, needs no root, and covers every cloud type, TeraBox included.", 13, false, Ui.SUB);
        what.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
        bridgeBox.addView(what);

        Switch toggle = new Switch(act);
        toggle.setText("Share my clouds with other file managers");
        toggle.setTextColor(Ui.INK);
        toggle.setChecked(on);
        toggle.setOnCheckedChangeListener((b, wanted) -> {
            if (wanted == BridgeSettings.enabled(act)) return;
            if (wanted) {
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                    act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
                BridgeService.start(act);
            } else {
                BridgeService.stop(act);
            }
            refreshBridge();
            handler.postDelayed(this::refreshBridge, 800);
        });
        bridgeBox.addView(toggle);
        if (!on) return;

        if (port == 0) {
            String err = BridgeService.lastError();
            bridgeBox.addView(Ui.text(act, err.isEmpty() ? "Starting..." : "Could not start: " + err, 13, false, Ui.SUB));
            return;
        }
        final String password = BridgeSettings.password(act);
        final String address = "http://127.0.0.1:" + port + "/";
        TextView details = Ui.text(act, "Server (host): 127.0.0.1\nUser name: " + BridgeSettings.USER + "\nPassword: " + password
                + "\n\nFTP (for built-in file managers)\nPort: " + BridgeService.ftpPort()
                + "\n\nWebDAV (for Solid Explorer, X-plore ...)\nPort: " + port + "\nAddress: " + address, 14, false, Ui.INK);
        details.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4));
        details.setTextIsSelectable(true);
        bridgeBox.addView(details);
        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(textButton("Copy address", () -> copy("Address", address)));
        buttons.addView(textButton("Copy password", () -> copy("Password", password)));
        buttons.addView(textButton("New password", () -> {
            BridgeSettings.newPassword(act);
            BridgeService.restart(act);   // so the running server uses the new password
            refreshBridge();
            handler.postDelayed(this::refreshBridge, 800);
        }));
        bridgeBox.addView(buttons);
        TextView how = Ui.text(act, "In your file manager look for Remote, Network or FTP (built-in managers such as Xiaomi's) "
                + "or for a WebDAV option (Solid Explorer, X-plore ...). Add a new server with the host, port, user name "
                + "and password above, choose plain FTP / HTTP (no SSL, no TLS) and type the host as 127.0.0.1, not localhost. "
                + "Large files are downloaded to the phone before they start to open. Keep the notification "
                + "that appears: it is what keeps the bridge alive.", 12, false, Ui.SUB);
        how.setPadding(0, Ui.dp(act, 6), 0, 0);
        bridgeBox.addView(how);
    }

    private void copy(String what, String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(what, text));
        toast(what + " copied");
    }

    private void toast(String text) {
        Toast.makeText(act, text, Toast.LENGTH_LONG).show();
    }
}
