package com.cloudbridge.app.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.run;

/** Tests for the local FTP bridge with a tiny FTP client written for the purpose. */
final class TestFtpServer {
    private TestFtpServer() {
    }

    static void runAll() {
        run("ftp bridge", TestFtpServer::testFtp);
    }

    private static final class Ftp implements AutoCloseable {
        final Socket s;
        final BufferedReader in;
        final OutputStream out;
        String lastData = "";

        Ftp(int port) throws IOException {
            s = new Socket("127.0.0.1", port);
            s.setSoTimeout(15_000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        /** One full reply, multi-line replies joined with \n. */
        String read() throws IOException {
            String first = in.readLine();
            if (first == null) throw new IOException("closed");
            if (first.length() > 3 && first.charAt(3) == '-') {
                StringBuilder sb = new StringBuilder(first);
                String code = first.substring(0, 3);
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append('\n').append(line);
                    if (line.startsWith(code + " ")) break;
                }
                return sb.toString();
            }
            return first;
        }

        String cmd(String c) throws IOException {
            out.write((c + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        Socket pasv() throws IOException {
            String r = cmd("PASV");
            Matcher m = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").matcher(r);
            if (!m.find()) throw new IOException("bad PASV reply: " + r);
            int port = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
            return new Socket("127.0.0.1", port);
        }

        /** Runs a command that sends data (LIST, RETR ...) and returns the data; the final status is in lastData. */
        byte[] fetch(String c) throws IOException {
            Socket d = pasv();
            String r = cmd(c);
            if (!r.startsWith("150")) {
                d.close();
                lastData = r;
                return new byte[0];
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream i = d.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = i.read(buf)) >= 0) b.write(buf, 0, n);
            }
            lastData = read();
            return b.toByteArray();
        }

        String store(String path, byte[] data) throws IOException {
            Socket d = pasv();
            String r = cmd("STOR " + path);
            if (!r.startsWith("150")) {
                d.close();
                return r;
            }
            try (OutputStream o = d.getOutputStream()) {
                o.write(data);
            }
            return read();
        }

        @Override
        public void close() throws IOException {
            s.close();
        }
    }

    private static void testFtp() throws Exception {
        final TestWebDavServer.MemoryCloud one = new TestWebDavServer.MemoryCloud();
        final TestWebDavServer.MemoryCloud two = new TestWebDavServer.MemoryCloud();
        one.files.put("/Docs", null);
        one.files.put("/Docs/a.txt", "hello ftp".getBytes(StandardCharsets.UTF_8));
        one.files.put("/Docs/café (1).txt", "x".getBytes(StandardCharsets.UTF_8));
        one.files.put("/big.bin", "0123456789".getBytes(StandardCharsets.UTF_8));
        WebDavServer.Backend backend = new WebDavServer.Backend() {
            @Override
            public List<String> names() {
                return Arrays.asList("My Drive", "Second");
            }

            @Override
            public CloudClient client(String name) throws IOException {
                if (name.equals("My Drive")) return one;
                if (name.equals("Second")) return two;
                throw new CloudException(404, "unknown");
            }
        };
        FtpServer server = new FtpServer(backend, Files.createTempDirectory("ftpcache").toFile(), "cloudbridge", "pw-123");
        int port = server.start(0);
        try {
            // ---- login
            try (Ftp f = new Ftp(port)) {
                check("greeting", f.read().startsWith("220"));
                check("commands before login are refused", f.cmd("PWD").startsWith("530"));
                check("user accepted", f.cmd("USER cloudbridge").startsWith("331"));
                check("wrong password refused", f.cmd("PASS nope").startsWith("530"));
                f.cmd("USER cloudbridge");
                check("right password accepted", f.cmd("PASS pw-123").startsWith("230"));
                check("wrong user refused", f.cmd("USER someone").startsWith("331") && f.cmd("PASS pw-123").startsWith("530"));
            }

            try (Ftp f = new Ftp(port)) {
                f.read();
                f.cmd("USER cloudbridge");
                f.cmd("PASS pw-123");
                String feat = f.cmd("FEAT");
                check("features", feat.contains("UTF8") && feat.contains("MLSD") && feat.contains("REST STREAM"));
                check("syst", f.cmd("SYST").startsWith("215"));
                check("secure FTP is declined politely", f.cmd("AUTH TLS").startsWith("502"));
                check("type", f.cmd("TYPE I").startsWith("200"));
                check("pwd at the top", f.cmd("PWD").startsWith("257 \"/\""));

                // ---- browsing
                String top = new String(f.fetch("LIST"), StandardCharsets.UTF_8);
                check("top level lists the clouds as folders", top.contains("drwxr-xr-x") && top.contains("My Drive") && top.contains("Second"));
                check("listing finished", f.lastData.startsWith("226"));
                check("cwd into a cloud", f.cmd("CWD My Drive").startsWith("250"));
                check("pwd after cwd", f.cmd("PWD").startsWith("257 \"/My Drive\""));
                String root = new String(f.fetch("LIST"), StandardCharsets.UTF_8);
                check("list a cloud root", root.contains("Docs") && root.contains("big.bin") && root.contains("-rw-r--r--"));
                check("file size in the listing", root.contains("          10 "));
                String names = new String(f.fetch("NLST Docs"), StandardCharsets.UTF_8);
                check("nlst of a relative folder", names.contains("a.txt") && names.contains("café (1).txt"));
                String mlsd = new String(f.fetch("MLSD /My Drive/Docs"), StandardCharsets.UTF_8);
                check("mlsd facts", mlsd.contains("type=file;size=9;modify=") && mlsd.contains("; a.txt"));
                String listFlags = new String(f.fetch("LIST -la Docs"), StandardCharsets.UTF_8);
                check("list flags are ignored", listFlags.contains("a.txt"));
                check("cwd into a file is refused", f.cmd("CWD Docs/a.txt").startsWith("550"));
                check("cwd to a missing folder", f.cmd("CWD nowhere").startsWith("550"));
                check("cdup", f.cmd("CWD Docs").startsWith("250") && f.cmd("CDUP").startsWith("250") && f.cmd("PWD").contains("\"/My Drive\""));
                check("cwd to a missing cloud", f.cmd("CWD /Nope").startsWith("550"));
                check("size", f.cmd("SIZE big.bin").equals("213 10"));
                check("mdtm", f.cmd("MDTM big.bin").matches("213 \\d{14}"));
                check("mlst", f.cmd("MLST big.bin").contains("size=10;"));

                // ---- downloads
                eq("retr", "hello ftp", new String(f.fetch("RETR Docs/a.txt"), StandardCharsets.UTF_8));
                check("retr finished", f.lastData.startsWith("226"));
                eq("retr odd name", "x", new String(f.fetch("RETR Docs/café (1).txt"), StandardCharsets.UTF_8));
                check("rest", f.cmd("REST 4").startsWith("350"));
                eq("retr from an offset", "456789", new String(f.fetch("RETR big.bin"), StandardCharsets.UTF_8));
                eq("offset is used once", "0123456789", new String(f.fetch("RETR big.bin"), StandardCharsets.UTF_8));
                f.fetch("RETR nothing.bin");
                check("retr missing file", f.lastData.startsWith("550"));
                f.fetch("RETR Docs");
                check("retr of a folder", f.lastData.startsWith("550"));
                check("data command without PASV", f.cmd("LIST").startsWith("425"));
                check("epsv", f.cmd("EPSV").matches("229 .*\\(\\|\\|\\|\\d+\\|\\).*"));

                // ---- uploads
                check("stor", f.store("Docs/new.txt", "fresh upload".getBytes(StandardCharsets.UTF_8)).startsWith("226"));
                eq("stor stored", "fresh upload", new String(one.files.get("/Docs/new.txt"), StandardCharsets.UTF_8));
                check("stor overwrite", f.store("Docs/new.txt", "again".getBytes()).startsWith("226"));
                eq("stor overwrote", "again", new String(one.files.get("/Docs/new.txt")));
                check("stor to a missing folder", f.store("Nowhere/x.txt", "x".getBytes()).startsWith("550"));
                check("stor at the top level", f.store("/loose.txt", "x".getBytes()).startsWith("550"));
                byte[] big = new byte[3_000_000];
                new java.util.Random(7).nextBytes(big);
                check("big stor", f.store("big2.bin", big).startsWith("226"));
                check("big stor intact", Arrays.equals(big, one.files.get("/big2.bin")));
                check("big retr intact", Arrays.equals(big, f.fetch("RETR big2.bin")));

                // ---- changes
                check("mkd", f.cmd("MKD New Folder").startsWith("257"));
                check("mkd made it", one.files.containsKey("/New Folder"));
                check("mkd without parent", f.cmd("MKD Zip/Zap").startsWith("550"));
                check("rnfr", f.cmd("RNFR Docs/new.txt").startsWith("350"));
                check("rnto", f.cmd("RNTO New Folder/renamed.txt").startsWith("250"));
                check("renamed", one.files.containsKey("/New Folder/renamed.txt") && !one.files.containsKey("/Docs/new.txt"));
                check("rnto without rnfr", f.cmd("RNTO x").startsWith("503"));
                f.cmd("RNFR big.bin");
                check("rename onto an existing file is refused", f.cmd("RNTO big2.bin").startsWith("553"));
                f.cmd("RNFR big.bin");
                check("rename across clouds is refused", f.cmd("RNTO /Second/big.bin").startsWith("553"));
                f.cmd("RNFR Docs");
                check("folder into itself is refused", f.cmd("RNTO Docs/inside").startsWith("553"));
                check("dele a folder is refused", f.cmd("DELE Docs").startsWith("550"));
                check("dele", f.cmd("DELE big2.bin").startsWith("250") && !one.files.containsKey("/big2.bin"));
                check("rmd a file is refused", f.cmd("RMD big.bin").startsWith("550"));
                check("rmd", f.cmd("RMD New Folder").startsWith("250") && !one.files.containsKey("/New Folder/renamed.txt"));
                check("a whole cloud cannot be deleted", f.cmd("RMD /My Drive").startsWith("550"));
                check("unknown command", f.cmd("FROB").startsWith("502"));
                check("port is declined", f.cmd("PORT 127,0,0,1,1,1").startsWith("502"));
                check("noop", f.cmd("NOOP").startsWith("200"));
                check("quit", f.cmd("QUIT").startsWith("221"));
            }

            // too many wrong passwords drop the connection
            try (Ftp f = new Ftp(port)) {
                f.read();
                boolean dropped = false;
                for (int i = 0; i < 6 && !dropped; i++) {
                    try {
                        f.cmd("USER cloudbridge");
                        f.cmd("PASS wrong" + i);
                        if (i >= 4) {
                            try {
                                f.cmd("NOOP");
                            } catch (IOException e) {
                                dropped = true;
                            }
                        }
                    } catch (IOException e) {
                        dropped = true;
                    }
                }
                check("repeated wrong passwords end the connection", dropped);
            }
        } finally {
            server.stop();
        }
    }
}
