package com.cloudbridge.app.core;

public final class CloudClients {
    private CloudClients() {
    }

    /** @throws IllegalArgumentException if the account settings are unusable (message is user-readable). */
    public static CloudClient create(Account a, HttpTransport http) {
        if (a.type == Account.Type.TERABOX) {
            return new TeraBoxClient(http, a.url, a.secret);
        }
        if (a.type == Account.Type.MEGA) {
            return new MegaClient(http, a.username, a.secret, a.clientId, a.clientSecret);
        }
        if (a.type == Account.Type.GOOGLE_DRIVE) {
            return new GoogleDriveClient(http, a.clientId, a.clientSecret, a.secret);
        }
        if (a.type == Account.Type.S3) {
            return new S3Client(http, a.url, a.region, a.bucket, a.username, a.secret, a.pathStyle);
        }
        return new WebDavClient(http, a.url, a.username, a.secret);
    }
}
