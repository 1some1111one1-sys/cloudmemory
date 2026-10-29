package com.cloudbridge.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.CloudClients;
import com.cloudbridge.app.core.HttpTransport;
import com.cloudbridge.app.core.Paths;
import com.cloudbridge.app.core.SyncEngine;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Runs the sync of one or all clouds. Used by the foreground service and by the periodic job. */
final class SyncRunner {
    private static final HttpTransport TRANSPORT = new OkHttpTransport();
    private static boolean running;
    private static volatile boolean cancelRequested;

    private SyncRunner() {
    }

    interface Progress {
        void update(String text);
    }

    /** Only one sync runs at a time. Returns false when one is already running. */
    static synchronized boolean begin() {
        if (running) return false;
        running = true;
        cancelRequested = false;
        return true;
    }

    static synchronized void end() {
        running = false;
    }

    static synchronized boolean isRunning() {
        return running;
    }

    static void cancel() {
        cancelRequested = true;
    }

    /** The folder on the phone that mirrors a cloud: Internal storage/CloudBridge/&lt;cloud name&gt;. */
    static File localRoot(Account a) {
        return new File(new File(Environment.getExternalStorageDirectory(), "CloudBridge"), SyncEngine.localName(Paths.sanitizeName(a.name)));
    }

    /** True when this app may write to the shared storage. */
    static boolean hasStorageAccess(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == 0;
    }

    /** Syncs every cloud that has the sync folder on; one line per cloud goes into its saved "last result". */
    static void runAll(Context context, Progress progress) {
        List<Account> accounts = SyncSettings.enabledAccounts(context);
        for (Account a : accounts) {
            if (cancelRequested) break;
            runOne(context, a, progress);
        }
        CloudDocumentsProvider.clearCaches();
    }

    static void runOne(final Context context, final Account a, final Progress progress) {
        SyncSettings.Entry s = SyncSettings.get(context, a.id);
        String summary;
        final Set<String> scan = new LinkedHashSet<>();
        try {
            if (!hasStorageAccess(context)) {
                throw new IOException("CloudBridge may not use the phone's storage yet. Open its sync settings and allow \"All files access\".");
            }
            CloudClient client = CloudClients.create(a, TRANSPORT);
            SyncEngine.Config cfg = new SyncEngine.Config();
            cfg.remoteRoot = s.remoteRoot;
            cfg.mirrorDeletes = s.mirrorDeletes;
            cfg.maxFileBytes = Math.max(1, s.maxMb) * 1024L * 1024L;
            File state = new File(new File(context.getFilesDir(), "sync"), a.id + ".json");
            SyncEngine.Result r = SyncEngine.sync(client, cfg, localRoot(a), state, new SyncEngine.Listener() {
                @Override
                public void progress(String text) {
                    if (progress != null) progress.update(a.name + ": " + text);
                }

                @Override
                public boolean cancelled() {
                    return cancelRequested;
                }

                @Override
                public void localChanged(File file) {
                    scan.add(file.getPath());
                }
            });
            summary = r.summary();
            if (!r.messages.isEmpty()) summary += "\n" + r.messages.get(0) + (r.messages.size() > 1 ? " (+" + (r.messages.size() - 1) + " more)" : "");
        } catch (IOException | RuntimeException e) {
            String m = e.getMessage();
            summary = "Failed: " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        }
        if (!scan.isEmpty()) {
            // Tell the gallery and music apps about new, changed and removed files.
            MediaScannerConnection.scanFile(context, scan.toArray(new String[0]), null, null);
        }
        SyncSettings.saveResult(context, a.id, summary);
    }
}
