package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Cloud files downloaded to the phone once and reused until they change in the cloud. Shared by the bridges. */
final class DownloadCache {
    private static final long LIMIT_BYTES = 512L * 1024 * 1024;

    private final File dir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    DownloadCache(File dir) {
        this.dir = dir;
    }

    void ensureDir() {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** The cloud file as a local file. The name includes size and date, so a changed file is fetched again. */
    File get(String account, String path, CloudEntry e, CloudClient c) throws IOException {
        ensureDir();
        File f = new File(dir, sha1(account + "|" + path) + "_" + e.size + "_" + e.modified);
        Object lock = locks.computeIfAbsent(f.getName(), k -> new Object());
        synchronized (lock) {
            if (!f.isFile()) {
                File part = new File(dir, f.getName() + ".part" + System.nanoTime());
                try {
                    c.download(path, part);
                    if (!part.renameTo(f)) throw new IOException("Could not store the downloaded file");
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                prune();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   // most recently used survives pruning
        }
        return f;
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= LIMIT_BYTES) return;
        Arrays.sort(files, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        long recent = System.currentTimeMillis() - 60_000;
        for (File f : files) {
            if (total <= LIMIT_BYTES) break;
            if (f.lastModified() > recent) continue;   // probably being read right now
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
