package com.cloudbridge.app.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXPERIMENTAL and UNOFFICIAL. TeraBox has no public API, so this talks to the same web endpoints that the
 * TeraBox website uses, with the session cookie obtained by signing in on the real website. TeraBox can change
 * those endpoints at any time without notice, and automated access may be against its terms of service.
 * Failures therefore carry TeraBox's own error number so they can be diagnosed.
 */
public final class TeraBoxClient implements CloudClient {
    public static final String DEFAULT_SITE = "https://www.1024terabox.com";

    private static final String APP_ID = "250528";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int BLOCK = 4 * 1024 * 1024;
    private static final long CACHE_TTL_MS = 20_000;
    private static final Pattern JS_TOKEN = Pattern.compile("fn\\(\"([^\"\\s]{16,})\"\\)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] TERABOX_DOMAINS = {"terabox.com", "1024terabox.com", "terabox.app", "teraboxapp.com",
            "1024tera.com", "4funbox.com", "mirrobox.com", "nephobox.com", "momerybox.com", "tibibox.com", "freeterabox.com"};

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private final HttpTransport http;
    private final String site;      // scheme://host
    private final String siteHost;
    private final String scheme;
    private final String cookie;
    private final State state;
    /** Servers that may answer the JSON API, best guess first. The www website itself usually does not. */
    private final List<String> bases;

    public TeraBoxClient(HttpTransport http, String siteUrl, String cookie) {
        this(http, siteUrl, cookie, null);
    }

    /** For tests: the list of API servers to try, in order. */
    TeraBoxClient(HttpTransport http, String siteUrl, String cookie, List<String> apiBases) {
        String s = siteUrl == null || siteUrl.trim().isEmpty() ? DEFAULT_SITE : siteUrl.trim();
        if (!s.contains("://")) s = "https://" + s;
        URI u;
        try {
            u = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The TeraBox address is not valid.");
        }
        if (u.getHost() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) {
            throw new IllegalArgumentException("The TeraBox address must look like https://www.1024terabox.com");
        }
        if (cookie == null || !cookie.contains("ndus=")) {
            throw new IllegalArgumentException("Press \"Sign in on the TeraBox website\" first.");
        }
        this.http = http;
        this.scheme = u.getScheme();
        this.siteHost = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        this.site = scheme + "://" + siteHost;
        String c = cookie.trim();
        if (!c.contains("PANWEB=")) c = c + "; PANWEB=1";
        this.cookie = c;
        this.bases = apiBases != null ? apiBases : candidateBases(scheme, siteHost);
        String key = site + "|" + this.cookie;
        State st = STATES.get(key);
        if (st == null) {
            st = new State();
            State prev = STATES.putIfAbsent(key, st);
            if (prev != null) st = prev;
        }
        this.state = st;
    }

    public static void clearCaches() {
        STATES.clear();
    }

    private static final class State {
        String bdstoken;
        String jsToken;
        String base;                                  // the API server that worked
        String uploadBase;                            // the upload server that worked
        final java.util.Set<String> badUploadBases = Collections.synchronizedSet(new LinkedHashSet<>());
        final java.util.Set<String> failedBases = Collections.synchronizedSet(new LinkedHashSet<>());
        boolean tokensFetched;
        final Map<String, long[]> listedAt = new ConcurrentHashMap<>();                  // dir -> {time}
        final Map<String, List<Entry>> listings = new ConcurrentHashMap<>();
    }

    private static final class Entry {
        String path;
        boolean dir;
        long size;
        long modified;
        String fsId;
    }

    /** TeraBox's website answers the API on its "dm." host; the "www." host serves pages only. */
    static List<String> candidateBases(String scheme, String host) {
        List<String> out = new ArrayList<>();
        String bare = host.replaceAll(":\\d+$", "");
        boolean local = bare.matches("[\\d.:]+") || bare.equals("localhost");
        if (!local && !bare.startsWith("dm.")) {
            String root = bare.startsWith("www.") ? bare.substring(4) : bare;
            out.add(scheme + "://dm." + root);
        }
        out.add(scheme + "://" + host);
        return out;
    }

    private String base() {
        String b = state.base;
        return b != null ? b : bases.get(0);
    }

    private interface Call {
        Map<String, Object> run() throws IOException;
    }

    /** True when the server answered with a web page or "not found" instead of API data: wrong server. */
    private static boolean wrongServer(CloudException e) {
        String m = String.valueOf(e.getMessage());
        return (e.status == 404 && m.contains("answered HTTP 404")) || (e.status == 502 && m.contains("did not answer"));
    }

