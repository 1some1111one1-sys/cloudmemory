package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Copy, move and delete between any two places: the phone, a folder, a cloud. */
public final class Transfer {
    private Transfer() {
    }

    public interface Progress {
        default void item(String name) {
        }

        default boolean cancelled() {
            return false;
        }
    }

    public static final class Result {
        public int done;
        public int failed;
        public boolean cancelled;
        public final List<String> errors = new ArrayList<>();
        /** The destination paths that were created (or the new paths after a move). */
        public final List<String> created = new ArrayList<>();

        public String summary(String verb) {
            StringBuilder sb = new StringBuilder();
            sb.append(done).append(done == 1 ? " item " : " items ").append(verb);
            if (failed > 0) sb.append(", ").append(failed).append(" failed");
            if (cancelled) sb.append(" (stopped early)");
            return sb.toString();
        }
    }

    /** "name.ext" -> "name (1).ext" -> "name (2).ext" ... the first name that is free in {@code dir}. */
    public static String uniqueName(CloudClient c, String dir, String name) throws IOException {
        Set<String> existing = new HashSet<>();
        try {
            for (CloudEntry e : c.list(dir)) existing.add(e.name());
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
        return uniqueAmong(existing, name);
    }

    static String uniqueAmong(Set<String> existing, String name) {
        if (!existing.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!existing.contains(candidate)) return candidate;
        }
        return name + "-" + System.nanoTime();
    }

    /**
     * Copies or moves the items to {@code dstDir}. Names that already exist there get " (1)" added; nothing is
     * overwritten. A move deletes the original only after the copy of that item completely succeeded.
     */
    public static Result copy(CloudClient from, List<String> srcPaths, CloudClient to, String dstDir, boolean move,
                              File tmpDir, Progress progress) {
        Progress p = progress == null ? new Progress() {
        } : progress;
        Result result = new Result();
        String dir = Paths.normalize(dstDir);
        Set<String> existing = new HashSet<>();
        try {
            for (CloudEntry e : to.list(dir)) existing.add(e.name());
        } catch (IOException e) {
            result.failed = srcPaths.size();
            result.errors.add("Could not read the destination folder: " + message(e));
            return result;
        }
        for (String src : srcPaths) {
            if (p.cancelled()) {
                result.cancelled = true;
                break;
            }
            String name = Paths.name(Paths.normalize(src));
            try {
                CloudEntry e = from.stat(src);
                boolean sameClient = from == to;
                if (sameClient && e.dir && (Paths.normalize(src).equals(dir) || Paths.isUnder(src, dir))) {
                    throw new IOException("A folder cannot be copied or moved into itself");
                }
                if (move && sameClient && Paths.parent(src).equals(dir)) {
                    result.done++;                        // moving into the folder it is already in
                    continue;
                }
                String target = uniqueAmong(existing, name);
                String dst = Paths.join(dir, target);
                p.item(name);
                if (move && sameClient) {
                    from.move(src, dst, e.dir);
                } else {
                    copyTree(from, src, e, to, dst, tmpDir, p, result);
                    if (move && !p.cancelled()) from.delete(src, e.dir);
                }
                existing.add(target);
                result.created.add(dst);
                result.done++;
            } catch (IOException | RuntimeException ex) {
                result.failed++;
                result.errors.add("\"" + name + "\": " + message(ex));
            }
        }
        return result;
    }

    private static void copyTree(CloudClient from, String src, CloudEntry e, CloudClient to, String dst, File tmpDir,
                                 Progress p, Result result) throws IOException {
        if (p.cancelled()) throw new IOException("Stopped");
        if (e.dir) {
            to.mkdir(dst);
            for (CloudEntry child : from.list(src)) {
                copyTree(from, child.path, child, to, Paths.join(dst, child.name()), tmpDir, p, result);
            }
            return;
        }
        p.item(e.name());
        if (from instanceof LocalClient) {
            File f = ((LocalClient) from).fileOf(src);
            to.upload(dst, f, Paths.mimeFor(e.name()));
        } else if (to instanceof LocalClient) {
            File f = ((LocalClient) to).fileOf(dst);
            File part = new File(f.getParentFile(), "." + f.getName() + ".cbpart");
            try {
                from.download(src, part);
                if (!part.renameTo(f)) throw new IOException("Could not save \"" + f.getName() + "\"");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            if (e.modified > 0) {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(e.modified);
            }
        } else {
            File tmp = File.createTempFile("xfer_", ".tmp", tmpDir);
            try {
                from.download(src, tmp);
                to.upload(dst, tmp, Paths.mimeFor(e.name()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    public static Result delete(CloudClient c, List<String> paths, Progress progress) {
        Progress p = progress == null ? new Progress() {
        } : progress;
        Result result = new Result();
        for (String path : paths) {
            if (p.cancelled()) {
                result.cancelled = true;
                break;
            }
            String name = Paths.name(Paths.normalize(path));
            try {
                p.item(name);
                CloudEntry e = c.stat(path);
                c.delete(path, e.dir);
                result.done++;
            } catch (IOException | RuntimeException ex) {
                result.failed++;
                result.errors.add("\"" + name + "\": " + message(ex));
            }
        }
        return result;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
