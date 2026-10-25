package com.cloudbridge.app.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Test-only transport on top of java.net.http (supports PROPFIND/MKCOL/MOVE, never follows redirects). */
final class JdkTransport implements HttpTransport {
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Response execute(Request r) throws IOException {
        HttpRequest.BodyPublisher body;
        if (r.bodyFile != null) body = HttpRequest.BodyPublishers.ofFile(r.bodyFile.toPath());
        else if (r.body != null) body = HttpRequest.BodyPublishers.ofByteArray(r.body);
        else body = HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(r.url)).timeout(Duration.ofSeconds(30)).method(r.method, body);
        for (Map.Entry<String, String> h : r.headers.entrySet()) b.header(h.getKey(), h.getValue());
        if (r.contentType != null) b.header("Content-Type", r.contentType);
        try {
            HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : resp.headers().map().entrySet()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", e.getValue()));
            }
            return new Response(resp.statusCode(), headers, resp.body(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
