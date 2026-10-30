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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-memory imitation of the parts of Google's OAuth token endpoint and Drive v3 that CloudBridge uses. */
final class MockDriveServer {
    static final String CLIENT_ID = "cid.apps.googleusercontent.com";
    static final String CLIENT_SECRET = "csecret";
    static final String FOLDER = "application/vnd.google-apps.folder";
    static final String DOC = "application/vnd.google-apps.document";
    static final String FORM = "application/vnd.google-apps.form";
    static final String SHORTCUT = "application/vnd.google-apps.shortcut";

    static final class F {
        String id;
        String name;
        String mime;
        String parent;
        byte[] bytes = new byte[0];
        boolean trashed;
        long created;
        String targetId;
        String targetMime;
        int modifiedSeq;
    }

    final Map<String, F> files = Collections.synchronizedMap(new LinkedHashMap<>());
    final String rootId = "ROOT0";
    final String refreshToken;
    volatile String challengeExpected;      // base64url(SHA-256(verifier)) the code exchange must match
    volatile String codeExpected = "the-code";
    volatile boolean exchangeReturnsRefresh = true;
    volatile boolean revoked;
    volatile int maxPage = 2;
    volatile int throttle429;               // answer this many API calls with 429
    volatile int throttle403;               // answer this many API calls with 403 rateLimitExceeded
    volatile String failWith;               // "accessNotConfigured" / "storageQuotaExceeded": every API call fails so
    final AtomicInteger refreshes = new AtomicInteger();
    final AtomicInteger apiCalls = new AtomicInteger();
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    volatile String lastSessionAuth = "(none)";
    volatile String currentToken = "AT0";
    private final AtomicInteger seq = new AtomicInteger(1);
    private final AtomicInteger tokenSeq = new AtomicInteger(1);
    private final Map<String, String[]> sessions = new HashMap<>();   // session id -> {fileId or "", metadata json}
    private final HttpServer server;

