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
import android.util.Log;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.CloudClients;
import com.cloudbridge.app.core.CloudException;
import com.cloudbridge.app.core.FtpServer;
import com.cloudbridge.app.core.HttpTransport;
import com.cloudbridge.app.core.Paths;
import com.cloudbridge.app.core.WebDavServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the local WebDAV bridge running while it is switched on, so that other file managers can reach all
 * connected clouds at http://127.0.0.1:PORT/. It runs as a foreground service so Android does not stop it.
 */
public class BridgeService extends Service {
    static final String ACTION_STOP = "com.cloudbridge.app.STOP_BRIDGE";
    static final String ACTION_RESTART = "com.cloudbridge.app.RESTART_BRIDGE";
    private static final String TAG = "CloudBridge";
    private static final String CHANNEL = "bridge";
    private static final int NOTIFICATION_ID = 7;
    private static final HttpTransport TRANSPORT = new OkHttpTransport();

    private static volatile int runningPort;
    private static volatile int runningFtpPort;
    private static volatile String lastError = "";

    private WebDavServer server;
    private FtpServer ftp;

    /** The port the bridge listens on, or 0 when it is not running. */
    public static int port() {
        return runningPort;
    }

    /** The port of the FTP bridge, or 0 when it is not running. */
    public static int ftpPort() {
        return runningFtpPort;
    }

    public static String lastError() {
        return lastError;
    }

    public static void start(Context c) {
        BridgeSettings.setEnabled(c, true);
        c.startForegroundService(new Intent(c, BridgeService.class));
    }

    /** Starts the bridge again so that a changed password takes effect. */
    public static void restart(Context c) {
        BridgeSettings.setEnabled(c, true);
        Intent i = new Intent(c, BridgeService.class);
        i.setAction(ACTION_RESTART);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        BridgeSettings.setEnabled(c, false);
        c.stopService(new Intent(c, BridgeService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            BridgeSettings.setEnabled(this, false);
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        showNotification(0);
        if (intent != null && ACTION_RESTART.equals(intent.getAction()) && server != null) {
            stopServers();
        }
        if (server == null) {
            try {
                AccountsBackend backend = new AccountsBackend(this);
                WebDavServer s = new WebDavServer(backend, getCacheDir(), BridgeSettings.USER, BridgeSettings.password(this));
                runningPort = s.start(BridgeSettings.PREFERRED_PORT);
                server = s;
                FtpServer f = new FtpServer(backend, getCacheDir(), BridgeSettings.USER, BridgeSettings.password(this));
                runningFtpPort = f.start(BridgeSettings.PREFERRED_FTP_PORT);
                ftp = f;
                lastError = "";
                showNotification(runningPort);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "The bridge could not start", e);
                stopServers();
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                BridgeSettings.setEnabled(this, false);
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServers();
        super.onDestroy();
    }

    private void stopServers() {
        if (server != null) server.stop();
        if (ftp != null) ftp.stop();
        server = null;
        ftp = null;
        runningPort = 0;
        runningFtpPort = 0;
    }

    private void showNotification(int port) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "WebDAV bridge", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, BridgeService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("CloudBridge is sharing your clouds")
                .setContentText(port == 0 ? "Starting..." : "WebDAV port " + port + ", FTP port " + runningFtpPort)
                .setSmallIcon(R.drawable.ic_cloud)
                .setContentIntent(open)
                .addAction(0, "Turn off", stopIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /** Every saved account, as a top-level folder named like the account (made unique when two share a name). */
    private static final class AccountsBackend implements WebDavServer.Backend {
        private final Context context;

        AccountsBackend(Context context) {
            this.context = context.getApplicationContext();
        }

        private Map<String, Account> byName() {
            Map<String, Account> out = new LinkedHashMap<>();
            for (Account a : AccountStore.load(context)) {
                String base = Paths.sanitizeName(a.name);
                String name = base;
                for (int i = 2; out.containsKey(name); i++) name = base + " (" + i + ")";
                out.put(name, a);
            }
            return out;
        }

        @Override
        public List<String> names() {
            return new ArrayList<>(byName().keySet());
        }

        @Override
        public CloudClient client(String name) throws IOException {
            Account a = byName().get(name);
            if (a == null) throw new CloudException(404, "This storage was removed from CloudBridge");
            try {
                return CloudClients.create(a, TRANSPORT);
            } catch (IllegalArgumentException e) {
                throw new CloudException(400, e.getMessage());
            }
        }
    }
}
