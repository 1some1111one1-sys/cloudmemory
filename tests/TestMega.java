package com.cloudbridge.app.core;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.expectFail;
import static com.cloudbridge.app.core.TestMain.names;
import static com.cloudbridge.app.core.TestMain.run;

/** Tests for the MEGA connector against an in-process imitation of MEGA's servers. */
final class TestMega {
    private TestMega() {
    }

    static void runAll() {
        run("mega crypto primitives", TestMega::testCrypto);
        run("mega login v2 + browse + download", TestMega::testBrowse);
        run("mega upload + changes", TestMega::testChanges);
        run("mega login v1", TestMega::testLoginV1);
        run("mega sessions, 2fa, errors", TestMega::testSessionsAndErrors);
    }

    private static byte[] bytes(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static MegaClient client(MockMegaServer s) {
        return new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "", "");
    }

    private static byte[] download(CloudClient c, String path) throws Exception {
        File f = File.createTempFile("mgd", ".bin");
        f.deleteOnExit();
        c.download(path, f);
        return Files.readAllBytes(f.toPath());
    }

    private static File fileOf(byte[] b) throws Exception {
        File f = File.createTempFile("mgu", ".bin");
        f.deleteOnExit();
        Files.write(f.toPath(), b);
        return f;
    }

    // ------------------------------------------------------------------ crypto

    private static void testCrypto() throws Exception {
        byte[] pw = "paßword".getBytes(StandardCharsets.UTF_8);
        byte[] salt = bytes(16, 1);
        byte[] mine = MegaCrypto.pbkdf2Sha512(pw, salt, 1000, 32);
        byte[] jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(new PBEKeySpec("paßword".toCharArray(), salt, 1000, 256)).getEncoded();
        check("pbkdf2-sha512 equals the JDK", Arrays.equals(mine, jdk));
        byte[] longOut = MegaCrypto.pbkdf2Sha512(pw, salt, 10, 100);
        byte[] jdkLong = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(new PBEKeySpec("paßword".toCharArray(), salt, 10, 800)).getEncoded();
        check("pbkdf2 multi-block output", Arrays.equals(longOut, jdkLong));

        eq("a32 round trip", Arrays.asList(1, -2, 3), Arrays.asList(
                MegaCrypto.strToA32(MegaCrypto.a32ToBytes(new int[]{1, -2, 3}))[0],
                MegaCrypto.strToA32(MegaCrypto.a32ToBytes(new int[]{1, -2, 3}))[1],
                MegaCrypto.strToA32(MegaCrypto.a32ToBytes(new int[]{1, -2, 3}))[2]));
        eq("str_to_a32 pads with zeros", 0x61620000, MegaCrypto.strToA32("ab".getBytes())[0]);
        eq("b64 url-safe, unpadded", "-_8", MegaCrypto.b64(new byte[]{(byte) 0xfb, (byte) 0xff}));
        check("b64 accepts the classic alphabet and commas", Arrays.equals(new byte[]{(byte) 0xfb, (byte) 0xff}, MegaCrypto.unb64("+/8,")));

        int[] key = {1, 2, 3, 4};
        int[] wrapped = MegaCrypto.encryptKey(new int[]{5, 6, 7, 8, 9, 10, 11, 12}, key);
        eq("key wrap round trip", Arrays.toString(new int[]{5, 6, 7, 8, 9, 10, 11, 12}), Arrays.toString(MegaCrypto.decryptKey(wrapped, key)));
        check("wrapping changes the words", wrapped[0] != 5);

        String enc = MegaCrypto.encryptAttrs("Café \"1\" / ☃.txt", key);
        eq("attribute round trip", "Café \"1\" / ☃.txt", MegaCrypto.decryptName(enc, key));
        check("wrong key gives no name", MegaCrypto.decryptName(enc, new int[]{9, 9, 9, 9}) == null);
        check("garbage gives no name", MegaCrypto.decryptName("AAAA", key) == null && MegaCrypto.decryptName("", key) == null);

        BigInteger big = new BigInteger(1, bytes(65, 3));
        int[] off = {0};
        eq("mpi round trip", big, MegaCrypto.readMpi(MegaCrypto.writeMpi(big), off));
        eq("mpi consumed", 2 + 65, off[0]);

        long c = 0x20000;
        StringBuilder sizes = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sizes.append(c / 1024).append(' ');
            c = MegaCrypto.nextChunkSize(c);
        }
        eq("chunk sizes", "128 256 384 512 640 768 896 1024 1024 1024 ", sizes.toString());

