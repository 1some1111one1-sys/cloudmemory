package com.cloudbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/** Settings of the local WebDAV bridge: whether it is on, and the password other apps must give. */
public final class BridgeSettings {
    public static final String USER = "cloudbridge";
    public static final int PREFERRED_PORT = 8484;
    private static final String PREFS = "cloudbridge_bridge";
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private BridgeSettings() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return "1".equals(prefs(c).getString("enabled", "0"));
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putString("enabled", on ? "1" : "0").commit();
    }

    /** The password for the user name {@link #USER}; made on first use. */
    public static synchronized String password(Context c) {
        String p = prefs(c).getString("password", "");
        if (p.length() >= 12) return p;
        return newPassword(c);
    }

    public static synchronized String newPassword(Context c) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        prefs(c).edit().putString("password", sb.toString()).commit();
        return sb.toString();
    }
}
