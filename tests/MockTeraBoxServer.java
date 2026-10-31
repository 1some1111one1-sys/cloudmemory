package com.cloudbridge.app.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory imitation of the TeraBox website's JSON endpoints (as far as CloudBridge uses them). */
final class MockTeraBoxServer {
    static final String COOKIE = "ndus=GOODSESSION; PANWEB=1";
    static final String BDSTOKEN = "BDS1234567890";
    static final String JSTOKEN = "ABCDEF0123456789ABCDEF0123456789";

    static final class F {
        long fsId;
        boolean dir;
        byte[] data = new byte[0];
        long mtime = 1_700_000_000L;
    }

    final TreeMap<String, F> files = new TreeMap<>();
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final Map<String, byte[]> blocks = new HashMap<>();         // md5 -> data (what TeraBox "already has")
    volatile boolean pageWithoutJsToken;
    volatile String forceErrno;                                // answer every /api call with this errno
    volatile boolean redirectLogin;
    volatile boolean breakTemplate;          // the token endpoints answer with an HTML 404 page
    volatile boolean bdstokenInPage;
    volatile String locateAnswer;            // host that locateupload names (default: this server)
    volatile String deadHost;                // requests for this Host header get an HTML 404 page (like www.)
    volatile int sawJsToken;
    final AtomicInteger uploadedParts = new AtomicInteger();
    private final Map<String, Map<Integer, byte[]>> sessions = new HashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1000);
    private final HttpServer server;

    MockTeraBoxServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    F put(String path, byte[] data) {
        F f = new F();
        f.fsId = seq.incrementAndGet();
        f.data = data;
        synchronized (files) {
            files.put(path, f);
        }
        return f;
    }

    F dir(String path) {
        F f = put(path, new byte[0]);
        f.dir = true;
        return f;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = form(ex.getRequestURI().getRawQuery());
            byte[] body = readAll(ex.getRequestBody());
            log.add(method + " " + path);
            String hostHeader = ex.getRequestHeaders().getFirst("Host");
            if (deadHost != null && deadHost.equalsIgnoreCase(hostHeader)) {
                text(ex, 404, "<!DOCTYPE html><html><head><title>we can't find the page</title></head></html>", "text/html");
                return;
            }
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            boolean authed = cookie != null && cookie.contains("ndus=GOODSESSION");
            if (path.equals("/main")) {
                if (redirectLogin && !authed) {
                    ex.getResponseHeaders().add("Location", "/login");
                    ex.sendResponseHeaders(302, -1);
                    return;
                }
                String html = "<html><script>function fn(a){}; var x=fn%28%22" + (pageWithoutJsToken ? "short" : JSTOKEN) + "%22%29;"
                        + (bdstokenInPage ? "window.yunData={\"bdstoken\":\"" + "0123456789abcdef0123456789abcdef\"};" : "") + "</script></html>";
                text(ex, 200, html, "text/html");
                return;
            }
            if (path.startsWith("/dl2/")) {
                download(ex, path.substring(5), authed);
                return;
            }
            if (path.startsWith("/dl/")) {
                ex.getResponseHeaders().add("Location", "/dl2/" + path.substring(4));
                ex.sendResponseHeaders(302, -1);
                return;
            }
            if (!authed) {
                if (redirectLogin) {
                    ex.getResponseHeaders().add("Location", "/login");
                    ex.sendResponseHeaders(302, -1);
                } else {
                    json(ex, "{\"errno\":-6}");
                }
                return;
            }
            if (forceErrno != null && path.startsWith("/api/") && !path.equals("/api/gettemplatevariable")) {
                json(ex, "{\"errno\":" + forceErrno + "}");
                return;
            }
            if (breakTemplate && (path.equals("/api/gettemplatevariable") || path.equals("/api/home/info"))) {
                text(ex, 404, "<!DOCTYPE html><html><head><title>we can't find the page</title></head></html>", "text/html");
                return;
            }
            if (q.get("jsToken") != null && q.get("jsToken").equals(JSTOKEN)) sawJsToken++;
            switch (path) {
                case "/api/gettemplatevariable":
                    json(ex, "{\"errno\":0,\"result\":{\"bdstoken\":\"" + BDSTOKEN + "\"}}");
                    break;
                case "/api/list":
                    list(ex, q);
                    break;
                case "/api/quota":
                    json(ex, "{\"errno\":0,\"total\":1099511627776,\"used\":104857600,\"free\":1099406770176}");
                    break;
                case "/api/filemetas":
                    filemetas(ex, q);
                    break;
                case "/api/precreate":
                    needToken(ex, q);
                    precreate(ex, form(new String(body, StandardCharsets.UTF_8)));
                    break;
                case "/rest/2.0/pcs/file":
                    json(ex, "{\"host\":\"" + (locateAnswer != null ? locateAnswer : "127.0.0.1:" + server.getAddress().getPort()) + "\"}");
                    break;
                case "/rest/2.0/pcs/superfile2":
                    part(ex, q, body, ex.getRequestHeaders().getFirst("Content-Type"));
                    break;
                case "/api/create":
                    needToken(ex, q);
                    create(ex, form(new String(body, StandardCharsets.UTF_8)));
                    break;
                case "/api/filemanager":
                    needToken(ex, q);
                    manage(ex, q, form(new String(body, StandardCharsets.UTF_8)));
                    break;
                default:
                    ex.sendResponseHeaders(404, -1);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    private void needToken(HttpExchange ex, Map<String, String> q) {
        String expected = bdstokenInPage ? "0123456789abcdef0123456789abcdef" : BDSTOKEN;
        if (breakTemplate && !bdstokenInPage) return;      // no token obtainable: the real site may not insist on one
        if (!expected.equals(q.get("bdstoken"))) throw new IllegalStateException("missing bdstoken on " + ex.getRequestURI());
    }

    private void list(HttpExchange ex, Map<String, String> q) throws IOException {
        String dir = q.get("dir");
        int num = Integer.parseInt(q.getOrDefault("num", "100"));
        int page = Integer.parseInt(q.getOrDefault("page", "1"));
        synchronized (files) {
            if (!dir.equals("/") && (!files.containsKey(dir) || !files.get(dir).dir)) {
                json(ex, "{\"errno\":-9}");
                return;
            }
            List<String> kids = new ArrayList<>();
            for (String p : files.keySet()) {
                if (!p.equals(dir) && parent(p).equals(dir)) kids.add(p);
            }
            StringBuilder sb = new StringBuilder("{\"errno\":0,\"list\":[");
            int from = (page - 1) * num;
            for (int i = from; i < Math.min(kids.size(), from + num); i++) {
                F f = files.get(kids.get(i));
                if (i > from) sb.append(',');
                sb.append("{\"fs_id\":").append(f.fsId).append(",\"path\":").append(Json.quote(kids.get(i)))
                        .append(",\"server_filename\":").append(Json.quote(name(kids.get(i))))
                        .append(",\"isdir\":").append(f.dir ? 1 : 0).append(",\"size\":").append(f.data.length)
                        .append(",\"server_mtime\":").append(f.mtime).append('}');
            }
            json(ex, sb.append("]}").toString());
        }
    }

    private void filemetas(HttpExchange ex, Map<String, String> q) throws IOException {
        Object target = Json.parse(q.get("target"));
        String p = (String) ((List<?>) target).get(0);
        synchronized (files) {
            F f = files.get(p);
            if (f == null) {
                json(ex, "{\"errno\":-9}");
                return;
            }
            json(ex, "{\"errno\":0,\"info\":[{\"fs_id\":" + f.fsId + ",\"dlink\":" + Json.quote(base() + "/dl/" + f.fsId) + "}]}");
        }
    }

    private void download(HttpExchange ex, String fsId, boolean authed) throws IOException {
        if (!authed) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        synchronized (files) {
            for (F f : files.values()) {
                if (String.valueOf(f.fsId).equals(fsId)) {
                    ex.sendResponseHeaders(200, f.data.length == 0 ? -1 : f.data.length);
                    if (f.data.length > 0) {
                        try (OutputStream o = ex.getResponseBody()) {
                            o.write(f.data);
                        }
                    }
                    return;
                }
            }
        }
        ex.sendResponseHeaders(404, -1);
    }

    private void precreate(HttpExchange ex, Map<String, String> f) throws IOException {
        List<?> md5s = (List<?>) Json.parse(f.get("block_list"));
        String uploadId = "UP" + seq.incrementAndGet();
        synchronized (sessions) {
            sessions.put(uploadId, new HashMap<>());
        }
        StringBuilder need = new StringBuilder("[");
        boolean any = false;
        synchronized (blocks) {
            for (int i = 0; i < md5s.size(); i++) {
                if (!blocks.containsKey(md5s.get(i))) {
                    need.append(any ? "," : "").append(i);
                    any = true;
                }
            }
        }
        json(ex, "{\"errno\":0,\"return_type\":1,\"uploadid\":\"" + uploadId + "\",\"block_list\":" + need + "]}");
    }

    private void part(HttpExchange ex, Map<String, String> q, byte[] body, String contentType) throws IOException {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        // The real upload server refuses uploads that are not marked as temporary parts, or that are not signed in.
        if (!"tmpfile".equals(q.get("type")) || cookie == null || !cookie.contains("ndus=GOODSESSION")) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        String s = new String(body, StandardCharsets.ISO_8859_1);
        int start = s.indexOf("\r\n\r\n") + 4;
        int end = s.lastIndexOf("\r\n--" + boundary + "--");
        byte[] data = java.util.Arrays.copyOfRange(body, start, end);
        String md5 = md5(data);
        synchronized (sessions) {
            Map<Integer, byte[]> sess = sessions.get(q.get("uploadid"));
            if (sess == null) {
                json(ex, "{\"errno\":31190}");
                return;
            }
            sess.put(Integer.parseInt(q.get("partseq")), data);
        }
        synchronized (blocks) {
            blocks.put(md5, data);
        }
        uploadedParts.incrementAndGet();
        json(ex, "{\"md5\":\"" + md5 + "\",\"partseq\":" + q.get("partseq") + "}");
    }

    private void create(HttpExchange ex, Map<String, String> f) throws IOException {
        String path = f.get("path");
        boolean isdir = "1".equals(f.get("isdir"));
        String rtype = f.getOrDefault("rtype", "0");
        synchronized (files) {
            if (!parent(path).equals("/") && !(files.containsKey(parent(path)) && files.get(parent(path)).dir)) {
                json(ex, "{\"errno\":-9}");
                return;
            }
            String target = path;
            if (files.containsKey(path)) {
                if (rtype.equals("0")) {
                    json(ex, "{\"errno\":-8}");
                    return;
                }
                if (rtype.equals("1")) {
                    int n = 1;
                    while (files.containsKey(path + "(" + n + ")")) n++;
                    target = path + "(" + n + ")";
                }
            }
            if (isdir) {
                dir(target);
            } else {
                List<?> md5s = (List<?>) Json.parse(f.get("block_list"));
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                synchronized (blocks) {
                    for (Object m : md5s) {
                        byte[] b = blocks.get(m);
                        if (b == null) {
                            json(ex, "{\"errno\":31299}");
                            return;
                        }
                        all.write(b, 0, b.length);
                    }
                }
                if (all.size() != Long.parseLong(f.get("size"))) {
                    json(ex, "{\"errno\":31299}");
                    return;
                }
                put(target, all.toByteArray());
            }
            json(ex, "{\"errno\":0,\"fs_id\":" + files.get(target).fsId + ",\"path\":" + Json.quote(target) + "}");
        }
    }

    private void manage(HttpExchange ex, Map<String, String> q, Map<String, String> f) throws IOException {
        String op = q.get("opera");
        List<?> list = (List<?>) Json.parse(f.get("filelist"));
        synchronized (files) {
            for (Object o : list) {
                if (op.equals("delete")) {
                    String p = (String) o;
                    if (!files.containsKey(p)) {
                        json(ex, "{\"errno\":-9}");
                        return;
                    }
                    List<String> gone = new ArrayList<>();
                    for (String k : files.keySet()) if (k.equals(p) || k.startsWith(p + "/")) gone.add(k);
                    for (String k : gone) files.remove(k);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    String from = (String) m.get("path");
                    String dest = op.equals("move") ? (String) m.get("dest") : parent(from);
                    String to = Paths.join(dest, (String) m.get("newname"));
                    if (!files.containsKey(from)) {
                        json(ex, "{\"errno\":-9}");
                        return;
                    }
                    if (files.containsKey(to)) {
                        json(ex, "{\"errno\":-8}");
                        return;
                    }
                    List<String> moved = new ArrayList<>();
                    for (String k : files.keySet()) if (k.equals(from) || k.startsWith(from + "/")) moved.add(k);
                    for (String k : moved) files.put(to + k.substring(from.length()), files.remove(k));
                }
            }
        }
        json(ex, "{\"errno\":0,\"info\":[]}");
    }

    // ------------------------------------------------------------------ helpers

    static String parent(String p) {
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    static String name(String p) {
        return p.substring(p.lastIndexOf('/') + 1);
    }

    static String md5(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        text(ex, 200, body, "application/json");
    }

    private static void text(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }
}
