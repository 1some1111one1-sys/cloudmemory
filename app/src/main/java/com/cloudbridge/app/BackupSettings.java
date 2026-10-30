package com.cloudbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/** Per-drive photo backup settings and the result of the last run. */
public final class BackupSettings {
    static final String DEFAULT_FOLDER = "/Photo backup";
    private static final String PREFS = "cloudbridge_backup";

    private BackupSettings() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c, String id) {
        return prefs(c).getBoolean(id + ".on", false);
    }

    static void setEnabled(Context c, String id, boolean on) {
        prefs(c).edit().putBoolean(id + ".on", on).commit();
    }

    /** Wi-Fi only is the default, so mobile data is never used unless the person says so. */
    static boolean wifiOnly(Context c, String id) {
        return prefs(c).getBoolean(id + ".wifi", true);
    }

    static void setWifiOnly(Context c, String id, boolean on) {
        prefs(c).edit().putBoolean(id + ".wifi", on).commit();
    }

    static String folder(Context c, String id) {
        String f = prefs(c).getString(id + ".folder", DEFAULT_FOLDER);
        return f == null || f.isEmpty() ? DEFAULT_FOLDER : f;
    }

    static void setFolder(Context c, String id, String folder) {
        prefs(c).edit().putString(id + ".folder", folder).commit();
    }

    static boolean anyEnabled(Context c) {
        for (com.cloudbridge.app.core.Account a : AccountStore.load(c)) {
            if (enabled(c, a.id)) return true;
        }
        return false;
    }

    /** True when every drive that has backup on is Wi-Fi only (then the job may wait for an unmetered network). */
    static boolean allWifiOnly(Context c) {
        for (com.cloudbridge.app.core.Account a : AccountStore.load(c)) {
            if (enabled(c, a.id) && !wifiOnly(c, a.id)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ last result

    static void setResult(Context c, String id, int backedUp, int total, String note) {
        prefs(c).edit().putInt(id + ".done", backedUp).putInt(id + ".total", total)
                .putLong(id + ".when", System.currentTimeMillis()).putString(id + ".note", note == null ? "" : note).commit();
    }

    static void setNote(Context c, String id, String note) {
        prefs(c).edit().putString(id + ".note", note).commit();
    }

    static void setRunning(Context c, String id, boolean running) {
        prefs(c).edit().putBoolean(id + ".running", running).commit();
    }

    static boolean running(Context c, String id) {
        return prefs(c).getBoolean(id + ".running", false);
    }

    /** One line for the Drives page, e.g. "120 of 340 backed up · 5 min ago". */
    static String status(Context c, String id) {
        SharedPreferences p = prefs(c);
        long when = p.getLong(id + ".when", 0);
        String note = p.getString(id + ".note", "");
        StringBuilder sb = new StringBuilder();
        if (running(c, id)) sb.append("Backing up now... ");
        else if (when == 0 && note.isEmpty()) return "Not run yet.";
        if (when > 0) {
            sb.append(p.getInt(id + ".done", 0)).append(" of ").append(p.getInt(id + ".total", 0)).append(" backed up");
            sb.append(" · ").append(android.text.format.DateUtils.getRelativeTimeSpanString(when));
        }
        if (!note.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append(note);
        return sb.toString();
    }

    static File logFile(Context c, String id) {
        File dir = new File(c.getApplicationContext().getFilesDir(), "backup");
        dir.mkdirs();
        return new File(dir, id + ".log");
    }

    /** Forget everything about a drive that was removed. */
    static void forget(Context c, String id) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : new String[]{".on", ".wifi", ".folder", ".done", ".total", ".when", ".note", ".running"}) e.remove(id + k);
        e.commit();
        logFile(c, id).delete();
    }
}
