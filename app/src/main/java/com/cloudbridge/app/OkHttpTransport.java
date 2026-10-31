package com.cloudbridge.app;

import com.cloudbridge.app.core.HttpTransport;
import com.cloudbridge.app.core.Doh;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** The real network layer. Redirects are handled by the clients themselves (see HttpTransport). */
public final class OkHttpTransport implements HttpTransport {
    /** Asks a public DNS-over-HTTPS server (reached by its address, so it needs no DNS itself). */
    private static final OkHttpClient DOH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build();
    private static final Map<String, Object[]> DOH_CACHE = new HashMap<>();

    /** The phone's own DNS first; when it does not know the name, a DNS-over-HTTPS server is asked. */
    private static final Dns DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String host) throws UnknownHostException {
            try {
                return Dns.SYSTEM.lookup(host);
            } catch (UnknownHostException first) {
                List<InetAddress> found = viaDoh(host);
                if (found.isEmpty()) throw first;
                return found;
            }
        }
    };

    private static List<InetAddress> viaDoh(String host) {
        synchronized (DOH_CACHE) {
            Object[] hit = DOH_CACHE.get(host);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 5 * 60 * 1000L) {
                @SuppressWarnings("unchecked")
                List<InetAddress> cached = (List<InetAddress>) hit[1];
                return cached;
            }
        }
        String[] servers = {"https://1.1.1.1/dns-query?name=", "https://8.8.8.8/resolve?name="};
        for (String server : servers) {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder().url(server + host + "&type=A")
                        .header("accept", "application/dns-json").build();
                try (okhttp3.Response r = DOH_CLIENT.newCall(req).execute()) {
                    ResponseBody b = r.body();
                    if (!r.isSuccessful() || b == null) continue;
                    List<InetAddress> out = new ArrayList<>();
                    for (String ip : Doh.parseAddresses(b.string())) {
                        // The host name is kept, so the certificate is still checked against it.
                        out.add(InetAddress.getByAddress(host, InetAddress.getByName(ip).getAddress()));
                    }
                    if (!out.isEmpty()) {
                        synchronized (DOH_CACHE) {
                            DOH_CACHE.put(host, new Object[]{System.currentTimeMillis(), out});
                        }
                        return out;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // try the next server
            }
        }
        return new ArrayList<>();
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dns(DNS)
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
