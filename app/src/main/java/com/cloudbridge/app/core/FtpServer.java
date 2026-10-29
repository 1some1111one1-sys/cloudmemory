package com.cloudbridge.app.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small FTP server that listens on this phone only (127.0.0.1) and shows every cloud account as a folder.
 * Many built-in file managers can add an FTP location even though they know nothing about cloud storage; they
 * connect here and then browse, open, upload, rename and delete in every cloud. Passive mode only, plain FTP,
 * and a password is required.
 */
public final class FtpServer {
    private final WebDavServer.Backend backend;
    private final DownloadCache cache;
    private final File uploads;
    private final byte[] expectedUser;
    private final byte[] expectedPassword;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cloudbridge-ftp");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket socket;
    private volatile boolean stopped;

    public FtpServer(WebDavServer.Backend backend, File cacheDir, String user, String password) {
        this.backend = backend;
        this.cache = new DownloadCache(new File(cacheDir, "webdav-dl"));   // shared with the WebDAV bridge
        this.uploads = new File(cacheDir, "ftp-up");
        this.expectedUser = user.getBytes(StandardCharsets.UTF_8);
        this.expectedPassword = password.getBytes(StandardCharsets.UTF_8);
    }

    /** Starts listening on 127.0.0.1 at the first free port from {@code preferredPort} on. Returns the port. */
    public synchronized int start(int preferredPort) throws IOException {
        if (socket != null) return socket.getLocalPort();
        cache.ensureDir();
        //noinspection ResultOfMethodCallIgnored
        uploads.mkdirs();
        File[] leftovers = uploads.listFiles();
        if (leftovers != null) {
            for (File f : leftovers) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        IOException last = null;
        for (int i = 0; i < (preferredPort == 0 ? 1 : 20); i++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort == 0 ? 0 : preferredPort + i), 50);
                socket = s;
                stopped = false;
                Thread t = new Thread(() -> acceptLoop(s), "cloudbridge-ftp-accept");
                t.setDaemon(true);
                t.start();
                return s.getLocalPort();
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("No free port near " + preferredPort + ": " + (last == null ? "" : last.getMessage()));
    }

