package com.cloudbridge.app.core;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Google OAuth 2.0 for an installed app: authorization code + PKCE (S256) with a loopback redirect,
 * using an OAuth client of type "Desktop app" that the user creates in their own Google Cloud project.
 */
public final class GoogleOAuth {
    public static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    /** Full Drive access (browse and edit every file). "drive.file" would only show files this app created. */
    public static final String SCOPE = "https://www.googleapis.com/auth/drive";

    private GoogleOAuth() {
    }

    public static final class Tokens {
        public final String accessToken;
        /** Null on a refresh (Google keeps the old refresh token valid). */
        public final String refreshToken;
        public final long expiresAtMillis;

        Tokens(String accessToken, String refreshToken, long expiresAtMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    public static String newVerifier() {
        byte[] b = new byte[48];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);   // 64 characters
    }

    public static String newState() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String authUrl(String clientId, String redirectUri, String state, String challenge) {
        return AUTH_URL + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&access_type=offline"
                + "&prompt=consent";
    }

    public static Tokens exchange(HttpTransport http, String tokenUrl, String clientId, String clientSecret,
                                  String code, String verifier, String redirectUri) throws IOException {
        String body = form("grant_type", "authorization_code", "code", code, "code_verifier", verifier,
                "redirect_uri", redirectUri, "client_id", clientId, "client_secret", clientSecret);
        Tokens t = tokenRequest(http, tokenUrl, body);
        if (t.refreshToken == null || t.refreshToken.isEmpty()) {
            throw new CloudException(502, "Google did not return a long-lived sign-in. Remove CloudBridge from "
                    + "myaccount.google.com/permissions and sign in again.");
        }
        return t;
    }

    public static Tokens refresh(HttpTransport http, String tokenUrl, String clientId, String clientSecret,
                                 String refreshToken) throws IOException {
        String body = form("grant_type", "refresh_token", "refresh_token", refreshToken,
                "client_id", clientId, "client_secret", clientSecret);
        return tokenRequest(http, tokenUrl, body);
    }

    private static Tokens tokenRequest(HttpTransport http, String tokenUrl, String body) throws IOException {
        HttpTransport.Request req = new HttpTransport.Request("POST", tokenUrl)
                .body(body.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
        req.header("Accept", "application/json");
        HttpTransport.Response r = http.execute(req);
        String text;
        int code = r.code;
        try {
            text = new String(r.readAll(1024 * 1024), StandardCharsets.UTF_8);
        } finally {
            r.close();
        }
        Map<String, Object> o;
        try {
            o = Json.parseObject(text, "Google sign-in");
        } catch (CloudException e) {
            throw new CloudException(code >= 400 ? code : 502, "Google sign-in failed (HTTP " + code + ")");
        }
        if (code / 100 != 2) {
            String error = Json.str(o, "error");
            String desc = Json.str(o, "error_description");
            if ("invalid_grant".equals(error)) {
                throw new CloudException(401, "The Google sign-in expired or was revoked. Edit this storage in "
                        + "CloudBridge and sign in again. (If your Google Cloud consent screen is still in "
                        + "\"Testing\", sign-ins expire after 7 days: publish it to \"In production\".)");
            }
            if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                throw new CloudException(401, "Google rejected the client ID or client secret"
                        + (desc == null ? "" : " (" + desc + ")") + ". Check both values.");
            }
            throw new CloudException(code, "Google sign-in failed (HTTP " + code + ")"
                    + (error == null ? "" : ": " + error + (desc == null ? "" : " - " + desc)));
        }
        String access = Json.str(o, "access_token");
        if (access == null || access.isEmpty()) throw new CloudException(502, "Google sent no access token");
        long ttl = Json.num(o, "expires_in", 3600);
        return new Tokens(access, Json.str(o, "refresh_token"), System.currentTimeMillis() + ttl * 1000L);
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k + 1 < kv.length; k += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[k])).append('=').append(enc(kv[k + 1]));
        }
        return sb.toString();
    }

    static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
