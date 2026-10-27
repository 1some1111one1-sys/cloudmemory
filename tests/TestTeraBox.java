package com.cloudbridge.app.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.expectFail;
import static com.cloudbridge.app.core.TestMain.names;
import static com.cloudbridge.app.core.TestMain.run;

/** Tests for the experimental TeraBox client against an imitation of the website's JSON endpoints. */
final class TestTeraBox {
    private TestTeraBox() {
    }

    static void runAll() {
        run("terabox browse + download", TestTeraBox::testBrowse);
        run("terabox changes", TestTeraBox::testChanges);
        run("terabox errors", TestTeraBox::testErrors);
    }

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static TeraBoxClient client(MockTeraBoxServer s) {
        TeraBoxClient.clearCaches();
        return new TeraBoxClient(new JdkTransport(), s.base(), MockTeraBoxServer.COOKIE);
    }

    private static File fileOf(byte[] b) throws Exception {
        File f = File.createTempFile("tbu", ".bin");
        f.deleteOnExit();
        Files.write(f.toPath(), b);
        return f;
    }

    private static byte[] download(CloudClient c, String path) throws Exception {
        File f = File.createTempFile("tbd", ".bin");
        f.deleteOnExit();
        c.download(path, f);
        return Files.readAllBytes(f.toPath());
    }

    private static void testBrowse() throws Exception {
        MockTeraBoxServer s = new MockTeraBoxServer();
        try {
            s.dir("/Docs");
            s.put("/Docs/a.txt", "hello tera".getBytes(StandardCharsets.UTF_8));
            s.put("/Docs/café (1) #.txt", "x".getBytes());
            s.put("/top.txt", "top".getBytes());
            for (int i = 0; i < 1005; i++) s.put("/many/f" + String.format("%04d", i), new byte[0]);
            s.dir("/many");
            TeraBoxClient c = client(s);
            eq("root", Arrays.asList("/Docs/", "/many/", "/top.txt"), names(c.list("/")));
            eq("docs", Arrays.asList("/Docs/a.txt", "/Docs/café (1) #.txt"), names(c.list("/Docs")));
            eq("pagination beyond 1000 entries", 1005, c.list("/many").size());
            check("stat file", c.stat("/Docs/a.txt").size == 10 && !c.stat("/Docs/a.txt").dir && c.stat("/Docs/a.txt").modified > 0);
            check("stat folder", c.stat("/Docs").dir && c.stat("/").dir);
            check("missing is 404", expectFail("missing", () -> c.stat("/Docs/nope")).isNotFound());
            check("missing folder listing is 404", expectFail("missing dir", () -> c.list("/Nope")).isNotFound());
            eq("download (through a redirect, with the cookie)", "hello tera", new String(download(c, "/Docs/a.txt")));
            eq("download odd name", "x", new String(download(c, "/Docs/café (1) #.txt")));
            check("folder download refused", expectFail("dl folder", () -> download(c, "/Docs")) != null);
            check("jsToken scraped from the page and sent", s.sawJsToken > 0);
            int before = s.log.size();
            c.list("/Docs");
            c.stat("/Docs/a.txt");
            eq("listings are cached briefly", before, s.log.size());
        } finally {
            s.stop();
        }
    }

