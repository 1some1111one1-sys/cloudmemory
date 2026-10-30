package com.cloudbridge.app.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;

/**
 * MEGA as a path-based {@link CloudClient}, speaking MEGA's own API directly (MEGA has no WebDAV or public
 * file API). Everything is encrypted on this device: names, keys and file contents. The whole file tree is
 * downloaded once and kept in memory for a minute.
 *
 * <p>Like Drive, MEGA allows several files with one name in a folder; the oldest keeps the name and the others
 * show as "name (2)", "name (3)". Deleting moves to the MEGA Rubbish Bin.
 */
public final class MegaClient implements CloudClient {
    public static final String API = "https://g.api.mega.co.nz";

    private static final char SLASH = '∕';
    private static final Pattern DUPLICATE = Pattern.compile("^(.*) \\((\\d+)\\)(\\.[^./ ]+)?$");
    private static final long TREE_TTL_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong SEQ = new AtomicLong(RANDOM.nextInt(1_000_000));

    /** Delay before the first retry of a "try again" answer; doubles each time. Tests shorten it. */
    static volatile long retryBaseMs = 500;

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private final HttpTransport http;
    private final String api;
    private final String email;
    private final String password;
    private final String appKey;
    private final String key;

    /**
     * @param sessionBlob what {@link #signIn} returned, or empty to sign in with the password when needed.
     */
    public MegaClient(HttpTransport http, String email, String password, String appKey, String sessionBlob) {
        this(http, API, email, password, appKey, sessionBlob);
    }

    MegaClient(HttpTransport http, String api, String email, String password, String appKey, String sessionBlob) {
        if (email == null || email.trim().isEmpty() || !email.contains("@")) {
            throw new IllegalArgumentException("Enter your MEGA e-mail address.");
        }
        boolean hasBlob = sessionBlob != null && !sessionBlob.trim().isEmpty();
        if (!hasBlob && (password == null || password.isEmpty())) throw new IllegalArgumentException("Enter your MEGA password.");
        this.http = http;
        this.api = api;
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.appKey = appKey == null ? "" : appKey.trim();
        this.key = api + "|" + this.email;
        if (hasBlob) {
            Session s = Session.parse(sessionBlob);
            if (s != null) SESSIONS.putIfAbsent(key, s);
        }
    }

    /** Forget sessions and cached file trees (after the user edits or removes an account). */
    public static void clearCaches() {
        SESSIONS.clear();
    }

    /**
     * Signs in (with the two-factor code if the account has one) and returns a session string that can be
     * stored (encrypted) so that later requests do not need the password or a new code.
     */
    public static String signIn(HttpTransport http, String email, String password, String twoFactorCode, String appKey)
            throws IOException {
        return signIn(http, API, email, password, twoFactorCode, appKey);
    }

    static String signIn(HttpTransport http, String api, String email, String password, String twoFactorCode,
                         String appKey) throws IOException {
        MegaClient c = new MegaClient(http, api, email, password, appKey, "");
        Session s = c.login(twoFactorCode == null ? "" : twoFactorCode.trim());
        SESSIONS.put(c.key, s);
        return s.blob();
    }

    // ---------------------------------------------------------------- state

    private static final class Node {
        String handle;
        String parent;
        int type;               // 0 file, 1 folder, 2 cloud drive (root), 3 inbox, 4 rubbish bin
        String name = "";
        long size = -1;
        long ts;
        int[] key;              // the node key (8 words for files, 4 for folders)
        int[] aes;              // 4-word key for names and (files) contents
        int[] nonce;
        int[] meta;

        boolean dir() {
            return type != 0;
        }

        String display() {
            return name.replace('/', SLASH);
        }
    }

    private static final class Session {
        String sid;
        int[] master;
        String userHandle;
        // file tree
        Map<String, Node> nodes;
        Map<String, List<Node>> byParent;
        String rootId;
        String rubbishId;
        long fetchedAt;

        String blob() {
            return sid + "|" + MegaCrypto.a32ToB64(master) + "|" + (userHandle == null ? "" : userHandle);
        }

