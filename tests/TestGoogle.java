package com.cloudbridge.app.core;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.expectFail;
import static com.cloudbridge.app.core.TestMain.names;
import static com.cloudbridge.app.core.TestMain.randomFile;
import static com.cloudbridge.app.core.TestMain.run;

/** Tests for the Google Drive connector: JSON, OAuth/PKCE, the loopback receiver and the Drive client. */
final class TestGoogle {
    private TestGoogle() {
    }

    static void runAll() {
        run("json", TestGoogle::testJson);
        run("google oauth", TestGoogle::testOAuth);
        run("loopback receiver", TestGoogle::testLoopback);
        run("drive browse + download", TestGoogle::testDriveBrowse);
        run("drive changes", TestGoogle::testDriveChanges);
        run("drive tokens + throttling", TestGoogle::testDriveTokens);
        run("drive errors + validation", TestGoogle::testDriveErrors);
    }

    // ------------------------------------------------------------------ json

    private static void testJson() throws Exception {
        Object v = Json.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\\\"\",true,null],\"b\":{\"c\":\"d\"},\"n\":\"123\",\"e\":{}}");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        eq("json list", Arrays.asList(1L, 2.5, "x\n\u00e9\"", true, null), Json.list(m, "a"));
        eq("json nested", "d", Json.str(Json.obj(m, "b"), "c"));
        eq("json numeric string", 123L, Json.num(m, "n", -1));
        eq("json missing", -1L, Json.num(m, "zzz", -1));
        eq("json empty object", 0, Json.obj(m, "e").size());
        eq("json quote", "\"a\\\"b\\\\c\\n\\u0001\u00e9\"", Json.quote("a\"b\\c\n\u0001\u00e9"));
        eq("json quote round trip", "he said \"hi\"\n\\o/ \u2603", Json.parse(Json.quote("he said \"hi\"\n\\o/ \u2603")));
        String[] bad = {"", "{", "[1,]", "{\"a\"}", "{\"a\":1,}", "nul", "\"abc", "{\"a\":1} x", "[1 2]", "\"\\q\""};
        for (String b : bad) {
            try {
                Json.parse(b);
                check("json rejects " + b, false);
            } catch (IllegalArgumentException e) {
                check("json rejects " + b, true);
            }
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        try {
            Json.parse(deep.toString());
            check("json depth limit", false);
        } catch (IllegalArgumentException e) {
            check("json depth limit", true);
        }
        expectFail("parseObject on array", () -> Json.parseObject("[1]", "X"));
    }

    // ------------------------------------------------------------------ oauth

