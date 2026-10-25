package com.cloudbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.cloudbridge.app.core.Account;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Saved cloud accounts. Secrets are encrypted with the Android Keystore; everything else is plain JSON. */
public final class AccountStore {
    private static final String TAG = "CloudBridge";
    private static final String PREFS = "cloudbridge_accounts";
    private static final String KEY = "accounts_json";
    private static List<Account> cache;

    private AccountStore() {
    }

    public static String newId() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static synchronized List<Account> load(Context ctx) {
        ensure(ctx);
        List<Account> copy = new ArrayList<>();
        for (Account a : cache) copy.add(a.copy());
        return copy;
    }

    public static synchronized Account find(Context ctx, String id) {
        ensure(ctx);
        for (Account a : cache) {
            if (a.id.equals(id)) return a.copy();
        }
        return null;
    }

    public static synchronized void upsert(Context ctx, Account account) {
        ensure(ctx);
        List<Account> next = new ArrayList<>(cache);
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(account.id)) {
                next.set(i, account.copy());
                replaced = true;
            }
        }
        if (!replaced) next.add(account.copy());
        persist(ctx, next);
        cache = next;
    }

    public static synchronized void remove(Context ctx, String id) {
        ensure(ctx);
        List<Account> next = new ArrayList<>();
        for (Account a : cache) {
            if (!a.id.equals(id)) next.add(a);
        }
        persist(ctx, next);
        cache = next;
    }

    private static void ensure(Context ctx) {
        if (cache == null) cache = read(ctx.getApplicationContext());
    }

    private static List<Account> read(Context ctx) {
        List<Account> out = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY, null);
        if (json == null) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Account a = new Account();
                a.id = o.getString("id");
                a.name = o.optString("name");
                a.type = "S3".equals(o.optString("type")) ? Account.Type.S3 : Account.Type.WEBDAV;
                a.url = o.optString("url");
                a.username = o.optString("username");
                a.region = o.optString("region");
                a.bucket = o.optString("bucket");
                a.pathStyle = o.optBoolean("pathStyle", true);
                a.secret = "";
                String enc = o.optString("secret");
                if (!enc.isEmpty()) {
                    try {
                        a.secret = SecretBox.decrypt(enc);
                    } catch (GeneralSecurityException | RuntimeException e) {
                        // Includes ProviderException, which the Keystore throws on some devices after a reset.
                        Log.w(TAG, "Could not decrypt the secret of " + a.name + "; it must be entered again", e);
                    }
                }
                out.add(a);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not read saved accounts", e);
        }
        return out;
    }

    private static void persist(Context ctx, List<Account> accounts) {
        JSONArray arr = new JSONArray();
        try {
            for (Account a : accounts) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("type", a.type.name());
                o.put("url", a.url);
                o.put("username", a.username);
                o.put("region", a.region);
                o.put("bucket", a.bucket);
                o.put("pathStyle", a.pathStyle);
                o.put("secret", a.secret == null || a.secret.isEmpty() ? "" : SecretBox.encrypt(a.secret));
                arr.put(o);
            }
        } catch (JSONException | GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Could not encrypt and save the account: " + e.getMessage(), e);
        }
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.edit().putString(KEY, arr.toString()).commit()) {
            throw new IllegalStateException("Could not write to the phone's storage");
        }
    }
}