    private static void testChanges() throws Exception {
        MockTeraBoxServer s = new MockTeraBoxServer();
        try {
            s.dir("/Docs");
            TeraBoxClient c = client(s);

            byte[] nine = bytes(9_500_000, 5);                      // three 4 MB blocks
            c.upload("/Docs/big.bin", fileOf(nine), "application/octet-stream");
            check("big upload stored", s.files.containsKey("/Docs/big.bin") && Arrays.equals(nine, s.files.get("/Docs/big.bin").data));
            eq("three parts uploaded", 3, s.uploadedParts.get());
            check("round trip", Arrays.equals(nine, download(c, "/Docs/big.bin")));
            eq("listed with size", 9_500_000L, c.stat("/Docs/big.bin").size);

            // the same content again: TeraBox already has every block, so nothing is re-sent
            int parts = s.uploadedParts.get();
            c.upload("/Docs/copy.bin", fileOf(nine), "application/octet-stream");
            eq("known blocks are not uploaded again", parts, s.uploadedParts.get());
            check("copy exists", Arrays.equals(nine, s.files.get("/Docs/copy.bin").data));

            // overwrite
            c.upload("/Docs/big.bin", fileOf("small".getBytes()), "x/y");
            eq("overwritten", "small", new String(s.files.get("/Docs/big.bin").data));
            check("no stray renamed copy", !s.files.containsKey("/Docs/big.bin(1)"));

            // empty file (what the Files app creates first)
            c.upload("/Docs/empty.txt", fileOf(new byte[0]), "text/plain");
            check("empty file created", s.files.containsKey("/Docs/empty.txt") && s.files.get("/Docs/empty.txt").data.length == 0);
            c.upload("/Docs/empty.txt", fileOf("filled".getBytes()), "text/plain");
            eq("then filled", "filled", new String(s.files.get("/Docs/empty.txt").data));

            check("upload into missing folder", expectFail("no parent", () -> c.upload("/Nope/x", fileOf(new byte[1]), "x/y")) != null);
            check("upload over a folder", expectFail("over folder", () -> c.upload("/Docs", fileOf(new byte[1]), "x/y")) != null);

            c.mkdir("/Docs/Sub");
            check("folder made", s.files.get("/Docs/Sub") != null && s.files.get("/Docs/Sub").dir);
            check("mkdir twice refused", expectFail("mkdir twice", () -> c.mkdir("/Docs/Sub")) != null);
            c.upload("/Docs/Sub/in.txt", fileOf("in".getBytes()), "text/plain");

            c.move("/Docs/copy.bin", "/Docs/renamed.bin", false);
            check("renamed", s.files.containsKey("/Docs/renamed.bin") && !s.files.containsKey("/Docs/copy.bin"));
            c.move("/Docs/renamed.bin", "/Docs/Sub/moved.bin", false);
            check("moved", s.files.containsKey("/Docs/Sub/moved.bin"));
            c.move("/Docs/Sub", "/Sub2", true);
            check("folder moved with children", s.files.containsKey("/Sub2/in.txt") && s.files.containsKey("/Sub2/moved.bin"));
            CloudException clash = expectFail("clash", () -> c.move("/Docs/big.bin", "/Sub2", false));
            eq("clash is 412", 412, clash == null ? -1 : clash.status);
            check("into itself refused", expectFail("into itself", () -> c.move("/Sub2", "/Sub2/x", true)) != null);

            c.delete("/Sub2/in.txt", false);
            check("deleted", !s.files.containsKey("/Sub2/in.txt"));
            c.delete("/Sub2", true);
            check("folder and children deleted", !s.files.containsKey("/Sub2") && !s.files.containsKey("/Sub2/moved.bin"));
            check("deleted gone from the listing", expectFail("gone", () -> c.stat("/Sub2")).isNotFound());
            check("root cannot be deleted", expectFail("delete root", () -> c.delete("/", true)) != null);
        } finally {
            s.stop();
        }
    }

    private static void testErrors() throws Exception {
        MockTeraBoxServer s = new MockTeraBoxServer();
        try {
            s.dir("/Docs");
            TeraBoxClient.clearCaches();
            TeraBoxClient bad = new TeraBoxClient(new JdkTransport(), s.base(), "ndus=WRONG");
            CloudException expired = expectFail("expired session", () -> bad.list("/"));
            check("expired session says sign in again", expired != null && expired.status == 401 && expired.getMessage().contains("sign in"));

            s.redirectLogin = true;
            TeraBoxClient.clearCaches();
            TeraBoxClient redirected = new TeraBoxClient(new JdkTransport(), s.base(), "ndus=WRONG");
            CloudException r = expectFail("redirect to login", () -> redirected.list("/"));
            check("redirect to the login page is a sign-in problem", r != null && r.status == 401);
            s.redirectLogin = false;

            s.pageWithoutJsToken = true;
            TeraBoxClient noJs = client(s);
            eq("works without a jsToken", 1, noJs.list("/").size());

            TeraBoxClient c = client(s);
            c.list("/");
            s.forceErrno = "4000023";
            TeraBoxClient c2 = client(s);
            CloudException check = expectFail("security check", () -> c2.stat("/Docs/x"));
            check("unknown errno is reported with its number", check != null);
            s.forceErrno = "31064";
            TeraBoxClient c3 = client(s);
            CloudException full = expectFail("full", () -> c3.list("/Docs"));
            check("errno carried in the message", full != null && full.getMessage().contains("31064") && full.status == 507);
            s.forceErrno = null;
        } finally {
            s.stop();
        }
        String[][] badArgs = {{"https://x.example", ""}, {"https://x.example", "nodus=1"}, {"ftp://x.example", "ndus=1"}};
        for (String[] b : badArgs) {
            try {
                new TeraBoxClient(null, b[0], b[1]);
                TestMain.check("terabox validation " + Arrays.toString(b), false);
            } catch (IllegalArgumentException e) {
                TestMain.check("terabox validation " + Arrays.toString(b), !e.getMessage().isEmpty());
            }
        }
        Account a = new Account();
        a.type = Account.Type.TERABOX;
        a.url = TeraBoxClient.DEFAULT_SITE;
        a.secret = "ndus=1";
        TestMain.check("factory builds a terabox client", CloudClients.create(a, null) instanceof TeraBoxClient);
    }
}
