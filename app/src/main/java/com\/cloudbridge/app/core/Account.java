package com.cloudbridge.app.core;

/** A configured cloud storage connection. */
public final class Account {
    public enum Type {WEBDAV, S3}

    public String id;
    public String name;
    public Type type = Type.WEBDAV;
    /** WebDAV: the server URL. S3: the endpoint, e.g. https://s3.amazonaws.com. */
    public String url = "";
    /** WebDAV: username. S3: access key id. */
    public String username = "";
    /** WebDAV: password. S3: secret access key. */
    public String secret = "";
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
        a.region = region;
        a.bucket = bucket;
        a.pathStyle = pathStyle;
        return a;
    }

    public String summary() {
        if (type == Type.S3) return "S3 · " + bucket + " · " + url;
        return "WebDAV · " + url;
    }
}