    MockDriveServer(String refreshToken) throws IOException {
        this.refreshToken = refreshToken;
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

    void invalidateAccessToken() {
        currentToken = "DEAD" + tokenSeq.getAndIncrement();
    }

    F add(String parent, String name, String mime, String content) {
        F f = new F();
        f.id = "id" + seq.getAndIncrement();
        f.name = name;
        f.mime = mime;
        f.parent = parent;
        f.bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        f.created = seq.get() * 1000L;
        files.put(f.id, f);
        return f;
    }

    F byName(String parent, String name) {
        synchronized (files) {
            for (F f : files.values()) {
                if (!f.trashed && parent.equals(f.parent) && name.equals(f.name)) return f;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            String method = ex.getRequestMethod();
            log.add(method + " " + path);
            byte[] body = readAll(ex.getRequestBody());
            if (path.equals("/token")) {
                token(ex, new String(body, StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/session/")) {
                lastSessionAuth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
                session(ex, path.substring("/session/".length()), body);
                return;
            }
            apiCalls.incrementAndGet();
            if (!("Bearer " + currentToken).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                error(ex, 401, "Invalid Credentials", "authError");
                return;
            }
            if (throttle429 > 0) {
                throttle429--;
                error(ex, 429, "Too many requests", "rateLimitExceeded");
                return;
            }
            if (throttle403 > 0) {
                throttle403--;
                error(ex, 403, "Rate Limit Exceeded", "userRateLimitExceeded");
                return;
            }
            if (failWith != null) {
                error(ex, 403, "simulated " + failWith, failWith);
                return;
            }
            api(ex, method, path, body);
        } catch (RuntimeException e) {
            e.printStackTrace();
            error(ex, 500, "mock server bug: " + e, "backendError");
        } finally {
            ex.close();
        }
    }

    private void token(HttpExchange ex, String form) throws IOException {
        Map<String, String> p = new HashMap<>();
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) p.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
        }
        if (!CLIENT_ID.equals(p.get("client_id")) || !CLIENT_SECRET.equals(p.get("client_secret"))) {
            json(ex, 401, "{\"error\":\"invalid_client\",\"error_description\":\"The OAuth client was not found.\"}");
            return;
        }
        String grant = p.get("grant_type");
        if ("refresh_token".equals(grant)) {
            if (revoked || !refreshToken.equals(p.get("refresh_token"))) {
                json(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");
                return;
            }
            refreshes.incrementAndGet();
            currentToken = "AT" + tokenSeq.getAndIncrement();
            json(ex, 200, "{\"access_token\":\"" + currentToken + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
            return;
        }
        if ("authorization_code".equals(grant)) {
            try {
                String v = p.get("code_verifier");
                String ch = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII)));
                if (!codeExpected.equals(p.get("code")) || !ch.equals(challengeExpected)
                        || p.get("redirect_uri") == null || !p.get("redirect_uri").startsWith("http://127.0.0.1:")) {
                    json(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}");
                    return;
                }
            } catch (Exception e) {
                json(ex, 400, "{\"error\":\"invalid_request\"}");
                return;
            }
            currentToken = "AT" + tokenSeq.getAndIncrement();
            json(ex, 200, "{\"access_token\":\"" + currentToken + "\",\"expires_in\":3599,"
                    + (exchangeReturnsRefresh ? "\"refresh_token\":\"" + refreshToken + "\"," : "")
                    + "\"token_type\":\"Bearer\"}");
            return;
        }
        json(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
    }

    private void api(HttpExchange ex, String method, String path, byte[] body) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String text = new String(body, StandardCharsets.UTF_8);
        if (path.equals("/drive/v3/files/root") && method.equals("GET")) {
            json(ex, 200, "{\"id\":" + Json.quote(rootId) + "}");
        } else if (path.equals("/drive/v3/about") && method.equals("GET")) {
            if ("storageQuota".equals(q.get("fields"))) {
                json(ex, 200, "{\"storageQuota\":{\"limit\":\"16106127360\",\"usage\":\"5368709120\"}}");
            } else {
                json(ex, 200, "{\"user\":{\"emailAddress\":\"me@example.com\"}}");
            }
        } else if (path.equals("/drive/v3/files") && method.equals("GET")) {
            list(ex, q);
        } else if (path.equals("/drive/v3/files") && method.equals("POST")) {
            Map<String, Object> m = Json.parseObject(text, "test");
            F f = add(parentOf(m), Json.str(m, "name"), Json.str(m, "mimeType"), "");
            json(ex, 200, fileJson(f));
        } else if (path.startsWith("/upload/drive/v3/files")) {
            if (!"resumable".equals(q.get("uploadType"))) {
                error(ex, 400, "only resumable uploads are supported by this mock", "invalid");
                return;
            }
            String id = path.equals("/upload/drive/v3/files") ? "" : path.substring("/upload/drive/v3/files/".length());
            if (!id.isEmpty() && !files.containsKey(id)) {
                error(ex, 404, "File not found: " + id, "notFound");
                return;
            }
            String sid = "S" + seq.getAndIncrement();
            synchronized (sessions) {
                sessions.put(sid, new String[]{id, text, ex.getRequestHeaders().getFirst("X-Upload-Content-Length"),
                        ex.getRequestHeaders().getFirst("X-Upload-Content-Type")});
            }
            ex.getResponseHeaders().add("Location", base() + "/session/" + sid);
            ex.sendResponseHeaders(200, -1);
        } else if (path.startsWith("/drive/v3/files/")) {
            String rest = path.substring("/drive/v3/files/".length());
            boolean export = rest.endsWith("/export");
            String id = export ? rest.substring(0, rest.length() - "/export".length()) : rest;
            F f = files.get(id);
            if (f == null) {
                error(ex, 404, "File not found: " + id, "notFound");
                return;
            }
            if (method.equals("GET") && export) {
                if (!DOC.equals(f.mime)) {
                    error(ex, 403, "Export only supports Docs Editors files.", "fileNotExportable");
                    return;
                }
                bytes(ex, ("EXPORT[" + q.get("mimeType") + "]" + f.name).getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("GET") && "media".equals(q.get("alt"))) {
                if (f.mime.startsWith("application/vnd.google-apps.")) {
                    error(ex, 403, "Only files with binary content can be downloaded.", "fileNotDownloadable");
                    return;
                }
                bytes(ex, f.bytes);
            } else if (method.equals("PATCH")) {
                Map<String, Object> m = Json.parseObject(text, "test");
                if (m.containsKey("name")) f.name = Json.str(m, "name");
                if (Boolean.TRUE.equals(m.get("trashed"))) f.trashed = true;
                if (q.containsKey("removeParents") && q.get("removeParents").equals(f.parent)) f.parent = q.get("addParents");
                json(ex, 200, fileJson(f));
            } else {
                error(ex, 400, "unsupported " + method + " " + path, "invalid");
            }
        } else {
            error(ex, 404, "no such endpoint " + path, "notFound");
        }
    }

    private void session(HttpExchange ex, String sid, byte[] body) throws IOException {
        String[] s;
        synchronized (sessions) {
            s = sessions.remove(sid);
        }
        if (s == null || !ex.getRequestMethod().equals("PUT")) {
            error(ex, 404, "unknown upload session", "notFound");
            return;
        }
        if (s[2] != null && Long.parseLong(s[2]) != body.length) {
            error(ex, 400, "length mismatch: declared " + s[2] + ", got " + body.length, "invalid");
            return;
        }
        F f;
        if (s[0].isEmpty()) {
            Map<String, Object> m = Json.parseObject(s[1], "test");
            f = add(parentOf(m), Json.str(m, "name"), Json.str(m, "mimeType"), "");
        } else {
            f = files.get(s[0]);
        }
        f.bytes = body;
        f.modifiedSeq++;
        json(ex, 200, fileJson(f));
    }

    // ------------------------------------------------------------------ listing with a tiny query evaluator

    private void list(HttpExchange ex, Map<String, String> q) throws IOException {
        String expr = q.get("q");
        int pageSize = Math.min(Integer.parseInt(q.getOrDefault("pageSize", "100")), maxPage);
        int start = q.containsKey("pageToken") ? Integer.parseInt(q.get("pageToken")) : 0;
        List<F> hits = new ArrayList<>();
        synchronized (files) {
            for (F f : files.values()) {
                if (new Query(expr, f).eval()) hits.add(f);
            }
        }
        StringBuilder sb = new StringBuilder("{");
        int end = Math.min(hits.size(), start + pageSize);
        if (end < hits.size()) sb.append("\"nextPageToken\":\"").append(end).append("\",");
        sb.append("\"files\":[");
        for (int i = start; i < end; i++) {
            if (i > start) sb.append(',');
            sb.append(fileJson(hits.get(i)));
        }
        json(ex, 200, sb.append("]}").toString());
    }

    private static final class Query {
        private static final Pattern TOKEN = Pattern.compile("\\s*('(?:\\\\.|[^'\\\\])*'|\\(|\\)|=|[A-Za-z]+)");
        private final List<String> t = new ArrayList<>();
        private int i;
        private final F f;

        Query(String q, F f) {
            this.f = f;
            Matcher m = TOKEN.matcher(q);
            int pos = 0;
            while (pos < q.length()) {
                if (!m.find(pos) || m.start() != pos) {
                    if (q.substring(pos).trim().isEmpty()) break;
                    throw new IllegalArgumentException("bad query at " + pos + ": " + q);
                }
                t.add(m.group(1));
                pos = m.end();
            }
        }

        boolean eval() {
            boolean v = or();
            if (i != t.size()) throw new IllegalArgumentException("trailing tokens in query");
            return v;
        }

        private boolean or() {
            boolean v = and();
            while (i < t.size() && t.get(i).equals("or")) {
                i++;
                boolean r = and();
                v = v || r;
            }
            return v;
        }

        private boolean and() {
            boolean v = atom();
            while (i < t.size() && t.get(i).equals("and")) {
                i++;
                boolean r = atom();
                v = v && r;
            }
            return v;
        }

        private boolean atom() {
            String x = t.get(i++);
            if (x.equals("(")) {
                boolean v = or();
                expect(")");
                return v;
            }
            if (x.startsWith("'")) {            // 'id' in parents
                expect("in");
                expect("parents");
                return unq(x).equals(f.parent);
            }
            expect("=");
            String rhs = t.get(i++);
            switch (x) {
                case "name":
                    return unq(rhs).equals(f.name);
                case "mimeType":
                    return unq(rhs).equals(f.mime);
                case "trashed":
                    return Boolean.parseBoolean(rhs) == f.trashed;
                default:
                    throw new IllegalArgumentException("unknown field " + x);
            }
        }

        private void expect(String s) {
            if (!t.get(i++).equals(s)) throw new IllegalArgumentException("expected " + s);
        }

        private static String unq(String s) {
            if (!s.startsWith("'") || !s.endsWith("'")) throw new IllegalArgumentException("not a string: " + s);
            return s.substring(1, s.length() - 1).replaceAll("\\\\(.)", "$1");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String parentOf(Map<String, Object> meta) {
        List<Object> p = Json.list(meta, "parents");
        return p.isEmpty() ? "ROOT0" : (String) p.get(0);
    }

    private String fileJson(F f) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(Json.quote(f.id));
        sb.append(",\"name\":").append(Json.quote(f.name));
        sb.append(",\"mimeType\":").append(Json.quote(f.mime));
        if (!f.mime.startsWith("application/vnd.google-apps.")) sb.append(",\"size\":\"").append(f.bytes.length).append('"');
        sb.append(",\"createdTime\":").append(Json.quote(Instant.ofEpochMilli(1_700_000_000_000L + f.created).toString()));
        sb.append(",\"modifiedTime\":").append(Json.quote(Instant.ofEpochMilli(1_700_100_000_000L + f.modifiedSeq * 1000L + f.created).toString()));
        sb.append(",\"parents\":[").append(Json.quote(f.parent)).append(']');
        if (SHORTCUT.equals(f.mime)) {
            sb.append(",\"shortcutDetails\":{\"targetId\":").append(Json.quote(f.targetId))
                    .append(",\"targetMimeType\":").append(Json.quote(f.targetMime)).append('}');
        }
        return sb.append('}').toString();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
        }
        return m;
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static void bytes(HttpExchange ex, byte[] b) throws IOException {
        ex.sendResponseHeaders(200, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        }
    }

    private static void error(HttpExchange ex, int code, String message, String reason) throws IOException {
        json(ex, code, "{\"error\":{\"code\":" + code + ",\"message\":" + Json.quote(message)
                + ",\"errors\":[{\"reason\":" + Json.quote(reason) + "}]}}");
    }
}