    /** Runs an API call; when this server is not the API server, moves on to the next candidate. */
    private Map<String, Object> failover(Call call) throws IOException {
        CloudException first = null;
        for (int attempt = 0; attempt < bases.size(); attempt++) {
            try {
                return call.run();
            } catch (CloudException e) {
                if (!wrongServer(e)) throw e;
                if (first == null) first = e;
                state.failedBases.add(base());
                String next = null;
                for (String b : bases) {
                    if (!state.failedBases.contains(b)) {
                        next = b;
                        break;
                    }
                }
                if (next == null) break;
                state.base = next;
            }
        }
        throw first;
    }

    private String diagnostics() {
        StringBuilder names = new StringBuilder();
        for (String part : cookie.split(";")) {
            String n = part.trim();
            int eq = n.indexOf('=');
            if (eq > 0) names.append(names.length() == 0 ? "" : ",").append(n, 0, eq);
        }
        return " [server " + base().replaceAll("^[a-z]+://", "") + "; jsToken "
                + (state.jsToken == null ? "not found" : "found") + "; bdstoken "
                + (state.bdstoken == null ? "not found" : "found") + "; cookies " + names + "]";
    }

    // ---------------------------------------------------------------- CloudClient

    @Override
    public List<CloudEntry> list(String dirPath) throws IOException {
        String dir = Paths.normalize(dirPath);
        List<CloudEntry> out = new ArrayList<>();
        for (Entry e : listing(dir)) out.add(new CloudEntry(e.path, e.dir, e.dir ? -1 : e.size, e.modified));
        return out;
    }

    @Override
    public CloudEntry stat(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return new CloudEntry("/", true, -1, 0);
        Entry e = find(p);
        return new CloudEntry(e.path, e.dir, e.dir ? -1 : e.size, e.modified);
    }

    @Override
    public void download(String path, File dest) throws IOException {
        String p = Paths.normalize(path);
        Entry e = find(p);
        if (e.dir) throw new CloudException(400, "\"" + Paths.name(p) + "\" is a folder");
        Map<String, Object> m = apiGet("filemetas", "&target=" + enc("[" + Json.quote(p) + "]") + "&dlink=1", "Downloading");
        List<Object> info = Json.list(m, "info");
        String dlink = info.isEmpty() || !(info.get(0) instanceof Map) ? null : Json.str(asMap(info.get(0)), "dlink");
        if (dlink == null) throw new CloudException(502, "TeraBox gave no download address for \"" + Paths.name(p) + "\"");
        String current = dlink;
        for (int hop = 0; hop < 6; hop++) {
            HttpTransport.Request req = new HttpTransport.Request("GET", current);
            req.header("User-Agent", UA);
            // The file servers refuse a link that is not opened from TeraBox's own page.
            req.header("Referer", site + "/main?category=all");
            req.header("Origin", site);
            if (sameFamily(current)) req.header("Cookie", cookie);
            HttpTransport.Response r = http.execute(req);
            String location = r.header("location");
            if ((r.code == 301 || r.code == 302 || r.code == 307 || r.code == 308) && location != null) {
                r.close();
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (r.code / 100 != 2) {
                int code = r.code;
                r.close();
                throw new CloudException(code, "Downloading failed: TeraBox's file server answered HTTP " + code);
            }
            Http.writeTo(r, dest);
            return;
        }
        throw new CloudException(310, "Too many redirects");
    }

    @Override
    public void upload(String path, File src, String mime) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot write to the root");
        String parent = Paths.parent(p);
        if (!parent.equals("/") && !find(parent).dir) throw new CloudException(409, "The destination is not a folder");
        Entry existing = null;
        try {
            existing = find(p);
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
        if (existing != null && existing.dir) throw new CloudException(409, "A folder named \"" + Paths.name(p) + "\" already exists");

        long len = src.length();
        List<String> md5s = new ArrayList<>();
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[BLOCK];
            int n;
            do {
                n = readUpTo(in, buf);
                if (n > 0 || md5s.isEmpty()) md5s.add(md5(buf, n < 0 ? 0 : n));
            } while (n == BLOCK);
        }
        String blockList = jsonStrings(md5s);
        String rtype = existing == null ? "1" : "3";

        Map<String, Object> pre = apiPost("precreate", "", "path=" + enc(p) + "&target_path=" + enc(parent.equals("/") ? "/" : parent) + "&autoinit=1&isdir=0&size=" + len
                + "&block_list=" + enc(blockList) + "&rtype=" + rtype + "&local_mtime=" + (src.lastModified() / 1000),
                "Starting the upload of \"" + Paths.name(p) + "\"");
        String uploadId = Json.str(pre, "uploadid");
        if (uploadId == null) uploadId = "";
        List<Object> needed = Json.list(pre, "block_list");
        boolean[] send = new boolean[md5s.size()];
        if (needed.isEmpty() && pre.containsKey("block_list")) {
            // TeraBox already has every block (for instance, the same file was uploaded before).
        } else if (needed.isEmpty()) {
            java.util.Arrays.fill(send, true);
        } else {
            for (Object o : needed) {
                if (o instanceof Number && ((Number) o).intValue() >= 0 && ((Number) o).intValue() < send.length) {
                    send[((Number) o).intValue()] = true;
                }
            }
        }
        if (Json.num(pre, "return_type", 1) == 2) java.util.Arrays.fill(send, false);

        List<String> uploadBases = null;
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[BLOCK];
            for (int i = 0; i < md5s.size(); i++) {
                int n = Math.max(0, readUpTo(in, buf));
                if (!send[i]) continue;
                if (uploadBases == null) uploadBases = uploadCandidates(p, uploadId);
                String used = sendBlock(uploadBases, p, uploadId, i, buf, n, md5s.get(i));
                // Keep the server that worked first, for the next parts and the next files.
                uploadBases = new ArrayList<>(uploadBases);
                uploadBases.remove(used);
                uploadBases.add(0, used);
                state.uploadBase = used;
            }
        }