        eq("v1 password key length", 4, MegaCrypto.prepareKey("secret".getBytes()).length);
        check("v1 hash is deterministic and 11 chars", MegaCrypto.stringHash("a@b.c", new int[]{1, 2, 3, 4})
                .equals(MegaCrypto.stringHash("a@b.c", new int[]{1, 2, 3, 4})) && MegaCrypto.stringHash("a@b.c", new int[]{1, 2, 3, 4}).length() == 11);
    }

    // ------------------------------------------------------------------ browse

    private static MockMegaServer seeded(int version) throws Exception {
        MegaClient.retryBaseMs = 1;
        MockMegaServer s = new MockMegaServer(version);
        MockMegaServer.N docs = s.folder(s.rootId, "Docs");
        s.file(docs.h, "a.txt", "hello mega".getBytes(StandardCharsets.UTF_8));
        s.file(docs.h, "big.bin", bytes(2_700_000, 11));            // crosses several chunk sizes
        s.file(docs.h, "empty.txt", new byte[0]);
        s.file(docs.h, "dup.txt", "first".getBytes());
        s.file(docs.h, "dup.txt", "second".getBytes());
        s.file(docs.h, "a/b.txt", "slash".getBytes());
        s.folder(docs.h, "Sub");
        s.file(s.rootId, "top.txt", "top".getBytes());
        MockMegaServer.N foreign = s.file(s.rootId, "shared-by-someone.txt", "x".getBytes());
        foreign.k = "OTHERUSER:" + foreign.k.substring(foreign.k.indexOf(':') + 1);
        s.file(s.rubbishId, "binned.txt", "gone".getBytes());
        return s;
    }

    private static void testBrowse() throws Exception {
        MockMegaServer s = seeded(2);
        try {
            MegaClient c = client(s);
            eq("root", java.util.Arrays.asList("/Docs/", "/top.txt"), names(c.list("/")));
            eq("docs", java.util.Arrays.asList("/Docs/Sub/", "/Docs/a.txt", "/Docs/a∕b.txt", "/Docs/big.bin",
                    "/Docs/dup (2).txt", "/Docs/dup.txt", "/Docs/empty.txt"), names(c.list("/Docs")));
            check("stat file", c.stat("/Docs/a.txt").size == 10 && !c.stat("/Docs/a.txt").dir && c.stat("/Docs/a.txt").modified > 0);
            check("stat folders", c.stat("/").dir && c.stat("/Docs").dir && c.stat("/Docs/Sub").dir);
            check("missing is 404", expectFail("missing", () -> c.stat("/nope")).isNotFound());
            check("foreign-shared node hidden", expectFail("foreign", () -> c.stat("/shared-by-someone.txt")).isNotFound());
            check("rubbish content not under root", expectFail("rubbish", () -> c.stat("/binned.txt")).isNotFound());
            check("file as a folder is 404", expectFail("file as dir", () -> c.stat("/Docs/a.txt/x")).isNotFound());

            eq("download small", "hello mega", new String(download(c, "/Docs/a.txt"), StandardCharsets.UTF_8));
            check("download 2.7 MB multi-chunk equals the original", Arrays.equals(bytes(2_700_000, 11), download(c, "/Docs/big.bin")));
            eq("download empty", 0, download(c, "/Docs/empty.txt").length);
            eq("duplicate 1", "first", new String(download(c, "/Docs/dup.txt")));
            eq("duplicate 2", "second", new String(download(c, "/Docs/dup (2).txt")));
            eq("slash name", "slash", new String(download(c, "/Docs/a∕b.txt")));
            check("download folder refused", expectFail("download folder", () -> download(c, "/Docs")) != null);

            // a flipped ciphertext bit must be caught by the MAC and leave no file behind
            MockMegaServer.N big = s.byName(s.byName(s.rootId, "Docs").h, "big.bin");
            big.data[1_500_000] ^= 1;
            File dest = File.createTempFile("mgd", ".bin");
            dest.delete();
            CloudException bad = expectFail("tampered download", () -> c.download("/Docs/big.bin", dest));
            check("tampering reported", bad != null && bad.getMessage().contains("damaged"));
            check("no partial file left", !dest.exists() && !new File(dest.getPath() + ".part").exists());

            int calls = s.apiCalls.get();
            c.list("/Docs");
            c.stat("/Docs/a.txt");
            eq("tree is cached between calls", calls, s.apiCalls.get());
        } finally {
            s.stop();
        }
    }

    // ------------------------------------------------------------------ changes

    private static void testChanges() throws Exception {
        MockMegaServer s = seeded(2);
        try {
            MegaClient c = client(s);
            MockMegaServer.N docs = s.byName(s.rootId, "Docs");

            byte[] three = bytes(3_300_000, 21);
            s.chunkPosts.clear();
            c.upload("/Docs/new.bin", fileOf(three), "application/octet-stream");
            MockMegaServer.N created = s.byName(docs.h, "new.bin");
            check("uploaded node exists", created != null && created.s == three.length);
            check("server stored ciphertext, not plaintext", !Arrays.equals(created.data, three));
            check("independent decrypt + MAC check of what was stored", Arrays.equals(three, s.plaintextChecked(created)));
            long[][] expectedPosts = {{0, 131072}, {131072, 262144}, {393216, 393216}, {786432, 524288}, {1310720, 655360},
                    {1966080, 786432}, {2752512, 547488}};
            boolean chunksOk = s.chunkPosts.size() == expectedPosts.length;
            for (int i = 0; chunksOk && i < expectedPosts.length; i++) chunksOk = Arrays.equals(expectedPosts[i], s.chunkPosts.get(i));
            check("chunks follow MEGA's growing sizes " + s.chunkPosts.size(), chunksOk);
            check("round trip through the client", Arrays.equals(three, download(c, "/Docs/new.bin")));
            eq("listed", 3_300_000L, c.stat("/Docs/new.bin").size);

            // overwrite: the old copy goes to the rubbish bin
            byte[] v2 = bytes(1000, 22);
            c.upload("/Docs/new.bin", fileOf(v2), "x/y");
            check("old copy in the rubbish bin", s.nodes.get(created.h) != null && s.rubbishId.equals(s.nodes.get(created.h).p));
            check("new content in place", Arrays.equals(v2, download(c, "/Docs/new.bin")));
            eq("only one visible copy", 1L, c.list("/Docs").stream().filter(e -> e.path.equals("/Docs/new.bin")).count());

            // empty placeholder then real content (what the Files app does): the placeholder is erased, not binned
            c.upload("/Docs/fresh.txt", fileOf(new byte[0]), "text/plain");
            MockMegaServer.N placeholder = s.byName(docs.h, "fresh.txt");
            check("empty file created", placeholder != null && placeholder.s == 0);
            c.upload("/Docs/fresh.txt", fileOf("filled".getBytes()), "text/plain");
            check("placeholder erased", !s.nodes.containsKey(placeholder.h));
            eq("filled", "filled", new String(download(c, "/Docs/fresh.txt")));

            check("upload into a missing folder", expectFail("no parent", () -> c.upload("/Nope/x", fileOf(v2), "x/y")).isNotFound());
            check("upload over a folder", expectFail("over folder", () -> c.upload("/Docs/Sub", fileOf(v2), "x/y")) != null);

            // folders, rename, move, delete
            c.mkdir("/Docs/New Folder");
            MockMegaServer.N nf = s.byName(docs.h, "New Folder");
            check("folder created", nf != null && nf.t == 1);
            check("mkdir twice refused", expectFail("mkdir twice", () -> c.mkdir("/Docs/New Folder")) != null);
            c.upload("/Docs/New Folder/inner.txt", fileOf("in".getBytes()), "text/plain");
            c.move("/Docs/a.txt", "/Docs/renamed.txt", false);
            check("renamed on the server", s.byName(docs.h, "renamed.txt") != null && s.byName(docs.h, "a.txt") == null);
            eq("renamed file still decrypts", "hello mega", new String(download(c, "/Docs/renamed.txt")));
            c.move("/Docs/renamed.txt", "/Docs/New Folder/moved.txt", false);
            check("moved into folder", s.byName(nf.h, "moved.txt") != null);
            c.move("/Docs/New Folder", "/Moved Folder", true);
            check("moved folder to root", s.byName(s.rootId, "Moved Folder") == nf);
            eq("children follow", "hello mega", new String(download(c, "/Moved Folder/moved.txt")));
            eq("folder listing", java.util.Arrays.asList("/Moved Folder/inner.txt", "/Moved Folder/moved.txt"), names(c.list("/Moved Folder")));
            CloudException clash = expectFail("move onto existing", () -> c.move("/top.txt", "/Moved Folder", false));
            eq("clash is 412", 412, clash == null ? -1 : clash.status);
            check("into itself refused", expectFail("into itself", () -> c.move("/Moved Folder", "/Moved Folder/x", true)) != null);

            c.delete("/Moved Folder/moved.txt", false);
            MockMegaServer.N moved = null;
            synchronized (s.nodes) {
                for (MockMegaServer.N n : s.nodes.values()) if (n.t == 0 && "moved.txt".equals(s.nameOf(n))) moved = n;
            }
            check("delete goes to the rubbish bin", moved != null && s.rubbishId.equals(moved.p));
            eq("gone from the listing", java.util.Arrays.asList("/Moved Folder/inner.txt"), names(c.list("/Moved Folder")));
            c.delete("/Moved Folder", true);
            check("folder deleted", expectFail("deleted folder", () -> c.list("/Moved Folder")).isNotFound());
            check("root cannot be deleted", expectFail("delete root", () -> c.delete("/", true)) != null);

            // a second client object shares the session and sees the same tree
            int logins = s.logins.get();
            MegaClient other = client(s);
            check("second client sees earlier work", other.stat("/Docs/new.bin").size == 1000);
            eq("one login for everything", logins, s.logins.get());
        } finally {
            s.stop();
        }
    }

    // ------------------------------------------------------------------ v1 accounts

    private static void testLoginV1() throws Exception {
        MockMegaServer s = seeded(1);
        try {
            MegaClient c = client(s);
            eq("v1 login lists", java.util.Arrays.asList("/Docs/", "/top.txt"), names(c.list("/")));
            eq("v1 download", "top", new String(download(c, "/top.txt")));
        } finally {
            s.stop();
        }
    }

    // ------------------------------------------------------------------ sessions and errors

    private static void testSessionsAndErrors() throws Exception {
        MockMegaServer s = seeded(2);
        try {
            // wrong password
            MegaClient wrong = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, "nope", "", "");
            CloudException bad = expectFail("wrong password", () -> wrong.list("/"));
            check("wrong password explained", bad != null && bad.status == 401 && bad.getMessage().contains("e-mail and password"));

            // two-factor accounts
            s.needs2fa = true;
            MegaClient plain = client(s);
            CloudException need = expectFail("2fa needed", () -> plain.list("/"));
            check("2fa explained", need != null && need.getMessage().contains("two-factor"));
            expectFail("wrong 2fa code", () -> MegaClient.signIn(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "000000", ""));
            String blob = MegaClient.signIn(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, " 123456 ", "");
            check("session blob has sid|key|handle", blob.split("\\|").length == 3 && blob.endsWith("|" + MockMegaServer.USER));

            // a new process: the saved session works without a password or another code
            MegaClient.clearCaches();
            int logins = s.logins.get();
            MegaClient viaBlob = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, "", "", blob);
            eq("blob client lists", java.util.Arrays.asList("/Docs/", "/top.txt"), names(viaBlob.list("/")));
            eq("blob client decrypts", "top", new String(download(viaBlob, "/top.txt")));
            eq("no login needed with a saved session", logins, s.logins.get());

            // the session dies on the server; with a password we sign in again, without one we say so
            s.sid = "";
            s.needs2fa = false;
            MegaClient.clearCaches();
            MegaClient viaBlob2 = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, "", "", blob);
            CloudException expired = expectFail("expired session without password", () -> viaBlob2.list("/"));
            check("expired session explained", expired != null && expired.status == 401 && expired.getMessage().contains("Test & save"));
            MegaClient withPw = client(s);
            withPw.list("/");
            s.sid = "stale";                                     // server forgets us mid-session
            MegaClient.clearCaches();
            MegaClient fresh = client(s);
            fresh.list("/");
            s.sid = "stale-again";
            // the tree is cached, so force an API call that needs the session
            File dest = File.createTempFile("mgd", ".bin");
            dest.delete();
            fresh.download("/top.txt", dest);
            eq("download after the session died", "top", new String(Files.readAllBytes(dest.toPath())));

            // busy server
            MegaClient.clearCaches();
            MegaClient busy = client(s);
            s.eagain = 3;
            eq("EAGAIN is retried", 2, busy.list("/").size());
            s.eagain = 100;
            MegaClient.clearCaches();
            CloudException tooBusy = expectFail("always busy", () -> client(s).list("/"));
            check("gives up politely", tooBusy != null && tooBusy.status == 429);
            s.eagain = 0;

            // quota
            MegaClient.clearCaches();
            MegaClient q = client(s);
            s.uploadQuota = 1;
            CloudException full = expectFail("quota", () -> q.upload("/x.bin", fileOf("abc".getBytes()), "x/y"));
            check("quota is 507", full != null && full.status == 507 && full.getMessage().contains("full"));

            // app key
            s.appKeyRequired = "KEY123";
            MegaClient.clearCaches();
            CloudException needKey = expectFail("app key needed", () -> client(s).list("/"));
            check("app key explained", needKey != null && needKey.getMessage().contains("app key"));
            MegaClient withKey = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "KEY123", "");
            withKey.list("/");
            check("app key sent on every request", !s.akSeen.isEmpty() && s.akSeen.get(s.akSeen.size() - 1).equals("KEY123"));
        } finally {
            s.stop();
        }
        String[][] badArgs = {{"", "p"}, {"no-at-sign", "p"}, {"a@b.c", ""}};
        for (String[] b : badArgs) {
            try {
                new MegaClient(null, b[0], b[1], "", "");
                check("mega validation " + Arrays.toString(b), false);
            } catch (IllegalArgumentException e) {
                check("mega validation " + Arrays.toString(b), !e.getMessage().isEmpty());
            }
        }
        Account a = new Account();
        a.type = Account.Type.MEGA;
        a.username = "me@example.com";
        a.secret = "pw";
        check("factory builds a mega client", CloudClients.create(a, null) instanceof MegaClient);
        eq("mega summary", "MEGA · me@example.com", a.summary());
    }
}
