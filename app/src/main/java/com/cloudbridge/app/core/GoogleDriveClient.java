package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Drive (REST API v3) as a path-based {@link CloudClient}.
 *
 * <p>Drive identifies files by id and allows several files with the same name in one folder, so this class
 * maps "/Folder/file.txt" to ids by looking names up one folder at a time (and caches the result briefly).
 * Where names collide, the oldest keeps its name and the others are shown as "name (2)", "name (3)", ...
 * Google Docs, Sheets, Slides and Drawings are not real files; they are shown as .docx, .xlsx, .pptx and .png
 * (exported when opened). Deleting moves a file to the Drive trash.
 */
public final class GoogleDriveClient implements CloudClient {
    public static final String API = "https://www.googleapis.com";

    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final String SHORTCUT = "application/vnd.google-apps.shortcut";
    private static final String GOOGLE_APPS = "application/vnd.google-apps.";
    /** { Google mime type, extension shown, mime type exported to }. */
    private static final String[][] EXPORTS = {
            {"application/vnd.google-apps.document", ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            {"application/vnd.google-apps.spreadsheet", ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
            {"application/vnd.google-apps.presentation", ".pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
            {"application/vnd.google-apps.drawing", ".png", "image/png"},
    };
    private static final String FIELDS = "id,name,mimeType,size,modifiedTime,createdTime,parents,"
            + "shortcutDetails(targetId,targetMimeType)";
    private static final Pattern DUPLICATE = Pattern.compile("^(.*) \\((\\d+)\\)(\\.[^./ ]+)?$");
    /** Drive names may contain "/", which would break paths, so it is shown as this look-alike. */
    private static final char SLASH = '∕';
    private static final long CACHE_TTL_MS = 30_000;
    private static final int MAX_PAGES = 200;

    /** Delay before the first retry of a throttled request; doubles each time. Tests shorten it. */
    static volatile long retryBaseMs = 500;

    // Kept across client instances (the provider creates a client per call).
    private static final Map<String, NodeCache> CACHES = new ConcurrentHashMap<>();
    private static final Map<String, String> ROOT_IDS = new ConcurrentHashMap<>();
    private static final Map<String, GoogleOAuth.Tokens> TOKENS = new ConcurrentHashMap<>();

    private final HttpTransport http;
    private final String api;
    private final String apiHost;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final String key;
    private final NodeCache cache;

    public GoogleDriveClient(HttpTransport http, String clientId, String clientSecret, String refreshToken) {
        this(http, API, GoogleOAuth.TOKEN_URL, clientId, clientSecret, refreshToken);
    }

    GoogleDriveClient(HttpTransport http, String api, String tokenUrl, String clientId, String clientSecret,
                      String refreshToken) {
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalArgumentException("Enter the Google client ID and client secret.");
        }
        if (isBlank(refreshToken)) throw new IllegalArgumentException("Press \"Sign in with Google\" first.");
        this.http = http;
        this.api = api;
        this.apiHost = URI.create(api).getHost();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.refreshToken = refreshToken.trim();
        this.key = api + "|" + this.clientId + "|" + this.refreshToken;
        NodeCache c = CACHES.get(key);
        if (c == null) {
            c = new NodeCache();
            NodeCache prev = CACHES.putIfAbsent(key, c);
            if (prev != null) c = prev;
        }
        this.cache = c;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Forget remembered folder lookups (used after the user changes accounts). */
    public static void clearCaches() {
        for (NodeCache c : CACHES.values()) c.clear();
    }

    // ---------------------------------------------------------------- CloudClient

    @Override
    public List<CloudEntry> list(String dirPath) throws IOException {
        String dir = Paths.normalize(dirPath);
        Node folder = resolve(dir);
        if (!folder.dir) throw new CloudException(404, "\"" + Paths.name(dir) + "\" is not a folder");
        List<Node> all = new ArrayList<>();
        String q = "'" + esc(folder.contentId) + "' in parents and trashed = false";
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = api + "/drive/v3/files?pageSize=1000&q=" + GoogleOAuth.enc(q)
                    + "&fields=" + GoogleOAuth.enc("nextPageToken,files(" + FIELDS + ")")
                    + (pageToken == null ? "" : "&pageToken=" + GoogleOAuth.enc(pageToken));
            Map<String, Object> o = getJson(url, "Listing the folder");
            for (Object f : Json.list(o, "files")) {
                @SuppressWarnings("unchecked")
                Node n = Node.from((Map<String, Object>) f, folder.contentId);
                if (n != null) all.add(n);
            }
            pageToken = Json.str(o, "nextPageToken");
            if (pageToken == null || pageToken.isEmpty()) break;
        }
        Map<String, List<Node>> groups = new LinkedHashMap<>();
        for (Node n : all) {
            List<Node> g = groups.get(n.display());
            if (g == null) {
                g = new ArrayList<>();
                groups.put(n.display(), g);
            }
            g.add(n);
        }
        List<CloudEntry> out = new ArrayList<>();
        for (Map.Entry<String, List<Node>> e : groups.entrySet()) {
            List<Node> g = e.getValue();
            Collections.sort(g, Node.OLDEST_FIRST);
            for (int i = 0; i < g.size(); i++) {
                Node n = g.get(i);
                String name = i == 0 ? e.getKey() : duplicateName(e.getKey(), i + 1);
                String path = Paths.join(dir, name);
                cache.put(path, n);
                out.add(new CloudEntry(path, n.dir, n.size, n.modified));
            }
        }
        return out;
    }

    @Override
    public CloudEntry stat(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return new CloudEntry("/", true, -1, 0);
        Node n = resolve(p);
        return new CloudEntry(p, n.dir, n.size, n.modified);
    }

    @Override
    public void download(String path, File dest) throws IOException {
        Node n = resolve(Paths.normalize(path));
        if (n.dir) throw new CloudException(400, "\"" + n.display() + "\" is a folder");
        String url = n.export != null
                ? api + "/drive/v3/files/" + GoogleOAuth.enc(n.contentId) + "/export?mimeType=" + GoogleOAuth.enc(n.export[2])
                : api + "/drive/v3/files/" + GoogleOAuth.enc(n.contentId) + "?alt=media";
        HttpTransport.Response r = send("GET", url, null, null, null, null, true);
        if (r.code / 100 != 2) throw driveFail(r, "Downloading \"" + n.display() + "\"");
        Http.writeTo(r, dest);
    }

    @Override
    public void upload(String path, File src, String mime) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot write to the root");
        Node parent = resolve(Paths.parent(p));
        if (!parent.dir) throw new CloudException(409, "The destination is not a folder");
        String name = Paths.name(p);
        Node existing = null;
        try {
            existing = resolve(p);
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
        if (existing != null && existing.dir) throw new CloudException(409, "A folder named \"" + name + "\" already exists");
        if (existing != null && existing.export != null) {
            throw new CloudException(400, "\"" + name + "\" is a Google Docs/Sheets/Slides file, which cannot be "
                    + "overwritten from here. Save under another name.");
        }
        String fileMime = isBlank(mime) ? Paths.mimeFor(name) : mime;
        long len = src.length();
        String createMeta = "{\"name\":" + Json.quote(rawName(name)) + ",\"parents\":["
                + Json.quote(parent.contentId) + "],\"mimeType\":" + Json.quote(fileMime) + "}";
        String fields = "&fields=" + GoogleOAuth.enc(FIELDS) + "&supportsAllDrives=true";
        Node saved;
        if (existing == null && len == 0) {
            Map<String, Object> o = sendJson("POST", api + "/drive/v3/files?" + fields.substring(1), createMeta,
                    "Creating \"" + name + "\"");
            saved = Node.from(o, parent.contentId);
        } else {
            String start = existing == null
                    ? api + "/upload/drive/v3/files?uploadType=resumable" + fields
                    : api + "/upload/drive/v3/files/" + GoogleOAuth.enc(existing.id) + "?uploadType=resumable" + fields;
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-Upload-Content-Type", fileMime);
            h.put("X-Upload-Content-Length", Long.toString(len));
            HttpTransport.Response r = send(existing == null ? "POST" : "PATCH", start, h,
                    (existing == null ? createMeta : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8), null,
                    "application/json; charset=UTF-8", true);
            if (r.code / 100 != 2) throw driveFail(r, "Starting the upload of \"" + name + "\"");
            String session = r.header("location");
            r.close();
            if (session == null) throw new CloudException(502, "Google Drive did not start the upload");
            HttpTransport.Response put = send("PUT", session, null, null, src, fileMime, false);
            if (put.code / 100 != 2) throw driveFail(put, "Uploading \"" + name + "\"");
            Map<String, Object> o = readJson(put, "Google Drive");
            saved = Node.from(o, parent.contentId);
        }
        cache.invalidate(p);
        if (saved != null) cache.put(p, saved);
    }

    @Override
    public void mkdir(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return;
        Node parent = resolve(Paths.parent(p));
        if (!parent.dir) throw new CloudException(409, "The destination is not a folder");
        String name = Paths.name(p);
        if (!candidates(parent, name).isEmpty()) throw new CloudException(405, "\"" + name + "\" already exists");
        String meta = "{\"name\":" + Json.quote(rawName(name)) + ",\"mimeType\":" + Json.quote(FOLDER)
                + ",\"parents\":[" + Json.quote(parent.contentId) + "]}";
        Map<String, Object> o = sendJson("POST", api + "/drive/v3/files?supportsAllDrives=true&fields="
                + GoogleOAuth.enc(FIELDS), meta, "Creating the folder \"" + name + "\"");
        cache.invalidate(p);
        Node n = Node.from(o, parent.contentId);
        if (n != null) cache.put(p, n);
    }

    @Override
    public void delete(String path, boolean isDir) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot delete the root");
        Node n = resolve(p);
        Map<String, Object> ignored = sendJson("PATCH", api + "/drive/v3/files/" + GoogleOAuth.enc(n.id)
                + "?supportsAllDrives=true&fields=id", "{\"trashed\":true}", "Deleting \"" + n.display() + "\"");
        cache.invalidate(p);
    }

    @Override
    public void move(String from, String to, boolean isDir) throws IOException {
        String src = Paths.normalize(from);
        String dst = Paths.normalize(to);
        if (src.equals("/") || dst.equals("/")) throw new CloudException(400, "Cannot move the root");
        if (src.equals(dst)) return;
        Node n = resolve(src);
        Node newParent = resolve(Paths.parent(dst));
        if (!newParent.dir) throw new CloudException(409, "The destination is not a folder");
        String newName = Paths.name(dst);
        if (n.dir && Paths.isUnder(src, dst)) throw new CloudException(400, "A folder cannot be moved into itself");
        if (!candidates(newParent, newName).isEmpty()) {
            throw new CloudException(412, "\"" + newName + "\" already exists in the destination");
        }
        String raw = rawName(newName);
        if (n.export != null && raw.endsWith(n.export[1]) && raw.length() > n.export[1].length()) {
            raw = raw.substring(0, raw.length() - n.export[1].length());
        }
        StringBuilder url = new StringBuilder(api).append("/drive/v3/files/").append(GoogleOAuth.enc(n.id))
                .append("?supportsAllDrives=true&fields=").append(GoogleOAuth.enc(FIELDS));
        if (n.parentId != null && !n.parentId.equals(newParent.contentId)) {
            url.append("&addParents=").append(GoogleOAuth.enc(newParent.contentId))
                    .append("&removeParents=").append(GoogleOAuth.enc(n.parentId));
        }
        Map<String, Object> o = sendJson("PATCH", url.toString(), "{\"name\":" + Json.quote(raw) + "}",
                "Moving \"" + n.display() + "\"");
        cache.invalidate(src);
        cache.invalidate(dst);
        Node moved = Node.from(o, newParent.contentId);
        if (moved != null) cache.put(dst, moved);
    }

    /** The email address of the signed-in Google account (used to name the storage). */
    public String signedInEmail() throws IOException {
        Map<String, Object> o = getJson(api + "/drive/v3/about?fields=" + GoogleOAuth.enc("user(emailAddress)"),
                "Reading the account");
        String email = Json.str(Json.obj(o, "user"), "emailAddress");
        return email == null ? "" : email;
    }

    // ---------------------------------------------------------------- path to id

    private Node resolve(String path) throws IOException {
        if (path.equals("/")) return rootNode();
        Node hit = cache.get(path);
        if (hit != null) return hit;
        Node parent = resolve(Paths.parent(path));
        String name = Paths.name(path);
        if (!parent.dir) throw new CloudException(404, "Not found: " + path);
        Node n = lookup(parent, name);
        if (n == null) throw new CloudException(404, "Not found: " + path);
        cache.put(path, n);
        return n;
    }

    private Node rootNode() throws IOException {
        String id = ROOT_IDS.get(key);
        if (id == null) {
            Map<String, Object> o = getJson(api + "/drive/v3/files/root?fields=id", "Opening Google Drive");
            id = Json.str(o, "id");
            if (id == null) throw new CloudException(502, "Google Drive did not report the root folder");
            ROOT_IDS.put(key, id);
        }
        return Node.root(id);
    }

    private Node lookup(Node parent, String display) throws IOException {
        List<Node> same = candidates(parent, display);
        if (!same.isEmpty()) return same.get(0);
        Matcher m = DUPLICATE.matcher(display);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2));
            String original = m.group(1) + (m.group(3) == null ? "" : m.group(3));
            if (n >= 2) {
                List<Node> group = candidates(parent, original);
                if (group.size() >= n) return group.get(n - 1);
            }
        }
        return null;
    }

    /** Every non-trashed child of parent that is shown under exactly this name, oldest first. */
    private List<Node> candidates(Node parent, String display) throws IOException {
        List<String> conds = new ArrayList<>();
        for (String raw : rawVariants(display)) conds.add("name = '" + esc(raw) + "'");
        for (String[] x : EXPORTS) {
            if (display.endsWith(x[1]) && display.length() > x[1].length()) {
                String base = display.substring(0, display.length() - x[1].length());
                for (String raw : rawVariants(base)) {
                    conds.add("(name = '" + esc(raw) + "' and mimeType = '" + x[0] + "')");
                }
            }
        }
        StringBuilder or = new StringBuilder();
        for (String c : conds) {
            if (or.length() > 0) or.append(" or ");
            or.append(c);
        }
        String q = "'" + esc(parent.contentId) + "' in parents and trashed = false and (" + or + ")";
        List<Node> found = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < 10; page++) {
            String url = api + "/drive/v3/files?pageSize=100&q=" + GoogleOAuth.enc(q)
                    + "&fields=" + GoogleOAuth.enc("nextPageToken,files(" + FIELDS + ")")
                    + (pageToken == null ? "" : "&pageToken=" + GoogleOAuth.enc(pageToken));
            Map<String, Object> o = getJson(url, "Looking up \"" + display + "\"");
            for (Object f : Json.list(o, "files")) {
                @SuppressWarnings("unchecked")
                Node n = Node.from((Map<String, Object>) f, parent.contentId);
                if (n != null && n.display().equals(display)) found.add(n);
            }
            pageToken = Json.str(o, "nextPageToken");
            if (pageToken == null || pageToken.isEmpty()) break;
        }
        Collections.sort(found, Node.OLDEST_FIRST);
        return found;
    }

    private static List<String> rawVariants(String display) {
        List<String> v = new ArrayList<>();
        v.add(display);
        if (display.indexOf(SLASH) >= 0) v.add(display.replace(SLASH, '/'));
        return v;
    }

    private static String rawName(String display) {
        return display.replace(SLASH, '/');
    }

    /** "a.txt", 2 -> "a (2).txt"; "notes", 3 -> "notes (3)". */
    private static String duplicateName(String display, int n) {
        int dot = display.lastIndexOf('.');
        if (dot > 0 && dot < display.length() - 1) {
            return display.substring(0, dot) + " (" + n + ")" + display.substring(dot);
        }
        return display + " (" + n + ")";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    // ---------------------------------------------------------------- one Drive item

    private static final class Node {
        static final java.util.Comparator<Node> OLDEST_FIRST = (a, b) -> {
            int c = Long.compare(a.created, b.created);
            return c != 0 ? c : a.id.compareTo(b.id);
        };

        String id;          // the item's own id (delete and move act on this)
        String contentId;   // what to read or list: the target of a shortcut, otherwise id
        String rawName;
        boolean dir;
        String[] export;    // row of EXPORTS for Google-native files, else null
        long size = -1;
        long modified;
        long created;
        String parentId;
        long time;

        static Node root(String id) {
            Node n = new Node();
            n.id = id;
            n.contentId = id;
            n.rawName = "";
            n.dir = true;
            n.time = Long.MAX_VALUE / 2;
            return n;
        }

        /** Null for things CloudBridge cannot show (forms, sites, broken shortcuts). */
        static Node from(Map<String, Object> o, String parentId) {
            if (o == null) return null;
            String id = Json.str(o, "id");
            String name = Json.str(o, "name");
            String mime = Json.str(o, "mimeType");
            if (id == null || name == null || mime == null) return null;
            Node n = new Node();
            n.id = id;
            n.contentId = id;
            n.rawName = name;
            n.parentId = parentId;
            if (SHORTCUT.equals(mime)) {
                Map<String, Object> d = Json.obj(o, "shortcutDetails");
                String target = Json.str(d, "targetId");
                String targetMime = Json.str(d, "targetMimeType");
                if (target == null || targetMime == null) return null;
                n.contentId = target;
                mime = targetMime;
            }
            n.dir = FOLDER.equals(mime);
            for (String[] x : EXPORTS) {
                if (x[0].equals(mime)) n.export = x;
            }
            if (!n.dir && n.export == null && mime.startsWith(GOOGLE_APPS)) return null;
            n.size = n.dir || n.export != null ? -1 : Json.num(o, "size", -1);
            n.modified = Http.parseIsoDate(Json.str(o, "modifiedTime"));
            n.created = Http.parseIsoDate(Json.str(o, "createdTime"));
            return n;
        }

        String display() {
            String base = rawName.replace('/', SLASH);
            return export == null ? base : base + export[1];
        }
    }

    private static final class NodeCache {
        private final LinkedHashMap<String, Node> map = new LinkedHashMap<String, Node>(256, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Node> eldest) {
                return size() > 5000;
            }
        };

        synchronized Node get(String path) {
            Node n = map.get(path);
            if (n == null) return null;
            if (System.currentTimeMillis() - n.time > CACHE_TTL_MS) {
                map.remove(path);
                return null;
            }
            return n;
        }

        synchronized void put(String path, Node n) {
            n.time = System.currentTimeMillis();
            map.put(path, n);
        }

        synchronized void invalidate(String path) {
            String prefix = path + "/";
            map.keySet().removeIf(k -> k.equals(path) || k.startsWith(prefix));
        }

        synchronized void clear() {
            map.clear();
        }
    }

    @Override
    public CloudQuota quota() throws IOException {
        Map<String, Object> m = getJson(api + "/drive/v3/about?fields=storageQuota", "Reading storage");
        Map<String, Object> q = Json.obj(m, "storageQuota");
        if (q == null) return null;
        long used = Json.num(q, "usage", -1);
        long limit = Json.num(q, "limit", -1);     // missing for accounts with unlimited storage
        return CloudQuota.ofUsedAndTotal(used, limit);
    }

    // ---------------------------------------------------------------- HTTP

    private Map<String, Object> getJson(String url, String what) throws IOException {
        HttpTransport.Response r = send("GET", url, null, null, null, null, true);
        if (r.code / 100 != 2) throw driveFail(r, what);
        return readJson(r, "Google Drive");
    }

    private Map<String, Object> sendJson(String method, String url, String json, String what) throws IOException {
        HttpTransport.Response r = send(method, url, null, json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                null, "application/json; charset=UTF-8", true);
        if (r.code / 100 != 2) throw driveFail(r, what);
        return readJson(r, "Google Drive");
    }

    private static Map<String, Object> readJson(HttpTransport.Response r, String who) throws IOException {
        String text;
        try {
            text = new String(r.readAll(Http.MAX_XML), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            r.close();
        }
        return Json.parseObject(text, who);
    }

    private String accessToken(String failedToken) throws IOException {
        synchronized (TOKENS) {
            GoogleOAuth.Tokens t = TOKENS.get(key);
            boolean fresh = t != null && t.expiresAtMillis - System.currentTimeMillis() > 60_000;
            if (fresh && (failedToken == null || !failedToken.equals(t.accessToken))) return t.accessToken;
            t = GoogleOAuth.refresh(http, tokenUrl, clientId, clientSecret, refreshToken);
            TOKENS.put(key, t);
            return t.accessToken;
        }
    }

    private boolean isApiHost(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null && h.equalsIgnoreCase(apiHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Sends a request with the Google access token. Handles an expired token (one refresh and retry),
     * Google's rate limiting (a few retries with growing delays) and redirects (the token is only ever sent to
     * the API host). Error responses come back with their body already buffered, so callers can read them.
     */
    private HttpTransport.Response send(String method, String url, Map<String, String> headers, byte[] body,
                                        File file, String contentType, boolean auth) throws IOException {
        String current = url;
        String failedToken = null;
        boolean refreshed = false;
        int redirects = 0;
        int retries = 0;
        while (true) {
            HttpTransport.Request req = new HttpTransport.Request(method, current);
            String token = null;
            if (auth && isApiHost(current)) {
                token = accessToken(failedToken);
                req.header("Authorization", "Bearer " + token);
            }
            if (headers != null) req.headers.putAll(headers);
            if (file != null) req.bodyFile(file, contentType);
            else if (body != null) req.body(body, contentType);
            HttpTransport.Response r = http.execute(req);
            int code = r.code;
            String location = r.header("location");
            if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && location != null) {
                r.close();
                if (++redirects > 5) throw new CloudException(310, "Too many redirects");
                try {
                    current = URI.create(current).resolve(location).toString();
                } catch (IllegalArgumentException e) {
                    throw new CloudException(502, "Google Drive sent a bad redirect");
                }
                continue;
            }
            if (code / 100 == 2) return r;
            r = buffered(r);
            if (code == 401 && token != null && !refreshed) {
                r.close();
                refreshed = true;
                failedToken = token;
                continue;
            }
            if (retries < 4 && (code == 429 || code == 500 || code == 502 || code == 503 || code == 504
                    || (code == 403 && isRateLimit(r)))) {
                r.close();
                try {
                    Thread.sleep(retryBaseMs << retries);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Interrupted");
                }
                retries++;
                continue;
            }
            return r;
        }
    }

    /** Reads an error body into memory so it can be inspected more than once. */
    private static HttpTransport.Response buffered(HttpTransport.Response r) {
        byte[] b;
        try {
            b = r.readAll(64 * 1024);
        } catch (IOException e) {
            b = new byte[0];
        }
        Map<String, String> headers = r.headers;
        int code = r.code;
        r.close();
        return new HttpTransport.Response(code, headers, b);
    }

    private static boolean isRateLimit(HttpTransport.Response r) {
        String body = peek(r);
        return body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded");
    }

    /** Body of a buffered response without consuming it (the stream is a ByteArrayInputStream). */
    private static String peek(HttpTransport.Response r) {
        try {
            java.io.InputStream in = r.stream();
            if (!in.markSupported()) return "";
            in.mark(64 * 1024);
            byte[] b = new byte[Math.min(in.available(), 64 * 1024)];
            int n = in.read(b);
            in.reset();
            return n <= 0 ? "" : new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static CloudException driveFail(HttpTransport.Response r, String what) {
        int status = r.code;
        String message = null;
        String reason = null;
        try {
            String body = new String(r.readAll(64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> err = Json.obj(Json.parseObject(body, "Google Drive"), "error");
            message = Json.str(err, "message");
            List<Object> errors = Json.list(err, "errors");
            if (!errors.isEmpty() && errors.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> first = (Map<String, Object>) errors.get(0);
                reason = Json.str(first, "reason");
            }
        } catch (IOException ignored) {
        }
        r.close();
        StringBuilder sb = new StringBuilder(what).append(" failed (HTTP ").append(status).append(')');
        if ("storageQuotaExceeded".equals(reason)) {
            status = 507;
            sb.append(": your Google Drive storage is full");
        } else if (status == 401) {
            sb.append(": the Google sign-in is no longer valid, so edit this storage and sign in again");
        } else if ("accessNotConfigured".equals(reason) || "SERVICE_DISABLED".equals(reason)) {
            sb.append(": the Google Drive API is switched off in your Google Cloud project. Open "
                    + "console.cloud.google.com, go to APIs & Services > Library, enable \"Google Drive API\", "
                    + "then try again");
        } else if (status == 403 && reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("ratelimit")) {
            sb.append(": Google is rate limiting this account, try again in a minute");
        } else if (status == 404) {
            sb.append(": not found");
        }
        if (message != null && !message.isEmpty()) sb.append(" [").append(message).append(']');
        return new CloudException(status, sb.toString());
    }
}
