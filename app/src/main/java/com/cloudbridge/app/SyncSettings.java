package com.cloudbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.cloudbridge.app.core.Account;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Per-cloud settings of "keep a copy on the phone" (the sync folder), plus the result of the last run. */
public final class SyncSettings {
    private static final String PREFS = "cloudbridge_sync";

    private SyncSettings() {
    }

    public static final class Entry {
        public boolean enabled;
        public String remoteRoot = "/CloudBridge";
        public boolean mirrorDeletes;
        public int maxMb = 500;
        public long lastTime;
        public String lastSummary = "";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Entry get(Context c, String accountId) {
        Entry e = new Entry();
        String raw = prefs(c).getString("a_" + accountId, "");
        if (raw == null || raw.isEmpty()) return e;
        try {
            JSONObject o = new JSONObject(raw);
            e.enabled = o.optBoolean("enabled", false);
            e.remoteRoot = o.optString("remoteRoot", e.remoteRoot);
            e.mirrorDeletes = o.optBoolean("mirrorDeletes", false);
            e.maxMb = o.optInt("maxMb", 500);
            e.lastTime = o.optLong("lastTime", 0);
            e.lastSummary = o.optString("lastSummary", "");
        } catch (JSONException ignored) {
            // damaged settings behave like no settings
        }
        return e;
    }

    public static synchronized void put(Context c, String accountId, Entry e) {
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", e.enabled);
            o.put("remoteRoot", e.remoteRoot);
            o.put("mirrorDeletes", e.mirrorDeletes);
            o.put("maxMb", e.maxMb);
            o.put("lastTime", e.lastTime);
            o.put("lastSummary", e.lastSummary);
            prefs(c).edit().putString("a_" + accountId, o.toString()).commit();
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static synchronized void saveResult(Context c, String accountId, String summary) {
        Entry e = get(c, accountId);
        e.lastTime = System.currentTimeMillis();
        e.lastSummary = summary;
        put(c, accountId, e);
    }

    public static boolean autoSync(Context c) {
        return "1".equals(prefs(c).getString("auto", "0"));
    }

    public static void setAutoSync(Context c, boolean on) {
        prefs(c).edit().putString("auto", on ? "1" : "0").commit();
    }

    /** The accounts that have the sync folder switched on. */
    public static List<Account> enabledAccounts(Context c) {
        List<Account> out = new ArrayList<>();
        for (Account a : AccountStore.load(c)) {
            if (get(c, a.id).enabled) out.add(a);
        }
        return out;
    }
}
