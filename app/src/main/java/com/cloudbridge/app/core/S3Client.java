package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * S3-compatible object storage (AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO, DigitalOcean Spaces, ...).
 * Folders are emulated with key prefixes, like every S3 browser does.
 */
public final class S3Client implements CloudClient {
    private static final byte[] EMPTY = new byte[0];
    private static final Map<String, String> NONE = Collections.emptyMap();
    private static final long MAX_SINGLE_PUT = 5L * 1024 * 1024 * 1024 - 1;

    private final HttpTransport http;
    private final String scheme;
    private final String host;
    private final int port;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final boolean pathStyle;

    public S3Client(HttpTransport http, String endpoint, String region, String bucket,
                    String accessKey, String secretKey, boolean pathStyle) {
        this.http = http;
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.isEmpty()) throw new IllegalArgumentException("Enter the S3 endpoint, e.g. https://s3.amazonaws.com");
        if (!e.contains("://")) e = "https://" + e;
        URI uri;
        try {
            uri = new URI(e);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("The endpoint is not a valid URL");
        }
        String s = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!s.equals("http") && !s.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("The endpoint must look like https://s3.amazonaws.com");
        }
        this.scheme = s;
        this.host = uri.getHost();
        this.port = uri.getPort();
        this.bucket = bucket == null ? "" : bucket.trim();
        if (this.bucket.isEmpty()) throw new IllegalArgumentException("Enter the bucket name");
        this.region = (region == null || region.trim().isEmpty()) ? "us-east-1" : region.trim();
        if (accessKey == null || accessKey.trim().isEmpty() || secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("Enter the access key ID and the secret access key");
        }
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey;
        this.pathStyle = pathStyle;
    }

    // ---------------------------------------------------------------- request plumbing

    private String hostHeader() {
        String h = pathStyle ? host : bucket + "." + host;
        boolean isDefault = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return isDefault ? h : h + ":" + port;
    }

    private static String encodeKey(String key) {
        String[] segments = key.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(Paths.encodeSegment(segments[i]));
        }
        return sb.toString();
    }

    private String canonicalUri(String key) {
        if (pathStyle) return "/" + Paths.encodeSegment(bucket) + (key.isEmpty() ? "" : "/" + encodeKey(key));
        return "/" + encodeKey(key);
    }

    private HttpTransport.Response call(String method, String key, Map<String, String> query, byte[] body,
                                        File file, String contentType, Map<String, String> extra) throws IOException {
        String payloadHash = file != null ? Crypto.sha256Hex(file) : Crypto.sha256Hex(body == null ? EMPTY : body);
        String host = hostHeader();
        String uri = canonicalUri(key);
        S3Signer.Result signed = S3Signer.sign(method, host, uri, query, extra, payloadHash,
                accessKey, secretKey, region, System.currentTimeMillis());
        String url = scheme + "://" + host + uri + (signed.canonicalQuery.isEmpty() ? "" : "?" + signed.canonicalQuery);
        HttpTransport.Request req = new HttpTransport.Request(method, url)
                .header("Authorization", signed.authorization)
                .header("x-amz-date", signed.amzDate)
                .header("x-amz-content-sha256", payloadHash);
        for (Map.Entry<String, String> h : extra.entrySet()) req.header(h.getKey(), h.getValue());
        if (file != null) req.bodyFile(file, contentType);
        else if (body != null) req.body(body, contentType);
        return http.execute(req);
    }

    private static String keyOf(String path) {
        String p = Paths.normalize(path);
        return p.equals("/") ? "" : p.substring(1);
    }

    private static String prefixOf(String dirPath) {
        String k = keyOf(dirPath);
        return k.isEmpty() ? "" : k + "/";
    }

    // ---------------------------------------------------------------- listing

    private static final class Page {
        final List<String[]> objects = new ArrayList<>();   // {key, size, lastModified}
        final List<String> prefixes = new ArrayList<>();
        String next;
    }

    private Page fetchPage(String prefix, boolean delimiter, String token, int maxKeys) throws IOException {
        Map<String, String> q = new TreeMap<>();
        q.put("list-type", "2");
        q.put("encoding-type", "url");
        if (delimiter) q.put("delimiter", "/");
        if (!prefix.isEmpty()) q.put("prefix", prefix);
        if (token != null) q.put("continuation-token", token);
        if (maxKeys > 0) q.put("max-keys", String.valueOf(maxKeys));
        HttpTransport.Response r = call("GET", "", q, null, null, null, NONE);
        if (r.code != 200) throw Http.fail(r, "Listing");
        byte[] xml = r.readAll(Http.MAX_XML);
        r.close();
        return parsePage(xml);
    }

    private static Page parsePage(byte[] xml) throws IOException {
        Element root = parseXml(xml).getDocumentElement();
        boolean encoded = "url".equalsIgnoreCase(childText(root, "EncodingType"));
        Page page = new Page();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (el.getTagName().equals("Contents")) {
                String key = decode(childRaw(el, "Key"), encoded);
                if (key != null) {
                    page.objects.add(new String[]{key, childText(el, "Size"), childText(el, "LastModified")});
                }
            } else if (el.getTagName().equals("CommonPrefixes")) {
                String p = decode(childRaw(el, "Prefix"), encoded);
                if (p != null) page.prefixes.add(p);
            }
        }
        if ("true".equalsIgnoreCase(childText(root, "IsTruncated"))) {
            String t = childText(root, "NextContinuationToken");   // opaque, never URL-encoded
            if (t != null && !t.isEmpty()) page.next = t;
        }
        return page;
    }

    @Override
    public List<CloudEntry> list(String dirPath) throws IOException {
        String prefix = prefixOf(dirPath);
        Map<String, CloudEntry> out = new LinkedHashMap<>();
        String token = null;
        int pages = 0;
        do {
            Page page = fetchPage(prefix, true, token, 0);
            for (String[] o : page.objects) {
                String key = o[0];
                if (!key.startsWith(prefix) || key.equals(prefix)) continue;   // skip the folder's own marker
                String rel = key.substring(prefix.length());
                int slash = rel.indexOf('/');
                if (slash == 0) continue;
                if (slash > 0) {
                    // A server that ignores "delimiter": report the immediate sub-folder instead.
                    String dir = "/" + prefix + rel.substring(0, slash);
                    if (!out.containsKey(dir)) out.put(dir, new CloudEntry(dir, true, -1, 0));
                } else {
                    String path = "/" + key;
                    out.put(path, new CloudEntry(path, false, Http.parseLong(o[1], -1), Http.parseIsoDate(o[2])));
                }
            }
            for (String p : page.prefixes) {
                if (!p.startsWith(prefix) || p.length() <= prefix.length()) continue;
                String dir = "/" + (p.endsWith("/") ? p.substring(0, p.length() - 1) : p);
                out.put(dir, new CloudEntry(dir, true, -1, 0));
            }
            token = page.next;
        } while (token != null && ++pages < 100000);
        return new ArrayList<>(out.values());
    }

    /** Every object key under a prefix, recursively. */
    private List<String> listKeys(String prefix) throws IOException {
        List<String> keys = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            Page page = fetchPage(prefix, false, token, 0);
            for (String[] o : page.objects) {
                if (o[0].startsWith(prefix)) keys.add(o[0]);
            }
            token = page.next;
        } while (token != null && ++pages < 100000);
        return keys;
    }

    private boolean hasChildren(String prefix) throws IOException {
        Page page = fetchPage(prefix, false, null, 1);
        return !page.objects.isEmpty() || !page.prefixes.isEmpty();
    }

    // ---------------------------------------------------------------- CloudClient

    @Override
    public CloudEntry stat(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return new CloudEntry("/", true, -1, 0);
        String key = keyOf(p);
        HttpTransport.Response r = call("HEAD", key, NONE, null, null, null, NONE);
        if (r.code == 200) {
            long size = Http.parseLong(r.header("content-length"), -1);
            long modified = Http.parseHttpDate(r.header("last-modified"));
            r.close();
            return new CloudEntry(p, false, size, modified);
        }
        if (r.code == 404 || r.code == 403) {
            // Not an object. It may still be a "folder" (a prefix with objects below it).
            r.close();
            if (hasChildren(key + "/")) return new CloudEntry(p, true, -1, 0);
            throw new CloudException(404, "Not found: " + p);
        }
        throw Http.fail(r, "Reading item");
    }

    @Override
    public void download(String path, File dest) throws IOException {
        HttpTransport.Response r = call("GET", keyOf(path), NONE, null, null, null, NONE);
        if (r.code != 200) throw Http.fail(r, "Download");
        Http.writeTo(r, dest);
    }

    @Override
    public void upload(String path, File src, String mime) throws IOException {
        if (src.length() > MAX_SINGLE_PUT) {
            throw new CloudException(413, "Files larger than 5 GB are not supported on S3 yet");
        }
        HttpTransport.Response r = call("PUT", keyOf(path), NONE, null, src,
                mime == null ? Paths.MIME_DEFAULT : mime, NONE);
        if (r.code != 200) throw Http.fail(r, "Upload");
        r.close();
    }

    @Override
    public void mkdir(String path) throws IOException {
        HttpTransport.Response r = call("PUT", keyOf(path) + "/", NONE, EMPTY, null, "application/x-directory", NONE);
        if (r.code != 200) throw Http.fail(r, "Creating folder");
        r.close();
    }

    @Override
    public void delete(String path, boolean isDir) throws IOException {
        if (isDir) {
            for (String key : listKeys(prefixOf(path))) deleteKey(key);
        } else {
            deleteKey(keyOf(path));
        }
    }

    private void deleteKey(String key) throws IOException {
        HttpTransport.Response r = call("DELETE", key, NONE, null, null, null, NONE);
        if (r.code != 200 && r.code != 204 && r.code != 404) throw Http.fail(r, "Delete");
        r.close();
    }

    @Override
    public void move(String from, String to, boolean isDir) throws IOException {
        if (!isDir) {
            copyKey(keyOf(from), keyOf(to));
            deleteKey(keyOf(from));
            return;
        }
        String src = prefixOf(from);
        String dst = prefixOf(to);
        List<String> keys = listKeys(src);
        if (keys.isEmpty()) throw new CloudException(404, "Folder not found: " + from);
        for (String k : keys) copyKey(k, dst + k.substring(src.length()));
        for (String k : keys) deleteKey(k);
    }

    private void copyKey(String srcKey, String dstKey) throws IOException {
        Map<String, String> extra = new TreeMap<>();
        extra.put("x-amz-copy-source", "/" + Paths.encodeSegment(bucket) + "/" + encodeKey(srcKey));
        HttpTransport.Response r = call("PUT", dstKey, NONE, EMPTY, null, null, extra);
        if (r.code != 200) throw Http.fail(r, "Copy");
        // S3 can answer 200 and still report a failure in the body.
        String body = new String(r.readAll(64 * 1024), StandardCharsets.UTF_8);
        r.close();
        if (body.contains("<Error>")) throw new CloudException(500, "Copy failed: " + body);
    }

    // ---------------------------------------------------------------- XML

    private static Document parseXml(byte[] xml) throws IOException {
        String notS3 = "The server did not answer with S3 data. Check the endpoint and bucket.";
        // Not namespace-aware: some services omit the namespace, so we match on plain tag names.
        Document doc = Http.parseXml(xml, false, notS3);
        if (!"ListBucketResult".equals(doc.getDocumentElement().getTagName())) throw new CloudException(502, notS3);
        return doc;
    }

    /** Text of the first child element with this tag, exactly as sent (keys may contain leading/trailing spaces). */
    private static String childRaw(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return n.getTextContent();
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        String t = childRaw(parent, tag);
        return t == null ? null : t.trim();
    }

    private static String decode(String s, boolean urlEncoded) {
        if (s == null || !urlEncoded) return s;
        try {
            return URLDecoder.decode(s, "UTF-8");   // encoding-type=url uses '+' for a space
        } catch (Exception e) {
            return s;
        }
    }
}
