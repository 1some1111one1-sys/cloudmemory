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
    private static final Pattern JS_TOKEN = Pattern.compile("fn(?:%28|\\()(?:%22|\")([0-9A-Za-z]{20,})(?:%22|\")");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private final HttpTransport http;
    private final String site;      // scheme://host
    private final String siteHost;
    private final String scheme;
    private final String cookie;
    private final State state;

    public TeraBoxClient(HttpTransport http, String siteUrl, String cookie) {
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
        this.cookie = cookie.trim();
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

        Map<String, Object> pre = apiPost("precreate", "", "path=" + enc(p) + "&autoinit=1&isdir=0&size=" + len
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

        String uploadBase = null;
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[BLOCK];
            for (int i = 0; i < md5s.size(); i++) {
                int n = Math.max(0, readUpTo(in, buf));
                if (!send[i]) continue;
                if (uploadBase == null) uploadBase = locateUploadHost(p, uploadId);
                sendBlock(uploadBase, p, uploadId, i, buf, n, md5s.get(i));
            }
        }

        Map<String, Object> created = apiPost("create", "", "path=" + enc(p) + "&size=" + len + "&isdir=0&rtype=" + rtype
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

    // ---------------------------------------------------------------- upload helpers

    private String locateUploadHost(String path, String uploadId) {
        // Best effort: ask TeraBox which server takes the upload; fall back to the main site.
        try {
            String base = siteHost.startsWith("127.") || siteHost.startsWith("localhost") ? site : scheme + "://d." + registrable(siteHost);
            Map<String, Object> m = rawGet(base + "/rest/2.0/pcs/file?method=locateupload&appid=" + APP_ID + "&path=" + enc(path)
                    + "&uploadid=" + enc(uploadId) + "&upload_version=2.0", "Finding the upload server");
            String host = Json.str(m, "host");
            if (host == null) {
                List<Object> servers = Json.list(m, "servers");
                if (!servers.isEmpty() && servers.get(0) instanceof Map) host = Json.str(asMap(servers.get(0)), "server");
            }
            if (host != null && !host.isEmpty()) {
                return host.contains("://") ? host : scheme + "://" + host;
            }
        } catch (IOException ignored) {
        }
        return site;
    }

    private void sendBlock(String base, String path, String uploadId, int index, byte[] buf, int len, String md5) throws IOException {
        String boundary = "----CloudBridge" + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE);
        ByteArrayOutputStream body = new ByteArrayOutputStream(len + 256);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        body.write(buf, 0, len);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        String url = base + "/rest/2.0/pcs/superfile2?method=upload&app_id=" + APP_ID + "&channel=dubox&clienttype=0&web=1"
                + "&path=" + enc(path) + "&uploadid=" + enc(uploadId) + "&uploadsign=0&partseq=" + index;
        HttpTransport.Request req = new HttpTransport.Request("POST", url).body(body.toByteArray(),
                "multipart/form-data; boundary=" + boundary);
        decorate(req, url);
        HttpTransport.Response r = http.execute(req);
        String text = readText(r, 64 * 1024);
        if (r.code / 100 != 2) throw new CloudException(r.code, "Uploading failed: TeraBox's upload server answered HTTP " + r.code);
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

    /** Both tokens are best effort: TeraBox moves them around, so every source is optional. */
    private synchronized void tokens() {
        if (state.tokensFetched) return;
        String fields = "app_id=" + APP_ID + "&web=1&channel=dubox&clienttype=0";
        try {
            HttpTransport.Request req = new HttpTransport.Request("GET", site + "/main");
            decorate(req, site);
            HttpTransport.Response r = http.execute(req);
            String page = readText(r, 4 * 1024 * 1024);
            Matcher m = JS_TOKEN.matcher(page);
            if (m.find()) state.jsToken = m.group(1);
            Matcher b = BDS_TOKEN.matcher(page);
            if (b.find()) state.bdstoken = b.group(1);
        } catch (IOException ignored) {
        }
        String js = state.jsToken == null ? "" : "&jsToken=" + enc(state.jsToken);
        if (state.bdstoken == null) {
            try {
                Map<String, Object> t = rawGet(site + "/api/gettemplatevariable?" + fields + "&fields=" + enc("[\"bdstoken\"]") + js,
                        "Opening TeraBox");
                state.bdstoken = Json.str(Json.obj(t, "result"), "bdstoken");
            } catch (IOException ignored) {
            }
        }
        if (state.bdstoken == null) {
            try {
                Map<String, Object> t = rawGet(site + "/api/home/info?" + fields + js, "Opening TeraBox");
                String token = Json.str(Json.obj(t, "data"), "bdstoken");
                state.bdstoken = token != null ? token : Json.str(t, "bdstoken");
            } catch (IOException ignored) {
            }
        }
        state.tokensFetched = true;
    }

    private Map<String, Object> apiGet(String endpoint, String extra, String what) throws IOException {
        tokens();
        return rawGet(site + "/api/" + endpoint + "?" + common() + extra, what);
    }

    private Map<String, Object> apiPost(String endpoint, String extraQuery, String form, String what) throws IOException {
        tokens();
        String url = site + "/api/" + endpoint + "?" + common() + extraQuery;
        HttpTransport.Request req = new HttpTransport.Request("POST", url)
                .body(form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded; charset=UTF-8");
        return finish(req, url, what);
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
        if (status / 100 != 2) throw new CloudException(status, what + " failed: TeraBox answered HTTP " + status);
        Map<String, Object> m;
        try {
            m = parse(text, what);
        } catch (CloudException e) {
            String path = url.replaceAll("^[a-z]+://[^/]+", "").replaceAll("\\?.*$", "");
            throw new CloudException(e.status, e.getMessage() + " [HTTP " + status + " " + path + "]");
        }
        long errno = Json.num(m, "errno", 0);
        if (errno != 0) throw errnoError(errno, what, text);
        return m;
    }

    private void decorate(HttpTransport.Request req, String url) {
        req.header("User-Agent", UA);
        req.header("Accept", "application/json, text/plain, */*");
        req.header("Referer", site + "/main");
        if (sameFamily(url)) req.header("Cookie", cookie);
    }

    /** The cookie is only sent to TeraBox's own hosts. */
    private boolean sameFamily(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return false;
            String mine = registrable(siteHost.replaceAll(":\\d+$", ""));
            return h.equalsIgnoreCase(siteHost.replaceAll(":\\d+$", "")) || h.toLowerCase(Locale.ROOT).endsWith("." + mine)
                    || h.equalsIgnoreCase(mine);
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
