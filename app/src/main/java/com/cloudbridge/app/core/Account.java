package com.cloudbridge.app.core;

/** A configured cloud storage connection. */
public final class Account {
    public enum Type {WEBDAV, S3, GOOGLE_DRIVE, MEGA}

    public String id;
    public String name;
    public Type type = Type.WEBDAV;
    /** WebDAV: the server URL. S3: the endpoint, e.g. https://s3.amazonaws.com. */
    public String url = "";
    /** WebDAV: username. S3: access key id. Google Drive: the signed-in e-mail (display only). MEGA: e-mail. */
    public String username = "";
    /** WebDAV: password. S3: secret access key. Google Drive: the OAuth refresh token. MEGA: password. */
    public String secret = "";
    /** Google Drive: the OAuth client the user created in their own Google Cloud project. MEGA: optional app key. */
    public String clientId = "";
    /** Google Drive: the OAuth client secret. MEGA: the saved sign-in session. Stored encrypted. */
    public String clientSecret = "";
    /** S3 only. */
    public String region = "us-east-1";
    /** S3 only. */
    public String bucket = "";
    /** S3 only. Path-style (endpoint/bucket/key) works almost everywhere; virtual-hosted needs bucket.endpoint DNS. */
    public boolean pathStyle = true;

    public Account copy() {
        Account a = new Account();
        a.id = id;
        a.name = name;
        a.type = type;
        a.url = url;
        a.username = username;
        a.secret = secret;
        a.clientId = clientId;
        a.clientSecret = clientSecret;
        a.region = region;
        a.bucket = bucket;
        a.pathStyle = pathStyle;
        return a;
    }

    public String summary() {
        if (type == Type.MEGA) return "MEGA \u00b7 " + username;
        if (type == Type.GOOGLE_DRIVE) return "Google Drive" + (username == null || username.isEmpty() ? "" : " · " + username);
        if (type == Type.S3) return "S3 · " + bucket + " · " + url;
        return "WebDAV · " + url;
    }
}
