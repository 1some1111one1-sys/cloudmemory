package com.cloudbridge.app.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * In-memory path-style S3 bucket. Verifies every request's SigV4 signature with its own, separate
 * implementation (re-deriving the canonical request from what actually arrived on the wire).
 */
final class MockS3Server {
    static final String BUCKET = "mybucket";
    static final String ACCESS_KEY = "TESTKEY";
    static final String SECRET_KEY = "test/secret+key";
    static final String REGION = "us-east-1";

    final TreeMap<String, byte[]> objects = new TreeMap<>();
    final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    final boolean honorEncoding;
    volatile int pageSize = 3;
    volatile int requests;
    private final HttpServer server;

    MockS3Server(boolean honorEncoding) throws IOException {
        this.honorEncoding = honorEncoding;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ signature check (independent code)

    private static String awsEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String sha256(byte[] b) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** @return null if the signature is valid, otherwise a reason. */
    private String verify(HttpExchange ex, byte[] body) throws Exception {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null) return "no Authorization header";
        Matcher m = Pattern.compile("AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, "
                + "SignedHeaders=([a-z0-9;-]+), Signature=([0-9a-f]{64})").matcher(auth);
        if (!m.matches()) return "malformed Authorization: " + auth;
        if (!m.group(1).equals(ACCESS_KEY)) return "unknown access key";
        if (!m.group(3).equals(REGION)) return "wrong region in scope";
        String amzDate = ex.getRequestHeaders().getFirst("x-amz-date");
        if (amzDate == null || !amzDate.startsWith(m.group(2))) return "bad x-amz-date";
        String claimed = ex.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (!sha256(body).equals(claimed)) return "BadDigest: x-amz-content-sha256 does not match the body";

        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : m.group(4).split(";")) {
            String v = ex.getRequestHeaders().getFirst(name);
            if (v == null) return "signed header missing: " + name;
            canonicalHeaders.append(name).append(':').append(v.trim()).append('\n');
        }
        // canonical URI: decode what arrived, then encode each segment once
        String decodedPath = ex.getRequestURI().getPath();
        String canonicalUri = decodedPath.equals("/") ? "/" : "/" + String.join("/",
                java.util.Arrays.stream(decodedPath.substring(1).split("/", -1)).map(MockS3Server::awsEncode).toArray(String[]::new));
        String raw = ex.getRequestURI().getRawQuery();
        TreeMap<String, String> q = new TreeMap<>();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                q.put(awsEncode(URLDecoder.decode(k.replace("+", "%2B"), StandardCharsets.UTF_8)),
                        awsEncode(URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8)));
            }
        }
        StringBuilder canonicalQuery = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (canonicalQuery.length() > 0) canonicalQuery.append('&');
            canonicalQuery.append(e.getKey()).append('=').append(e.getValue());
        }
        String canonicalRequest = ex.getRequestMethod() + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + m.group(4) + "\n" + claimed;
        String scope = m.group(2) + "/" + m.group(3) + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), m.group(2));
        k = hmac(k, m.group(3));
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        String expected = hex(hmac(k, toSign));
        if (!expected.equals(m.group(5))) return "SignatureDoesNotMatch (canonical request was: " + canonicalRequest.replace("\n", "\\n") + ")";
        return null;
    }

    // ------------------------------------------------------------------ protocol

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    private void send(HttpExchange ex, int code, String type, byte[] body, String... headers) throws IOException {
        if (type != null) ex.getResponseHeaders().set("Content-Type", type);
        for (int i = 0; i + 1 < headers.length; i += 2) ex.getResponseHeaders().set(headers[i], headers[i + 1]);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(code, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        ex.close();
    }

    private void error(HttpExchange ex, int code, String s3Code, String message) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + s3Code + "</Code><Message>"
                + message.replace("&", "&amp;").replace("<", "&lt;") + "</Message></Error>";
        send(ex, code, "application/xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String out(String s) {
        return honorEncoding ? URLEncoder.encode(s, StandardCharsets.UTF_8) : xmlEscape(s);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new TreeMap<>();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            requests++;
            byte[] body = readAll(ex.getRequestBody());
            String problem = verify(ex, body);
            if (problem != null) {
                problems.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " -> " + problem);
                error(ex, 403, "SignatureDoesNotMatch", problem);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (!path.equals("/" + BUCKET) && !path.startsWith("/" + BUCKET + "/")) {
                error(ex, 404, "NoSuchBucket", "The specified bucket does not exist");
                return;
            }
            String key = path.length() > BUCKET.length() + 1 ? path.substring(BUCKET.length() + 2) : "";
            String method = ex.getRequestMethod();
            if (key.isEmpty() && method.equals("GET")) {
                list(ex, query(ex.getRequestURI().getRawQuery()));
                return;
            }
            synchronized (objects) {
                switch (method) {
                    case "HEAD": {
                        byte[] v = objects.get(key);
                        if (v == null) send(ex, 404, null, new byte[0]);
                        else send(ex, 200, "application/octet-stream", new byte[0], "Content-Length", String.valueOf(v.length),
                                "Last-Modified", "Mon, 01 Jan 2024 10:00:00 GMT");
                        return;
                    }
                    case "GET": {
                        byte[] v = objects.get(key);
                        if (v == null) error(ex, 404, "NoSuchKey", "The specified key does not exist.");
                        else send(ex, 200, "application/octet-stream", v);
                        return;
                    }
                    case "PUT": {
                        String copy = ex.getRequestHeaders().getFirst("x-amz-copy-source");
                        if (copy != null) {
                            String src = URLDecoder.decode(copy.replace("+", "%2B"), StandardCharsets.UTF_8);
                            if (!src.startsWith("/" + BUCKET + "/")) {
                                error(ex, 404, "NoSuchBucket", "bad copy source " + src);
                                return;
                            }
                            byte[] v = objects.get(src.substring(BUCKET.length() + 2));
                            if (v == null) {
                                error(ex, 404, "NoSuchKey", "copy source missing: " + src);
                                return;
                            }
                            objects.put(key, v.clone());
                            send(ex, 200, "application/xml", ("<CopyObjectResult><ETag>\"x\"</ETag></CopyObjectResult>").getBytes());
                            return;
                        }
                        objects.put(key, body);
                        send(ex, 200, null, new byte[0], "ETag", "\"x\"");
                        return;
                    }
                    case "DELETE":
                        objects.remove(key);
                        send(ex, 204, null, new byte[0]);
                        return;
                    default:
                        error(ex, 405, "MethodNotAllowed", method);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            problems.add("server exception: " + e);
            error(ex, 500, "InternalError", String.valueOf(e));
        }
    }

    private void list(HttpExchange ex, Map<String, String> q) throws IOException {
        if (!"2".equals(q.get("list-type"))) {
            error(ex, 400, "InvalidArgument", "only list-type=2 is supported");
            return;
        }
        String prefix = q.getOrDefault("prefix", "");
        String delimiter = q.get("delimiter");
        List<String[]> items = new ArrayList<>();   // {"c"|"p", name}
        synchronized (objects) {
            String lastPrefix = null;
            for (String k : objects.tailMap(prefix).keySet()) {
                if (!k.startsWith(prefix)) break;
                String rel = k.substring(prefix.length());
                int d = delimiter == null ? -1 : rel.indexOf(delimiter);
                if (d >= 0) {
                    String cp = prefix + rel.substring(0, d + delimiter.length());
                    if (!cp.equals(lastPrefix)) items.add(new String[]{"p", cp});
                    lastPrefix = cp;
                } else {
                    items.add(new String[]{"c", k});
                }
            }
            int start = 0;
            String token = q.get("continuation-token");
            if (token != null) start = Integer.parseInt(token.substring(1));
            int limit = pageSize;
            if (q.containsKey("max-keys")) limit = Math.min(limit, Integer.parseInt(q.get("max-keys")));
            int end = Math.min(items.size(), start + limit);
            boolean truncated = end < items.size();
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>" + BUCKET + "</Name>"
                    + "<Prefix>" + out(prefix) + "</Prefix><KeyCount>" + (end - start) + "</KeyCount><MaxKeys>1000</MaxKeys>");
            if (delimiter != null) xml.append("<Delimiter>").append(delimiter).append("</Delimiter>");
            if (honorEncoding && "url".equals(q.get("encoding-type"))) xml.append("<EncodingType>url</EncodingType>");
            xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
            if (truncated) xml.append("<NextContinuationToken>t").append(end).append("</NextContinuationToken>");
            for (int i = start; i < end; i++) {
                String[] it = items.get(i);
                if (it[0].equals("c")) {
                    xml.append("<Contents><Key>").append(out(it[1])).append("</Key>"
                            + "<LastModified>2024-01-01T10:00:00.000Z</LastModified><ETag>\"x\"</ETag><Size>")
                            .append(objects.get(it[1]).length).append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
                } else {
                    xml.append("<CommonPrefixes><Prefix>").append(out(it[1])).append("</Prefix></CommonPrefixes>");
                }
            }
            xml.append("</ListBucketResult>");
            send(ex, 200, "application/xml", xml.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
