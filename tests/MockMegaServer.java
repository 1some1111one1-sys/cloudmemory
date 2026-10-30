package com.cloudbridge.app.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory imitation of MEGA's API and storage servers (stores only what a real server would see: ciphertext). */
final class MockMegaServer {
    static final String EMAIL = "me@example.com";
    static final String PASSWORD = "pässw0rd!";
    static final String USER = "USERHANDLE1";

    static final class N {
        String h;
        String p;
        int t;
        String a;          // encrypted attributes (b64)
        String k;          // "USER:<encrypted key b64>" or any other string
        long s = -1;
        long ts;
        byte[] data;       // ciphertext, as uploaded
        String u = USER;
    }

    final Map<String, N> nodes = Collections.synchronizedMap(new LinkedHashMap<>());
    final String rootId = "ROOTNODE";
    final String inboxId = "INBOXNOD";
    final String rubbishId = "RUBBISH1";
    final int[] master = new int[4];
    final int version;
    final int[] pwKey;
    final String uh;
    final byte[] salt = new byte[16];
    final BigInteger rsaN;
    final BigInteger rsaD;
    final BigInteger rsaP;
    final BigInteger rsaQ;
    final String privkB64;
    volatile String sid = "";
    volatile boolean needs2fa;
    volatile String appKeyRequired;      // when set, requests without &ak=<this> answer -22
    volatile String expectedAk;
    volatile int eagain;                 // answer this many API requests with -3
    volatile int uploadQuota;            // answer 'u' with -17 while > 0
    final AtomicInteger logins = new AtomicInteger();
    final AtomicInteger apiCalls = new AtomicInteger();
    final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    final List<long[]> chunkPosts = Collections.synchronizedList(new ArrayList<>());   // {offset, length}
    final List<String> akSeen = Collections.synchronizedList(new ArrayList<>());
    private final SecureRandom rnd = new SecureRandom();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, long[]> uploads = new HashMap<>();       // upload id -> {declared size, received}
    private final Map<String, ByteArrayOutputStream> uploadData = new HashMap<>();
    private final Map<String, String> uploadHandle = new HashMap<>();   // completion handle -> upload id
    private final HttpServer server;

