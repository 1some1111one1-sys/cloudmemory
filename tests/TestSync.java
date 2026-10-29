package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.run;

/** Tests for the two-way sync between a cloud folder and a folder on the phone. */
final class TestSync {
    private TestSync() {
    }

    static void runAll() {
        run("sync basics", TestSync::testBasics);
        run("sync changes and conflicts", TestSync::testChanges);
        run("sync deletions", TestSync::testDeletions);
        run("sync safety", TestSync::testSafety);
    }

    private static final class Env {
        final TestWebDavServer.MemoryCloud cloud = new TestWebDavServer.MemoryCloud();
        final File local;
        final File state;
        final SyncEngine.Config cfg = new SyncEngine.Config();
        final List<String> touched = new ArrayList<>();
        boolean cancel;

        Env() throws IOException {
            File base = Files.createTempDirectory("sync").toFile();
            local = new File(base, "phone");
            state = new File(base, "state.json");
            cfg.remoteRoot = "/Sync";
            cloud.put("/Sync", (String) null);
        }

        SyncEngine.Result run() throws IOException {
            touched.clear();
            return SyncEngine.sync(cloud, cfg, local, state, new SyncEngine.Listener() {
                @Override
                public boolean cancelled() {
                    return cancel;
                }

                @Override
                public void localChanged(File f) {
                    touched.add(f.getName());
                }
            });
        }

        File file(String rel) {
            return new File(local, rel);
        }

        String read(String rel) throws IOException {
            return new String(Files.readAllBytes(file(rel).toPath()), StandardCharsets.UTF_8);
        }

        void write(String rel, String text) throws IOException {
            File f = file(rel);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
            f.setLastModified(f.lastModified() + 10_000 + (long) (Math.random() * 1000) * 1000);
        }