        static Session parse(String blob) {
            try {
                String[] p = blob.trim().split("\\|", -1);
                if (p.length < 2 || p[0].isEmpty()) return null;
                Session s = new Session();
                s.sid = p[0];
                s.master = MegaCrypto.b64ToA32(p[1]);
                if (s.master.length != 4) return null;
                s.userHandle = p.length > 2 && !p[2].isEmpty() ? p[2] : null;
                return s;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- CloudClient

    @Override
    public List<CloudEntry> list(String dirPath) throws IOException {
        String dir = Paths.normalize(dirPath);
        Session s = tree();
        synchronized (s) {
            Node folder = resolve(s, dir);
            if (!folder.dir()) throw new CloudException(404, "\"" + Paths.name(dir) + "\" is not a folder");
            List<CloudEntry> out = new ArrayList<>();
            for (Map.Entry<String, List<Node>> e : groups(s, folder.handle).entrySet()) {
                List<Node> g = e.getValue();
                for (int i = 0; i < g.size(); i++) {
                    Node n = g.get(i);
                    String name = i == 0 ? e.getKey() : duplicateName(e.getKey(), i + 1);
                    out.add(new CloudEntry(Paths.join(dir, name), n.dir(), n.dir() ? -1 : n.size, n.ts * 1000L));
                }
            }
            return out;
        }
    }

    @Override
    public CloudEntry stat(String path) throws IOException {
        String p = Paths.normalize(path);
        Session s = tree();
        synchronized (s) {
            Node n = resolve(s, p);
            return new CloudEntry(p, n.dir(), n.dir() ? -1 : n.size, n.ts * 1000L);
        }
    }

    @Override
    public void download(String path, File dest) throws IOException {
        Session s = tree();
        Node n;
        synchronized (s) {
            n = resolve(s, Paths.normalize(path));
        }
        if (n.dir()) throw new CloudException(400, "\"" + n.display() + "\" is a folder");
        File part = new File(dest.getPath() + ".part");
        if (n.size == 0) {
            new FileOutputStream(part).close();     // an empty file
            finish(part, dest);
            return;
        }
        Object g = api("{\"a\":\"g\",\"g\":1,\"ssl\":1,\"n\":" + Json.quote(n.handle) + "}", true, "Downloading");
        String url = g instanceof Map ? Json.str(asMap(g), "g") : null;
        if (url == null) throw new CloudException(502, "MEGA did not give a download address for \"" + n.display() + "\"");
        HttpTransport.Response r = fetch(url);
        try (InputStream in = r.stream(); OutputStream out = new FileOutputStream(part)) {
            Cipher ctr = MegaCrypto.ctr(n.aes, n.nonce);
            MegaCrypto.FileMac mac = new MegaCrypto.FileMac(n.aes, n.nonce);
            byte[] buf = new byte[1024 * 1024];
            long pos = 0;
            long chunk = 0x20000;
            while (pos < n.size) {
                int len = (int) Math.min(chunk, n.size - pos);
                readFully(in, buf, len);
                byte[] plain = ctr.update(buf, 0, len);
                mac.chunk(plain, len);
                out.write(plain, 0, len);
                pos += len;
                chunk = MegaCrypto.nextChunkSize(chunk);
            }
            if (!Arrays.equals(mac.meta(), n.meta)) {
                throw new CloudException(502, "\"" + n.display() + "\" arrived damaged from MEGA (its checksum did not match)");
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            r.close();
        }
        finish(part, dest);
    }

    @Override
    public void upload(String path, File src, String mime) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot write to the root");
        Session s = tree();
        Node parent;
        Node existing = null;
        synchronized (s) {
            parent = resolve(s, Paths.parent(p));
            if (!parent.dir()) throw new CloudException(409, "The destination is not a folder");
            try {
                existing = resolve(s, p);
            } catch (CloudException e) {
                if (!e.isNotFound()) throw e;
            }
        }
        String name = Paths.name(p);
        if (existing != null && existing.dir()) throw new CloudException(409, "A folder named \"" + name + "\" already exists");
        long len = src.length();

        Object u = api("{\"a\":\"u\",\"s\":" + len + ",\"ssl\":1}", true, "Starting the upload of \"" + name + "\"");
        String url = u instanceof Map ? Json.str(asMap(u), "p") : null;
        if (url == null) throw new CloudException(502, "MEGA did not accept the upload of \"" + name + "\"");

        int[] ul = new int[6];
        for (int i = 0; i < 6; i++) ul[i] = RANDOM.nextInt();
        int[] aes = Arrays.copyOf(ul, 4);
        int[] nonce = {ul[4], ul[5]};
        Cipher ctr = MegaCrypto.ctr(aes, nonce);
        MegaCrypto.FileMac mac = new MegaCrypto.FileMac(aes, nonce);
        String handle = null;
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[1024 * 1024];
            long pos = 0;
            long chunk = 0x20000;
            do {
                int n = (int) Math.min(chunk, len - pos);
                readFully(in, buf, n);
                mac.chunk(buf, n);
                byte[] enc = n == 0 ? new byte[0] : ctr.update(buf, 0, n);
                String reply = postChunk(url + "/" + pos, enc);
                if (!reply.isEmpty()) handle = reply;
                pos += n;
                chunk = MegaCrypto.nextChunkSize(chunk);
            } while (pos < len);
        }
        if (handle == null) throw new CloudException(502, "MEGA did not confirm the upload of \"" + name + "\"");

        int[] meta = mac.meta();
        int[] fileKey = {ul[0] ^ ul[4], ul[1] ^ ul[5], ul[2] ^ meta[0], ul[3] ^ meta[1], ul[4], ul[5], meta[0], meta[1]};
        synchronized (s) {
            String cmd = "{\"a\":\"p\",\"t\":" + Json.quote(parent.handle) + ",\"n\":[{\"h\":" + Json.quote(handle)
                    + ",\"t\":0,\"a\":" + Json.quote(MegaCrypto.encryptAttrs(name, aes))
                    + ",\"k\":" + Json.quote(MegaCrypto.a32ToB64(MegaCrypto.encryptKey(fileKey, s.master))) + "}]}";
            Node created = firstNode(s, api(cmd, true, "Saving \"" + name + "\""));
            if (created == null) throw new CloudException(502, "MEGA did not accept the file \"" + name + "\"");
            if (existing != null) {
                // The old copy goes to the Rubbish Bin (or is erased when it was only an empty placeholder).
                discard(s, existing, existing.size == 0);
            }
            addNode(s, created);
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return;
        Session s = tree();
        synchronized (s) {
            Node parent = resolve(s, Paths.parent(p));
            if (!parent.dir()) throw new CloudException(409, "The destination is not a folder");
            String name = Paths.name(p);
            if (child(s, parent.handle, name) != null) throw new CloudException(405, "\"" + name + "\" already exists");
            int[] folderKey = new int[4];
            for (int i = 0; i < 4; i++) folderKey[i] = RANDOM.nextInt();
            String cmd = "{\"a\":\"p\",\"t\":" + Json.quote(parent.handle) + ",\"n\":[{\"h\":\"xxxxxxxx\",\"t\":1,\"a\":"
                    + Json.quote(MegaCrypto.encryptAttrs(name, folderKey)) + ",\"k\":"
                    + Json.quote(MegaCrypto.a32ToB64(MegaCrypto.encryptKey(folderKey, s.master))) + "}]}";
            Node created = firstNode(s, api(cmd, true, "Creating the folder \"" + name + "\""));
            if (created == null) throw new CloudException(502, "MEGA did not create the folder \"" + name + "\"");
            addNode(s, created);
        }
    }

    @Override
    public void delete(String path, boolean isDir) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot delete the root");
        Session s = tree();
        synchronized (s) {
            discard(s, resolve(s, p), false);
        }
    }

    @Override
    public void move(String from, String to, boolean isDir) throws IOException {
        String src = Paths.normalize(from);
        String dst = Paths.normalize(to);
        if (src.equals("/") || dst.equals("/")) throw new CloudException(400, "Cannot move the root");
        if (src.equals(dst)) return;
        Session s = tree();
        synchronized (s) {
            Node n = resolve(s, src);
            Node newParent = resolve(s, Paths.parent(dst));
            if (!newParent.dir()) throw new CloudException(409, "The destination is not a folder");
            if (n.dir() && Paths.isUnder(src, dst)) throw new CloudException(400, "A folder cannot be moved into itself");
            String newName = Paths.name(dst);
            if (child(s, newParent.handle, newName) != null) {
                throw new CloudException(412, "\"" + newName + "\" already exists in the destination");
            }
            if (!newName.equals(n.display())) {
                String raw = newName.replace(SLASH, '/');
                String cmd = "{\"a\":\"a\",\"n\":" + Json.quote(n.handle) + ",\"attr\":"
                        + Json.quote(MegaCrypto.encryptAttrs(raw, n.aes)) + ",\"key\":"
                        + Json.quote(MegaCrypto.a32ToB64(MegaCrypto.encryptKey(n.key, s.master))) + ",\"i\":" + Json.quote(requestId()) + "}";
                api(cmd, true, "Renaming \"" + n.display() + "\"");
                n.name = raw;
            }
            if (!newParent.handle.equals(n.parent)) {
                api("{\"a\":\"m\",\"n\":" + Json.quote(n.handle) + ",\"t\":" + Json.quote(newParent.handle) + ",\"i\":"
                        + Json.quote(requestId()) + "}", true, "Moving \"" + n.display() + "\"");
                reparent(s, n, newParent.handle);
            }
        }
    }

    // ---------------------------------------------------------------- tree

    private Session tree() throws IOException {
        Session s = session();
        synchronized (s) {
            if (s.nodes == null || System.currentTimeMillis() - s.fetchedAt > TREE_TTL_MS) fetchTree(s);
        }
        return s;
    }

    private void fetchTree(Session s) throws IOException {
        Object r = api("{\"a\":\"f\",\"c\":1}", true, "Reading your MEGA files");
        Map<String, Object> m = asMap(r);
        if (m == null) throw new CloudException(502, "MEGA sent an unexpected file list");
        List<Object> all = Json.list(m, "f");
        // The owner's handle is on the root nodes if the login reply did not carry it.
        if (s.userHandle == null) {
            for (Object o : all) {
                Map<String, Object> n = asMap(o);
                if (n != null && Json.num(n, "t", -1) == 2) s.userHandle = Json.str(n, "u");
            }
        }
        Map<String, Node> nodes = new HashMap<>();
        Map<String, List<Node>> byParent = new HashMap<>();
        String root = null;
        String rubbish = null;
        for (Object o : all) {
            Node n = parseNode(s, asMap(o));
            if (n == null) continue;
            nodes.put(n.handle, n);
            if (n.type == 2) root = n.handle;
            if (n.type == 4) rubbish = n.handle;
        }
        if (root == null) throw new CloudException(502, "MEGA did not report your Cloud Drive");
        for (Node n : nodes.values()) {
            if (n.type >= 2 || n.parent == null) continue;
            if (!nodes.containsKey(n.parent)) continue;      // shared into this account by someone else
            index(byParent, n);
        }
        s.nodes = nodes;
        s.byParent = byParent;
        s.rootId = root;
        s.rubbishId = rubbish;
        s.fetchedAt = System.currentTimeMillis();
    }

    private static void index(Map<String, List<Node>> byParent, Node n) {
        List<Node> l = byParent.get(n.parent);
        if (l == null) {
            l = new ArrayList<>();
            byParent.put(n.parent, l);
        }
        l.add(n);
    }

    /** Null for nodes that are not ours or cannot be decrypted. */
    private Node parseNode(Session s, Map<String, Object> m) {
        if (m == null) return null;
        String h = Json.str(m, "h");
        if (h == null) return null;
        int t = (int) Json.num(m, "t", -1);
        Node n = new Node();
        n.handle = h;
        n.parent = Json.str(m, "p");
        n.type = t;
        n.size = Json.num(m, "s", -1);
        n.ts = Json.num(m, "ts", 0);
        if (t == 2 || t == 3 || t == 4) return n;
        if (t != 0 && t != 1) return null;
        String k = Json.str(m, "k");
        if (k == null || s.userHandle == null) return null;
        try {
            String enc = null;
            for (String part : k.split("/")) {
                int c = part.indexOf(':');
                if (c > 0 && part.substring(0, c).equals(s.userHandle)) {
                    enc = part.substring(c + 1);
                    break;
                }
            }
            if (enc == null) return null;
            int[] raw = MegaCrypto.decryptKey(MegaCrypto.b64ToA32(enc), s.master);
            if (t == 0) {
                if (raw.length < 8) return null;
                n.key = Arrays.copyOf(raw, 8);
                n.aes = new int[]{raw[0] ^ raw[4], raw[1] ^ raw[5], raw[2] ^ raw[6], raw[3] ^ raw[7]};
                n.nonce = new int[]{raw[4], raw[5]};
                n.meta = new int[]{raw[6], raw[7]};
            } else {
                if (raw.length < 4) return null;
                n.key = Arrays.copyOf(raw, 4);
                n.aes = n.key;
            }
            String name = MegaCrypto.decryptName(Json.str(m, "a"), n.aes);
            n.name = name == null ? "(unreadable " + h + ")" : name;
            return n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Node firstNode(Session s, Object reply) {
        Map<String, Object> m = asMap(reply);
        List<Object> f = Json.list(m, "f");
        return f.isEmpty() ? null : parseNode(s, asMap(f.get(0)));
    }

    private static void addNode(Session s, Node n) {
        s.nodes.put(n.handle, n);
        if (n.parent != null) index(s.byParent, n);
    }

    private static void unindex(Session s, Node n) {
        List<Node> l = s.byParent.get(n.parent);
        if (l != null) l.remove(n);
    }

    private static void reparent(Session s, Node n, String newParent) {
        unindex(s, n);
        n.parent = newParent;
        index(s.byParent, n);
    }

    /** Moves a node to the Rubbish Bin, or erases it when there is none (or when asked to). */
    private void discard(Session s, Node n, boolean erase) throws IOException {
        if (!erase && s.rubbishId != null) {
            api("{\"a\":\"m\",\"n\":" + Json.quote(n.handle) + ",\"t\":" + Json.quote(s.rubbishId) + ",\"i\":"
                    + Json.quote(requestId()) + "}", true, "Deleting \"" + n.display() + "\"");
            reparent(s, n, s.rubbishId);
        } else {
            api("{\"a\":\"d\",\"n\":" + Json.quote(n.handle) + ",\"i\":" + Json.quote(requestId()) + "}", true,
                    "Deleting \"" + n.display() + "\"");
            unindex(s, n);
            s.nodes.remove(n.handle);
        }
    }

    // ---- names -> nodes (callers hold the session lock)

    private Node resolve(Session s, String path) throws IOException {
        Node cur = s.nodes.get(s.rootId);
        if (path.equals("/")) return cur;
        for (String seg : path.substring(1).split("/")) {
            if (!cur.dir()) throw new CloudException(404, "Not found: " + path);
            cur = child(s, cur.handle, seg);
            if (cur == null) throw new CloudException(404, "Not found: " + path);
        }
        return cur;
    }

    /** Children grouped by shown name; each group is oldest first. */
    private Map<String, List<Node>> groups(Session s, String parent) {
        Map<String, List<Node>> g = new LinkedHashMap<>();
        List<Node> kids = s.byParent.get(parent);
        if (kids != null) {
            for (Node n : kids) {
                List<Node> l = g.get(n.display());
                if (l == null) {
                    l = new ArrayList<>();
                    g.put(n.display(), l);
                }
                l.add(n);
            }
        }
        Comparator<Node> oldest = (a, b) -> {
            int c = Long.compare(a.ts, b.ts);
            return c != 0 ? c : a.handle.compareTo(b.handle);
        };
        for (List<Node> l : g.values()) Collections.sort(l, oldest);
        return g;
    }

    private Node child(Session s, String parent, String display) {
        Map<String, List<Node>> g = groups(s, parent);
        List<Node> same = g.get(display);
        if (same != null && !same.isEmpty()) return same.get(0);
        Matcher m = DUPLICATE.matcher(display);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2));
            String original = m.group(1) + (m.group(3) == null ? "" : m.group(3));
            List<Node> group = g.get(original);
            if (n >= 2 && group != null && group.size() >= n) return group.get(n - 1);
        }
        return null;
    }