    public synchronized void stop() {
        stopped = true;
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop(ServerSocket s) {
        while (!stopped) {
            try {
                final Socket c = s.accept();
                pool.execute(() -> new Session(c).run());
            } catch (IOException e) {
                if (stopped || s.isClosed()) return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ paths

    private static final class Target {
        String account;     // null for the top level (the list of clouds)
        String path = "/";

        boolean top() {
            return account == null;
        }

        boolean accountRoot() {
            return account != null && path.equals("/");
        }
    }

    private Target target(String virtual) throws CloudException {
        String full = Paths.normalize(virtual);
        Target t = new Target();
        if (full.equals("/")) return t;
        String rest = full.substring(1);
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        if (!backend.names().contains(name)) throw new CloudException(404, "No storage called \"" + name + "\"");
        t.account = name;
        t.path = slash < 0 ? "/" : Paths.normalize(rest.substring(slash));
        return t;
    }

    private static final class Row {
        final String name;
        final boolean dir;
        final long size;
        final long modified;

        Row(String name, boolean dir, long size, long modified) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    // ------------------------------------------------------------------ formatting

    private static String stamp(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String lsLine(Row r) {
        long m = r.modified > 0 ? r.modified : System.currentTimeMillis();
        boolean recent = System.currentTimeMillis() - m < 180L * 24 * 3600 * 1000 && m <= System.currentTimeMillis() + 24L * 3600 * 1000;
        SimpleDateFormat f = new SimpleDateFormat(recent ? "MMM dd HH:mm" : "MMM dd  yyyy", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return (r.dir ? "drwxr-xr-x" : "-rw-r--r--") + " 1 cloud cloud " + String.format(Locale.US, "%12d", Math.max(0, r.size)) + " "
                + f.format(new Date(m)) + " " + r.name;
    }

    private static String mlsLine(Row r) {
        long m = r.modified > 0 ? r.modified : System.currentTimeMillis();
        return "type=" + (r.dir ? "dir" : "file") + ";" + (r.size >= 0 && !r.dir ? "size=" + r.size + ";" : "")
                + "modify=" + stamp(m) + ";perm=" + (r.dir ? "cfmpel" : "rfadw") + "; " + r.name;
    }

    private static String oneLine(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.replace('\r', ' ').replace('\n', ' ');
    }

    // ------------------------------------------------------------------ one control connection

    private final class Session {
        private final Socket control;
        private BufferedReader in;
        private OutputStream out;
        private String pendingUser;
        private boolean loggedIn;
        private int failures;
        private String cwd = "/";
        private long rest;
        private Target renameFrom;
        private ServerSocket passive;

        Session(Socket control) {
            this.control = control;
        }

        void run() {
            try {
                control.setSoTimeout(5 * 60_000);
                in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8), 4096);
                out = control.getOutputStream();
                reply(220, "CloudBridge FTP ready. Log in with the user name and password shown in CloudBridge.");
                String line;
                while (!stopped && (line = in.readLine()) != null) {
                    if (line.length() > 8192) break;
                    String cmd = line;
                    String arg = "";
                    int sp = line.indexOf(' ');
                    if (sp > 0) {
                        cmd = line.substring(0, sp);
                        arg = line.substring(sp + 1);
                    }
                    if (!dispatch(cmd.toUpperCase(Locale.ROOT), arg)) break;
                }
            } catch (IOException | RuntimeException ignored) {
                // the client went away
            } finally {
                closePassive();
                try {
                    control.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void reply(int code, String text) throws IOException {
            out.write((code + " " + text + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void closePassive() {
            ServerSocket p = passive;
            passive = null;
            if (p != null) {
                try {
                    p.close();
                } catch (IOException ignored) {
                }
            }
        }

        private String abs(String arg) {
            String a = arg == null ? "" : arg.trim();
            while (a.startsWith("-")) {                       // "LIST -la" and similar flags
                int sp = a.indexOf(' ');
                a = sp < 0 ? "" : a.substring(sp + 1).trim();
            }
            if (a.isEmpty()) return cwd;
            return Paths.normalize(a.startsWith("/") ? a : cwd + "/" + a);
        }

        /** Returns false when the connection should be closed. */
        private boolean dispatch(String cmd, String arg) throws IOException {
            switch (cmd) {
                case "USER":
                    pendingUser = arg;
                    loggedIn = false;
                    reply(331, "Password required");
                    return true;
                case "PASS":
                    return login(arg);
                case "QUIT":
                    reply(221, "Goodbye");
                    return false;
                case "FEAT":
                    out.write(("211-Features:\r\n UTF8\r\n MLST type*;size*;modify*;perm*;\r\n MLSD\r\n SIZE\r\n MDTM\r\n"
                            + " REST STREAM\r\n EPSV\r\n211 End\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return true;
                case "OPTS":
                    reply(200, "OK");
                    return true;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    return true;
                case "NOOP":
                    reply(200, "OK");
                    return true;
                case "AUTH":
                    reply(502, "Secure FTP is not available here; use plain FTP on 127.0.0.1");
                    return true;
                default:
                    break;
            }
            if (!loggedIn) {
                reply(530, "Please log in first");
                return true;
            }
            try {
                switch (cmd) {
                    case "PWD":
                    case "XPWD":
                        reply(257, "\"" + cwd.replace("\"", "\"\"") + "\" is the current directory");
                        break;
                    case "CWD":
                    case "XCWD":
                        cwd(abs(arg));
                        break;
                    case "CDUP":
                    case "XCUP":
                        cwd(Paths.parent(cwd));
                        break;
                    case "TYPE":
                        reply(200, "Type set");
                        break;
                    case "MODE":
                    case "STRU":
                    case "ALLO":
                        reply(200, "OK");
                        break;
                    case "PBSZ":
                    case "PROT":
                        reply(503, "No secure connection");
                        break;
                    case "PASV":
                        pasv(false);
                        break;
                    case "EPSV":
                        pasv(true);
                        break;
                    case "PORT":
                    case "EPRT":
                        reply(502, "Use passive mode (PASV)");
                        break;
                    case "REST":
                        try {
                            rest = Math.max(0, Long.parseLong(arg.trim()));
                            reply(350, "Restarting at " + rest);
                        } catch (NumberFormatException e) {
                            reply(501, "Bad offset");
                        }
                        break;
                    case "LIST":
                    case "NLST":
                    case "MLSD":
                        listing(cmd, abs(arg));
                        break;
                    case "MLST":
                        mlst(abs(arg));
                        break;
                    case "RETR":
                        retr(abs(arg));
                        break;
                    case "STOR":
                        stor(abs(arg));
                        break;
                    case "SIZE":
                        size(abs(arg));
                        break;
                    case "MDTM":
                        mdtm(abs(arg));
                        break;
                    case "DELE":
                        remove(abs(arg), false);
                        break;
                    case "RMD":
                    case "XRMD":
                        remove(abs(arg), true);
                        break;
                    case "MKD":
                    case "XMKD":
                        mkd(abs(arg));
                        break;
                    case "RNFR":
                        rnfr(abs(arg));
                        break;
                    case "RNTO":
                        rnto(abs(arg));
                        break;
                    case "ABOR":
                        reply(226, "Nothing to abort");
                        break;
                    case "STAT":
                        reply(211, "CloudBridge FTP, logged in");
                        break;
                    case "SITE":
                    case "APPE":
                    case "STOU":
                        reply(502, "Not supported");
                        break;
                    default:
                        reply(502, "Command not implemented");
                }
            } catch (CloudException e) {
                reply(550, oneLine(e));
            } catch (SocketTimeoutException e) {
                reply(425, "The data connection did not open; use PASV and try again");
            } catch (IOException e) {
                if (e instanceof java.net.SocketException && control.isClosed()) throw e;
                reply(451, oneLine(e));
            } catch (IllegalArgumentException e) {
                reply(550, oneLine(e));
            } finally {
                // a passive port is good for one transfer command only, whether it worked or not
                if (cmd.equals("LIST") || cmd.equals("NLST") || cmd.equals("MLSD") || cmd.equals("RETR") || cmd.equals("STOR")) {
                    closePassive();
                }
            }
            return true;
        }

        private boolean login(String password) throws IOException {
            boolean ok = pendingUser != null
                    && MessageDigest.isEqual(pendingUser.getBytes(StandardCharsets.UTF_8), expectedUser)
                    & MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), expectedPassword);
            if (ok) {
                loggedIn = true;
                reply(230, "Logged in");
                return true;
            }
            loggedIn = false;
            failures++;
            reply(530, "Login incorrect");
            return failures < 5;
        }

        private void cwd(String virtual) throws IOException {
            Target t = target(virtual);
            if (!t.top() && !t.accountRoot()) {
                CloudEntry e = backend.client(t.account).stat(t.path);
                if (!e.dir) {
                    reply(550, "Not a folder");
                    return;
                }
            }
            cwd = Paths.normalize(virtual);
            reply(250, "Folder changed to " + cwd);
        }

        private void pasv(boolean extended) throws IOException {
            closePassive();
            ServerSocket p = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            p.setSoTimeout(30_000);
            passive = p;
            int port = p.getLocalPort();
            if (extended) reply(229, "Entering Extended Passive Mode (|||" + port + "|)");
            else reply(227, "Entering Passive Mode (127,0,0,1," + (port >> 8) + "," + (port & 255) + ")");
        }

        /** Waits for the client's data connection. Sends the 150 first, like real servers do. */
        private Socket openData(String what) throws IOException {
            ServerSocket p = passive;
            if (p == null) {
                reply(425, "Use PASV first");
                return null;
            }
            reply(150, what);
            try {
                return p.accept();
            } finally {
                closePassive();
            }
        }

        private List<Row> rows(Target t) throws IOException {
            List<Row> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            if (t.top()) {
                for (String name : backend.names()) out.add(new Row(name, true, -1, now));
                return out;
            }
            CloudClient c = backend.client(t.account);
            CloudEntry self = t.accountRoot() ? new CloudEntry("/", true, -1, now) : c.stat(t.path);
            if (!self.dir) {
                out.add(new Row(self.name(), false, self.size, self.modified));
                return out;
            }
            for (CloudEntry e : c.list(t.path)) out.add(new Row(e.name(), e.dir, e.size, e.modified));
            return out;
        }

        private void listing(String cmd, String virtual) throws IOException {
            Target t = target(virtual);
            List<Row> rows = rows(t);
            Socket data = openData(cmd.equals("MLSD") ? "Sending the machine-readable listing" : "Sending the folder listing");
            if (data == null) return;
            try (OutputStream o = data.getOutputStream()) {
                StringBuilder sb = new StringBuilder();
                for (Row r : rows) {
                    sb.append(cmd.equals("NLST") ? r.name : cmd.equals("MLSD") ? mlsLine(r) : lsLine(r)).append("\r\n");
                }
                o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                data.close();
            }
            reply(226, "Listing sent");
        }

        private void mlst(String virtual) throws IOException {
            Target t = target(virtual);
            Row row;
            if (t.top() || t.accountRoot()) {
                row = new Row(t.top() ? "/" : t.account, true, -1, System.currentTimeMillis());
            } else {
                CloudEntry e = backend.client(t.account).stat(t.path);
                row = new Row(e.name(), e.dir, e.size, e.modified);
            }
            out.write(("250-Listing " + virtual + "\r\n " + mlsLine(row) + "\r\n250 End\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void retr(String virtual) throws IOException {
            Target t = target(virtual);
            if (t.top() || t.accountRoot()) {
                reply(550, "That is a folder");
                return;
            }
            CloudClient c = backend.client(t.account);
            CloudEntry e = c.stat(t.path);
            if (e.dir) {
                reply(550, "That is a folder");
                return;
            }
            long offset = rest;
            rest = 0;
            if (passive == null) {
                reply(425, "Use PASV first");
                return;
            }
            reply(150, "Getting the file from the cloud");   // the download may take a while
            File f;
            try {
                f = cache.get(t.account, t.path, e, c);
            } catch (IOException ex) {
                closePassive();
                reply(451, oneLine(ex));
                return;
            }
            Socket data;
            try {
                data = passive.accept();
            } finally {
                closePassive();
            }
            try (OutputStream o = data.getOutputStream(); RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(Math.min(offset, f.length()));
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = raf.read(buf)) >= 0) o.write(buf, 0, n);
            } finally {
                data.close();
            }
            reply(226, "Transfer complete");
        }

        private void stor(String virtual) throws IOException {
            Target t = target(virtual);
            if (t.top() || t.accountRoot()) {
                reply(550, "Cannot write here; open one of the storage folders first");
                return;
            }
            CloudClient c = backend.client(t.account);
            String parent = Paths.parent(t.path);
            if (!parent.equals("/")) {
                CloudEntry p = c.stat(parent);
                if (!p.dir) {
                    reply(550, "The destination is not a folder");
                    return;
                }
            }
            rest = 0;
            File tmp = File.createTempFile("stor_", ".tmp", uploads);
            try {
                Socket data = openData("Ready to receive the file");
                if (data == null) return;
                try (InputStream i = data.getInputStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = i.read(buf)) >= 0) fo.write(buf, 0, n);
                } finally {
                    data.close();
                }
                c.upload(t.path, tmp, Paths.mimeFor(Paths.name(t.path)));
                reply(226, "Upload complete");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        private CloudEntry fileOrFolder(Target t) throws IOException {
            if (t.top() || t.accountRoot()) return new CloudEntry("/", true, -1, System.currentTimeMillis());
            return backend.client(t.account).stat(t.path);
        }

        private void size(String virtual) throws IOException {
            CloudEntry e = fileOrFolder(target(virtual));
            if (e.dir || e.size < 0) reply(550, "Size not available");
            else reply(213, String.valueOf(e.size));
        }

        private void mdtm(String virtual) throws IOException {
            CloudEntry e = fileOrFolder(target(virtual));
            reply(213, stamp(e.modified > 0 ? e.modified : System.currentTimeMillis()));
        }

        private void remove(String virtual, boolean folder) throws IOException {
            Target t = target(virtual);
            if (t.top() || t.accountRoot()) {
                reply(550, "A storage cannot be deleted from here; remove it in CloudBridge");
                return;
            }
            CloudClient c = backend.client(t.account);
            CloudEntry e = c.stat(t.path);
            if (e.dir != folder) {
                reply(550, folder ? "Not a folder" : "That is a folder; use RMD");
                return;
            }
            c.delete(t.path, e.dir);
            reply(250, "Deleted");
        }

        private void mkd(String virtual) throws IOException {
            Target t = target(virtual);
            if (t.top() || t.accountRoot()) {
                reply(550, "That folder already exists");
                return;
            }
            CloudClient c = backend.client(t.account);
            String parent = Paths.parent(t.path);
            if (!parent.equals("/") && !c.stat(parent).dir) {
                reply(550, "The parent is not a folder");
                return;
            }
            c.mkdir(t.path);
            reply(257, "\"" + virtual.replace("\"", "\"\"") + "\" created");
        }

        private void rnfr(String virtual) throws IOException {
            Target t = target(virtual);
            if (t.top() || t.accountRoot()) {
                reply(550, "A storage cannot be renamed from here");
                return;
            }
            backend.client(t.account).stat(t.path);
            renameFrom = t;
            reply(350, "Ready for the new name");
        }

        private void rnto(String virtual) throws IOException {
            Target from = renameFrom;
            renameFrom = null;
            if (from == null) {
                reply(503, "Send RNFR first");
                return;
            }
            Target to = target(virtual);
            if (to.top() || to.accountRoot()) {
                reply(553, "Not allowed");
                return;
            }
            if (!from.account.equals(to.account)) {
                reply(553, "Files cannot be moved between different storages here; copy and delete instead");
                return;
            }
            CloudClient c = backend.client(from.account);
            CloudEntry e = c.stat(from.path);
            if (e.dir && Paths.isUnder(from.path, to.path)) {
                reply(553, "A folder cannot be moved into itself");
                return;
            }
            try {
                c.stat(to.path);
                reply(553, "The destination already exists");
                return;
            } catch (CloudException ex) {
                if (!ex.isNotFound()) throw ex;
            }
            c.move(from.path, to.path, e.dir);
            reply(250, "Renamed");
        }
    }
}
