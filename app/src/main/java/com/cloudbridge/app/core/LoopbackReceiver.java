package com.cloudbridge.app.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Listens on 127.0.0.1 for the browser's redirect at the end of the Google sign-in and returns the
 * authorization code. Only this device can reach a loopback address, and the "state" value is checked.
 */
public final class LoopbackReceiver implements Closeable {
    private final ServerSocket server;

    public LoopbackReceiver() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /**
     * Waits until the browser comes back with a code. Other requests (such as /favicon.ico) are ignored.
     *
     * @throws CloudException when the user denied access, the state does not match, or the wait timed out.
     */
    public String awaitCode(String expectedState, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new CloudException(408, "The Google sign-in was not finished in time");
            server.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            Socket s;
            try {
                s = server.accept();
            } catch (SocketTimeoutException e) {
                throw new CloudException(408, "The Google sign-in was not finished in time");
            }
            try {
                s.setSoTimeout(5000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                String line = in.readLine();
                Map<String, String> q = query(line);
                if (q == null || (!q.containsKey("code") && !q.containsKey("error"))) {
                    reply(s, 404, "Not found");
                    continue;
                }
                if (!expectedState.equals(q.get("state"))) {
                    reply(s, 400, "This sign-in does not belong to CloudBridge. You can close this page.");
                    continue;
                }
                if (q.containsKey("error")) {
                    reply(s, 200, "Sign-in cancelled. You can close this page and go back to CloudBridge.");
                    throw new CloudException(403, "Google sign-in was cancelled or denied (" + q.get("error") + ")");
                }
                reply(s, 200, "Signed in. You can close this page and go back to CloudBridge.");
                return q.get("code");
            } catch (SocketTimeoutException | java.net.SocketException ignored) {
                // A browser that opened a connection and sent nothing: wait for the next one.
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** "GET /?code=a&state=b HTTP/1.1" -> {code=a, state=b}; null for anything else. */
    static Map<String, String> query(String requestLine) {
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[0].equals("GET")) return null;
        int qm = parts[1].indexOf('?');
        if (qm < 0) return null;
        Map<String, String> out = new HashMap<>();
        for (String pair : parts[1].substring(qm + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void reply(Socket s, int status, String message) throws IOException {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>CloudBridge</title></head>"
                + "<body style=\"font-family:sans-serif;padding:32px\"><h2>CloudBridge</h2><p>" + message
                + "</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }
}