    private static String duplicateName(String display, int n) {
        int dot = display.lastIndexOf('.');
        if (dot > 0 && dot < display.length() - 1) {
            return display.substring(0, dot) + " (" + n + ")" + display.substring(dot);
        }
        return display + " (" + n + ")";
    }

    // ---------------------------------------------------------------- login

    private Session session() throws IOException {
        Session s = SESSIONS.get(key);
        if (s != null) return s;
        synchronized (SESSIONS) {
            s = SESSIONS.get(key);
            if (s != null) return s;
            if (password.isEmpty()) {
                throw new CloudException(401, "The MEGA sign-in expired. Edit this storage and press \"Test & save\" again.");
            }
            s = login("");
            SESSIONS.put(key, s);
            return s;
        }
    }

    private Session login(String twoFactor) throws IOException {
        Object pre = api("{\"a\":\"us0\",\"user\":" + Json.quote(email) + "}", false, "Signing in to MEGA");
        Map<String, Object> pm = asMap(pre);
        long version = Json.num(pm, "v", 1);
        int[] pwKey;
        String uh;
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        if (version >= 2) {
            String salt = Json.str(pm, "s");
            if (salt == null) throw new CloudException(502, "MEGA did not send the account's salt");
            byte[] derived = MegaCrypto.pbkdf2Sha512(pw, MegaCrypto.unb64(salt), 100_000, 32);
            pwKey = MegaCrypto.strToA32(Arrays.copyOfRange(derived, 0, 16));
            uh = MegaCrypto.b64(Arrays.copyOfRange(derived, 16, 32));
        } else {
            pwKey = MegaCrypto.prepareKey(pw);
            uh = MegaCrypto.stringHash(email, pwKey);
        }
        StringBuilder cmd = new StringBuilder("{\"a\":\"us\",\"user\":").append(Json.quote(email))
                .append(",\"uh\":").append(Json.quote(uh));
        if (!twoFactor.isEmpty()) cmd.append(",\"mfa\":").append(Json.quote(twoFactor));
        cmd.append('}');
        Map<String, Object> r = asMap(api(cmd.toString(), false, "Signing in to MEGA"));
        String k = Json.str(r, "k");
        String privk = Json.str(r, "privk");
        String csid = Json.str(r, "csid");
        if (k == null || privk == null || csid == null) {
            throw new CloudException(502, "MEGA's sign-in reply was not understood. This version of CloudBridge may "
                    + "not support your account type yet.");
        }
        try {
            Session s = new Session();
            s.master = MegaCrypto.decryptKey(MegaCrypto.b64ToA32(k), pwKey);
            byte[] priv = MegaCrypto.a32ToBytes(MegaCrypto.decryptKey(MegaCrypto.b64ToA32(privk), s.master));
            s.sid = MegaCrypto.sessionId(csid, priv);
            s.userHandle = Json.str(r, "u");
            return s;
        } catch (RuntimeException e) {
            throw new CloudException(502, "MEGA's sign-in reply could not be decrypted (wrong password?)");
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static String requestId() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        return MegaCrypto.b64(b);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @Override
    public CloudQuota quota() throws IOException {
        Object v = api("{\"a\":\"uq\",\"strg\":1}", true, "Reading storage");
        Map<String, Object> m = asMap(v);
        if (m == null) return null;
        return CloudQuota.ofUsedAndTotal(Json.num(m, "cstrg", -1), Json.num(m, "mstrg", -1));
    }

    /** Sends one API command. Returns the reply (a Map, or a Long for plain success). */
    private Object api(String command, boolean withSession, String what) throws IOException {
        int tries = 0;
        boolean signedInAgain = false;
        while (true) {
            Session s = withSession ? session() : null;
            String url = api + "/cs?id=" + SEQ.incrementAndGet()
                    + (s == null ? "" : "&sid=" + GoogleOAuth.enc(s.sid))
                    + (appKey.isEmpty() ? "" : "&ak=" + GoogleOAuth.enc(appKey));
            HttpTransport.Request req = new HttpTransport.Request("POST", url)
                    .body(("[" + command + "]").getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
            HttpTransport.Response r = http.execute(req);
            String text;
            int status = r.code;
            try {
                text = new String(r.readAll(Http.MAX_XML), StandardCharsets.UTF_8);
            } finally {
                r.close();
            }
            if (status >= 500 && tries < 4) {
                tries++;
                pause(retryBaseMs << (tries - 1));
                continue;
            }
            if (status != 200) throw new CloudException(status, what + " failed: MEGA answered HTTP " + status);
            Object v;
            try {
                v = Json.parse(text);
            } catch (IllegalArgumentException e) {
                throw new CloudException(502, what + " failed: MEGA sent a reply that could not be read");
            }
            Object first = v instanceof List ? (((List<?>) v).isEmpty() ? null : ((List<?>) v).get(0)) : v;
            if (first instanceof Long && (Long) first < 0) {
                int code = (int) (long) (Long) first;
                if ((code == -3 || code == -4) && tries < 6) {
                    tries++;
                    pause(retryBaseMs << (tries - 1));
                    continue;
                }
                if (code == -15 && withSession && !signedInAgain) {
                    SESSIONS.remove(key);
                    signedInAgain = true;
                    continue;
                }
                throw megaError(code, what, !withSession);
            }
            return first;
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted");
        }
    }

    static CloudException megaError(int code, String what, boolean duringLogin) {
        int status = 502;
        String why;
        switch (code) {
            case -2:
                why = "MEGA rejected the request";
                break;
            case -3:
            case -4:
                status = 429;
                why = "MEGA is busy or limiting requests, try again in a minute";
                break;
            case -9:
                if (duringLogin) {
                    status = 401;
                    why = "MEGA does not accept this e-mail and password";
                } else {
                    status = 404;
                    why = "not found";
                }
                break;
            case -11:
                status = 403;
                why = "access denied";
                break;
            case -12:
                status = 412;
                why = "it already exists";
                break;
            case -15:
                status = 401;
                why = "the MEGA sign-in expired, so edit this storage and press \"Test & save\" again";
                break;
            case -16:
                status = 403;
                why = "this MEGA account is blocked";
                break;
            case -17:
            case -24:
                status = 507;
                why = "your MEGA storage or transfer quota is full";
                break;
            case -22:
                status = 401;
                why = "MEGA wants an app key. Get a free one at mega.io/developers and enter it in the \"App key\" box";
                break;
            case -26:
                status = 401;
                why = "this account has two-factor authentication: enter the current 6-digit code and try again";
                break;
            default:
                why = "MEGA error";
        }
        return new CloudException(status, what + " failed: " + why + " (MEGA code " + code + ")");
    }

    private HttpTransport.Response fetch(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpTransport.Response r = http.execute(new HttpTransport.Request("GET", current));
            String location = r.header("location");
            if ((r.code == 301 || r.code == 302 || r.code == 307 || r.code == 308) && location != null) {
                r.close();
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (r.code != 200) {
                int code = r.code;
                r.close();
                throw new CloudException(code, "Downloading failed: MEGA's storage server answered HTTP " + code);
            }
            return r;
        }
        throw new CloudException(310, "Too many redirects");
    }

    /** Uploads one encrypted chunk and returns the server's reply text ("" for all but the last chunk). */
    private String postChunk(String url, byte[] data) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpTransport.Request req = new HttpTransport.Request("POST", url).body(data, "application/octet-stream");
            HttpTransport.Response r = http.execute(req);
            String text;
            int status = r.code;
            try {
                text = new String(r.readAll(64 * 1024), StandardCharsets.UTF_8).trim();
            } finally {
                r.close();
            }
            boolean negative = text.matches("-\\d+");
            if ((status >= 500 || (negative && (text.equals("-3") || text.equals("-4")))) && attempt < 4) {
                pause(retryBaseMs << attempt);
                continue;
            }
            if (status != 200) throw new CloudException(status, "Uploading failed: MEGA's storage server answered HTTP " + status);
            if (negative) throw megaError(Integer.parseInt(text), "Uploading", false);
            return text;
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("The file ended earlier than expected");
            off += n;
        }
    }

    private static void finish(File part, File dest) throws IOException {
        if (dest.exists() && !dest.delete()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Could not replace " + dest.getName());
        }
        if (!part.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Could not save the downloaded file");
        }
    }
}