        String cloudText(String path) {
            byte[] b = cloud.files.get(path);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void quiet(String label, SyncEngine.Result r) {
        check(label + ": nothing to do", r.downloaded + r.uploaded + r.deletedLocal + r.deletedRemote + r.conflicts + r.errors == 0);
    }

    private static void testBasics() throws Exception {
        Env e = new Env();
        e.cloud.put("/Sync/a.txt", "alpha");
        e.cloud.put("/Sync/sub", (String) null);
        e.cloud.put("/Sync/sub/b.txt", "beta");
        e.cloud.put("/Sync/empty", (String) null);
        e.cloud.put("/Sync/a:b?.txt", "odd name");
        e.cloud.put("/Other", (String) null);
        e.cloud.put("/Other/outside.txt", "not synced");

        SyncEngine.Result r = e.run();
        eq("first run downloads the files", 3, r.downloaded);
        eq("no errors", 0, r.errors);
        eq("content", "alpha", e.read("a.txt"));
        eq("nested content", "beta", e.read("sub/b.txt"));
        check("empty folder created", e.file("empty").isDirectory());
        eq("illegal characters replaced", "odd name", e.read("a_b_.txt"));
        check("only the chosen folder is synced", !e.file("outside.txt").exists());
        eq("file time copied", (long) e.cloud.mtimes.get("/Sync/a.txt"), e.file("a.txt").lastModified());
        check("media scanner is told about downloads", e.touched.contains("a.txt") && e.touched.contains("b.txt"));
        quiet("second run", e.run());

        // new things on the phone go up
        e.write("fromphone.txt", "phone says hi");
        e.write("newdir/deep/x.txt", "deep");
        new File(e.local, "emptyphone").mkdirs();
        r = e.run();
        eq("uploads", 2, r.uploaded);
        eq("uploaded content", "phone says hi", e.cloudText("/Sync/fromphone.txt"));
        eq("uploaded nested content", "deep", e.cloudText("/Sync/newdir/deep/x.txt"));
        check("folders created in the cloud", e.cloud.files.containsKey("/Sync/newdir") && e.cloud.files.containsKey("/Sync/emptyphone"));
        quiet("run after uploading", e.run());

        // the cloud folder is created when missing
        Env fresh = new Env();
        fresh.cloud.files.remove("/Sync");
        fresh.write("hello.txt", "hi");
        fresh.run();
        eq("missing cloud folder is created", "hi", fresh.cloudText("/Sync/hello.txt"));

        // a whole cloud is refused
        Env whole = new Env();
        whole.cfg.remoteRoot = "/";
        try {
            whole.run();
            check("syncing the whole cloud is refused", false);
        } catch (IOException ex) {
            check("syncing the whole cloud is refused", ex.getMessage().contains("Choose a folder"));
        }
    }

    private static void testChanges() throws Exception {
        Env e = new Env();
        e.cloud.put("/Sync/down.txt", "v1");
        e.cloud.put("/Sync/up.txt", "v1");
        e.cloud.put("/Sync/both.txt", "v1");
        e.run();

        e.cloud.put("/Sync/down.txt", "cloud v2 longer");
        e.write("up.txt", "phone v2");
        SyncEngine.Result r = e.run();
        eq("cloud change downloaded", "cloud v2 longer", e.read("down.txt"));
        eq("phone change uploaded", "phone v2", e.cloudText("/Sync/up.txt"));
        eq("one down, one up", 1, r.downloaded);
        eq("one up", 1, r.uploaded);
        quiet("then quiet", e.run());

        // changed on both sides: both versions survive
        e.cloud.put("/Sync/both.txt", "cloud version");
        e.write("both.txt", "phone version");
        r = e.run();
        eq("conflict counted", 1, r.conflicts);
        eq("the cloud's version takes the original name", "cloud version", e.read("both.txt"));
        eq("the phone's version is kept next to it", "phone version", e.read("both (phone copy).txt"));
        eq("the phone's version is uploaded too", "phone version", e.cloudText("/Sync/both (phone copy).txt"));
        quiet("conflict settles", e.run());

        // no memory of earlier runs: same-size files are assumed equal, different ones are kept both
        Env lost = new Env();
        lost.cloud.put("/Sync/same.txt", "1234");
        lost.cloud.put("/Sync/diff.txt", "cloud text");
        lost.write("same.txt", "abcd");
        lost.write("diff.txt", "phone text, longer");
        r = lost.run();
        eq("only the different one is a conflict", 1, r.conflicts);
        eq("same size is left alone", "abcd", lost.read("same.txt"));
        eq("different: cloud version wins the name", "cloud text", lost.read("diff.txt"));
        eq("different: phone version kept", "phone text, longer", lost.read("diff (phone copy).txt"));
    }

    private static void testDeletions() throws Exception {
        Env e = new Env();
        e.cloud.put("/Sync/keep.txt", "k");
        e.cloud.put("/Sync/gone.txt", "g");
        e.cloud.put("/Sync/edited.txt", "e");
        e.cloud.put("/Sync/folder", (String) null);
        e.cloud.put("/Sync/folder/inner.txt", "i");
        e.run();

        // deleted in the cloud: removed from the phone; a phone edit survives
        e.cloud.files.remove("/Sync/gone.txt");
        e.cloud.files.remove("/Sync/edited.txt");
        e.write("edited.txt", "edited on the phone");
        e.cloud.files.remove("/Sync/folder/inner.txt");
        e.cloud.files.remove("/Sync/folder");
        SyncEngine.Result r = e.run();
        check("cloud deletion removes the phone copy", !e.file("gone.txt").exists());
        check("cloud deletion removes the folder too", !e.file("folder").exists());
        eq("an edited file is not deleted, it is uploaded", "edited on the phone", e.cloudText("/Sync/edited.txt"));
        eq("removed from the phone (two files and the folder)", 3, r.deletedLocal);
        quiet("settled", e.run());

        // deleted on the phone, mirroring off: comes back
        new File(e.local, "keep.txt").delete();
        r = e.run();
        eq("restored from the cloud", "k", e.read("keep.txt"));
        eq("nothing deleted in the cloud", 0, r.deletedRemote);
        check("cloud still has it", e.cloudText("/Sync/keep.txt") != null);

        // mirroring on: deleted in the cloud too
        e.cfg.mirrorDeletes = true;
        e.file("keep.txt").delete();
        r = e.run();
        eq("deleted in the cloud", 1, r.deletedRemote);
        check("cloud copy gone", e.cloudText("/Sync/keep.txt") == null);
        check("not restored", !e.file("keep.txt").exists());

        // deleted on the phone but changed in the cloud since: the cloud's change wins
        e.cloud.put("/Sync/news.txt", "n1");
        e.run();
        e.file("news.txt").delete();
        e.cloud.put("/Sync/news.txt", "n2 changed");
        e.run();
        eq("changed cloud file survives a phone deletion", "n2 changed", e.read("news.txt"));
        check("and stays in the cloud", e.cloudText("/Sync/news.txt") != null);
    }

    private static void testSafety() throws Exception {
        // an empty or failing cloud must not wipe the phone
        Env e = new Env();
        for (int i = 0; i < 20; i++) e.cloud.put("/Sync/f" + i + ".txt", "file " + i);
        e.run();
        for (int i = 0; i < 20; i++) e.cloud.files.remove("/Sync/f" + i + ".txt");
        SyncEngine.Result r = e.run();
        check("mass deletion is held back", r.aborted && r.deletedLocal == 0);
        check("the phone's files are still there", e.file("f0.txt").isFile() && e.file("f19.txt").isFile());
        check("the summary explains it", r.summary().contains("NOT applied"));

        // a folder that cannot be read is left alone
        Env u = new Env();
        u.cloud.put("/Sync/sub", (String) null);
        u.cloud.put("/Sync/sub/x.txt", "x");
        u.cloud.put("/Sync/top.txt", "t");
        u.run();
        u.cloud.unreadableDirs.add("/Sync/sub");
        r = u.run();
        check("an unreadable folder is reported", r.errors == 1 && r.messages.get(0).contains("sub"));
        check("its files are not deleted", u.file("sub/x.txt").isFile());
        u.write("sub/y.txt", "new");     // a file added while the folder is unreadable waits
        u.cloud.unreadableDirs.clear();
        u.run();
        eq("it is uploaded once the folder can be read", "new", u.cloudText("/Sync/sub/y.txt"));

        // an unreadable cloud root changes nothing
        Env root = new Env();
        root.cloud.put("/Sync/a.txt", "a");
        root.run();
        root.cloud.unreadableDirs.add("/Sync");
        r = root.run();
        check("unreadable root: nothing deleted", root.file("a.txt").isFile() && r.deletedLocal == 0 && r.errors >= 1);

        // size limit
        Env big = new Env();
        big.cfg.maxFileBytes = 5;
        big.cloud.put("/Sync/small.txt", "tiny");
        big.cloud.put("/Sync/large.txt", "this is too big");
        r = big.run();
        eq("small one downloaded", 1, r.downloaded);
        eq("big one skipped", 1, r.skipped);
        check("skipped file is not on the phone", !big.file("large.txt").exists());
        big.cfg.maxFileBytes = 1000;
        r = big.run();
        eq("raising the limit fetches it", "this is too big", big.read("large.txt"));

        // cancelling
        Env c = new Env();
        c.cloud.put("/Sync/a.txt", "a");
        c.cancel = true;
        r = c.run();
        check("cancelled before starting", r.cancelled && r.downloaded == 0);
        c.cancel = false;
        r = c.run();
        eq("a later run does the work", 1, r.downloaded);

        // a damaged memory file is harmless
        Files.write(c.state.toPath(), "{not json".getBytes());
        quiet("damaged memory", c.run());

        // names
        eq("local names", "a_b_c.txt", SyncEngine.localName("a:b?c.txt"));
        eq("empty local name", "_", SyncEngine.localName(".."));
    }
}
