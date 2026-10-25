package com.cloudbridge.app.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executors;

/** A small WebDAV server over a temp folder, with the quirks real servers have. */
final class MockDavServer {
    final File root;
    final String prefix;           // e.g. "/remote.php/dav/files/me"
    final boolean absoluteHrefs;   // some servers return full URLs in <href>
    private final String expectedAuth;
    private final HttpServer server;
    int requests;

    MockDavServer(File root, String prefix, String user, String pass, boolean absoluteHrefs) throws IOException {
        this.root = root;
        this.prefix = prefix;
        this.absoluteHrefs = absoluteHrefs;
        this.expectedAuth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String url() {
        return "http://127.0.0.1:" + port() + prefix;
    }

    void stop() {
        server.stop(0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    private void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        if (type != null) ex.getResponseHeaders().set("Content-Type", type);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(code, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        ex.close();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            requests++;
            byte[] reqBody = readAll(ex.getRequestBody());
            if (!expectedAuth.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"mock\"");
                send(ex, 401, "text/plain", "Unauthorized".getBytes());
                return;
            }
            String rawPath = ex.getRequestURI().getRawPath();
            String path = ex.getRequestURI().getPath();   // decoded by the JDK
            if (!path.startsWith(prefix)) {
                send(ex, 404, "text/plain", "no".getBytes());
                return;
            }
            String rel = path.substring(prefix.length());
            File f = new File(root, rel);
            switch (ex.getRequestMethod()) {
                case "PROPFIND": {
                    if (!f.exists()) {
                        send(ex, 404, "text/plain", "missing".getBytes());
                        return;
                    }
                    if (f.isDirectory() && !rawPath.endsWith("/")) {   // like Apache: redirect to the slash form
                        ex.getResponseHeaders().set("Location", "http://127.0.0.1:" + port() + rawPath + "/");
                        send(ex, 301, "text/html", "moved".getBytes());
                        return;
                    }
                    boolean deep = "1".equals(ex.getRequestHeaders().getFirst("Depth")) && f.isDirectory();
                    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">");
                    xml.append(entry(rel, f));
                    if (deep) {
                        File[] kids = f.listFiles();
                        Arrays.sort(kids);
                        for (File k : kids) xml.append(entry((rel.endsWith("/") ? rel : rel + "/") + k.getName(), k));
                    }
                    xml.append("</D:multistatus>");
                    send(ex, 207, "application/xml; charset=utf-8", xml.toString().getBytes(StandardCharsets.UTF_8));
                    return;
                }
                case "GET":
                case "HEAD": {
                    if (!f.exists()) send(ex, 404, "text/plain", "missing".getBytes());
                    else if (f.isDirectory()) send(ex, 200, "text/html", "<html>listing</html>".getBytes());
                    else send(ex, 200, "application/octet-stream", Files.readAllBytes(f.toPath()));
                    return;
                }
                case "PUT": {
                    if (f.isDirectory()) {
                        send(ex, 405, "text/plain", "is a dir".getBytes());
                        return;
                    }
                    if (!f.getParentFile().isDirectory()) {
                        send(ex, 409, "text/plain", "no parent".getBytes());
                        return;
                    }
                    boolean existed = f.exists();
                    Files.write(f.toPath(), reqBody);
                    send(ex, existed ? 204 : 201, null, new byte[0]);
                    return;
                }
                case "MKCOL": {
                    if (f.exists()) send(ex, 405, "text/plain", "exists".getBytes());
                    else if (!f.getParentFile().isDirectory()) send(ex, 409, "text/plain", "no parent".getBytes());
                    else {
                        f.mkdir();
                        send(ex, 201, null, new byte[0]);
                    }
                    return;
                }
                case "DELETE": {
                    if (!f.exists()) {
                        send(ex, 404, "text/plain", "missing".getBytes());
                        return;
                    }
                    deleteRecursive(f);
                    send(ex, 204, null, new byte[0]);
                    return;
                }
                case "MOVE": {
                    String dest = ex.getRequestHeaders().getFirst("Destination");
                    String destPath = URI.create(dest).getPath();
                    if (!destPath.startsWith(prefix)) {
                        send(ex, 502, "text/plain", "bad destination".getBytes());
                        return;
                    }
                    File target = new File(root, destPath.substring(prefix.length()));
                    if (!f.exists()) {
                        send(ex, 404, "text/plain", "missing".getBytes());
                        return;
                    }
                    if (target.exists() && "F".equals(ex.getRequestHeaders().getFirst("Overwrite"))) {
                        send(ex, 412, "text/plain", "exists".getBytes());
                        return;
                    }
                    Files.move(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    send(ex, 201, null, new byte[0]);
                    return;
                }
                default:
                    send(ex, 405, "text/plain", "no".getBytes());
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            send(ex, 500, "text/plain", String.valueOf(e).getBytes());
        }
    }

    private String entry(String rel, File f) throws IOException {
        String p = prefix + (rel.startsWith("/") || rel.isEmpty() ? rel : "/" + rel);
        if (f.isDirectory() && !p.endsWith("/")) p += "/";
        // URI(null,null,path,null) percent-encodes spaces/non-ASCII but leaves ( ) ! etc. alone, like many servers.
        String href;
        try {
            href = new URI(null, null, p, null).getRawPath();
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (absoluteHrefs) href = "http://127.0.0.1:" + port() + href;
        String modified = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.lastModified()), ZoneOffset.UTC));
        StringBuilder sb = new StringBuilder("<D:response><D:href>").append(href).append("</D:href>");
        sb.append("<D:propstat><D:prop>");
        sb.append(f.isDirectory() ? "<D:resourcetype><D:collection/></D:resourcetype>" : "<D:resourcetype/>");
        sb.append("<D:getlastmodified>").append(modified).append("</D:getlastmodified>");
        if (f.isFile()) sb.append("<D:getcontentlength>").append(f.length()).append("</D:getcontentlength>");
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>");
        if (f.isDirectory()) {   // folders have no length: servers report that in a second propstat
            sb.append("<D:propstat><D:prop><D:getcontentlength/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>");
        }
        return sb.append("</D:response>").toString();
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
