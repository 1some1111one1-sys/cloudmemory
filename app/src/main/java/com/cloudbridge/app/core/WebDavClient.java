package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** WebDAV client (Nextcloud, ownCloud, Synology, Apache/nginx dav, Box, pCloud, Koofr, ...). */
public final class WebDavClient implements CloudClient {
    private static final String DAV = "DAV:";
    private static final Pattern ORIGIN = Pattern.compile("^(https?://[^/]+)(/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final byte[] PROPFIND_BODY = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/>"
            + "<d:getlastmodified/></d:prop></d:propfind>").getBytes(StandardCharsets.UTF_8);

    private final HttpTransport http;
    private final String origin;     // scheme://host[:port]
    private final String basePath;   // decoded, no trailing slash, "" for the server root
    private final String baseUrl;    // origin + encoded basePath
    private final String auth;       // "Basic ..." or null

    public WebDavClient(HttpTransport http, String url, String username, String password) {
        this.http = http;
        String u = url == null ? "" : url.trim();
        Matcher m = ORIGIN.matcher(u);
        if (!m.matches()) throw new IllegalArgumentException("The server URL must start with http:// or https://");
        this.origin = m.group(1);
        String rawPath = m.group(2) == null ? "" : m.group(2);
        String path = Paths.normalize(Paths.decode(rawPath));
        this.basePath = path.equals("/") ? "" : path;
        this.baseUrl = origin + (basePath.isEmpty() ? "" : Paths.encodePath(basePath));
        this.auth = (username == null || username.isEmpty()) ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- requests

    private String url(String path) {
        String p = Paths.normalize(path);
        return baseUrl + (p.equals("/") ? "/" : Paths.encodePath(p));
    }

    private boolean sameOrigin(String url) {
        return url.toLowerCase(Locale.ROOT).startsWith(origin.toLowerCase(Locale.ROOT) + "/")
                || url.equalsIgnoreCase(origin);
    }

    private HttpTransport.Response send(String method, String url, Map<String, String> headers,
                                        byte[] body, File file, String contentType) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpTransport.Request req = new HttpTransport.Request(method, current);
            if (auth != null && sameOrigin(current)) req.header("Authorization", auth);
            req.headers.putAll(headers);
            if (file != null) req.bodyFile(file, contentType);
            else if (body != null) req.body(body, contentType);
            HttpTransport.Response r = http.execute(req);
            String location = r.header("location");
            if ((r.code == 301 || r.code == 302 || r.code == 307 || r.code == 308) && location != null) {
                r.close();
                current = resolve(current, location);
                continue;
            }
            return r;
        }
        throw new CloudException(310, "Too many redirects");
    }

    private static String resolve(String base, String location) {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (IllegalArgumentException e) {
            return location;
        }
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---------------------------------------------------------------- CloudClient

    @Override
    public List<CloudEntry> list(String dirPath) throws IOException {
        String dir = Paths.normalize(dirPath);
        String u = url(dir);
        if (!u.endsWith("/")) u += "/";
        HttpTransport.Response r = send("PROPFIND", u, headers("Depth", "1"), PROPFIND_BODY, null, "application/xml");
        if (r.code != 207 && r.code != 200) throw Http.fail(r, "Listing folder");
        byte[] xml = r.readAll(Http.MAX_XML);
        r.close();
        List<CloudEntry> out = new ArrayList<>();
        for (CloudEntry e : parseMultiStatus(xml)) {
            if (!e.path.equals(dir)) out.add(e);
        }
        return out;
    }

    @Override
    public CloudEntry stat(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return new CloudEntry("/", true, -1, 0);
        HttpTransport.Response r = send("PROPFIND", url(p), headers("Depth", "0"), PROPFIND_BODY, null, "application/xml");
        if (r.code != 207 && r.code != 200) throw Http.fail(r, "Reading item");
        byte[] xml = r.readAll(Http.MAX_XML);
        r.close();
        List<CloudEntry> entries = parseMultiStatus(xml);
        if (entries.isEmpty()) throw new CloudException(404, "Not found: " + p);
        CloudEntry e = entries.get(0);
        return new CloudEntry(p, e.dir, e.size, e.modified);
    }

    @Override
    public void download(String path, File dest) throws IOException {
        HttpTransport.Response r = send("GET", url(path), headers(), null, null, null);
        if (r.code != 200) throw Http.fail(r, "Download");
        Http.writeTo(r, dest);
    }

    @Override
    public void upload(String path, File src, String mime) throws IOException {
        HttpTransport.Response r = send("PUT", url(path), headers(), null, src,
                mime == null ? Paths.MIME_DEFAULT : mime);
        if (r.code != 200 && r.code != 201 && r.code != 204) throw Http.fail(r, "Upload");
        r.close();
    }

    @Override
    public void mkdir(String path) throws IOException {
        HttpTransport.Response r = send("MKCOL", url(path), headers(), null, null, null);
        if (r.code != 201 && r.code != 200) throw Http.fail(r, "Creating folder");
        r.close();
    }

    @Override
    public void delete(String path, boolean isDir) throws IOException {
        String u = url(path);
        if (isDir && !u.endsWith("/")) u += "/";
        HttpTransport.Response r = send("DELETE", u, headers(), null, null, null);
        if (r.code != 200 && r.code != 202 && r.code != 204) throw Http.fail(r, "Delete");
        r.close();
    }

    @Override
    public void move(String from, String to, boolean isDir) throws IOException {
        String slash = isDir ? "/" : "";
        String destination = baseUrl + Paths.encodePath(to) + slash;
        String u = url(from);
        if (isDir && !u.endsWith("/")) u += "/";
        HttpTransport.Response r = send("MOVE", u, headers("Destination", destination, "Overwrite", "F"), null, null, null);
        if (r.code != 200 && r.code != 201 && r.code != 204) throw Http.fail(r, "Move");
        r.close();
    }

    // ---------------------------------------------------------------- parsing

    /** Converts an href (absolute URL or absolute path, percent-encoded) to a path under the base URL, or null. */
    String hrefToPath(String href) {
        String h = href.trim();
        Matcher m = ORIGIN.matcher(h);
        if (m.matches()) h = m.group(2) == null ? "/" : m.group(2);
        int q = h.indexOf('?');
        if (q >= 0) h = h.substring(0, q);
        String norm = Paths.normalize(Paths.decode(h));
        if (basePath.isEmpty()) return norm;
        if (norm.equals(basePath)) return "/";
        if (norm.startsWith(basePath + "/")) return norm.substring(basePath.length());
        return null;
    }

    List<CloudEntry> parseMultiStatus(byte[] xml) throws IOException {
        String notDav = "The server did not answer with WebDAV data. Is this really a WebDAV URL?";
        Document doc = Http.parseXml(xml, true, notDav);
        if (!isDav(doc.getDocumentElement(), "multistatus")) throw new CloudException(502, notDav);
        List<CloudEntry> out = new ArrayList<>();
        NodeList responses = doc.getElementsByTagNameNS(DAV, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = text(child(resp, "href"));
            if (href == null) continue;
            String path = hrefToPath(href);
            if (path == null) continue;
            boolean dir = href.trim().endsWith("/");   // fallback when the server omits resourcetype
            long size = -1;
            long modified = 0;
            for (Node n = resp.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element) || !isDav((Element) n, "propstat")) continue;
                Element propstat = (Element) n;
                String status = text(child(propstat, "status"));
                if (status != null && !status.contains(" 200")) continue;
                Element prop = child(propstat, "prop");
                if (prop == null) continue;
                Element type = child(prop, "resourcetype");
                if (type != null) dir = child(type, "collection") != null;
                size = Http.parseLong(text(child(prop, "getcontentlength")), size);
                String lm = text(child(prop, "getlastmodified"));
                if (lm != null) modified = Http.parseHttpDate(lm);
            }
            out.add(new CloudEntry(path, dir, dir ? -1 : size, modified));
        }
        return out;
    }

    private static boolean isDav(Element e, String local) {
        return DAV.equals(e.getNamespaceURI()) && local.equals(e.getLocalName());
    }

    private static Element child(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && isDav((Element) n, local)) return (Element) n;
        }
        return null;
    }

    private static String text(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }
}