        Map<String, Object> created = apiPost("create", "", "path=" + enc(p) + "&target_path=" + enc(parent.endsWith("/") ? parent : parent + "/") + "&size=" + len + "&isdir=0&rtype=" + rtype
                + "&uploadid=" + enc(uploadId) + "&block_list=" + enc(blockList)
                + "&local_mtime=" + (src.lastModified() / 1000), "Saving \"" + Paths.name(p) + "\"");
        String savedAs = Json.str(created, "path");
        forget();
        if (savedAs != null && !savedAs.equals(p)) {
            throw new CloudException(409, "TeraBox saved the file as \"" + Paths.name(savedAs) + "\" instead of \""
                    + Paths.name(p) + "\"");
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return;
        String parent = Paths.parent(p);
        if (!parent.equals("/") && !find(parent).dir) throw new CloudException(409, "The destination is not a folder");
        try {
            find(p);
            throw new CloudException(405, "\"" + Paths.name(p) + "\" already exists");
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
        apiPost("create", "", "path=" + enc(p) + "&isdir=1&size=0&block_list=" + enc("[]") + "&rtype=0",
                "Creating the folder \"" + Paths.name(p) + "\"");
        forget();
    }

    @Override
    public void delete(String path, boolean isDir) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot delete the root");
        find(p);
        fileManager("delete", "[" + Json.quote(p) + "]", "Deleting \"" + Paths.name(p) + "\"");
        forget();
    }

    @Override
    public void move(String from, String to, boolean isDir) throws IOException {
        String src = Paths.normalize(from);
        String dst = Paths.normalize(to);
        if (src.equals("/") || dst.equals("/")) throw new CloudException(400, "Cannot move the root");
        if (src.equals(dst)) return;
        Entry e = find(src);
        String dstParent = Paths.parent(dst);
        if (!dstParent.equals("/") && !find(dstParent).dir) throw new CloudException(409, "The destination is not a folder");
        if (e.dir && Paths.isUnder(src, dst)) throw new CloudException(400, "A folder cannot be moved into itself");
        try {
            find(dst);
            throw new CloudException(412, "\"" + Paths.name(dst) + "\" already exists in the destination");
        } catch (CloudException ex) {
            if (!ex.isNotFound()) throw ex;
        }
        if (Paths.parent(src).equals(dstParent)) {
            fileManager("rename", "[{\"path\":" + Json.quote(src) + ",\"newname\":" + Json.quote(Paths.name(dst)) + "}]",
                    "Renaming \"" + Paths.name(src) + "\"");
        } else {
            fileManager("move", "[{\"path\":" + Json.quote(src) + ",\"dest\":" + Json.quote(dstParent)
                    + ",\"newname\":" + Json.quote(Paths.name(dst)) + "}]", "Moving \"" + Paths.name(src) + "\"");
        }
        forget();
    }

    @Override
    public CloudQuota quota() throws IOException {
        Map<String, Object> m = apiGet("quota", "&checkfree=1&checkexpire=1", "Reading storage");
        long total = Json.num(m, "total", -1);
        long used = Json.num(m, "used", -1);
        return CloudQuota.ofUsedAndTotal(used, total);
    }

