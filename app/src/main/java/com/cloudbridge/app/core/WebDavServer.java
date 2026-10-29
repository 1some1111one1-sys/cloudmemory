package com.cloudbridge.app.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small WebDAV server that listens on this phone only (127.0.0.1) and shows every cloud account as a folder.
 * File managers that can add a WebDAV / network location (Solid Explorer, X-plore, CX, FX ...) connect to it and
 * then browse, open, upload, rename and delete in all clouds, whatever the cloud is. A password is required, so
 * other apps on the phone cannot read the clouds just because the port is open.
 */
public final class WebDavServer {
    /** Where the clouds come from. Names are the top-level folder names and must be unique. */
    public interface Backend {
        List<String> names();

        /** @throws IOException when the name is unknown or the account cannot be used */
        CloudClient client(String name) throws IOException;
    }

    private static final int MAX_LINE = 16 * 1024;

    private final Backend backend;
    private final DownloadCache cache;
    private final File uploads;
    private final byte[] expectedUser;
    private final byte[] expectedPassword;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cloudbridge-webdav");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket socket;
    private volatile boolean stopped;

    public WebDavServer(Backend backend, File cacheDir, String user, String password) {
        this.backend = backend;
        this.cache = new DownloadCache(new File(cacheDir, "webdav-dl"));
        this.uploads = new File(cacheDir, "webdav-up");
        this.expectedUser = user.getBytes(StandardCharsets.UTF_8);
        this.expectedPassword = password.getBytes(StandardCharsets.UTF_8);
    }

    /** Starts listening on 127.0.0.1, at the first free port from {@code preferredPort} on. Returns the port. */
    public synchronized int start(int preferredPort) throws IOException {
        if (socket != null) return socket.getLocalPort();
        //noinspection ResultOfMethodCallIgnored
        cache.ensureDir();
        //noinspection ResultOfMethodCallIgnored
        uploads.mkdirs();
        File[] leftovers = uploads.listFiles();
        if (leftovers != null) {
            for (File f : leftovers) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        IOException last = null;
        for (int i = 0; i < (preferredPort == 0 ? 1 : 20); i++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort == 0 ? 0 : preferredPort + i), 50);
                socket = s;
                stopped = false;
                final ServerSocket accepting = s;
                Thread t = new Thread(() -> acceptLoop(accepting), "cloudbridge-webdav-accept");
                t.setDaemon(true);
                t.start();
                return s.getLocalPort();
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("No free port near " + preferredPort + ": " + (last == null ? "" : last.getMessage()));
    }

