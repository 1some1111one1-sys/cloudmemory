package com.cloudbridge.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.PhotoBackup;
import java.util.List;

/** Does the actual photo backup of one drive; used by the foreground service and by the scheduled job. */
final class BackupRunner {
    interface Cancel {
        boolean cancelled();
    }

    interface Progress {
        void text(String text);
    }

    private static final Object LOCK = new Object();

    private BackupRunner() {
    }

    /** True when the phone is connected to an unmetered network (normally Wi-Fi). */
    static boolean onUnmetered(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    static boolean online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** Backs up every drive that has backup switched on. */
    static void runAll(Context c, Cancel cancel, Progress progress) {
        for (Account a : AccountStore.load(c)) {
            if (cancel.cancelled()) return;
            if (BackupSettings.enabled(c, a.id)) run(c, a, cancel, progress);
        }
    }

    /** Backs up one drive now (whether or not its switch is on). One backup runs at a time. */
    static void run(Context c, Account a, final Cancel cancel, final Progress progress) {
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            BackupSettings.setRunning(app, a.id, true);
            try {
                runLocked(app, a, cancel, progress);
            } finally {
                BackupSettings.setRunning(app, a.id, false);
            }
        }
    }

    private static void runLocked(Context app, Account a, final Cancel cancel, final Progress progress) {
        if (!MediaScan.hasPermission(app)) {
            BackupSettings.setNote(app, a.id, "Allow access to photos and videos to start the backup.");
            return;
        }
        if (BackupSettings.wifiOnly(app, a.id) ? !onUnmetered(app) : !online(app)) {
            BackupSettings.setNote(app, a.id, BackupSettings.wifiOnly(app, a.id)
                    ? "Waiting for Wi-Fi." : "Waiting for a network connection.");
            return;
        }
        List<PhotoBackup.Item> items = MediaScan.scan(app);
        PhotoBackup.FileLog log = new PhotoBackup.FileLog(BackupSettings.logFile(app, a.id));
        try {
            PhotoBackup.Result r = PhotoBackup.run(Clients.get(a), BackupSettings.folder(app, a.id), items, log,
                    new PhotoBackup.Listener() {
                        @Override
                        public void progress(String text) {
                            progress.text(a.name + ": " + text);
                        }

                        @Override
                        public boolean cancelled() {
                            return cancel.cancelled();
                        }
                    });
            int pending = PhotoBackup.pending(items, log).size();
            String note = r.failed > 0 ? r.failed + " could not be uploaded; they will be tried again." : "";
            if (!r.messages.isEmpty() && r.failed > 0) note += "\n" + r.messages.get(0);
            if (r.cancelled) note = "Stopped. It continues next time.";
            BackupSettings.setResult(app, a.id, items.size() - pending, items.size(), note);
        } catch (Exception e) {
            BackupSettings.setNote(app, a.id, "Backup stopped: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
