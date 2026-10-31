package com.cloudbridge.app.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies the phone's photos and videos to a separate folder in a cloud, once each. A remembered list of what was
 * uploaded means a photo that is later deleted from the cloud is NOT uploaded again.
 */
public final class PhotoBackup {
    private PhotoBackup() {
    }

    /** One photo or video on the phone. */
    public static final class Item {
        public final File file;
        public final String bucket;     // the album / folder it lives in, e.g. "Camera"
        public final String name;
        public final long size;
        public final long taken;        // millis

        public Item(File file, String bucket, String name, long size, long taken) {
            this.file = file;
            this.bucket = bucket;
            this.name = name;
            this.size = size;
            this.taken = taken;
        }

        /** The same photo has the same key after a rename of the album or a reinstall. */
        public String key() {
            return sha1(name + "|" + size + "|" + (taken / 1000));
        }
    }

    /** What has been uploaded already. */
    public interface Log {
        boolean has(String key);

        void add(String key) throws IOException;
    }

    public interface Listener {
        default void progress(String text) {
        }

        default boolean cancelled() {
            return false;
        }

        /** One photo could not be uploaded (told at once, so a screen can show what is wrong while it runs). */
        default void failed(String message) {
        }

        /** How long to wait before trying a photo again after a network failure; < 0 gives up. attempt starts at 1. */
        default long retryDelayMillis(int attempt) {
            return -1;
        }
    }

    /** After this many failures in a row the run stops: something is wrong with the cloud, not with one photo. */
    public static final int MAX_FAILURES_IN_A_ROW = 4;

    public static final class Result {
        public int uploaded;
        public int alreadyBackedUp;
        public int failed;
        public long bytes;
        public boolean cancelled;
        /** Set when the run gave up after repeated failures: the last error. */
        public String stoppedBecause;
        public final List<String> messages = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(uploaded).append(" backed up");
            if (failed > 0) sb.append(", ").append(failed).append(" failed");
            if (cancelled) sb.append(" (stopped early)");
            return sb.toString();
        }
    }

    /** Photos not yet backed up. */
    public static List<Item> pending(List<Item> items, Log log) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (i.size > 0 && !log.has(i.key())) out.add(i);
        }
        return out;
    }

    public static Result run(CloudClient cloud, String remoteRoot, List<Item> items, Log log, Listener listener) throws IOException {
        Listener l = listener == null ? new Listener() {
        } : listener;
        Result result = new Result();
        String root = Paths.normalize(remoteRoot);
        if (root.equals("/")) throw new CloudException(400, "Choose a folder for the backup, not the whole cloud.");
        List<Item> todo = pending(items, log);
        result.alreadyBackedUp = items.size() - todo.size();
        Collections.sort(todo, (a, b) -> Long.compare(b.taken, a.taken));   // newest first
        ensureDir(cloud, root);
        Set<String> madeDirs = new HashSet<>();
        int n = 0;
        int inARow = 0;
        for (Item it : todo) {
            if (l.cancelled()) {
                result.cancelled = true;
                break;
            }
            n++;
            l.progress("Backing up " + n + " of " + todo.size() + ": " + it.name);
            try {
                String dir = Paths.join(root, Paths.sanitizeName(it.bucket == null || it.bucket.isEmpty() ? "Other" : it.bucket));
                if (madeDirs.add(dir)) ensureDir(cloud, dir);
                String path = Paths.join(dir, it.name);
                boolean skip = false;
                try {
                    CloudEntry existing = cloud.stat(path);
                    if (!existing.dir && existing.size == it.size) {
                        skip = true;                                 // it is there already (an earlier run was cut off)
                    } else {
                        path = Paths.join(dir, Transfer.uniqueName(cloud, dir, it.name));   // never overwrite another file
                    }
                } catch (CloudException e) {
                    if (!e.isNotFound()) throw e;
                }
                if (!skip) {
                    uploadWithRetries(cloud, path, it, l);
                    result.uploaded++;
                    result.bytes += it.size;
                }
                log.add(it.key());
                inARow = 0;
            } catch (IOException | RuntimeException e) {
                result.failed++;
                String msg = it.name + ": " + message(e);
                if (result.messages.size() < 5) result.messages.add(msg);
                l.failed(msg);
                if (++inARow >= MAX_FAILURES_IN_A_ROW) {
                    result.stoppedBecause = msg;
                    break;
                }
            }
        }
        return result;
    }

    /** A network that comes and goes (no DNS for a moment, a dropped connection) is waited out, not counted as failure. */
    private static void uploadWithRetries(CloudClient cloud, String path, Item it, Listener l) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                cloud.upload(path, it.file, Paths.mimeFor(it.name));
                return;
            } catch (CloudException e) {
                throw e;                                   // TeraBox / the server answered: retrying will not help
            } catch (IOException e) {
                long wait = l.retryDelayMillis(attempt);
                if (wait < 0 || l.cancelled()) throw e;
                l.progress("No connection, trying again in " + Math.max(1, wait / 1000) + " s: " + it.name);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void ensureDir(CloudClient c, String path) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) return;
        try {
            c.stat(p);
            return;
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
        ensureDir(c, Paths.parent(p));
        try {
            c.mkdir(p);
        } catch (CloudException e) {
            c.stat(p);   // somebody else made it meanwhile
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The remembered list, kept in a text file with one key per line; it only ever grows. */
    public static final class FileLog implements Log {
        private final File file;
        private final Set<String> keys = new HashSet<>();

        public FileLog(File file) {
            this.file = file;
            if (file.isFile()) {
                try {
                    for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                        if (!line.trim().isEmpty()) keys.add(line.trim());
                    }
                } catch (IOException ignored) {
                    // an unreadable list behaves like an empty one; photos already in the cloud are recognised by name and size
                }
            }
        }

        @Override
        public synchronized boolean has(String key) {
            return keys.contains(key);
        }

        @Override
        public synchronized void add(String key) throws IOException {
            if (!keys.add(key)) return;
            File parent = file.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
                w.write(key);
                w.newLine();
            }
        }

        public synchronized int size() {
            return keys.size();
        }
    }
}
