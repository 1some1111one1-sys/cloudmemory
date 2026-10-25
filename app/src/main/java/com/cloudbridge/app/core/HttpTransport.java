package com.cloudbridge.app.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP abstraction so the protocol code has no Android/OkHttp dependency.
 * Implementations must NOT follow redirects (the clients do that themselves, so credentials
 * are never sent to another host) and must not throw for non-2xx statuses.
 */
public interface HttpTransport {
    Response execute(Request request) throws IOException;

    final class Request {
        public final String method;
        public final String url;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body;
        public File bodyFile;
        public String contentType;

        public Request(String method, String url) {
            this.method = method;
            this.url = url;
        }

        public Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Request body(byte[] bytes, String contentType) {
            this.body = bytes;
            this.contentType = contentType;
            return this;
        }

        public Request bodyFile(File file, String contentType) {
            this.bodyFile = file;
            this.contentType = contentType;
            return this;
        }
    }

    final class Response implements Closeable {
        public final int code;
        /** Header names are lower-case. */
        public final Map<String, String> headers;
        private final InputStream stream;
        private final Closeable onClose;

        public Response(int code, Map<String, String> headers, InputStream stream, Closeable onClose) {
            this.code = code;
            this.headers = headers;
            this.stream = stream == null ? new ByteArrayInputStream(new byte[0]) : stream;
            this.onClose = onClose;
        }

        public Response(int code, Map<String, String> headers, byte[] body) {
            this(code, headers, new ByteArrayInputStream(body), null);
        }

        public InputStream stream() {
            return stream;
        }

        public String header(String lowerCaseName) {
            return headers.get(lowerCaseName);
        }

        /** Reads the whole body, failing if it is larger than maxBytes. */
        public byte[] readAll(int maxBytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) {
                if (out.size() + n > maxBytes) throw new IOException("Response too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }

        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            if (onClose != null) {
                try {
                    onClose.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
