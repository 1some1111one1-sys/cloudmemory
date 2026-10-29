package com.cloudbridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import com.cloudbridge.app.core.Account;

/** Runs a sync in the foreground so Android does not stop it, with a notification that shows the progress. */
public class SyncService extends Service {
    static final String ACTION_STOP = "com.cloudbridge.app.STOP_SYNC";
    static final String EXTRA_ACCOUNT = "account";
    private static final String CHANNEL = "sync";
    private static final int NOTIFICATION_ID = 8;
    private static final int DONE_ID = 9;

    /** Starts a sync of one cloud, or of every cloud that has the sync folder on when accountId is null. */
    public static void start(Context c, String accountId) {
        Intent i = new Intent(c, SyncService.class);
        if (accountId != null) i.putExtra(EXTRA_ACCOUNT, accountId);
        c.startForegroundService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SyncRunner.cancel();
            return Service.START_NOT_STICKY;
        }
        notifyProgress("Starting...");
        final String accountId = intent == null ? null : intent.getStringExtra(EXTRA_ACCOUNT);
        if (!SyncRunner.begin()) {
            // A sync is already running (maybe from the periodic job); it reports its own result.
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                SyncRunner.Progress progress = new SyncRunner.Progress() {
                    private long last;

                    @Override
                    public void update(String text) {
                        long now = System.currentTimeMillis();
                        if (now - last < 700) return;
                        last = now;
                        notifyProgress(text);
                    }
                };
                if (accountId == null) {
                    SyncRunner.runAll(SyncService.this, progress);
                } else {
                    Account a = AccountStore.find(SyncService.this, accountId);
                    if (a != null) SyncRunner.runOne(SyncService.this, a, progress);
                    CloudDocumentsProvider.clearCaches();
                }
            } finally {
                SyncRunner.end();
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                notifyDone();
                stopSelf();
            }
        }, "cloudbridge-sync").start();
        return Service.START_NOT_STICKY;
    }

    private NotificationManager manager() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW));
        return nm;
    }

    private void notifyProgress(String text) {
        manager();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, SyncService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("CloudBridge is syncing")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_cloud)
                .setContentIntent(open)
                .addAction(0, "Stop", stopIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void notifyDone() {
        NotificationManager nm = manager();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("CloudBridge sync finished")
                .setContentText("Open CloudBridge to see the result")
                .setSmallIcon(R.drawable.ic_cloud)
                .setContentIntent(open)
                .build();
        nm.notify(DONE_ID, n);
    }
}
