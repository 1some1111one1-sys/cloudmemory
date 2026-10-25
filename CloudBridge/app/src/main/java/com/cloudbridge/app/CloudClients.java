package com.cloudbridge.app.core;

public final class CloudClients {
    private CloudClients() {
    }

    /** @throws IllegalArgumentException if the account settings are unusable (message is user-readable). */
    public static CloudClient create(Account a, HttpTransport http) {
        if (a.type == Account.Type.S3) {
            return new S3Client(http, a.url, a.region, a.bucket, a.username, a.secret, a.pathStyle);
        }
        return new WebDavClient(http, a.url, a.username, a.secret);
    }
}
