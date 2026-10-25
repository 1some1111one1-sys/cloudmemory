package com.cloudbridge.app.core;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/** Plain-Java test runner (no frameworks): javac the core + tests, then run TestMain. Exit code 1 on any failure. */
public final class TestMain {
    private static int passed;
    private static int failed;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) System.out.println("   expected: " + expected + "\n   actual:   " + actual);
        check(name, ok);
    }

    private static void run(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL (exception): " + name + " -> " + t);
            t.printStackTrace(System.out);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static CloudException expectFail(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (CloudException e) {
            passed++;
            return e;
        } catch (Exception e) {
            failed++;
            System.out.println("FAIL: " + name + " threw " + e + " instead of CloudException");
            return null;
        }
        failed++;
        System.out.println("FAIL: " + name + " did not throw");
        return null;
    }

    private static File tmpDir() throws IOException {
        return Files.createTempDirectory("cbtest").toFile();
    }

    private static File randomFile(int size, long seed) throws IOException {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        File f = File.createTempFile("cbt", ".bin");
        f.deleteOnExit();
        Files.write(f.toPath(), b);
        return f;
    }

    private static List<String> names(List<CloudEntry> l) {
        List<String> n = new ArrayList<>();
        for (CloudEntry e : l) n.add(e.path + (e.dir ? "/" : ""));
        Collections.sort(n);
        return n;
    }

    public static void main(String[] args) {
        run("paths", TestMain::testPaths);
        run("ids", TestMain::testIds);
        run("sigv4 vectors", TestMain::testSigV4Vectors);
        run("webdav parsing", TestMain::testWebDavParsing);
        run("webdav e2e relative hrefs", () -> testWebDavEndToEnd(false, "/remote.php/dav/files/me"));
        run("webdav e2e absolute hrefs", () -> testWebDavEndToEnd(true, "/dav"));
        run("webdav e2e root server", () -> testWebDavEndToEnd(false, ""));
        run("webdav errors", TestMain::testWebDavErrors);
        run("s3 e2e (url-encoded listing)", () -> testS3EndToEnd(true));
        run("s3 e2e (server ignores encoding-type)", () -> testS3EndToEnd(false));
        run("s3 errors", TestMain::testS3Errors);
        run("s3 validation", TestMain::testValidation);
        System.out.println(passed + " checks passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ unit tests

    private static void testPaths() {
        eq("normalize dots", "/a/c", Paths.normalize("a//b/../c/./"));
        eq("normalize root", "/", Paths.normalize("/"));
        eq("normalize empty", "/", Paths.normalize(""));
        eq("normalize null", "/", Paths.normalize(null));
        eq("normalize above root", "/", Paths.normalize("../.."));
        eq("parent", "/a", Paths.parent("/a/b"));
        eq("parent of top", "/", Paths.parent("/a"));
        eq("parent of root", "/", Paths.parent("/"));
        eq("name", "b.txt", Paths.name("/a/b.txt"));
        eq("name of root", "", Paths.name("/"));
        eq("join root", "/x", Paths.join("/", "x"));
        eq("join", "/a/x y", Paths.join("/a", "x y"));
        eq("sanitize slash", "a_b", Paths.sanitizeName("a/b"));
        eq("sanitize empty", "_", Paths.sanitizeName(""));
        eq("sanitize dotdot", "_", Paths.sanitizeName(".."));
        eq("sanitize trim", "x", Paths.sanitizeName("  x "));
        eq("encodeSegment", "a%20b%2Bc%25%C3%A9%2F%C3%BC~-._", Paths.encodeSegment("a b+c%é/ü~-._"));
        eq("encodePath", "/a%20b/c", Paths.encodePath("/a b/c"));
        eq("encodePath root", "/", Paths.encodePath("/"));
        String[] samples = {"plain", "with space", "café (1) +x", "100%", "😀 emoji", "a+b", "中文.txt", "%41 literal"};
        for (String s : samples) eq("roundtrip " + s, s, Paths.decode(Paths.encodeSegment(s)));
        eq("decode emoji", "😀", Paths.decode("%F0%9F%98%80"));
        eq("decode plus stays", "a+b", Paths.decode("a+b"));
        eq("decode trailing percent", "100%", Paths.decode("100%"));
        eq("decode bad escape", "%zz", Paths.decode("%zz"));
        eq("decode short escape", "%4", Paths.decode("%4"));
        eq("decode ok", "A", Paths.decode("%41"));
        eq("decode raw unicode", "café 😀", Paths.decode("café 😀"));
        check("isUnder child", Paths.isUnder("/a", "/a/b"));
        check("isUnder sibling prefix", !Paths.isUnder("/a", "/ab"));
        check("isUnder root", Paths.isUnder("/", "/a"));
        check("isUnder self", !Paths.isUnder("/a", "/a"));
        eq("mime jpg", "image/jpeg", Paths.mimeFor("x.JPG"));
        eq("mime none", Paths.MIME_DEFAULT, Paths.mimeFor("noext"));
        eq("mime trailing dot", Paths.MIME_DEFAULT, Paths.mimeFor("x."));
    }

    private static void testIds() {
        eq("encode", "ab12:/x/y", Ids.encode("ab12", "/x/y"));
        eq("account", "ab12", Ids.accountOf("ab12:/x/y"));
        eq("path", "/x/y", Ids.pathOf("ab12:/x/y"));
        eq("path root", "/", Ids.pathOf("ab12:/"));
        eq("path with colon", "/a:b", Ids.pathOf("ab12:/a:b"));
        try {
            Ids.pathOf("nocolon");
            check("bad id throws", false);
        } catch (IllegalArgumentException e) {
            check("bad id throws", true);
        }
    }

    /** Examples from "Authenticating Requests: Using the Authorization Header (AWS Signature Version 4)" in the S3 docs. */
    private static void testSigV4Vectors() {
        long t = Instant.parse("2013-05-24T00:00:00Z").toEpochMilli();
        String ak = "AKIAIOSFODNN7EXAMPLE";
        String sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        Map<String, String> none = new TreeMap<>();

        Map<String, String> range = new TreeMap<>();
        range.put("Range", "bytes=0-9");
        S3Signer.Result r1 = S3Signer.sign("GET", "examplebucket.s3.amazonaws.com", "/test.txt", none, range, empty, ak, sk, "us-east-1", t);
        check("get object signed headers", r1.authorization.contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"));
        check("get object signature", r1.authorization.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
        eq("amz date", "20130524T000000Z", r1.amzDate);

        String body = "Welcome to Amazon S3.";
        String bodyHash = Crypto.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        eq("put payload hash", "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", bodyHash);
        Map<String, String> put = new TreeMap<>();
        put.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        put.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        S3Signer.Result r2 = S3Signer.sign("PUT", "examplebucket.s3.amazonaws.com", "/" + Paths.encodeSegment("test$file.text"),
                none, put, bodyHash, ak, sk, "us-east-1", t);
        check("put object signature", r2.authorization.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"));

        Map<String, String> lifecycle = new TreeMap<>();
        lifecycle.put("lifecycle", "");
        S3Signer.Result r3 = S3Signer.sign("GET", "examplebucket.s3.amazonaws.com", "/", lifecycle, none, empty, ak, sk, "us-east-1", t);
        check("lifecycle signature", r3.authorization.endsWith("Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"));
        eq("lifecycle query", "lifecycle=", r3.canonicalQuery);

        Map<String, String> list = new TreeMap<>();
        list.put("prefix", "J");
        list.put("max-keys", "2");
        S3Signer.Result r4 = S3Signer.sign("GET", "examplebucket.s3.amazonaws.com", "/", list, none, empty, ak, sk, "us-east-1", t);
        check("list signature", r4.authorization.endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"));
        eq("list query sorted", "max-keys=2&prefix=J", r4.canonicalQuery);
    }

    private static void testWebDavParsing() throws Exception {
        WebDavClient c = new WebDavClient(null, "https://cloud.example.com/remote.php/dav/files/me", "me", "x");
        String xml = "<?xml version=\"1.0\"?>"
                + "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:s=\"http://sabredav.org/ns\">"
                // the folder itself, absolute href
                + "<d:response><d:href>https://cloud.example.com/remote.php/dav/files/me/Docs/</d:href><d:propstat><d:prop>"
                + "<d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Tue, 02 Jan 2024 03:04:05 GMT</d:getlastmodified>"
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                // a file with spaces/unicode/plus encoded, other props in a 404 propstat
                + "<d:response><d:href>/remote.php/dav/files/me/Docs/caf%C3%A9%20menu%20%2B%20wine.pdf</d:href>"
                + "<d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12345</d:getcontentlength>"
                + "<d:getlastmodified>Tue, 02 Jan 2024 03:04:05 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
                + "<d:propstat><d:prop><oc:size/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"
                // a sub-folder
                + "<d:response><d:href>/remote.php/dav/files/me/Docs/Sub%20folder/</d:href><d:propstat><d:prop>"
                + "<d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                // something outside the base path must be ignored
                + "<d:response><d:href>/somewhere/else</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                + "</d:multistatus>";
        List<CloudEntry> e = c.parseMultiStatus(xml.getBytes(StandardCharsets.UTF_8));
        eq("entry count", 3, e.size());
        eq("folder path", "/Docs", e.get(0).path);
        check("folder is dir", e.get(0).dir);
        eq("file path", "/Docs/café menu + wine.pdf", e.get(1).path);
        check("file is not dir", !e.get(1).dir);
        eq("file size", 12345L, e.get(1).size);
        eq("file mtime", Instant.parse("2024-01-02T03:04:05Z").toEpochMilli(), e.get(1).modified);
        eq("subfolder path", "/Docs/Sub folder", e.get(2).path);
        check("subfolder is dir", e.get(2).dir);
        eq("mime from name", "application/pdf", e.get(1).mime());

        // A server without a path prefix, with hrefs lacking resourcetype (dir detected by trailing slash).
        WebDavClient root = new WebDavClient(null, "http://nas.local:5005", "", "");
        List<CloudEntry> r = root.parseMultiStatus(("<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/a/</D:href><D:propstat><D:prop/>"
                + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>").getBytes(StandardCharsets.UTF_8));
        check("no resourcetype uses trailing slash", r.size() == 1 && r.get(0).dir && r.get(0).path.equals("/a"));

        CloudException bad = expectFail("html is not webdav", () -> c.parseMultiStatus("<html><body>Login</body></html>".getBytes()));
        check("html error mentions WebDAV", bad != null && bad.getMessage().contains("WebDAV"));
        expectFail("doctype rejected", () -> c.parseMultiStatus(("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&e;</d:href></d:response></d:multistatus>").getBytes()));
    }

    // ------------------------------------------------------------------ WebDAV end to end

    private static void testWebDavEndToEnd(boolean absoluteHrefs, String prefix) throws Exception {
        String label = (absoluteHrefs ? "abs" : "rel") + prefix + ": ";
        File root = tmpDir();
        MockDavServer s = new MockDavServer(root, prefix, "me", "p@ss:word", absoluteHrefs);
        try {
            WebDavClient c = new WebDavClient(new JdkTransport(), s.url() + "/", "me", "p@ss:word");   // trailing slash on purpose
            eq(label + "empty root", Collections.emptyList(), c.list("/"));

            c.mkdir("/Photos");
            c.mkdir("/Photos/Trip 2024");
            String weird = "/Photos/Trip 2024/café (1) +x #%.bin";
            File src = randomFile(300 * 1024, 1);
            c.upload(weird, src, "application/octet-stream");
            check(label + "file on disk", new File(root, weird.substring(1)).isFile());

            List<CloudEntry> inTrip = c.list("/Photos/Trip 2024");
            eq(label + "list trip", Arrays.asList(weird), names(inTrip));
            eq(label + "list size", 300L * 1024, inTrip.get(0).size);
            eq(label + "list root", Arrays.asList("/Photos/"), names(c.list("/")));

            CloudEntry dir = c.stat("/Photos");   // no trailing slash: server redirects, client must follow
            check(label + "stat dir", dir.dir);
            CloudEntry file = c.stat(weird);
            check(label + "stat file", !file.dir && file.size == 300 * 1024 && file.modified > 0);
            CloudException nf = expectFail(label + "stat missing", () -> c.stat("/nope/missing.txt"));
            check(label + "stat missing is 404", nf != null && nf.isNotFound());

            File back = File.createTempFile("back", ".bin");
            c.download(weird, back);
            check(label + "download equals upload", Arrays.equals(Files.readAllBytes(src.toPath()), Files.readAllBytes(back.toPath())));

            // zero-byte file (what createDocument does before the app writes content)
            File empty = File.createTempFile("empty", ".bin");
            c.upload("/Photos/empty.txt", empty, "text/plain");
            eq(label + "empty upload size", 0L, c.stat("/Photos/empty.txt").size);

            // overwrite
            File v2 = randomFile(10, 2);
            c.upload("/Photos/empty.txt", v2, "text/plain");
            eq(label + "overwrite size", 10L, c.stat("/Photos/empty.txt").size);

            c.move(weird, "/Photos/renamed file.bin", false);
            check(label + "moved file", new File(root, "Photos/renamed file.bin").isFile() && !new File(root, weird.substring(1)).exists());
            c.move("/Photos/Trip 2024", "/Photos/Trip 2025", true);
            check(label + "moved dir", new File(root, "Photos/Trip 2025").isDirectory());
            eq(label + "list photos", Arrays.asList("/Photos/Trip 2025/", "/Photos/empty.txt", "/Photos/renamed file.bin"), names(c.list("/Photos")));

            CloudException exists = expectFail(label + "move onto existing", () -> c.move("/Photos/empty.txt", "/Photos/renamed file.bin", false));
            eq(label + "move onto existing is 412", 412, exists == null ? -1 : exists.status);

            c.delete("/Photos", true);
            eq(label + "deleted tree", Collections.emptyList(), c.list("/"));
            check(label + "deleted on disk", !new File(root, "Photos").exists());
        } finally {
            s.stop();
        }
    }

    private static void testWebDavErrors() throws Exception {
        File root = tmpDir();
        MockDavServer s = new MockDavServer(root, "/dav", "me", "secret", false);
        try {
            WebDavClient bad = new WebDavClient(new JdkTransport(), s.url(), "me", "WRONG");
            CloudException e = expectFail("wrong password", () -> bad.list("/"));
            check("wrong password is 401 with hint", e != null && e.status == 401 && e.getMessage().contains("password"));

            WebDavClient c = new WebDavClient(new JdkTransport(), s.url(), "me", "secret");
            CloudException conflict = expectFail("upload without parent", () -> c.upload("/missing/f.txt", randomFile(5, 3), "text/plain"));
            eq("missing parent is 409", 409, conflict == null ? -1 : conflict.status);
            c.mkdir("/x");
            CloudException dup = expectFail("mkdir twice", () -> c.mkdir("/x"));
            eq("mkdir twice is 405", 405, dup == null ? -1 : dup.status);
        } finally {
            s.stop();
        }
        // A web page that is not WebDAV at all (200 + HTML for everything).
        HttpServer html = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        html.createContext("/", ex -> {
            byte[] b = "<html><body>Welcome</body></html>".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        html.start();
        try {
            WebDavClient c = new WebDavClient(new JdkTransport(), "http://127.0.0.1:" + html.getAddress().getPort(), "", "");
            CloudException e = expectFail("html server", () -> c.list("/"));
            check("html server explains", e != null && e.getMessage().contains("WebDAV"));
        } finally {
            html.stop(0);
        }
        // Credentials must not follow a redirect to another host.
        final List<String> seenAuth = Collections.synchronizedList(new ArrayList<>());
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", ex -> {
            seenAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        other.start();
        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", ex -> {
            ex.getResponseHeaders().set("Location", "http://localhost:" + other.getAddress().getPort() + "/stolen/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        redirector.start();
        try {
            WebDavClient c = new WebDavClient(new JdkTransport(), "http://127.0.0.1:" + redirector.getAddress().getPort(), "me", "secret");
            expectFail("cross-host redirect", () -> c.list("/"));
            check("auth not sent to other host", seenAuth.size() == 1 && seenAuth.get(0).equals("null"));
        } finally {
            redirector.stop(0);
            other.stop(0);
        }
    }

    // ------------------------------------------------------------------ S3 end to end

    private static void testS3EndToEnd(boolean honorEncoding) throws Exception {
        String label = (honorEncoding ? "enc" : "raw") + ": ";
        MockS3Server s = new MockS3Server(honorEncoding);
        try {
            S3Client c = new S3Client(new JdkTransport(), s.endpoint(), MockS3Server.REGION, MockS3Server.BUCKET,
                    MockS3Server.ACCESS_KEY, MockS3Server.SECRET_KEY, true);
            eq(label + "empty bucket", Collections.emptyList(), c.list("/"));

            c.mkdir("/docs");
            c.mkdir("/empty");
            File f1 = randomFile(200 * 1024, 11);
            c.upload("/docs/hello world+1 (a).txt", f1, "text/plain");
            c.upload("/docs/sub/é.txt", randomFile(5, 12), "text/plain");
            c.upload("/docs/a.txt", randomFile(7, 13), "text/plain");
            c.upload("/docs/b.txt", randomFile(8, 14), "text/plain");
            c.upload("/docs/c.txt", randomFile(9, 15), "text/plain");
            c.upload("/top.bin", randomFile(1000, 16), null);
            check(label + "keys stored", s.objects.containsKey("docs/hello world+1 (a).txt") && s.objects.containsKey("docs/sub/é.txt"));
            check(label + "folder marker stored", s.objects.containsKey("docs/") && s.objects.containsKey("empty/"));

            eq(label + "list root", Arrays.asList("/docs/", "/empty/", "/top.bin"), names(c.list("/")));
            List<CloudEntry> docs = c.list("/docs");   // 5 entries + marker, page size 3 -> pagination
            eq(label + "list docs", Arrays.asList("/docs/a.txt", "/docs/b.txt", "/docs/c.txt", "/docs/hello world+1 (a).txt", "/docs/sub/"), names(docs));
            check(label + "paginated", s.requests > 6);
            for (CloudEntry e : docs) {
                if (e.path.endsWith("hello world+1 (a).txt")) eq(label + "list size", 200L * 1024, e.size);
            }
            eq(label + "list empty folder", Collections.emptyList(), c.list("/empty"));
            eq(label + "list nested", Arrays.asList("/docs/sub/é.txt"), names(c.list("/docs/sub")));

            check(label + "stat dir via prefix", c.stat("/docs").dir);
            check(label + "stat marker-only dir", c.stat("/empty").dir);
            CloudEntry top = c.stat("/top.bin");
            check(label + "stat file", !top.dir && top.size == 1000 && top.modified > 0);
            CloudException nf = expectFail(label + "stat missing", () -> c.stat("/nope"));
            check(label + "stat missing is 404", nf != null && nf.isNotFound());

            File back = File.createTempFile("back", ".bin");
            c.download("/docs/hello world+1 (a).txt", back);
            check(label + "download equals upload", Arrays.equals(Files.readAllBytes(f1.toPath()), Files.readAllBytes(back.toPath())));

            c.move("/docs/hello world+1 (a).txt", "/docs/sub/moved & renamed.txt", false);
            check(label + "file moved", s.objects.containsKey("docs/sub/moved & renamed.txt") && !s.objects.containsKey("docs/hello world+1 (a).txt"));
            c.move("/docs/sub", "/docs/sub2", true);
            check(label + "dir moved", s.objects.containsKey("docs/sub2/é.txt") && s.objects.containsKey("docs/sub2/moved & renamed.txt")
                    && !s.objects.containsKey("docs/sub/é.txt"));
            c.delete("/top.bin", false);
            check(label + "file deleted", !s.objects.containsKey("top.bin"));
            c.delete("/docs", true);
            eq(label + "dir deleted", new TreeSet<>(Arrays.asList("empty/")), new TreeSet<>(s.objects.keySet()));
            check(label + "all signatures valid", s.problems.isEmpty());
            if (!s.problems.isEmpty()) System.out.println("   " + s.problems);
        } finally {
            s.stop();
        }
    }

    private static void testS3Errors() throws Exception {
        MockS3Server s = new MockS3Server(true);
        try {
            S3Client wrongSecret = new S3Client(new JdkTransport(), s.endpoint(), MockS3Server.REGION, MockS3Server.BUCKET,
                    MockS3Server.ACCESS_KEY, "not-the-secret", true);
            CloudException e = expectFail("wrong secret", () -> wrongSecret.list("/"));
            check("wrong secret is 403 and says why", e != null && e.status == 403 && e.getMessage().contains("SignatureDoesNotMatch"));

            S3Client wrongBucket = new S3Client(new JdkTransport(), s.endpoint(), MockS3Server.REGION, "otherbucket",
                    MockS3Server.ACCESS_KEY, MockS3Server.SECRET_KEY, true);
            CloudException nb = expectFail("wrong bucket", () -> wrongBucket.list("/"));
            check("wrong bucket is 404", nb != null && nb.status == 404 && nb.getMessage().contains("NoSuchBucket"));

            S3Client ok = new S3Client(new JdkTransport(), s.endpoint(), MockS3Server.REGION, MockS3Server.BUCKET,
                    MockS3Server.ACCESS_KEY, MockS3Server.SECRET_KEY, true);
            expectFail("move missing folder", () -> ok.move("/ghost", "/ghost2", true));
        } finally {
            s.stop();
        }
    }

    private static void testValidation() {
        ThrowingRunnable[] bad = {
                () -> new WebDavClient(null, "ftp://x", "", ""),
                () -> new WebDavClient(null, "", "", ""),
                () -> new S3Client(null, "", "r", "b", "a", "s", true),
                () -> new S3Client(null, "https://s3.amazonaws.com", "r", "", "a", "s", true),
                () -> new S3Client(null, "https://s3.amazonaws.com", "r", "b", "", "s", true),
        };
        for (int i = 0; i < bad.length; i++) {
            try {
                bad[i].run();
                check("validation " + i, false);
            } catch (IllegalArgumentException e) {
                check("validation " + i, e.getMessage() != null && !e.getMessage().isEmpty());
            } catch (Exception e) {
                check("validation " + i + " wrong exception " + e, false);
            }
        }
        // an endpoint without scheme defaults to https and a blank region to us-east-1
        new S3Client(null, "s3.eu-west-1.amazonaws.com", "", "b", "a", "s", true);
        check("endpoint without scheme accepted", true);
    }
}