    public synchronized void stop() {
        stopped = true;
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    public boolean isRunning() {
        return socket != null;
    }

    private void acceptLoop(ServerSocket s) {
        while (!stopped) {
            try {
                final Socket c = s.accept();
                pool.execute(() -> serve(c));
            } catch (IOException e) {
                if (stopped || s.isClosed()) return;
            } catch (RuntimeException e) {
                return;   // pool shut down
            }
        }
    }

    // ------------------------------------------------------------------ HTTP framing

    private static final class Req {
        String method;
        String target;
        boolean http11;
        final Map<String, String> headers = new LinkedHashMap<>();
        InputStream body;
        boolean bodyTouched;

        String header(String lowerName) {
            return headers.get(lowerName);
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(120_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 32 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32 * 1024);
            while (!stopped) {
                Req r = readRequest(in);
                if (r == null) break;
                r.body = bodyOf(r, in);
                boolean keep = handle(r, out);
                out.flush();
                if (!keep) break;
                if (!drain(r.body)) break;
            }
        } catch (IOException | RuntimeException ignored) {
            // the client went away or sent nonsense: just drop the connection
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Req readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        while (line != null && line.isEmpty()) line = readLine(in);   // tolerate blank lines between requests
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 3) throw new IOException("bad request line");
        Req r = new Req();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        r.target = parts[1];
        r.http11 = parts[2].equalsIgnoreCase("HTTP/1.1");
        for (int i = 0; i < 200; i++) {
            String h = readLine(in);
            if (h == null) throw new IOException("truncated headers");
            if (h.isEmpty()) return r;
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        throw new IOException("too many headers");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] raw = b.toByteArray();
                int n = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
                return new String(raw, 0, n, StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            if (b.size() > MAX_LINE) throw new IOException("line too long");
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static InputStream bodyOf(Req r, InputStream in) {
        String te = r.header("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) return new ChunkedInput(in);
        String cl = r.header("content-length");
        if (cl != null) {
            try {
                return new LimitedInput(in, Math.max(0, Long.parseLong(cl.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return new LimitedInput(in, 0);
    }

    /** Reads whatever the handler left of the request body, so the next request on the connection starts clean. */
    private static boolean drain(InputStream body) {
        try {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = body.read(buf)) >= 0) {
                total += n;
                if (total > (64L << 20)) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class LimitedInput extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInput(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int c = in.read();
            if (c >= 0) left--;
            else left = 0;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) {
                left = 0;
                return -1;
            }
            left -= n;
            return n;
        }
    }

    /** Decodes "Transfer-Encoding: chunked" request bodies (some clients upload that way). */
    private static final class ChunkedInput extends InputStream {
        private final InputStream in;
        private long left;
        private boolean done;

        ChunkedInput(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (left == 0) {
                String size = readLine(in);
                if (size == null) throw new IOException("truncated chunked body");
                int semi = size.indexOf(';');
                if (semi >= 0) size = size.substring(0, semi);
                try {
                    left = Long.parseLong(size.trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size");
                }
                if (left == 0) {
                    String trailer;
                    while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                        // skip trailers
                    }
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("truncated chunked body");
            left -= n;
            if (left == 0) readLine(in);   // the CRLF after the chunk
            return n;
        }
    }

    // ------------------------------------------------------------------ responses

    private static String reason(int code) {
        switch (code) {
            case 100: return "Continue";
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 207: return "Multi-Status";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 412: return "Precondition Failed";
            case 415: return "Unsupported Media Type";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return "Status";
        }
    }

    private static void head(OutputStream out, int code, Map<String, String> headers) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Server: CloudBridge\r\n");
        sb.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue().replace('\r', ' ').replace('\n', ' ')).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void send(OutputStream out, int code, Map<String, String> extra, byte[] body, String type, boolean headOnly,
                             boolean keep) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        if (extra != null) h.putAll(extra);
        if (type != null && body != null && body.length > 0) h.put("Content-Type", type);
        if (code != 204 && code != 304) h.put("Content-Length", String.valueOf(body == null ? 0 : body.length));
        if (!keep) h.put("Connection", "close");
        head(out, code, h);
        if (!headOnly && body != null && code != 204) out.write(body);
    }

    private static String httpDate(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(millis));
    }

    // ------------------------------------------------------------------ routing

    private boolean authorized(Req r) {
        String a = r.header("authorization");
        if (a == null || a.length() < 7 || !a.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            byte[] raw = Base64.getDecoder().decode(a.substring(6).trim());
            int colon = -1;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == ':') {
                    colon = i;
                    break;
                }
            }
            if (colon < 0) return false;
            byte[] u = Arrays.copyOfRange(raw, 0, colon);
            byte[] p = Arrays.copyOfRange(raw, colon + 1, raw.length);
            boolean ok = MessageDigest.isEqual(u, expectedUser);
            ok &= MessageDigest.isEqual(p, expectedPassword);
            return ok;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Where a URL points: nothing (the list of clouds), or a cloud and a path inside it. */
    private static final class Target {
        String account;     // null for the top level
        String path = "/";

        boolean top() {
            return account == null;
        }

        boolean accountRoot() {
            return account != null && path.equals("/");
        }
    }

    private Target resolve(String rawTarget) throws CloudException {
        String t = rawTarget;
        int scheme = t.indexOf("://");
        if (scheme > 0 && scheme < 8) {
            int slash = t.indexOf('/', scheme + 3);
            t = slash < 0 ? "/" : t.substring(slash);
        }
        int cut = t.indexOf('?');
        if (cut >= 0) t = t.substring(0, cut);
        cut = t.indexOf('#');
        if (cut >= 0) t = t.substring(0, cut);
        String full = Paths.normalize(Paths.decode(t));
        Target out = new Target();
        if (full.equals("/")) return out;
        String rest = full.substring(1);
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        if (!backend.names().contains(name)) throw new CloudException(404, "No storage called \"" + name + "\"");
        out.account = name;
        out.path = slash < 0 ? "/" : Paths.normalize(rest.substring(slash));
        return out;
    }

    private static String hrefOf(String account, String path, boolean dir) {
        String full = account == null ? "/" : Paths.join("/" + account, path);
        String enc = Paths.encodePath(full);
        return dir && !enc.endsWith("/") ? enc + "/" : enc;
    }

    /** Handles one request. Returns whether the connection may be reused. */
    private boolean handle(Req r, OutputStream out) throws IOException {
        String conn = r.header("connection");
        boolean keep = r.http11 ? conn == null || !conn.equalsIgnoreCase("close")
                : conn != null && conn.equalsIgnoreCase("keep-alive");
        boolean head = r.method.equals("HEAD");
        if (!authorized(r)) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("WWW-Authenticate", "Basic realm=\"CloudBridge\", charset=\"UTF-8\"");
            send(out, 401, h, "Sign in with the user name and password shown in CloudBridge.".getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", head, false);
            return false;
        }
        String expect = r.header("expect");
        if (expect != null && expect.equalsIgnoreCase("100-continue")) {
            head(out, 100, new LinkedHashMap<String, String>());
            out.flush();
        }
        try {
            switch (r.method) {
                case "OPTIONS":
                    options(out, keep);
                    break;
                case "PROPFIND":
                    propfind(r, out, keep);
                    break;
                case "PROPPATCH":
                    proppatch(r, out, keep);
                    break;
                case "GET":
                case "HEAD":
                    get(r, out, keep, head);
                    break;
                case "PUT":
                    put(r, out, keep);
                    break;
                case "DELETE":
                    delete(r, out, keep);
                    break;
                case "MKCOL":
                    mkcol(r, out, keep);
                    break;
                case "MOVE":
                case "COPY":
                    moveOrCopy(r, out, keep, r.method.equals("MOVE"));
                    break;
                case "LOCK":
                    lock(out, keep);
                    break;
                case "UNLOCK":
                    send(out, 204, null, null, null, false, keep);
                    break;
                default:
                    Map<String, String> h = new LinkedHashMap<>();
                    h.put("Allow", ALLOW);
                    send(out, 405, h, null, null, head, keep);
            }
            return keep;
        } catch (CloudException e) {
            return fail(out, statusFor(e), message(e), head, keep);
        } catch (IllegalArgumentException e) {
            return fail(out, 502, message(e), head, keep);
        } catch (SocketException e) {
            throw e;
        } catch (IOException e) {
            return fail(out, 502, message(e), head, keep);
        }
    }

    private static final String ALLOW = "OPTIONS, PROPFIND, PROPPATCH, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK";

    private static boolean fail(OutputStream out, int code, String text, boolean head, boolean keep) throws IOException {
        send(out, code, null, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", head, keep);
        return keep;
    }

    private static int statusFor(CloudException e) {
        if (e.isNotFound()) return 404;
        switch (e.status) {
            case 400:
            case 403:
            case 405:
            case 409:
            case 412:
            case 507:
                return e.status;
            case 429:
                return 503;
            default:
                return 502;
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ methods

    private void options(OutputStream out, boolean keep) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("DAV", "1, 2");
        h.put("MS-Author-Via", "DAV");
        h.put("Allow", ALLOW);
        send(out, 200, h, null, null, false, keep);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void entryXml(StringBuilder sb, String href, String displayName, boolean dir, long size, long modified) {
        sb.append("<D:response><D:href>").append(href).append("</D:href><D:propstat><D:prop>");
        sb.append("<D:displayname>").append(xml(displayName)).append("</D:displayname>");
        if (dir) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            sb.append("<D:resourcetype/>");
            if (size >= 0) sb.append("<D:getcontentlength>").append(size).append("</D:getcontentlength>");
            sb.append("<D:getcontenttype>").append(xml(Paths.mimeFor(displayName))).append("</D:getcontenttype>");
            sb.append("<D:getetag>\"").append(size).append('-').append(modified).append("\"</D:getetag>");
        }
        if (modified > 0) sb.append("<D:getlastmodified>").append(httpDate(modified)).append("</D:getlastmodified>");
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private void propfind(Req r, OutputStream out, boolean keep) throws IOException {
        Target t = resolve(r.target);
        String depth = r.header("depth");
        boolean children = depth == null || !depth.trim().equals("0");
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
        long now = System.currentTimeMillis();
        if (t.top()) {
            entryXml(sb, "/", "CloudBridge", true, -1, now);
            if (children) {
                for (String name : backend.names()) entryXml(sb, hrefOf(name, "/", true), name, true, -1, now);
            }
        } else {
            CloudClient c = backend.client(t.account);
            CloudEntry self = t.accountRoot() ? new CloudEntry("/", true, -1, now) : c.stat(t.path);
            entryXml(sb, hrefOf(t.account, t.path, self.dir), t.accountRoot() ? t.account : self.name(), self.dir, self.size,
                    self.modified);
            if (children && self.dir) {
                for (CloudEntry e : c.list(t.path)) {
                    entryXml(sb, hrefOf(t.account, e.path, e.dir), e.name(), e.dir, e.size, e.modified);
                }
            }
        }
        sb.append("</D:multistatus>");
        send(out, 207, null, sb.toString().getBytes(StandardCharsets.UTF_8), "application/xml; charset=utf-8", false, keep);
    }

    private void proppatch(Req r, OutputStream out, boolean keep) throws IOException {
        Target t = resolve(r.target);
        // Clouds keep their own timestamps; pretend the change was accepted so clients do not report an error.
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>"
                + hrefOf(t.account, t.path, false) + "</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status>"
                + "</D:propstat></D:response></D:multistatus>";
        send(out, 207, null, xml.getBytes(StandardCharsets.UTF_8), "application/xml; charset=utf-8", false, keep);
    }

    private void lock(OutputStream out, boolean keep) throws IOException {
        // Locks are not really kept (the clouds have none); handing one out lets clients that insist on locking work.
        String token = "opaquelocktoken:" + UUID.randomUUID();
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth>"
                + "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
                + "</D:activelock></D:lockdiscovery></D:prop>";
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Lock-Token", "<" + token + ">");
        send(out, 200, h, xml.getBytes(StandardCharsets.UTF_8), "application/xml; charset=utf-8", false, keep);
    }

    private void get(Req r, OutputStream out, boolean keep, boolean head) throws IOException {
        Target t = resolve(r.target);
        if (t.top() || t.accountRoot()) {
            send(out, 405, null, "This is a folder; browse it with a WebDAV client.".getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", head, keep);
            return;
        }
        CloudClient c = backend.client(t.account);
        CloudEntry e = c.stat(t.path);
        if (e.dir) {
            send(out, 405, null, "This is a folder; browse it with a WebDAV client.".getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8", head, keep);
            return;
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Content-Type", e.mime());
        if (e.modified > 0) h.put("Last-Modified", httpDate(e.modified));
        if (head && e.size >= 0) {
            h.put("Content-Length", String.valueOf(e.size));
            if (!keep) h.put("Connection", "close");
            head(out, 200, h);
            return;
        }
        File f = cache.get(t.account, t.path, e, c);
        long size = f.length();
        long from = 0;
        long to = size - 1;
        int code = 200;
        String range = r.header("range");
        if (range != null && range.toLowerCase(Locale.ROOT).startsWith("bytes=") && !range.contains(",")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long n = Long.parseLong(spec.substring(1));
                    from = Math.max(0, size - n);
                } else if (dash > 0) {
                    from = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) to = Math.min(size - 1, Long.parseLong(spec.substring(dash + 1)));
                }
                if (from >= size || from > to) {
                    Map<String, String> bad = new LinkedHashMap<>();
                    bad.put("Content-Range", "bytes */" + size);
                    send(out, 416, bad, null, null, head, keep);
                    return;
                }
                code = 206;
                h.put("Content-Range", "bytes " + from + "-" + to + "/" + size);
            } catch (NumberFormatException ex) {
                from = 0;
                to = size - 1;
            }
        }
        long length = size == 0 ? 0 : to - from + 1;
        h.put("Content-Length", String.valueOf(length));
        if (!keep) h.put("Connection", "close");
        head(out, code, h);
        if (head || length == 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(from);
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("The cached file ended early");
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static boolean exists(CloudClient c, String path) throws IOException {
        if (path.equals("/")) return true;
        try {
            c.stat(path);
            return true;
        } catch (CloudException e) {
            if (e.isNotFound()) return false;
            throw e;
        }
    }

    private void put(Req r, OutputStream out, boolean keep) throws IOException {
        Target t = resolve(r.target);
        if (t.top() || t.accountRoot()) {
            fail(out, 405, "That is a storage folder, not a file.", false, keep);
            return;
        }
        CloudClient c = backend.client(t.account);
        if (!exists(c, Paths.parent(t.path))) {
            fail(out, 409, "The folder does not exist.", false, keep);
            return;
        }
        boolean existed = exists(c, t.path);
        File tmp = File.createTempFile("put_", ".tmp", uploads);
        try {
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = r.body.read(buf)) >= 0) fo.write(buf, 0, n);
            }
            c.upload(t.path, tmp, Paths.mimeFor(Paths.name(t.path)));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        send(out, existed ? 204 : 201, null, null, null, false, keep);
    }

    private void delete(Req r, OutputStream out, boolean keep) throws IOException {
        Target t = resolve(r.target);
        if (t.top() || t.accountRoot()) {
            fail(out, 403, "A storage cannot be deleted from here; remove it in CloudBridge.", false, keep);
            return;
        }
        CloudClient c = backend.client(t.account);
        CloudEntry e = c.stat(t.path);
        c.delete(t.path, e.dir);
        send(out, 204, null, null, null, false, keep);
    }

    private void mkcol(Req r, OutputStream out, boolean keep) throws IOException {
        Target t = resolve(r.target);
        if (t.top() || t.accountRoot()) {
            fail(out, 405, "That folder already exists.", false, keep);
            return;
        }
        CloudClient c = backend.client(t.account);
        if (exists(c, t.path)) {
            fail(out, 405, "That folder already exists.", false, keep);
            return;
        }
        if (!exists(c, Paths.parent(t.path))) {
            fail(out, 409, "The parent folder does not exist.", false, keep);
            return;
        }
        c.mkdir(t.path);
        send(out, 201, null, null, null, false, keep);
    }

    private void moveOrCopy(Req r, OutputStream out, boolean keep, boolean move) throws IOException {
        Target src = resolve(r.target);
        String dest = r.header("destination");
        if (dest == null) {
            fail(out, 400, "No Destination header.", false, keep);
            return;
        }
        Target dst = resolve(dest);
        if (src.top() || src.accountRoot() || dst.top() || dst.accountRoot()) {
            fail(out, 403, "A storage folder itself cannot be moved or copied.", false, keep);
            return;
        }
        boolean overwrite = !"F".equalsIgnoreCase(String.valueOf(r.header("overwrite")).trim());
        CloudClient sc = backend.client(src.account);
        CloudClient dc = src.account.equals(dst.account) ? sc : backend.client(dst.account);
        CloudEntry e = sc.stat(src.path);
        if (src.account.equals(dst.account) && (src.path.equals(dst.path) || (e.dir && Paths.isUnder(src.path, dst.path)))) {
            fail(out, 403, "Source and destination are the same, or the destination is inside the source.", false, keep);
            return;
        }
        if (!exists(dc, Paths.parent(dst.path))) {
            fail(out, 409, "The destination folder does not exist.", false, keep);
            return;
        }
        boolean existed = exists(dc, dst.path);
        if (existed) {
            if (!overwrite) {
                fail(out, 412, "The destination already exists.", false, keep);
                return;
            }
            dc.delete(dst.path, dc.stat(dst.path).dir);
        }
        if (move && src.account.equals(dst.account)) {
            sc.move(src.path, dst.path, e.dir);
        } else {
            copyTree(sc, src.path, e, dc, dst.path);
            if (move) sc.delete(src.path, e.dir);
        }
        send(out, existed ? 204 : 201, null, null, null, false, keep);
    }

    private void copyTree(CloudClient from, String src, CloudEntry e, CloudClient to, String dst) throws IOException {
        if (e.dir) {
            to.mkdir(dst);
            for (CloudEntry child : from.list(src)) {
                copyTree(from, child.path, child, to, Paths.join(dst, child.name()));
            }
            return;
        }
        File tmp = File.createTempFile("copy_", ".tmp", uploads);
        try {
            from.download(src, tmp);
            to.upload(dst, tmp, Paths.mimeFor(Paths.name(dst)));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
