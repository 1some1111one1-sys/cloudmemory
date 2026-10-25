package com.cloudbridge.app;

import com.cloudbridge.app.core.HttpTransport;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** The real network layer. Redirects are handled by the clients themselves (see HttpTransport). */
public final class OkHttpTransport implements HttpTransport {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private static boolean needsBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
                || method.equals("PROPPATCH") || method.equals("REPORT");
    }

    @Override
    public Response execute(Request r) throws IOException {
        MediaType type = r.contentType == null ? null : MediaType.parse(r.contentType);
        RequestBody body = null;
        if (r.bodyFile != null) body = RequestBody.create(r.bodyFile, type);
        else if (r.body != null) body = RequestBody.create(r.body, type);
        else if (needsBody(r.method)) body = RequestBody.create(new byte[0], null);

        okhttp3.Request.Builder builder;
        try {
            builder = new okhttp3.Request.Builder().url(r.url).method(r.method, body);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid address: " + r.url);
        }
        builder.header("User-Agent", "CloudBridge/1.0");
        for (Map.Entry<String, String> h : r.headers.entrySet()) builder.header(h.getKey(), h.getValue());

        okhttp3.Response resp = CLIENT.newCall(builder.build()).execute();
        Map<String, String> headers = new HashMap<>();
        Headers hs = resp.headers();
        for (String name : hs.names()) headers.put(name.toLowerCase(Locale.ROOT), hs.get(name));
        ResponseBody rb = resp.body();
        return new Response(resp.code(), headers, rb == null ? null : rb.byteStream(), resp);
    }
}
