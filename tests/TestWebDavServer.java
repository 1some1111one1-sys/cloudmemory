package com.cloudbridge.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.run;

/** Tests for the local WebDAV bridge, using in-memory clouds and a real HTTP client. */
final class TestWebDavServer {
    private TestWebDavServer() {
    }

    static void runAll() {
        run("webdav bridge", TestWebDavServer::testBridge);
        run("webdav bridge raw protocol", TestWebDavServer::testRaw);
    }

    /** A cloud in memory. */
    static final class MemoryCloud implements CloudClient {
        final TreeMap<String, byte[]> files = new TreeMap<>();   // path -> data (null = folder)
        boolean failDownloads;

        @Override
        public List<CloudEntry> list(String dir) throws IOException {
            String d = Paths.normalize(dir);
            if (!d.equals("/") && !files.containsKey(d)) throw new CloudException(404, "Not found: " + d);
            List<CloudEntry> out = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (Paths.parent(e.getKey()).equals(d) && !e.getKey().equals(d)) out.add(entry(e.getKey()));
            }
            return out;
        }

        private CloudEntry entry(String p) {
            byte[] data = files.get(p);
            return new CloudEntry(p, data == null, data == null ? -1 : data.length, 1_700_000_000_000L);
        }

        @Override
        public CloudEntry stat(String path) throws IOException {
            String p = Paths.normalize(path);
            if (p.equals("/")) return new CloudEntry("/", true, -1, 0);
            if (!files.containsKey(p)) throw new CloudException(404, "Not found: " + p);
            return entry(p);
        }

        @Override
        public void download(String path, File dest) throws IOException {
            if (failDownloads) throw new CloudException(502, "the cloud is down");
            byte[] d = files.get(Paths.normalize(path));
            if (d == null) throw new CloudException(404, "Not found: " + path);
            Files.write(dest.toPath(), d);
        }

        @Override
        public void upload(String path, File src, String mime) throws IOException {
            String p = Paths.normalize(path);
            if (!Paths.parent(p).equals("/") && !files.containsKey(Paths.parent(p))) throw new CloudException(409, "no parent");
            files.put(p, Files.readAllBytes(src.toPath()));
        }

        @Override
        public void mkdir(String path) throws IOException {
            String p = Paths.normalize(path);
            if (files.containsKey(p)) throw new CloudException(405, "exists");
            files.put(p, null);
        }

        @Override
        public void delete(String path, boolean isDir) throws IOException {
            String p = Paths.normalize(path);
            if (!files.containsKey(p)) throw new CloudException(404, "Not found: " + p);
            List<String> gone = new ArrayList<>();
            for (String k : files.keySet()) if (k.equals(p) || k.startsWith(p + "/")) gone.add(k);
            for (String k : gone) files.remove(k);
        }

