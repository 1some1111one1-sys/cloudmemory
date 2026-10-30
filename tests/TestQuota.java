package com.cloudbridge.app.core;

import java.nio.file.Files;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.run;

/** Free / total space for each kind of cloud. */
final class TestQuota {
    private TestQuota() {
    }

    static void runAll() {
        run("quota (webdav, drive, mega, terabox)", TestQuota::testQuota);
    }

    private static void testQuota() throws Exception {
        // ---- WebDAV
        java.io.File root = Files.createTempDirectory("quota").toFile();
        MockDavServer dav = new MockDavServer(root, "", "me", "pw", false);
        try {
            WebDavClient c = new WebDavClient(new JdkTransport(), dav.url() + "/", "me", "pw");
            check("a server without quota support gives null", c.quota() == null);
            dav.quotaAvailable = 700;
            dav.quotaUsed = 300;
            CloudQuota q = c.quota();
            eq("webdav used", 300L, q.used);
            eq("webdav total", 1000L, q.total);
            eq("webdav free", 700L, q.free);
            check("webdav known", q.known());
        } finally {
            dav.stop();
        }

        // ---- Google Drive
        MockDriveServer drive = new MockDriveServer("RT-quota");
        try {
            GoogleDriveClient c = new GoogleDriveClient(new JdkTransport(), drive.base(), drive.base() + "/token",
                    MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, drive.refreshToken);
            CloudQuota q = c.quota();
            eq("drive used", 5368709120L, q.used);
            eq("drive total", 16106127360L, q.total);
            eq("drive free", 10737418240L, q.free);
        } finally {
            drive.stop();
        }

        // ---- MEGA
        MockMegaServer mega = new MockMegaServer(2);
        try {
            MegaClient.clearCaches();
            MegaClient c = new MegaClient(new JdkTransport(), mega.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "", "");
            CloudQuota q = c.quota();
            eq("mega used", 2147483648L, q.used);
            eq("mega total", 21474836480L, q.total);
            eq("mega free", 19327352832L, q.free);
        } finally {
            mega.stop();
        }

        // ---- TeraBox
        MockTeraBoxServer tera = new MockTeraBoxServer();
        try {
            TeraBoxClient.clearCaches();
            TeraBoxClient c = new TeraBoxClient(new JdkTransport(), tera.base(), MockTeraBoxServer.COOKIE);
            CloudQuota q = c.quota();
            eq("terabox total", 1099511627776L, q.total);
            eq("terabox used", 104857600L, q.used);
            eq("terabox free", 1099511627776L - 104857600L, q.free);
        } finally {
            tera.stop();
        }

        // ---- the unknown case and the helper
        eq("unknown free", -1L, CloudQuota.ofUsedAndTotal(-1, 100).free);
        check("unknown is not known", !CloudQuota.ofUsedAndTotal(-1, -1).known());
        eq("never negative", 0L, CloudQuota.ofUsedAndTotal(200, 100).free);
    }
}