    private static void testOAuth() throws Exception {
        // RFC 7636 appendix B test vector
        eq("pkce S256 vector", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String v = GoogleOAuth.newVerifier();
        check("verifier length/charset", v.length() == 64 && v.matches("[A-Za-z0-9_-]+"));
        check("verifiers differ", !v.equals(GoogleOAuth.newVerifier()) && !GoogleOAuth.newState().equals(GoogleOAuth.newState()));

        String url = GoogleOAuth.authUrl("cid 1", "http://127.0.0.1:5555", "st&ate", "CHAL");
        check("auth url base", url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        for (String part : new String[]{"client_id=cid+1", "redirect_uri=http%3A%2F%2F127.0.0.1%3A5555", "response_type=code",
                "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive", "state=st%26ate", "code_challenge=CHAL",
                "code_challenge_method=S256", "access_type=offline", "prompt=consent"}) {
            check("auth url has " + part, url.contains(part));
        }

        MockDriveServer s = new MockDriveServer("RT-oauth");
        try {
            HttpTransport http = new JdkTransport();
            String tokenUrl = s.base() + "/token";
            String verifier = GoogleOAuth.newVerifier();
            s.challengeExpected = GoogleOAuth.challenge(verifier);
            GoogleOAuth.Tokens t = GoogleOAuth.exchange(http, tokenUrl, MockDriveServer.CLIENT_ID,
                    MockDriveServer.CLIENT_SECRET, "the-code", verifier, "http://127.0.0.1:4444");
            eq("exchange refresh token", "RT-oauth", t.refreshToken);
            check("exchange access token + expiry", t.accessToken.startsWith("AT")
                    && t.expiresAtMillis > System.currentTimeMillis() + 3_000_000);

            CloudException wrongCode = expectFail("exchange with a wrong code", () -> GoogleOAuth.exchange(http, tokenUrl,
                    MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, "bad", verifier, "http://127.0.0.1:4444"));
            check("wrong code is explained", wrongCode != null && wrongCode.status == 401 && wrongCode.getMessage().contains("sign in again"));
            CloudException wrongVerifier = expectFail("exchange with a wrong verifier", () -> GoogleOAuth.exchange(http, tokenUrl,
                    MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, "the-code", "x", "http://127.0.0.1:4444"));
            check("wrong verifier rejected", wrongVerifier != null);
            CloudException badSecret = expectFail("bad client secret", () -> GoogleOAuth.exchange(http, tokenUrl,
                    MockDriveServer.CLIENT_ID, "nope", "the-code", verifier, "http://127.0.0.1:4444"));
            check("bad client message", badSecret != null && badSecret.status == 401 && badSecret.getMessage().contains("client ID or client secret"));

            s.exchangeReturnsRefresh = false;
            CloudException noRefresh = expectFail("no refresh token returned", () -> GoogleOAuth.exchange(http, tokenUrl,
                    MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, "the-code", verifier, "http://127.0.0.1:4444"));
            check("no refresh token explained", noRefresh != null && noRefresh.getMessage().contains("long-lived"));

            GoogleOAuth.Tokens r = GoogleOAuth.refresh(http, tokenUrl, MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, "RT-oauth");
            check("refresh gives access token only", r.accessToken != null && r.refreshToken == null);
            s.revoked = true;
            CloudException gone = expectFail("revoked refresh", () -> GoogleOAuth.refresh(http, tokenUrl,
                    MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, "RT-oauth"));
            check("revoked says sign in again", gone != null && gone.status == 401 && gone.getMessage().contains("sign in again"));
        } finally {
            s.stop();
        }
    }

    // ------------------------------------------------------------------ loopback

    private static String get(String url) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + ":" + r.body();
    }

    private static void testLoopback() throws Exception {
        // success: junk and wrong-state requests are ignored until the real redirect arrives
        try (LoopbackReceiver rx = new LoopbackReceiver()) {
            check("redirect uri is loopback", rx.redirectUri().matches("http://127\\.0\\.0\\.1:\\d+"));
            AtomicReference<Object> result = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    result.set(rx.awaitCode("STATE1", 10_000));
                } catch (Exception e) {
                    result.set(e);
                }
            });
            t.start();
            check("favicon ignored", get(rx.redirectUri() + "/favicon.ico").startsWith("404:"));
            check("wrong state refused", get(rx.redirectUri() + "/?code=evil&state=OTHER").startsWith("400:"));
            check("real redirect accepted", get(rx.redirectUri() + "/?code=4%2F0AbC%20d&state=STATE1").startsWith("200:"));
            t.join(5000);
            eq("code decoded", "4/0AbC d", result.get());
        }
        // denied
        try (LoopbackReceiver rx = new LoopbackReceiver()) {
            AtomicReference<Object> result = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    result.set(rx.awaitCode("S2", 10_000));
                } catch (Exception e) {
                    result.set(e);
                }
            });
            t.start();
            get(rx.redirectUri() + "/?error=access_denied&state=S2");
            t.join(5000);
            check("denied raises 403", result.get() instanceof CloudException && ((CloudException) result.get()).status == 403);
        }
        // timeout
        try (LoopbackReceiver rx = new LoopbackReceiver()) {
            CloudException e = expectFail("timeout", () -> rx.awaitCode("S3", 300));
            eq("timeout status", 408, e == null ? -1 : e.status);
        }
    }

    // ------------------------------------------------------------------ drive

    private static GoogleDriveClient drive(MockDriveServer s) {
        return new GoogleDriveClient(new JdkTransport(), s.base(), s.base() + "/token",
                MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, s.refreshToken);
    }

    private static String text(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File download(CloudClient c, String path) throws Exception {
        File f = File.createTempFile("gdl", ".bin");
        f.deleteOnExit();
        c.download(path, f);
        return f;
    }

    private static MockDriveServer seeded(String token) throws Exception {
        GoogleDriveClient.retryBaseMs = 1;
        MockDriveServer s = new MockDriveServer(token);
        MockDriveServer.F docs = s.add(s.rootId, "Docs", MockDriveServer.FOLDER, null);
        s.add(docs.id, "a.txt", "text/plain", "hello a");
        s.add(docs.id, "Report", MockDriveServer.DOC, null);
        s.add(docs.id, "dup.txt", "text/plain", "first");
        s.add(docs.id, "dup.txt", "text/plain", "second");
        s.add(docs.id, "dup.txt", "text/plain", "third");
        s.add(docs.id, "it's \"quoted\" \\ name.txt", "text/plain", "quoted");
        s.add(docs.id, "a/b.txt", "text/plain", "slash");
        s.add(docs.id, "Survey", MockDriveServer.FORM, null);                 // cannot be shown
        MockDriveServer.F gone = s.add(docs.id, "old.txt", "text/plain", "x");  // in the trash
        gone.trashed = true;
        MockDriveServer.F sc = s.add(s.rootId, "Docs shortcut", MockDriveServer.SHORTCUT, null);
        sc.targetId = docs.id;
        sc.targetMime = MockDriveServer.FOLDER;
        s.add(s.rootId, "top.txt", "text/plain", "top");
        return s;
    }

    private static void testDriveBrowse() throws Exception {
        MockDriveServer s = seeded("RT-browse");
        try {
            GoogleDriveClient c = drive(s);
            eq("root listing", Arrays.asList("/Docs shortcut/", "/Docs/", "/top.txt").stream().sorted().collect(java.util.stream.Collectors.toList()),
                    names(c.list("/")));
            List<String> inDocs = names(c.list("/Docs"));
            eq("docs listing (pagination of 2 per page, duplicates numbered, forms/trash hidden)", Arrays.asList(
                    "/Docs/Report.docx", "/Docs/a.txt", "/Docs/a\u2215b.txt", "/Docs/dup (2).txt", "/Docs/dup (3).txt",
                    "/Docs/dup.txt", "/Docs/it's \"quoted\" \\ name.txt"), inDocs);
            check("listing used several pages", s.log.stream().filter(l -> l.equals("GET /drive/v3/files")).count() >= 4);
            eq("shortcut lists its target", inDocs.size(), names(c.list("/Docs shortcut")).size());

            CloudEntry e = c.stat("/Docs/a.txt");
            check("stat file", !e.dir && e.size == 7 && e.modified > 0);
            check("stat folder", c.stat("/Docs").dir && c.stat("/").dir);
            check("stat shortcut-to-folder is a folder", c.stat("/Docs shortcut").dir);
            eq("stat native has unknown size", -1L, c.stat("/Docs/Report.docx").size);
            CloudException nf = expectFail("stat missing", () -> c.stat("/Docs/nope.txt"));
            check("missing is not-found", nf != null && nf.isNotFound());
            CloudException hidden = expectFail("form hidden", () -> c.stat("/Docs/Survey"));
            check("form not reachable", hidden != null && hidden.isNotFound());
            check("trashed hidden", expectFail("trashed", () -> c.stat("/Docs/old.txt")) != null);

            eq("download a.txt", "hello a", text(download(c, "/Docs/a.txt")));
            eq("download via shortcut folder", "hello a", text(download(c, "/Docs shortcut/a.txt")));
            eq("download first duplicate", "first", text(download(c, "/Docs/dup.txt")));
            eq("download 2nd duplicate", "second", text(download(c, "/Docs/dup (2).txt")));
            eq("download 3rd duplicate", "third", text(download(c, "/Docs/dup (3).txt")));
            eq("download name with quote/backslash", "quoted", text(download(c, "/Docs/it's \"quoted\" \\ name.txt")));
            eq("download name with slash", "slash", text(download(c, "/Docs/a\u2215b.txt")));
            eq("export google doc as docx", "EXPORT[application/vnd.openxmlformats-officedocument.wordprocessingml.document]Report",
                    text(download(c, "/Docs/Report.docx")));
            CloudException folderDl = expectFail("download folder", () -> download(c, "/Docs"));
            check("folder download refused", folderDl != null);
            // resolution is cached: a second walk does not repeat the lookups
            int before = s.apiCalls.get();
            c.stat("/Docs/a.txt");
            eq("cached stat makes no request", before, s.apiCalls.get());
        } finally {
            s.stop();
        }
    }

    private static void testDriveChanges() throws Exception {
        MockDriveServer s = seeded("RT-changes");
        try {
            GoogleDriveClient c = drive(s);
            MockDriveServer.F docs = s.byName(s.rootId, "Docs");

            // create
            File small = randomFile(5000, 7);
            c.upload("/Docs/new.bin", small, "application/octet-stream");
            MockDriveServer.F created = s.byName(docs.id, "new.bin");
            check("uploaded file exists with the right bytes", created != null
                    && Arrays.equals(created.bytes, Files.readAllBytes(small.toPath())));
            eq("upload session carries no bearer token", "null", s.lastSessionAuth);
            eq("stat after upload", 5000L, c.stat("/Docs/new.bin").size);
            check("listing includes it", names(c.list("/Docs")).contains("/Docs/new.bin"));

            // overwrite in place (same id)
            File v2 = randomFile(77, 8);
            c.upload("/Docs/new.bin", v2, "application/octet-stream");
            check("overwrite keeps the same file", s.byName(docs.id, "new.bin") == created && created.bytes.length == 77);
            eq("stat after overwrite", 77L, c.stat("/Docs/new.bin").size);

            // empty file (what the Files app does before writing)
            File empty = File.createTempFile("empty", ".bin");
            c.upload("/Docs/empty.txt", empty, "text/plain");
            check("empty file created", s.byName(docs.id, "empty.txt") != null && s.byName(docs.id, "empty.txt").bytes.length == 0);
            c.upload("/Docs/empty.txt", v2, "text/plain");
            eq("empty then filled", 77, s.byName(docs.id, "empty.txt").bytes.length);

            // native docs cannot be overwritten; folders cannot be replaced by files
            CloudException docWrite = expectFail("overwrite google doc", () -> c.upload("/Docs/Report.docx", v2, "x/y"));
            check("doc overwrite refused clearly", docWrite != null && docWrite.getMessage().contains("Google Docs"));
            check("folder overwrite refused", expectFail("overwrite folder", () -> c.upload("/Docs", v2, "x/y")) != null);
            check("upload into a missing folder fails", expectFail("missing parent", () -> c.upload("/Nope/x.txt", v2, "x/y")) != null);

            // folders
            c.mkdir("/Docs/Sub");
            MockDriveServer.F sub = s.byName(docs.id, "Sub");
            check("folder created", sub != null && MockDriveServer.FOLDER.equals(sub.mime));
            check("mkdir existing refused", expectFail("mkdir twice", () -> c.mkdir("/Docs/Sub")) != null);
            c.upload("/Docs/Sub/inner.txt", v2, "text/plain");
            check("file in new folder", s.byName(sub.id, "inner.txt") != null);

            // rename and move
            c.move("/Docs/a.txt", "/Docs/b.txt", false);
            check("renamed", s.byName(docs.id, "b.txt") != null && s.byName(docs.id, "a.txt") == null);
            eq("renamed file readable at new path", "hello a", text(download(c, "/Docs/b.txt")));
            check("old path gone", expectFail("old path", () -> c.stat("/Docs/a.txt")) != null);
            c.move("/Docs/b.txt", "/Docs/Sub/b.txt", false);
            check("moved to another folder", s.byName(sub.id, "b.txt") != null && s.byName(docs.id, "b.txt") == null);
            c.move("/Docs/Sub", "/Sub2", true);
            check("moved folder to root and renamed", s.byName(s.rootId, "Sub2") == sub && s.byName(docs.id, "Sub") == null);
            eq("children follow the folder", "hello a", text(download(c, "/Sub2/b.txt")));
            CloudException clash = expectFail("move onto existing", () -> c.move("/top.txt", "/Sub2", false));
            eq("clash is 412", 412, clash == null ? -1 : clash.status);
            check("folder into itself refused", expectFail("into itself", () -> c.move("/Sub2", "/Sub2/x", true)) != null);
            c.move("/Docs/Report.docx", "/Docs/Final.docx", false);
            check("renaming an exported doc drops the extension", s.byName(docs.id, "Final") != null);
            eq("renamed doc still exports", "EXPORT[application/vnd.openxmlformats-officedocument.wordprocessingml.document]Final",
                    text(download(c, "/Docs/Final.docx")));

            // delete = trash
            c.delete("/Sub2/b.txt", false);
            MockDriveServer.F trashed = null;
            synchronized (s.files) {
                for (MockDriveServer.F f : s.files.values()) if (f.name.equals("b.txt")) trashed = f;
            }
            check("delete trashes instead of destroying", trashed != null && trashed.trashed);
            eq("deleted is gone from the listing", Arrays.asList("/Sub2/inner.txt"), names(c.list("/Sub2")));
            c.delete("/Sub2", true);
            check("folder deleted", expectFail("deleted folder", () -> c.list("/Sub2")) != null);
            check("root cannot be deleted", expectFail("delete root", () -> c.delete("/", true)) != null);
        } finally {
            s.stop();
        }
    }

    private static void testDriveTokens() throws Exception {
        MockDriveServer s = seeded("RT-tokens");
        try {
            GoogleDriveClient c = drive(s);
            c.list("/");
            c.stat("/top.txt");
            eq("one refresh for many calls", 1, s.refreshes.get());
            // a second client object (the provider makes a new one per call) reuses the cached token
            drive(s).list("/Docs");
            eq("token shared between client objects", 1, s.refreshes.get());

            // the access token dies on the server: 401, one refresh, the call succeeds
            s.invalidateAccessToken();
            eq("call after token death works", "top", text(download(drive(s), "/top.txt")));
            eq("refreshed exactly once more", 2, s.refreshes.get());

            // throttling
            s.throttle429 = 2;
            eq("429 retried", "top", text(download(drive(s), "/top.txt")));
            s.throttle403 = 1;
            eq("403 rateLimitExceeded retried", "top", text(download(drive(s), "/top.txt")));
            s.throttle429 = 100;
            CloudException limited = expectFail("endless throttling", () -> drive(s).list("/Docs"));
            check("gives up on endless throttling", limited != null && limited.status == 429);
            s.throttle429 = 0;

            // revoked sign-in
            s.revoked = true;
            s.invalidateAccessToken();
            CloudException revoked = expectFail("revoked", () -> drive(s).list("/"));
            check("revoked asks to sign in again", revoked != null && revoked.status == 401 && revoked.getMessage().contains("sign in again"));
        } finally {
            s.stop();
        }
    }

    private static void testDriveErrors() throws Exception {
        MockDriveServer s = seeded("RT-errors");
        try {
            GoogleDriveClient c = drive(s);
            c.list("/");
            s.failWith = "accessNotConfigured";
            CloudException off = expectFail("api disabled", () -> drive(s).list("/Docs"));
            check("api-disabled hint", off != null && off.getMessage().contains("Google Drive API"));
            s.failWith = "storageQuotaExceeded";
            CloudException full = expectFail("quota", () -> drive(s).mkdir("/Full"));
            check("quota is 507 and explained", full != null && full.status == 507 && full.getMessage().contains("storage is full"));
            s.failWith = null;
            c.stat("/top.txt");
            check("recovers after errors", true);
        } finally {
            s.stop();
        }
        String[][] bad = {{"", "s", "r"}, {"c", "", "r"}, {"c", "s", ""}, {"  ", "s", "r"}};
        for (String[] b : bad) {
            try {
                new GoogleDriveClient(null, b[0], b[1], b[2]);
                check("drive validation " + Arrays.toString(b), false);
            } catch (IllegalArgumentException e) {
                check("drive validation " + Arrays.toString(b), e.getMessage() != null && !e.getMessage().isEmpty());
            }
        }
        Account a = new Account();
        a.type = Account.Type.GOOGLE_DRIVE;
        a.clientId = "c";
        a.clientSecret = "s";
        a.secret = "r";
        a.username = "me@example.com";
        check("factory builds a drive client", CloudClients.create(a, null) instanceof GoogleDriveClient);
        check("copy keeps google fields", a.copy().clientId.equals("c") && a.copy().clientSecret.equals("s"));
        eq("summary", "Google Drive \u00b7 me@example.com", a.summary());
        check("empty list helper", Collections.emptyList().isEmpty());
    }
}