    // ---------------------------------------------------------------- listing

    private List<Entry> listing(String dir) throws IOException {
        long[] at = state.listedAt.get(dir);
        List<Entry> cached = state.listings.get(dir);
        if (at != null && cached != null && System.currentTimeMillis() - at[0] < CACHE_TTL_MS) return cached;
        List<Entry> all = new ArrayList<>();
        for (int page = 1; page <= 500; page++) {
            Map<String, Object> m = apiGet("list", "&dir=" + enc(dir) + "&order=name&desc=0&showempty=1&num=1000&page=" + page,
                    "Listing \"" + (dir.equals("/") ? "/" : Paths.name(dir)) + "\"");
            List<Object> items = Json.list(m, "list");
            for (Object o : items) {
                Map<String, Object> f = asMap(o);
                if (f == null) continue;
                String name = Json.str(f, "server_filename");
                if (name == null || name.isEmpty()) continue;
                Entry e = new Entry();
                e.path = Paths.join(dir, name);
                e.dir = Json.num(f, "isdir", 0) == 1;
                e.size = Json.num(f, "size", -1);
                e.modified = Json.num(f, "server_mtime", 0) * 1000L;
                Object id = f.get("fs_id");
                e.fsId = id == null ? "" : String.valueOf(id);
                all.add(e);
            }
            if (items.size() < 1000) break;
        }
        state.listings.put(dir, all);
        state.listedAt.put(dir, new long[]{System.currentTimeMillis()});
        return all;
    }

    private Entry find(String path) throws IOException {
        String parent = Paths.parent(path);
        String name = Paths.name(path);
        List<Entry> l;
        try {
            l = listing(parent);
        } catch (CloudException e) {
            if (e.isNotFound()) throw new CloudException(404, "Not found: " + path);
            throw e;
        }
        for (Entry e : l) {
            if (e.path.equals(Paths.join(parent, name))) return e;
        }
        throw new CloudException(404, "Not found: " + path);
    }

    private void forget() {
        state.listings.clear();
        state.listedAt.clear();
    }

    // ---------------------------------------------------------------- self test

    /** One request, answered in a line: the status and the start of the answer, or why it failed. */
    private String probe(HttpTransport.Request req) {
        return probe(req, null);
    }