        @Override
        public void move(String from, String to, boolean isDir) throws IOException {
            String f = Paths.normalize(from);
            String t = Paths.normalize(to);
            if (files.containsKey(t)) throw new CloudException(412, "exists");
            List<String> keys = new ArrayList<>();
            for (String k : files.keySet()) if (k.equals(f) || k.startsWith(f + "/")) keys.add(k);
            for (String k : keys) files.put(t + k.substring(f.length()), files.remove(k));
        }
    }

    private static final String USER = "cloudbridge";
    private static final String PASS = "s3cret-pass";

    private static String auth() {
        return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpTransport.Response call(String method, String url, String body, String... headers) throws Exception {
        HttpTransport.Request r = new HttpTransport.Request(method, url);
        r.header("Authorization", auth());
        for (int i = 0; i < headers.length; i += 2) r.header(headers[i], headers[i + 1]);
        if (body != null) r.body(body.getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        return new JdkTransport().execute(r);
    }

    private static String text(HttpTransport.Response r) throws Exception {
        return new String(r.readAll(1 << 20), StandardCharsets.UTF_8);
    }

    private static List<String> names(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<D:displayname>(.*?)</D:displayname>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static void testBridge() throws Exception {
        final MemoryCloud one = new MemoryCloud();
        final MemoryCloud two = new MemoryCloud();
        one.files.put("/Docs", null);
        one.files.put("/Docs/a.txt", "hello bridge".getBytes(StandardCharsets.UTF_8));
        one.files.put("/Docs/café (1) #.txt", "x".getBytes(StandardCharsets.UTF_8));
        one.files.put("/top.bin", "0123456789".getBytes(StandardCharsets.UTF_8));
        WebDavServer.Backend backend = new WebDavServer.Backend() {
            @Override
            public List<String> names() {
                return Arrays.asList("My Drive", "Second");
            }

            @Override
            public CloudClient client(String name) throws IOException {
                if (name.equals("My Drive")) return one;
                if (name.equals("Second")) return two;
                throw new CloudException(404, "unknown");
            }
        };
        File cache = Files.createTempDirectory("wdcache").toFile();
        WebDavServer server = new WebDavServer(backend, cache, USER, PASS);
        int port = server.start(0);
        String base = "http://127.0.0.1:" + port;
        try {
            // ---- access control
            HttpTransport.Request anon = new HttpTransport.Request("PROPFIND", base + "/").header("Depth", "0");
            HttpTransport.Response r = new JdkTransport().execute(anon);
            eq("no password is refused", 401, r.code);
            check("401 asks for basic auth", String.valueOf(r.header("www-authenticate")).startsWith("Basic"));
            r.close();
            HttpTransport.Request wrong = new HttpTransport.Request("PROPFIND", base + "/").header("Depth", "0")
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("cloudbridge:nope".getBytes()));
            r = new JdkTransport().execute(wrong);
            eq("wrong password is refused", 401, r.code);
            r.close();

            r = call("OPTIONS", base + "/", null);
            eq("options", 200, r.code);
            check("options announces WebDAV", String.valueOf(r.header("dav")).contains("1"));
            r.close();

            // ---- browsing
            r = call("PROPFIND", base + "/", null, "Depth", "1");
            eq("propfind top", 207, r.code);
            List<String> top = names(text(r));
            check("top level lists every cloud as a folder", top.contains("My Drive") && top.contains("Second"));
            r = call("PROPFIND", base + "/My%20Drive/Docs/", null, "Depth", "1");
            eq("propfind folder", 207, r.code);
            String xml = text(r);
            List<String> kids = names(xml);
            check("folder children listed", kids.contains("a.txt") && kids.contains("café (1) #.txt"));
            check("hrefs are percent-encoded", xml.contains("/My%20Drive/Docs/caf%C3%A9%20%281%29%20%23.txt"));
            check("file size reported", xml.contains("<D:getcontentlength>12</D:getcontentlength>"));
            r = call("PROPFIND", base + "/My%20Drive/Docs/a.txt", null, "Depth", "0");
            eq("propfind one file", 207, r.code);
            check("depth 0 lists only itself", names(text(r)).equals(Arrays.asList("a.txt")));
            r = call("PROPFIND", base + "/Nope/", null, "Depth", "0");
            eq("unknown cloud", 404, r.code);
            r.close();
            r = call("PROPFIND", base + "/My%20Drive/missing", null, "Depth", "0");
            eq("missing file", 404, r.code);
            r.close();

            // ---- reading
            r = call("GET", base + "/My%20Drive/Docs/a.txt", null);
            eq("get", 200, r.code);
            eq("get body", "hello bridge", text(r));
            r = call("GET", base + "/My%20Drive/top.bin", null, "Range", "bytes=2-5");
            eq("range", 206, r.code);
            eq("range body", "2345", text(r));
            check("content-range header", "bytes 2-5/10".equals(r.header("content-range")));
            r = call("GET", base + "/My%20Drive/top.bin", null, "Range", "bytes=7-");
            eq("open range", "789", text(r));
            r = call("GET", base + "/My%20Drive/top.bin", null, "Range", "bytes=-3");
            eq("suffix range", "789", text(r));
            r = call("GET", base + "/My%20Drive/top.bin", null, "Range", "bytes=50-60");
            eq("unsatisfiable range", 416, r.code);
            r.close();
            r = call("HEAD", base + "/My%20Drive/top.bin", null);
            eq("head", 200, r.code);
            eq("head length", "10", r.header("content-length"));
            r.close();
            r = call("GET", base + "/My%20Drive/Docs/caf%C3%A9%20%281%29%20%23.txt", null);
            eq("get odd name", "x", text(r));
            r = call("GET", base + "/My%20Drive/Docs", null);
            eq("get on a folder", 405, r.code);
            r.close();
            one.failDownloads = true;
            r = call("GET", base + "/My%20Drive/Docs/a.txt", null);   // served from the cache: no new download needed
            eq("cached file survives a cloud outage", "hello bridge", text(r));
            r = call("GET", base + "/My%20Drive/top.bin", null, "Range", "bytes=0-1");   // cached earlier too
            eq("cached range", 206, r.code);
            r.close();
            one.files.put("/new.bin", "fresh".getBytes());
            r = call("GET", base + "/My%20Drive/new.bin", null);
            eq("cloud error becomes a gateway error", 502, r.code);
            check("error text is passed on", text(r).contains("the cloud is down"));
            one.failDownloads = false;

            // ---- writing
            r = call("PUT", base + "/My%20Drive/Docs/up.txt", "uploaded");
            eq("put new", 201, r.code);
            r.close();
            eq("put stored", "uploaded", new String(one.files.get("/Docs/up.txt"), StandardCharsets.UTF_8));
            r = call("PUT", base + "/My%20Drive/Docs/up.txt", "second");
            eq("put overwrite", 204, r.code);
            r.close();
            eq("put overwrote", "second", new String(one.files.get("/Docs/up.txt"), StandardCharsets.UTF_8));
            r = call("GET", base + "/My%20Drive/Docs/up.txt", null);
            eq("changed file is read fresh", "second", text(r));
            r = call("PUT", base + "/My%20Drive/NoFolder/x.txt", "x");
            eq("put without folder", 409, r.code);
            r.close();
            r = call("PUT", base + "/My%20Drive", "x");
            eq("put onto a storage folder", 405, r.code);
            r.close();
            r = call("MKCOL", base + "/My%20Drive/New%20Folder", null);
            eq("mkcol", 201, r.code);
            r.close();
            check("mkcol made it", one.files.containsKey("/New Folder"));
            r = call("MKCOL", base + "/My%20Drive/New%20Folder", null);
            eq("mkcol twice", 405, r.code);
            r.close();
            r = call("MKCOL", base + "/My%20Drive/Zip/Zap", null);
            eq("mkcol without parent", 409, r.code);
            r.close();

            // ---- move, copy, delete
            r = call("MOVE", base + "/My%20Drive/Docs/up.txt", null, "Destination", base + "/My%20Drive/New%20Folder/moved.txt");
            eq("move", 201, r.code);
            r.close();
            check("moved", one.files.containsKey("/New Folder/moved.txt") && !one.files.containsKey("/Docs/up.txt"));
            r = call("MOVE", base + "/My%20Drive/top.bin", null, "Destination", "/My%20Drive/New%20Folder/moved.txt", "Overwrite", "F");
            eq("move without overwrite", 412, r.code);
            r.close();
            r = call("MOVE", base + "/My%20Drive/top.bin", null, "Destination", "/My%20Drive/New%20Folder/moved.txt");
            eq("move with overwrite", 204, r.code);
            r.close();
            eq("overwritten", "0123456789", new String(one.files.get("/New Folder/moved.txt")));
            r = call("MOVE", base + "/My%20Drive/Docs", null, "Destination", base + "/My%20Drive/Docs/inside");
            eq("folder into itself", 403, r.code);
            r.close();
            r = call("COPY", base + "/My%20Drive/Docs", null, "Destination", base + "/Second/DocsCopy");
            eq("copy a folder to another cloud", 201, r.code);
            r.close();
            check("copy is complete", two.files.containsKey("/DocsCopy") && two.files.containsKey("/DocsCopy/a.txt")
                    && two.files.containsKey("/DocsCopy/café (1) #.txt") && one.files.containsKey("/Docs/a.txt"));
            r = call("MOVE", base + "/Second/DocsCopy/a.txt", null, "Destination", base + "/My%20Drive/a-from-second.txt");
            eq("move across clouds", 201, r.code);
            r.close();
            check("moved across clouds", one.files.containsKey("/a-from-second.txt") && !two.files.containsKey("/DocsCopy/a.txt"));
            r = call("DELETE", base + "/Second/DocsCopy", null);
            eq("delete folder", 204, r.code);
            r.close();
            check("deleted", two.files.isEmpty());
            r = call("DELETE", base + "/My%20Drive", null);
            eq("a whole storage cannot be deleted", 403, r.code);
            r.close();
            r = call("DELETE", base + "/My%20Drive/ghost", null);
            eq("delete missing", 404, r.code);
            r.close();

            // ---- odds and ends
            r = call("LOCK", base + "/My%20Drive/Docs/a.txt", "<lockinfo/>");
            eq("lock", 200, r.code);
            check("lock token", String.valueOf(r.header("lock-token")).contains("opaquelocktoken:"));
            r.close();
            r = call("UNLOCK", base + "/My%20Drive/Docs/a.txt", null, "Lock-Token", "<opaquelocktoken:x>");
            eq("unlock", 204, r.code);
            r.close();
            r = call("PROPPATCH", base + "/My%20Drive/Docs/a.txt", "<x/>");
            eq("proppatch accepted", 207, r.code);
            r.close();
            r = call("TRACE", base + "/", null);
            eq("unknown method", 405, r.code);
            r.close();

            // ---- a second server finds another port
            WebDavServer other = new WebDavServer(backend, cache, USER, PASS);
            int p2 = other.start(port);
            check("busy port: next free one is used", p2 != port && p2 > port && p2 <= port + 20);
            other.stop();
        } finally {
            server.stop();
        }
        check("stopped server refuses connections", !canConnect(port));
    }

    private static boolean canConnect(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return s.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    private static String readResponseHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            byte[] x = b.toByteArray();
            int n = x.length;
            if (n >= 4 && x[n - 4] == '\r' && x[n - 3] == '\n' && x[n - 2] == '\r' && x[n - 1] == '\n') break;
        }
        return b.toString("ISO-8859-1");
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int k = in.read(b, off, n - off);
            if (k < 0) throw new IOException("short read");
            off += k;
        }
        return b;
    }

    private static void testRaw() throws Exception {
        final MemoryCloud one = new MemoryCloud();
        one.files.put("/f.txt", "abc".getBytes());
        WebDavServer.Backend backend = new WebDavServer.Backend() {
            @Override
            public List<String> names() {
                return Arrays.asList("C");
            }

            @Override
            public CloudClient client(String name) {
                return one;
            }
        };
        WebDavServer server = new WebDavServer(backend, Files.createTempDirectory("wdraw").toFile(), USER, PASS);
        int port = server.start(0);
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            String a = "Authorization: " + auth() + "\r\n";

            // two requests on one connection (keep-alive)
            out.write(("GET /C/f.txt HTTP/1.1\r\nHost: x\r\n" + a + "\r\n").getBytes());
            out.flush();
            String h1 = readResponseHead(in);
            check("keep-alive: first answer", h1.startsWith("HTTP/1.1 200") && h1.contains("Content-Length: 3"));
            eq("keep-alive: first body", "abc", new String(readN(in, 3)));
            out.write(("GET /C/f.txt HTTP/1.1\r\nHost: x\r\nRange: bytes=1-\r\n" + a + "\r\n").getBytes());
            out.flush();
            String h2 = readResponseHead(in);
            check("keep-alive: second answer on the same connection", h2.startsWith("HTTP/1.1 206"));
            eq("keep-alive: second body", "bc", new String(readN(in, 2)));

            // a chunked upload with Expect: 100-continue
            out.write(("PUT /C/chunked.txt HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nExpect: 100-continue\r\n" + a + "\r\n")
                    .getBytes());
            out.flush();
            String cont = readResponseHead(in);
            check("100 continue", cont.startsWith("HTTP/1.1 100"));
            out.write("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".getBytes());
            out.flush();
            String put = readResponseHead(in);
            check("chunked put created", put.startsWith("HTTP/1.1 201"));
            eq("chunked body decoded", "hello world", new String(one.files.get("/chunked.txt")));

            // the connection is still usable afterwards, and an absolute-form request target works
            out.write(("PROPFIND http://127.0.0.1:" + port + "/C/ HTTP/1.1\r\nHost: x\r\nDepth: 0\r\n" + a + "\r\n").getBytes());
            out.flush();
            String pf = readResponseHead(in);
            check("absolute-form target", pf.startsWith("HTTP/1.1 207"));
        } finally {
            server.stop();
        }
    }
}