    MockMegaServer(int version) throws IOException {
        this.version = version;
        for (int i = 0; i < 4; i++) master[i] = rnd.nextInt();
        rnd.nextBytes(salt);
        byte[] pw = PASSWORD.getBytes(StandardCharsets.UTF_8);
        if (version >= 2) {
            byte[] derived = MegaCrypto.pbkdf2Sha512(pw, salt, 100_000, 32);
            pwKey = MegaCrypto.strToA32(Arrays.copyOfRange(derived, 0, 16));
            uh = MegaCrypto.b64(Arrays.copyOfRange(derived, 16, 32));
        } else {
            pwKey = MegaCrypto.prepareKey(pw);
            uh = MegaCrypto.stringHash(EMAIL, pwKey);
        }
        // An RSA key with e = 257 as MEGA uses.
        BigInteger e = BigInteger.valueOf(257);
        BigInteger p, q, phi;
        do {
            p = BigInteger.probablePrime(512, rnd);
            q = BigInteger.probablePrime(512, rnd);
            phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
        } while (!phi.gcd(e).equals(BigInteger.ONE) || p.equals(q));
        rsaP = p;
        rsaQ = q;
        rsaN = p.multiply(q);
        rsaD = e.modInverse(phi);
        BigInteger u = q.modInverse(p);
        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        for (BigInteger v : new BigInteger[]{p, q, rsaD, u}) priv.write(MegaCrypto.writeMpi(v));
        byte[] pb = priv.toByteArray();
        pb = Arrays.copyOf(pb, (pb.length + 15) / 16 * 16);
        privkB64 = MegaCrypto.a32ToB64(MegaCrypto.encryptKey(MegaCrypto.strToA32(pb), master));

        add(rootId, null, 2, "", null);
        add(inboxId, null, 3, "", null);
        add(rubbishId, null, 4, "", null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ seeding helpers

    private N add(String h, String parent, int t, String attr, String k) {
        N n = new N();
        n.h = h;
        n.p = parent;
        n.t = t;
        n.a = attr;
        n.k = k;
        n.ts = seq.getAndIncrement();
        nodes.put(h, n);
        return n;
    }

    String newHandle() {
        byte[] b = new byte[6];
        rnd.nextBytes(b);
        return MegaCrypto.b64(b);
    }

    N folder(String parent, String name) {
        int[] key = new int[4];
        for (int i = 0; i < 4; i++) key[i] = rnd.nextInt();
        return add(newHandle(), parent, 1, MegaCrypto.encryptAttrs(name, key),
                USER + ":" + MegaCrypto.a32ToB64(MegaCrypto.encryptKey(key, master)));
    }

    /** A file with real encryption and a real MAC, as another MEGA client would have uploaded it. */
    N file(String parent, String name, byte[] content) {
        int[] ul = new int[6];
        for (int i = 0; i < 6; i++) ul[i] = rnd.nextInt();
        int[] aes = Arrays.copyOf(ul, 4);
        int[] nonce = {ul[4], ul[5]};
        MegaCrypto.FileMac mac = new MegaCrypto.FileMac(aes, nonce);
        javax.crypto.Cipher ctr = MegaCrypto.ctr(aes, nonce);
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        long pos = 0;
        long chunk = 0x20000;
        while (pos < content.length) {
            int len = (int) Math.min(chunk, content.length - pos);
            byte[] piece = Arrays.copyOfRange(content, (int) pos, (int) pos + len);
            mac.chunk(piece, len);
            enc.write(ctr.update(piece), 0, len);
            pos += len;
            chunk = MegaCrypto.nextChunkSize(chunk);
        }
        int[] meta = mac.meta();
        int[] key = {ul[0] ^ ul[4], ul[1] ^ ul[5], ul[2] ^ meta[0], ul[3] ^ meta[1], ul[4], ul[5], meta[0], meta[1]};
        N n = add(newHandle(), parent, 0, MegaCrypto.encryptAttrs(name, aes),
                USER + ":" + MegaCrypto.a32ToB64(MegaCrypto.encryptKey(key, master)));
        n.s = content.length;
        n.data = enc.toByteArray();
        return n;
    }

    N byName(String parent, String name) {
        synchronized (nodes) {
            for (N n : nodes.values()) {
                if (parent.equals(n.p) && name.equals(nameOf(n))) return n;
            }
        }
        return null;
    }

    /** Decrypts a node's name the way a client would (for assertions). */
    String nameOf(N n) {
        int[] key = nodeKeys(n)[0];
        return MegaCrypto.decryptName(n.a, key);
    }

    /** {aes key, full key} of one of our nodes. */
    int[][] nodeKeys(N n) {
        int[] raw = MegaCrypto.decryptKey(MegaCrypto.b64ToA32(n.k.substring(n.k.indexOf(':') + 1)), master);
        if (n.t == 0) return new int[][]{{raw[0] ^ raw[4], raw[1] ^ raw[5], raw[2] ^ raw[6], raw[3] ^ raw[7]}, raw};
        return new int[][]{Arrays.copyOf(raw, 4), raw};
    }

    /** Decrypts stored ciphertext and checks the MAC stored in the key, independently of the client under test. */
    byte[] plaintextChecked(N n) {
        int[][] keys = nodeKeys(n);
        int[] aes = keys[0];
        int[] nonce = {keys[1][4], keys[1][5]};
        MegaCrypto.FileMac mac = new MegaCrypto.FileMac(aes, nonce);
        javax.crypto.Cipher ctr = MegaCrypto.ctr(aes, nonce);
        byte[] plain = n.data.length == 0 ? new byte[0] : ctr.update(n.data);
        long pos = 0;
        long chunk = 0x20000;
        while (pos < plain.length) {
            int len = (int) Math.min(chunk, plain.length - pos);
            mac.chunk(Arrays.copyOfRange(plain, (int) pos, (int) pos + len), len);
            pos += len;
            chunk = MegaCrypto.nextChunkSize(chunk);
        }
        if (plain.length > 0 && !Arrays.equals(mac.meta(), new int[]{keys[1][6], keys[1][7]})) return null;
        return plain;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            byte[] body = readAll(ex.getRequestBody());
            if (path.equals("/cs")) {
                cs(ex, new String(body, StandardCharsets.UTF_8));
            } else if (path.startsWith("/dl/")) {
                N n = nodes.get(path.substring(4));
                if (n == null || n.data == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.sendResponseHeaders(200, n.data.length == 0 ? -1 : n.data.length);
                    if (n.data.length > 0) {
                        try (OutputStream o = ex.getResponseBody()) {
                            o.write(n.data);
                        }
                    }
                }
            } else if (path.startsWith("/ul/")) {
                upload(ex, path.substring(4), body);
            } else {
                ex.sendResponseHeaders(404, -1);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    private void reply(HttpExchange ex, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private void cs(HttpExchange ex, String text) throws IOException {
        apiCalls.incrementAndGet();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        if (q.containsKey("ak")) akSeen.add(q.get("ak"));
        Object parsed = Json.parse(text);
        @SuppressWarnings("unchecked")
        Map<String, Object> cmd = (Map<String, Object>) ((List<?>) parsed).get(0);
        String a = Json.str(cmd, "a");
        commands.add(a);
        if (appKeyRequired != null && !appKeyRequired.equals(q.get("ak"))) {
            reply(ex, "[-22]");
            return;
        }
        if (eagain > 0) {
            eagain--;
            reply(ex, "[-3]");
            return;
        }
        if (a.equals("us0")) {
            reply(ex, version >= 2 ? "[{\"v\":2,\"s\":" + Json.quote(MegaCrypto.b64(salt)) + "}]" : "[{\"v\":1}]");
            return;
        }
        if (a.equals("us")) {
            login(ex, cmd);
            return;
        }
        if (sid.isEmpty() || !sid.equals(q.get("sid"))) {
            reply(ex, "[-15]");
            return;
        }
        switch (a) {
            case "f":
                reply(ex, "[" + tree() + "]");
                break;
            case "g":
                getUrl(ex, cmd);
                break;
            case "uq":
                reply(ex, "[{\"cstrg\":2147483648,\"mstrg\":21474836480}]");
                break;
            case "u":
                if (uploadQuota > 0) {
                    uploadQuota--;
                    reply(ex, "[-17]");
                    break;
                }
                startUpload(ex, cmd);
                break;
            case "p":
                putNodes(ex, cmd);
                break;
            case "a":
                rename(ex, cmd);
                break;
            case "m":
                move(ex, cmd);
                break;
            case "d":
                delete(ex, cmd);
                break;
            default:
                reply(ex, "[-2]");
        }
    }

    private void login(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        logins.incrementAndGet();
        if (!EMAIL.equals(Json.str(cmd, "user")) || !uh.equals(Json.str(cmd, "uh"))) {
            reply(ex, "[-9]");
            return;
        }
        if (needs2fa && !"123456".equals(Json.str(cmd, "mfa"))) {
            reply(ex, "[-26]");
            return;
        }
        byte[] sidBytes = new byte[43];
        rnd.nextBytes(sidBytes);
        sidBytes[0] |= 0x40;                                        // keep the top byte non-zero
        sid = MegaCrypto.b64(sidBytes);
        byte[] plain = new byte[43 + 8];
        System.arraycopy(sidBytes, 0, plain, 0, 43);
        byte[] pad = new byte[8];
        rnd.nextBytes(pad);
        System.arraycopy(pad, 0, plain, 43, 8);
        BigInteger enc = new BigInteger(1, plain).modPow(BigInteger.valueOf(257), rsaN);
        reply(ex, "[{\"k\":" + Json.quote(MegaCrypto.a32ToB64(MegaCrypto.encryptKey(master, pwKey)))
                + ",\"privk\":" + Json.quote(privkB64)
                + ",\"csid\":" + Json.quote(MegaCrypto.b64(MegaCrypto.writeMpi(enc)))
                + ",\"u\":" + Json.quote(USER) + "}]");
    }

    private String nodeJson(N n) {
        StringBuilder sb = new StringBuilder("{\"h\":").append(Json.quote(n.h));
        if (n.p != null) sb.append(",\"p\":").append(Json.quote(n.p));
        sb.append(",\"u\":").append(Json.quote(n.u)).append(",\"t\":").append(n.t);
        if (n.a != null) sb.append(",\"a\":").append(Json.quote(n.a));
        if (n.k != null) sb.append(",\"k\":").append(Json.quote(n.k));
        if (n.t == 0) sb.append(",\"s\":").append(n.s);
        sb.append(",\"ts\":").append(n.ts);
        return sb.append('}').toString();
    }

    private String tree() {
        StringBuilder sb = new StringBuilder("{\"f\":[");
        boolean first = true;
        synchronized (nodes) {
            for (N n : nodes.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(nodeJson(n));
            }
        }
        return sb.append("]}").toString();
    }

    private void getUrl(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        N n = nodes.get(Json.str(cmd, "n"));
        if (n == null || n.t != 0) {
            reply(ex, "[-9]");
            return;
        }
        reply(ex, "[{\"g\":" + Json.quote(base() + "/dl/" + n.h) + ",\"s\":" + n.s + "}]");
    }

    private void startUpload(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        String id = "U" + seq.getAndIncrement();
        synchronized (uploads) {
            uploads.put(id, new long[]{Json.num(cmd, "s", -1), 0});
            uploadData.put(id, new ByteArrayOutputStream());
        }
        reply(ex, "[{\"p\":" + Json.quote(base() + "/ul/" + id) + "}]");
    }

    private void upload(HttpExchange ex, String rest, byte[] body) throws IOException {
        int slash = rest.indexOf('/');
        String id = rest.substring(0, slash);
        long offset = Long.parseLong(rest.substring(slash + 1));
        String answer = "";
        synchronized (uploads) {
            long[] st = uploads.get(id);
            if (st == null || st[1] != offset) {
                answer = "-9";
            } else {
                chunkPosts.add(new long[]{offset, body.length});
                st[1] += body.length;
                uploadData.get(id).write(body, 0, body.length);
                if (st[1] >= st[0]) {
                    String handle = newHandle() + newHandle() + newHandle();
                    uploadHandle.put(handle, id);
                    answer = handle;
                }
            }
        }
        byte[] b = answer.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        }
    }

    private void putNodes(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        String parent = Json.str(cmd, "t");
        N pn = nodes.get(parent);
        if (pn == null || pn.t == 0) {
            reply(ex, "[-9]");
            return;
        }
        StringBuilder out = new StringBuilder("{\"f\":[");
        boolean first = true;
        for (Object o : Json.list(cmd, "n")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            int t = (int) Json.num(m, "t", 0);
            N n;
            if (t == 0) {
                String id = uploadHandle.remove(Json.str(m, "h"));
                if (id == null) {
                    reply(ex, "[-9]");
                    return;
                }
                n = add(newHandle(), parent, 0, Json.str(m, "a"), USER + ":" + Json.str(m, "k"));
                synchronized (uploads) {
                    n.data = uploadData.remove(id).toByteArray();
                    n.s = n.data.length;
                }
            } else {
                n = add(newHandle(), parent, 1, Json.str(m, "a"), USER + ":" + Json.str(m, "k"));
            }
            if (!first) out.append(',');
            first = false;
            out.append(nodeJson(n));
        }
        reply(ex, "[" + out + "]}]");
    }

    private void rename(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        N n = nodes.get(Json.str(cmd, "n"));
        if (n == null) {
            reply(ex, "[-9]");
            return;
        }
        String key = n.k.substring(n.k.indexOf(':') + 1);
        if (!key.equals(Json.str(cmd, "key"))) {          // a rename must carry the node's own key unchanged
            reply(ex, "[-14]");
            return;
        }
        n.a = Json.str(cmd, "attr");
        reply(ex, "[0]");
    }

    private void move(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        N n = nodes.get(Json.str(cmd, "n"));
        N to = nodes.get(Json.str(cmd, "t"));
        if (n == null || to == null || to.t == 0) {
            reply(ex, "[-9]");
            return;
        }
        n.p = to.h;
        reply(ex, "[0]");
    }

    private void delete(HttpExchange ex, Map<String, Object> cmd) throws IOException {
        N n = nodes.remove(Json.str(cmd, "n"));
        reply(ex, n == null ? "[-9]" : "[0]");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }
}