    /** {@code origin}: null keeps the usual headers, "" sends none, anything else is used as Origin and Referer. */
    private String probe(HttpTransport.Request req, String origin) {
        try {
            decorate(req, req.url);
            if (origin != null) {
                req.headers.remove("Origin");
                req.headers.remove("Referer");
                if (!origin.isEmpty()) {
                    req.header("Origin", origin);
                    req.header("Referer", origin + "/main?category=all");
                }
            }
            HttpTransport.Response r = http.execute(req);
            String text = readText(r, 4096).replaceAll("\\s+", " ").trim();
            String type = r.header("content-type");
            return "HTTP " + r.code + (type == null ? "" : " " + type.replaceAll(";.*", "")) + (text.isEmpty() ? ""
                    : " | " + text.substring(0, Math.min(text.length(), 160)));
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Tries every step of using TeraBox (listing, the servers, an upload of one byte that is never saved, a download)
     * and reports what each one answered, so a failure can be traced to its step.
     */
    public String diagnose() {
        StringBuilder r = new StringBuilder();
        String root = registrable(siteHost);
        r.append("TeraBox test, site ").append(siteHost).append("\n");
        List<CloudEntry> top = null;
        try {
            top = list("/");
            r.append("List: ok, ").append(top.size()).append(" items in the top folder\n");
        } catch (IOException e) {
            r.append("List: FAILED ").append(e.getMessage()).append("\n");
        }
        r.append(diagnostics().trim()).append("\n\nServers (a plain request to each):\n");
        List<String> hosts = new ArrayList<>();
        for (String h : new String[]{"www." + root, "dm." + root, "d." + root, "c-jp." + root, "c." + root,
                "d.terabox.com", "c-jp.terabox.com", "c.terabox.com", "data.terabox.com"}) {
            if (!hosts.contains(h)) hosts.add(h);
        }
        if (localSite()) {
            hosts.clear();
            hosts.add(siteHost);
        }
        for (String h : hosts) r.append(" ").append(h).append(": ").append(probe(new HttpTransport.Request("GET", scheme + "://" + h + "/"))).append("\n");

        r.append("\nUpload test (one byte, never saved):\n");
        String uploadId = null;
        try {
            String p = "/.cloudbridge-test";
            Map<String, Object> pre = apiPost("precreate", "", "path=" + enc(p) + "&target_path=" + enc("/") + "&autoinit=1&isdir=0&size=1"
                    + "&block_list=" + enc("[\"" + md5(new byte[]{'x'}, 1) + "\"]") + "&rtype=1&local_mtime=" + (System.currentTimeMillis() / 1000),
                    "Starting a test upload");
            uploadId = Json.str(pre, "uploadid");
            r.append(" precreate: ok, upload id ").append(uploadId == null ? "none" : "received").append("\n");
        } catch (IOException e) {
            r.append(" precreate: FAILED ").append(e.getMessage()).append("\n");
        }
        if (uploadId != null) {
            List<String> asks = new ArrayList<>();
            if (localSite()) {
                asks.add(site);
            } else {
                asks.add(scheme + "://d." + root);
                asks.add(scheme + "://d.terabox.com");
                asks.add(base());
            }
            for (String b : asks) {
                r.append(" locateupload on ").append(b.replaceAll("^[a-z]+://", "")).append(": ").append(probe(new HttpTransport.Request("GET",
                        b + "/rest/2.0/pcs/file?method=locateupload&appid=" + APP_ID + "&path=" + enc("/.cloudbridge-test")
                                + "&uploadid=" + enc(uploadId) + "&upload_version=2.0"))).append("\n");
            }
            String[][] variants = {{"site origin", site, "1"}, {"www.terabox.com origin", "https://www.terabox.com", "1"},
                    {"no origin", "", "0"}};
            for (String b : uploadCandidates("/.cloudbridge-test", uploadId)) {
                for (String[] v : variants) {
                    if (localSite() && !v[2].equals("1")) continue;
                    String boundary = "----CloudBridgeTest";
                    byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
                            + "Content-Type: application/octet-stream\r\n\r\nx\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
                    String url = b + "/rest/2.0/pcs/superfile2?method=upload&type=tmpfile&app_id=" + APP_ID
                            + "&channel=dubox&clienttype=0&web=1&path=" + enc("/.cloudbridge-test") + "&uploadid=" + enc(uploadId)
                            + "&uploadsign=0&partseq=0";
                    HttpTransport.Request req = new HttpTransport.Request("POST", url).body(body, "multipart/form-data; boundary=" + boundary);
                    r.append(" upload to ").append(b.replaceAll("^[a-z]+://", "")).append(" (").append(v[0]).append("): ")
                            .append(probe(req, v[1])).append("\n");
                }
            }
        }

        r.append("\nDownload test:\n");
        CloudEntry file = null;
        if (top != null) {
            for (CloudEntry e : top) {
                if (!e.dir) {
                    file = e;
                    break;
                }
            }
            for (CloudEntry e : top) {
                if (file != null || !e.dir) continue;
                try {
                    for (CloudEntry child : list(e.path)) {
                        if (!child.dir) {
                            file = child;
                            break;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        if (file == null) {
            r.append(" no file in the top folder to try (put a small file there and run the test again)\n");
        } else {
            try {
                Map<String, Object> m = apiGet("filemetas", "&target=" + enc("[" + Json.quote(file.path) + "]") + "&dlink=1", "Downloading");
                List<Object> info = Json.list(m, "info");
                String dlink = info.isEmpty() || !(info.get(0) instanceof Map) ? null : Json.str(asMap(info.get(0)), "dlink");
                if (dlink == null) {
                    r.append(" filemetas: no download address (").append(m.keySet()).append(")\n");
                } else {
                    r.append(" download address on ").append(URI.create(dlink).getHost()).append("\n");
                    HttpTransport.Request req = new HttpTransport.Request("GET", dlink);
                    req.header("User-Agent", UA);
                    req.header("Referer", site + "/main?category=all");
                    req.header("Origin", site);
                    req.header("Range", "bytes=0-0");
                    if (sameFamily(dlink)) req.header("Cookie", cookie);
                    try {
                        HttpTransport.Response resp = http.execute(req);
                        String loc = resp.header("location");
                        r.append(" first request: HTTP ").append(resp.code).append(loc == null ? "" : " -> " + URI.create(loc).getHost()).append("\n");
                        resp.close();
                    } catch (IOException e) {
                        r.append(" first request: error ").append(e.getMessage()).append("\n");
                    }
                }
            } catch (IOException e) {
                r.append(" filemetas: FAILED ").append(e.getMessage()).append("\n");
            }
        }
        return r.toString();
    }

    // ---------------------------------------------------------------- upload helpers

    /** True for a site that is a local test server, where there is only one host. */
    private boolean localSite() {
        return siteHost.startsWith("127.") || siteHost.startsWith("localhost");
    }

    /**
     * The servers that may take an upload, best first. The website host (www) does not accept uploads: TeraBox
     * answers 403 there, so the upload has to go to the server it names itself ("c-jp.", "c." ...).
     */
    private List<String> uploadCandidates(String path, String uploadId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (state.uploadBase != null) out.add(state.uploadBase);
        if (state.uploadBase == null) out.addAll(locateUploadHosts(path, uploadId));
        if (!localSite()) {
            String root = registrable(siteHost);
            out.add(scheme + "://c-jp." + root);
            out.add(scheme + "://c-jp.terabox.com");
            out.add(base());
            out.add(scheme + "://c.terabox.com");
        }
        out.add(site);
        // Servers that already failed for this account are tried last, so a dead one is not retried for every file.
        List<String> good = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (String b : out) (state.badUploadBases.contains(b) ? bad : good).add(b);
        good.addAll(bad);
        return good;
    }

    /**
     * Asks TeraBox which servers take the upload. The answer of the API server itself comes first: it names the
     * data centre that holds this upload (another data centre answers "user not exists").
     */
    private List<String> locateUploadHosts(String path, String uploadId) {
        List<String> asks = new ArrayList<>();
        if (localSite()) {
            asks.add(site);
        } else {
            asks.add(base());
            asks.add(scheme + "://d." + registrable(siteHost));
            asks.add(scheme + "://d.terabox.com");
        }
        List<String> found = new ArrayList<>();
        for (String base : asks) {
            try {
                Map<String, Object> m = rawGet(base + "/rest/2.0/pcs/file?method=locateupload&appid=" + APP_ID + "&path=" + enc(path)
                        + "&uploadid=" + enc(uploadId) + "&upload_version=2.0", "Finding the upload server");
                String host = Json.str(m, "host");
                if (host != null && !host.isEmpty()) found.add(host.contains("://") ? host : scheme + "://" + host);
                for (Object o : Json.list(m, "servers")) {
                    if (!(o instanceof Map)) continue;
                    String server = Json.str(asMap(o), "server");
                    if (server != null && server.startsWith("https://") && !found.contains(server)) found.add(server);
                }
                if (!found.isEmpty()) return found;
            } catch (IOException ignored) {
                // try the next one
            }
        }
        return found;
    }

    /** Sends one part to the first server that takes it; returns the server that did. */
    private String sendBlock(List<String> bases, String path, String uploadId, int index, byte[] buf, int len, String md5)
            throws IOException {
        IOException last = null;
        List<String> notes = new ArrayList<>();
        for (String b : bases) {
            String name = b.replaceAll("^[a-z]+://", "");
            try {
                sendBlockTo(b, path, uploadId, index, buf, len, md5);
                state.badUploadBases.remove(b);
                return b;
            } catch (CloudException e) {
                // A wrong server answers 403 / 404 / 405 / 5xx. A real answer from TeraBox (a JSON error) is final.
                if (e.status != 403 && e.status != 404 && e.status != 405 && e.status / 100 != 5) throw e;
                last = new CloudException(e.status, e.getMessage());
                notes.add(name + ": " + String.valueOf(e.getMessage()).replaceAll("^.*answered ", ""));
                state.badUploadBases.add(b);
            } catch (IOException e) {
                last = e;           // the server does not exist or cannot be reached
                notes.add(name + ": " + String.valueOf(e.getMessage()).replaceAll("^Unable to resolve host.*", "no such server"));
                state.badUploadBases.add(b);
            }
        }
        int status = last instanceof CloudException ? ((CloudException) last).status : 502;
        throw new CloudException(status, "Uploading failed: no TeraBox upload server took the file. "
                + String.join("; ", notes) + diagnostics());
    }

    private void sendBlockTo(String base, String path, String uploadId, int index, byte[] buf, int len, String md5)
            throws IOException {
        String boundary = "----CloudBridge" + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE);
        ByteArrayOutputStream body = new ByteArrayOutputStream(len + 256);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        body.write(buf, 0, len);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        String url = base + "/rest/2.0/pcs/superfile2?method=upload&type=tmpfile&app_id=" + APP_ID
                + "&channel=dubox&clienttype=0&web=1"
                + "&path=" + enc(path) + "&uploadid=" + enc(uploadId) + "&uploadsign=0&partseq=" + index;
        HttpTransport.Request req = new HttpTransport.Request("POST", url).body(body.toByteArray(),
                "multipart/form-data; boundary=" + boundary);
        decorate(req, url);
        // The upload servers expect the request to come from TeraBox's website, not from the API host.
        req.header("Origin", site);
        req.header("Referer", site + "/main?category=all");
        HttpTransport.Response r = http.execute(req);
        String text = readText(r, 64 * 1024);
        if (r.code / 100 != 2) {
            String said = text.replaceAll("\\s+", " ").trim();
            throw new CloudException(r.code, "Uploading failed: TeraBox's upload server " + base.replaceAll("^[a-z]+://", "")
                    + " answered HTTP " + r.code + (said.isEmpty() ? "" : " (" + said.substring(0, Math.min(said.length(), 120)) + ")"));
        }
        Map<String, Object> m = parse(text, "Uploading");
        long errno = Json.num(m, "errno", 0);
        if (errno != 0) throw errnoError(errno, "Uploading", text);
        String got = Json.str(m, "md5");
        if (got != null && !got.equalsIgnoreCase(md5)) {
            throw new CloudException(502, "TeraBox received part " + (index + 1) + " damaged (checksum mismatch)");
        }
    }

    private static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off == 0 ? -1 : off;
    }

    private static String md5(byte[] b, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(b, 0, len);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonStrings(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(l.get(i)));
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------- web API

    private void fileManager(String opera, String fileList, String what) throws IOException {
        apiPost("filemanager", "&opera=" + opera + "&async=0&onnest=fail", "filelist=" + enc(fileList), what);
    }

    private String common() {
        return "app_id=" + APP_ID + "&web=1&channel=dubox&clienttype=0"
                + (state.jsToken == null ? "" : "&jsToken=" + enc(state.jsToken))
                + (state.bdstoken == null ? "" : "&bdstoken=" + enc(state.bdstoken));
    }

    private static final Pattern BDS_TOKEN = Pattern.compile("bdstoken(?:\\\"|%22|\"|')?\\s*(?::|%3A|=)\\s*(?:\\\\?\"|%22|')?([0-9a-fA-F]{32})");

    /** Removes %XX escapes (the page hides its script inside one); leaves anything malformed alone. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                sb.append((char) (hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2))));
                i += 2;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    /** Both tokens are best effort: TeraBox moves them around, so every source is optional. */
    private synchronized void tokens() {
        if (state.tokensFetched) return;
        String fields = "app_id=" + APP_ID + "&web=1&channel=dubox&clienttype=0";
        String pageBase = null;
        for (String b : bases) {
            try {
                HttpTransport.Request req = new HttpTransport.Request("GET", b + "/main?category=all");
                decorate(req, b);
                HttpTransport.Response r = http.execute(req);
                int code = r.code;
                String raw = readText(r, 4 * 1024 * 1024);
                if (code / 100 != 2) continue;
                String page = unescape(raw);
                Matcher m = JS_TOKEN.matcher(page);
                if (m.find()) state.jsToken = m.group(1);
                Matcher t = BDS_TOKEN.matcher(page);
                if (t.find()) state.bdstoken = t.group(1);
                if (pageBase == null) pageBase = b;
                if (state.jsToken != null) {
                    pageBase = b;
                    break;
                }
            } catch (IOException ignored) {
            }
        }
        if (pageBase != null) state.base = pageBase;
        String js = state.jsToken == null ? "" : "&jsToken=" + enc(state.jsToken);
        if (state.bdstoken == null) {
            try {
                Map<String, Object> t = rawGet(base() + "/api/gettemplatevariable?" + fields + "&fields=" + enc("[\"bdstoken\"]") + js,
                        "Opening TeraBox");
                state.bdstoken = Json.str(Json.obj(t, "result"), "bdstoken");
            } catch (IOException ignored) {
            }
        }
        if (state.bdstoken == null) {
            try {
                Map<String, Object> t = rawGet(base() + "/api/home/info?" + fields + js, "Opening TeraBox");
                String token = Json.str(Json.obj(t, "data"), "bdstoken");
                state.bdstoken = token != null ? token : Json.str(t, "bdstoken");
            } catch (IOException ignored) {
            }
        }
        state.tokensFetched = true;
    }

    private Map<String, Object> apiGet(String endpoint, String extra, String what) throws IOException {
        tokens();
        return failover(() -> rawGet(base() + "/api/" + endpoint + "?" + common() + extra, what));
    }

    private Map<String, Object> apiPost(String endpoint, String extraQuery, String form, String what) throws IOException {
        tokens();
        return failover(() -> {
            String url = base() + "/api/" + endpoint + "?" + common() + extraQuery;
            HttpTransport.Request req = new HttpTransport.Request("POST", url)
                    .body(form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded; charset=UTF-8");
            return finish(req, url, what);
        });
    }

    private Map<String, Object> rawGet(String url, String what) throws IOException {
        return finish(new HttpTransport.Request("GET", url), url, what);
    }

    private Map<String, Object> finish(HttpTransport.Request req, String url, String what) throws IOException {
        decorate(req, url);
        HttpTransport.Response r = http.execute(req);
        int status = r.code;
        String text = readText(r, Http.MAX_XML);
        if (status == 301 || status == 302 || status == 303 || status == 307) {
            throw new CloudException(401, what + " failed: TeraBox sent us to its sign-in page. Sign in again.");
        }
        if (status / 100 != 2) {
            String where = url.replaceAll("^[a-z]+://", "").replaceAll("\\?.*$", "");
            throw new CloudException(status, what + " failed: TeraBox answered HTTP " + status + " [" + where + "]");
        }
        Map<String, Object> m;
        try {
            m = parse(text, what);
        } catch (CloudException e) {
            String path = url.replaceAll("^[a-z]+://[^/]+", "").replaceAll("\\?.*$", "");
            throw new CloudException(e.status, e.getMessage() + " [HTTP " + status + " " + path + "]");
        }
        long errno = Json.num(m, "errno", 0);
        if (errno != 0) {
            CloudException ce = errnoError(errno, what, text);
            if (ce.status == 401) throw new CloudException(401, ce.getMessage() + diagnostics());
            throw ce;
        }
        return m;
    }

    private void decorate(HttpTransport.Request req, String url) {
        req.header("User-Agent", UA);
        req.header("Accept", "application/json, text/plain, */*");
        req.header("Referer", base() + "/main?category=all");
        req.header("Origin", base());
        if (sameFamily(url)) req.header("Cookie", cookie);
    }

    /** The cookie is only sent to TeraBox's own hosts. */
    private boolean sameFamily(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return false;
            for (String b : bases) {
                if (h.equalsIgnoreCase(URI.create(b).getHost())) return true;
            }
            String mine = registrable(siteHost.replaceAll(":\\d+$", ""));
            if (h.equalsIgnoreCase(siteHost.replaceAll(":\\d+$", "")) || h.toLowerCase(Locale.ROOT).endsWith("." + mine)
                    || h.equalsIgnoreCase(mine)) {
                return true;
            }
            // TeraBox runs the same service under several names; its upload servers may be on any of them.
            String lower = h.toLowerCase(Locale.ROOT);
            for (String d : TERABOX_DOMAINS) {
                if (lower.equals(d) || lower.endsWith("." + d)) return true;
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String registrable(String host) {
        String[] p = host.replaceAll(":\\d+$", "").split("\\.");
        if (p.length <= 2 || host.matches("[\\d.:]+")) return host.replaceAll(":\\d+$", "");
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    private static String readText(HttpTransport.Response r, int max) throws IOException {
        try {
            return new String(r.readAll(max), StandardCharsets.UTF_8);
        } finally {
            r.close();
        }
    }

    private static Map<String, Object> parse(String text, String what) throws CloudException {
        try {
            return Json.parseObject(text, "TeraBox");
        } catch (CloudException e) {
            String snippet = text.length() > 120 ? text.substring(0, 120) : text;
            throw new CloudException(502, what + " failed: TeraBox did not answer with data CloudBridge understands "
                    + "(its website may have changed). Start of the reply: " + snippet.replaceAll("\\s+", " "));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String enc(String s) {
        return GoogleOAuth.enc(s);
    }

    static CloudException errnoError(long errno, String what, String raw) {
        int status = 502;
        String why;
        if (errno == -6 || errno == 31045) {
            status = 401;
            why = "TeraBox says you are not signed in. Edit this storage and sign in again";
        } else if (errno == -9 || errno == 31066 || errno == 31190 || (errno == 2 && what.startsWith("Listing"))) {
            status = 404;
            why = "not found";
        } else if (errno == -8 || errno == 31061 || errno == 12) {
            status = 412;
            why = "it already exists";
        } else if (errno == 31062) {
            status = 400;
            why = "TeraBox does not allow that name";
        } else if (errno == 4000023 || errno == 400031 || errno == 400040) {
            status = 403;
            why = "TeraBox wants a security check; open the TeraBox website once, solve it, then sign in again";
        } else if (errno == 31064 || errno == 31208 || errno == 10) {
            status = 507;
            why = "your TeraBox storage may be full";
        } else {
            why = "TeraBox refused";
        }
        return new CloudException(status, what + " failed: " + why + " (TeraBox error " + errno + ")");
    }
}
