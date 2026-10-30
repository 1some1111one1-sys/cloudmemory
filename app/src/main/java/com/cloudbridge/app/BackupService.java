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

/** Runs a photo backup in the foreground (so Android keeps it alive) when the person taps "Back up now". */
public class BackupService extends Service {
    static final String ACTION_STOP = "com.cloudbridge.app.STOP_BACKUP";
    private static final String EXTRA_ACCOUNT = "account";
    private static final String CHANNEL = "backup";
    private static final int NOTIFICATION_ID = 8;

    private volatile boolean cancelled;
    private volatile boolean busy;

    /** @param accountId the drive to back up, or null for every drive that has backup on. */
    static void start(Context c, String accountId) {
        Intent i = new Intent(c, BackupService.class);
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
            cancelled = true;
            return Service.START_NOT_STICKY;
        }
        show("Looking for new photos...");
        if (busy) return Service.START_NOT_STICKY;
        busy = true;
        cancelled = false;
        final String id = intent == null ? null : intent.getStringExtra(EXTRA_ACCOUNT);
        new Thread(() -> {
            try {
                BackupRunner.Cancel cancel = () -> cancelled;
                BackupRunner.Progress progress = this::show;
                if (id == null) {
                    BackupRunner.runAll(this, cancel, progress);
                } else {
                    Account a = AccountStore.find(this, id);
                    if (a != null) BackupRunner.run(this, a, cancel, progress);
                }
            } finally {
                busy = false;
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }).start();
        return Service.START_NOT_STICKY;
    }

    private void show(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Photo backup", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, BackupService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Backing up photos and videos")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_cloud)
                .setContentIntent(open)
                .addAction(0, "Stop", stopIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
