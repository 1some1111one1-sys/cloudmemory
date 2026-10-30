package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.cloudbridge.app.core.TestMain.check;
import static com.cloudbridge.app.core.TestMain.eq;
import static com.cloudbridge.app.core.TestMain.expectFail;
import static com.cloudbridge.app.core.TestMain.run;

/** The phone as a place, copy / move / delete between places, and the photo backup. */
final class TestFileOps {
    private TestFileOps() {
    }

    static void runAll() {
        run("local client", TestFileOps::testLocal);
        run("transfer", TestFileOps::testTransfer);
        run("photo backup", TestFileOps::testBackup);
    }

    private static File temp(String name) throws IOException {
        return Files.createTempDirectory(name).toFile();
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> names(List<CloudEntry> l) {
        List<String> n = new ArrayList<>();
        for (CloudEntry e : l) n.add(e.name());
        Collections.sort(n);
        return n;
    }

    private static void testLocal() throws Exception {
        File root = temp("local");
        LocalClient c = new LocalClient(root, false);
        write(new File(root, "a.txt"), "aaa");
        write(new File(root, ".hidden"), "h");
        write(new File(root, "dir/b.txt"), "bbb");
        eq("hidden files are not listed", Arrays.asList("a.txt", "dir"), names(c.list("/")));
        eq("hidden files are listed when asked", Arrays.asList(".hidden", "a.txt", "dir"), names(new LocalClient(root, true).list("/")));
        eq("count children", 2, c.countChildren("/"));
        eq("stat file", 3L, c.stat("/a.txt").size);
        check("stat folder", c.stat("/dir").dir);
        check("stat missing", expectFail("missing", () -> c.stat("/nope")) != null);
        check("escape attempts stay inside", c.fileOf("/../../etc/passwd").getPath().startsWith(root.getPath()));

        c.mkdir("/new");
        check("mkdir", new File(root, "new").isDirectory());
        check("mkdir twice", expectFail("mkdir twice", () -> c.mkdir("/new")) != null);
        check("mkdir without parent", expectFail("no parent", () -> c.mkdir("/x/y")) != null);

        File src = new File(temp("src"), "up.bin");
        write(src, "uploaded");
        c.upload("/new/up.bin", src, "application/octet-stream");
        eq("upload copies", "uploaded", read(new File(root, "new/up.bin")));
        check("upload without folder", expectFail("no folder", () -> c.upload("/zz/up.bin", src, "x")) != null);
        File out = new File(temp("out"), "down.bin");
        c.download("/new/up.bin", out);
        eq("download copies", "uploaded", read(out));

        c.move("/new/up.bin", "/moved.bin", false);
        check("move", new File(root, "moved.bin").isFile() && !new File(root, "new/up.bin").exists());
        check("move onto existing", expectFail("exists", () -> c.move("/moved.bin", "/a.txt", false)) != null);
        c.delete("/dir", true);
        check("delete a folder with content", !new File(root, "dir").exists());
        check("delete the root is refused", expectFail("root", () -> c.delete("/", true)) != null);
        CloudQuota q = c.quota();
        check("quota of the phone", q != null && q.total > 0 && q.free >= 0 && q.used >= 0);
    }

    private static void testTransfer() throws Exception {
        File tmp = temp("tmp");
        File phone = temp("phone");
        LocalClient local = new LocalClient(phone, false);
        TestWebDavServer.MemoryCloud cloud = new TestWebDavServer.MemoryCloud();
        cloud.put("/Docs", (String) null);
        write(new File(phone, "photo.jpg"), "pixels");
        write(new File(phone, "album/one.jpg"), "1");
        write(new File(phone, "album/sub/two.jpg"), "22");
        new File(phone, "empty").mkdirs();

        // phone -> cloud: copy a file and a folder tree
        Transfer.Result r = Transfer.copy(local, Arrays.asList("/photo.jpg", "/album", "/empty"), cloud, "/Docs", false, tmp, null);
        eq("copied 3 items", 3, r.done);
        eq("no failures", 0, r.failed);
        eq("file copied", "pixels", new String(cloud.files.get("/Docs/photo.jpg")));
        eq("tree copied", "22", new String(cloud.files.get("/Docs/album/sub/two.jpg")));
        check("empty folder copied", cloud.files.containsKey("/Docs/empty") && cloud.files.get("/Docs/empty") == null);
        check("a copy leaves the originals", new File(phone, "photo.jpg").exists() && new File(phone, "album/one.jpg").exists());

        // copying again never overwrites: names get a number
        r = Transfer.copy(local, Arrays.asList("/photo.jpg", "/album"), cloud, "/Docs", false, tmp, null);
        check("second copy gets new names", cloud.files.containsKey("/Docs/photo (1).jpg") && cloud.files.containsKey("/Docs/album (1)/one.jpg"));
        eq("the original is untouched", "pixels", new String(cloud.files.get("/Docs/photo.jpg")));

        // phone -> cloud: move deletes the originals
        write(new File(phone, "move/me.txt"), "mine");
        r = Transfer.copy(local, Arrays.asList("/move"), cloud, "/Docs", true, tmp, null);
        eq("moved", 1, r.done);
        eq("moved content", "mine", new String(cloud.files.get("/Docs/move/me.txt")));
        check("original is gone after a move", !new File(phone, "move").exists());

        // cloud -> phone: download (copy) keeps the cloud copy
        File downloads = new File(phone, "Download");
        downloads.mkdirs();
        r = Transfer.copy(cloud, Arrays.asList("/Docs/photo.jpg", "/Docs/album"), local, "/Download", false, tmp, null);
        eq("downloaded 2", 2, r.done);
        eq("downloaded file", "pixels", read(new File(downloads, "photo.jpg")));
        eq("downloaded folder content", "22", read(new File(downloads, "album/sub/two.jpg")));
        check("modified time is kept", new File(downloads, "photo.jpg").lastModified() == cloud.mtimes.get("/Docs/photo.jpg"));
        check("the cloud copy stays", cloud.files.containsKey("/Docs/photo.jpg"));
        check("no partial files are left behind", names(local.list("/Download")).equals(Arrays.asList("album", "photo.jpg")));

        // cloud -> phone move
        r = Transfer.copy(cloud, Arrays.asList("/Docs/move"), local, "/Download", true, tmp, null);
        eq("move to phone", "mine", read(new File(downloads, "move/me.txt")));
        check("moved out of the cloud", !cloud.files.containsKey("/Docs/move") && !cloud.files.containsKey("/Docs/move/me.txt"));

        // folder -> folder on the phone (a rename, no copy)
        File a = new File(phone, "A");
        File b = new File(phone, "B");
        write(new File(a, "x.txt"), "x");
        b.mkdirs();
        r = Transfer.copy(local, Arrays.asList("/A"), local, "/B", true, tmp, null);
        eq("moved within the phone", "x", read(new File(b, "A/x.txt")));
        check("old place empty", !a.exists());
        // into itself, and into the folder it is already in
        r = Transfer.copy(local, Arrays.asList("/B"), local, "/B/A", true, tmp, null);
        check("a folder cannot go into itself", r.failed == 1 && r.errors.get(0).contains("into itself"));
        r = Transfer.copy(local, Arrays.asList("/B/A"), local, "/B", true, tmp, null);
        check("moving into the same folder changes nothing", r.done == 1 && new File(b, "A/x.txt").isFile());
        // copy within the same folder makes a numbered copy
        r = Transfer.copy(local, Arrays.asList("/B/A"), local, "/B", false, tmp, null);
        check("copy into the same folder gets a new name", new File(b, "A (1)/x.txt").isFile());

        // cloud -> another cloud
        TestWebDavServer.MemoryCloud other = new TestWebDavServer.MemoryCloud();
        r = Transfer.copy(cloud, Arrays.asList("/Docs/album"), other, "/", true, tmp, null);
        check("moved to another cloud", other.files.containsKey("/album/sub/two.jpg") && !cloud.files.containsKey("/Docs/album"));
        // same cloud move uses the cloud's own move
        cloud.put("/Docs/target", (String) null);
        cloud.put("/Docs/loose.txt", "l");
        r = Transfer.copy(cloud, Arrays.asList("/Docs/loose.txt"), cloud, "/Docs/target", true, tmp, null);
        check("move inside one cloud", cloud.files.containsKey("/Docs/target/loose.txt") && !cloud.files.containsKey("/Docs/loose.txt"));

        // one failing item does not stop the others, and a failed move keeps the original
        TestWebDavServer.MemoryCloud flaky = new TestWebDavServer.MemoryCloud();
        write(new File(phone, "ok.txt"), "ok");
        write(new File(phone, "bad.txt"), "bad");
        TestWebDavServer.MemoryCloud picky = new TestWebDavServer.MemoryCloud() {
            @Override
            public void upload(String path, File src, String mime) throws IOException {
                if (path.endsWith("bad.txt")) throw new CloudException(502, "the cloud said no");
                super.upload(path, src, mime);
            }
        };
        r = Transfer.copy(local, Arrays.asList("/bad.txt", "/ok.txt"), picky, "/", true, tmp, null);
        eq("the good one went through", 1, r.done);
        eq("the bad one is reported", 1, r.failed);
        check("error text says why", r.errors.get(0).contains("the cloud said no"));
        check("failed move keeps the original", new File(phone, "bad.txt").exists() && !new File(phone, "ok.txt").exists());

        // cancel
        Transfer.Progress stop = new Transfer.Progress() {
            @Override
            public boolean cancelled() {
                return true;
            }
        };
        r = Transfer.copy(local, Arrays.asList("/bad.txt"), flaky, "/", false, tmp, stop);
        check("cancelled", r.cancelled && r.done == 0);

        // delete on the phone and in the cloud
        r = Transfer.delete(local, Arrays.asList("/Download/album", "/Download/photo.jpg", "/nothing"), null);
        eq("deleted two", 2, r.done);
        eq("one was missing", 1, r.failed);
        check("deleted", !new File(downloads, "album").exists() && !new File(downloads, "photo.jpg").exists());
        r = Transfer.delete(cloud, Arrays.asList("/Docs/target"), null);
        check("deleted in the cloud", r.done == 1 && !cloud.files.containsKey("/Docs/target/loose.txt"));

        // unique names
        eq("unique name", "a (1).txt", Transfer.uniqueAmong(new java.util.HashSet<>(Arrays.asList("a.txt")), "a.txt"));
        eq("unique name, second", "a (2).txt", Transfer.uniqueAmong(new java.util.HashSet<>(Arrays.asList("a.txt", "a (1).txt")), "a.txt"));
        eq("unique name without extension", "d (1)", Transfer.uniqueAmong(new java.util.HashSet<>(Arrays.asList("d")), "d"));
        eq("a free name is kept", "z.txt", Transfer.uniqueAmong(new java.util.HashSet<>(), "z.txt"));
    }

    private static PhotoBackup.Item photo(File dir, String bucket, String name, String content, long taken) throws IOException {
        File f = new File(new File(dir, bucket), name);
        write(f, content);
        return new PhotoBackup.Item(f, bucket, name, f.length(), taken);
    }

    private static void testBackup() throws Exception {
        File dir = temp("photos");
        File logFile = new File(temp("log"), "backup.txt");
        TestWebDavServer.MemoryCloud cloud = new TestWebDavServer.MemoryCloud();
        PhotoBackup.FileLog log = new PhotoBackup.FileLog(logFile);
        List<PhotoBackup.Item> items = new ArrayList<>();
        items.add(photo(dir, "Camera", "IMG_1.jpg", "one", 1_700_000_001_000L));
        items.add(photo(dir, "Camera", "IMG_2.jpg", "two!", 1_700_000_002_000L));
        items.add(photo(dir, "WhatsApp Images", "IMG_1.jpg", "different", 1_700_000_003_000L));
        items.add(photo(dir, "Camera", "VID_1.mp4", "video data", 1_700_000_004_000L));

        PhotoBackup.Result r = PhotoBackup.run(cloud, "/Photo backup", items, log, null);
        eq("everything uploaded", 4, r.uploaded);
        eq("no failures", 0, r.failed);
        check("separate folder per album", cloud.files.containsKey("/Photo backup/Camera/IMG_1.jpg")
                && cloud.files.containsKey("/Photo backup/WhatsApp Images/IMG_1.jpg")
                && cloud.files.containsKey("/Photo backup/Camera/VID_1.mp4"));
        eq("content", "two!", new String(cloud.files.get("/Photo backup/Camera/IMG_2.jpg")));

        // a second run has nothing to do
        r = PhotoBackup.run(cloud, "/Photo backup", items, log, null);
        eq("nothing uploaded twice", 0, r.uploaded);
        eq("all recognised", 4, r.alreadyBackedUp);

        // deleted from the cloud: it must NOT come back
        cloud.files.remove("/Photo backup/Camera/IMG_2.jpg");
        cloud.files.remove("/Photo backup/Camera/VID_1.mp4");
        r = PhotoBackup.run(cloud, "/Photo backup", items, new PhotoBackup.FileLog(logFile), null);   // a fresh run of the app
        eq("deleted photos are not uploaded again", 0, r.uploaded);
        check("and they are still gone from the cloud", !cloud.files.containsKey("/Photo backup/Camera/IMG_2.jpg"));
        eq("pending list is empty", 0, PhotoBackup.pending(items, new PhotoBackup.FileLog(logFile)).size());

        // a new photo is uploaded; a different photo with a taken name is not overwritten
        List<PhotoBackup.Item> more = new ArrayList<>(items);
        more.add(photo(dir, "Camera", "IMG_9.jpg", "nine", 1_700_000_009_000L));
        cloud.put("/Photo backup/Camera/IMG_5.jpg", "someone else's file");
        more.add(photo(dir, "Camera", "IMG_5.jpg", "my photo", 1_700_000_010_000L));
        r = PhotoBackup.run(cloud, "/Photo backup", more, log, null);
        eq("only the new ones", 2, r.uploaded);
        eq("existing different file kept", "someone else's file", new String(cloud.files.get("/Photo backup/Camera/IMG_5.jpg")));
        eq("mine gets another name", "my photo", new String(cloud.files.get("/Photo backup/Camera/IMG_5 (1).jpg")));

        // a file already in the cloud with the same size (an interrupted earlier run) is not uploaded again
        PhotoBackup.Item resumed = photo(dir, "Camera", "IMG_7.jpg", "seven", 1_700_000_011_000L);
        cloud.put("/Photo backup/Camera/IMG_7.jpg", "seven");
        r = PhotoBackup.run(cloud, "/Photo backup", Arrays.asList(resumed), log, null);
        eq("recognised by name and size", 0, r.uploaded);
        eq("and remembered", 0, PhotoBackup.pending(Arrays.asList(resumed), log).size());

        // empty files, failures and cancelling
        PhotoBackup.Item empty = photo(dir, "Camera", "EMPTY.jpg", "", 1_700_000_012_000L);
        PhotoBackup.Item good = photo(dir, "Camera", "GOOD.jpg", "good", 1_700_000_013_000L);
        PhotoBackup.Item bad = photo(dir, "Camera", "BAD.jpg", "bad", 1_700_000_014_000L);
        TestWebDavServer.MemoryCloud picky = new TestWebDavServer.MemoryCloud() {
            @Override
            public void upload(String path, File src, String mime) throws IOException {
                if (path.endsWith("BAD.jpg")) throw new CloudException(502, "no");
                super.upload(path, src, mime);
            }
        };
        PhotoBackup.FileLog log2 = new PhotoBackup.FileLog(new File(temp("log2"), "b.txt"));
        r = PhotoBackup.run(picky, "/Backup", Arrays.asList(empty, good, bad), log2, null);
        eq("good one uploaded", 1, r.uploaded);
        eq("bad one counted", 1, r.failed);
        check("a failed photo is retried next time, an empty one never", PhotoBackup.pending(Arrays.asList(empty, good, bad), log2).size() == 1
                && PhotoBackup.pending(Arrays.asList(empty, good, bad), log2).get(0).name.equals("BAD.jpg"));
        PhotoBackup.Result c = PhotoBackup.run(new TestWebDavServer.MemoryCloud(), "/B", Arrays.asList(good), new PhotoBackup.FileLog(new File(temp("log3"), "b.txt")),
                new PhotoBackup.Listener() {
                    @Override
                    public boolean cancelled() {
                        return true;
                    }
                });
        check("cancelled", c.cancelled && c.uploaded == 0);
        check("the whole cloud is refused", expectFail("root", () -> PhotoBackup.run(cloud, "/", items, log, null)) != null);
        check("the list survives a restart", new PhotoBackup.FileLog(logFile).size() >= 6);
    }
}
