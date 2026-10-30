package com.cloudbridge.app;

import android.content.Context;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.CloudClients;
import com.cloudbridge.app.core.CloudQuota;
import com.cloudbridge.app.core.HttpTransport;
import com.cloudbridge.app.core.LocalClient;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** One shared client per account (so a MEGA or TeraBox sign-in is reused), and the phone as a place. */
final class Clients {
    static final HttpTransport TRANSPORT = new OkHttpTransport();
    private static final Map<String, CloudClient> CACHE = new HashMap<>();
    private static final Map<String, Object[]> QUOTA = new HashMap<>();

    private Clients() {
    }

    static synchronized CloudClient get(Account a) {
        String key = a.id + "|" + a.secret + "|" + a.url + "|" + a.clientSecret;
        CloudClient c = CACHE.get(key);
        if (c == null) {
            c = CloudClients.create(a, TRANSPORT);
            CACHE.put(key, c);
        }
        return c;
    }

    /** Call after accounts were added, edited or removed. */
    static synchronized void reset() {
        CACHE.clear();
        QUOTA.clear();
    }

    static File phoneRoot() {
        return android.os.Environment.getExternalStorageDirectory();
    }

    private static LocalClient phone;

    /** The phone's shared storage as a place. One instance, so a move inside it can use a plain rename. */
    static synchronized LocalClient phone() {
        if (phone == null) phone = new LocalClient(phoneRoot(), true);
        return phone;
    }

    /** The folder that "Download" saves into. */
    static File downloads() {
        File d = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** The last known space of an account (null if never fetched or unknown). */
    static synchronized CloudQuota cachedQuota(String accountId) {
        Object[] e = QUOTA.get(accountId);
        return e == null ? null : (CloudQuota) e[1];
    }

    interface QuotaListener {
        void quota(CloudQuota q, String error);
    }

    /** Fetches the space in the background (cached for 5 minutes) and calls back on the main thread. */
    static void quota(final Context ctx, final Account a, final boolean force, final QuotaListener l) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        synchronized (Clients.class) {
            Object[] e = QUOTA.get(a.id);
            if (!force && e != null && System.currentTimeMillis() - (Long) e[0] < 5 * 60 * 1000L) {
                final CloudQuota q = (CloudQuota) e[1];
                main.post(() -> l.quota(q, null));
                return;
            }
        }
        new Thread(() -> {
            CloudQuota q = null;
            String error = null;
            try {
                q = get(a).quota();
                synchronized (Clients.class) {
                    QUOTA.put(a.id, new Object[]{System.currentTimeMillis(), q});
                }
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final CloudQuota fq = q;
            final String fe = error;
            main.post(() -> l.quota(fq, fe));
        }).start();
    }
}
