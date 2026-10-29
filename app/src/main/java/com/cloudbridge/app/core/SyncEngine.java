package com.cloudbridge.app.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Keeps a folder in a cloud and a folder on the phone the same, in both directions. Real files on the phone are
 * what every app can read, whatever the app, with no root.
 *
 * <p>The engine remembers what both sides looked like after the last run. Comparing the two sides with that memory
 * tells it who changed what: a change on one side is copied to the other, a change on both sides keeps both
 * versions (the phone's version is saved as "name (phone copy).ext"), and a deletion on one side deletes the file
 * on the other. Deletions made on the phone are only copied to the cloud when {@link Config#mirrorDeletes} is on;
 * otherwise the cloud is the master and the file comes back.
 */
public final class SyncEngine {
    private SyncEngine() {
    }

    public static final class Config {
        /** The cloud folder that is kept on the phone. */
        public String remoteRoot = "/CloudBridge";
        /** Copy deletions made on the phone to the cloud. Off: the file is downloaded again. */
        public boolean mirrorDeletes;
        /** Files bigger than this are neither downloaded nor uploaded. */
        public long maxFileBytes = 500L * 1024 * 1024;
    }

    public interface Listener {
        default void progress(String text) {
        }

        default boolean cancelled() {
            return false;
        }

        /** A file on the phone was added, changed or removed (for the media scanner). */
        default void localChanged(File file) {
        }
    }

    public static final class Result {
        public int downloaded;
        public int uploaded;
        public int deletedLocal;
        public int deletedRemote;
        public int conflicts;
        public int skipped;
        public int errors;
        public boolean aborted;
        public boolean cancelled;
        public final List<String> messages = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(downloaded).append(" downloaded, ").append(uploaded).append(" uploaded");
            if (deletedLocal + deletedRemote > 0) {
                sb.append(", ").append(deletedLocal).append(" removed from the phone, ").append(deletedRemote).append(" removed from the cloud");
            }
            if (conflicts > 0) sb.append(", ").append(conflicts).append(" changed on both sides (both versions kept)");
            if (skipped > 0) sb.append(", ").append(skipped).append(" skipped (too big)");
            if (errors > 0) sb.append(", ").append(errors).append(" failed");
            if (aborted) sb.append(". Deletions were NOT applied because they looked like a mistake");
            if (cancelled) sb.append(". Stopped early");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ model

    private static final class Remote {
        String rel;
        String remotePath;
        boolean dir;
        long size;
        long modified;
    }

    /** What both sides looked like after the last run. */
    private static final class Known {
        boolean dir;
        long remoteSize;
        long remoteModified;
        long localSize;
        long localModified;
    }

    private enum Kind {MKDIR_LOCAL, MKDIR_REMOTE, DOWNLOAD, UPLOAD, CONFLICT, DELETE_LOCAL, DELETE_REMOTE, FORGET, RECORD}

    private static final class Action {
        final Kind kind;
        final String rel;
        final boolean dir;

        Action(Kind kind, String rel, boolean dir) {
            this.kind = kind;
            this.rel = rel;
            this.dir = dir;
        }
    }

    private static final String PART = ".cbpart";

    // ------------------------------------------------------------------ public entry point

    public static Result sync(CloudClient cloud, Config cfg, File localRoot, File stateFile, Listener listener) throws IOException {
        Listener l = listener == null ? new Listener() {
        } : listener;
        Result result = new Result();
        String root = Paths.normalize(cfg.remoteRoot);
        if (root.equals("/")) {
            // Syncing a whole cloud is almost never what is wanted and can fill the phone; ask for a folder.
            throw new IOException("Choose a folder in the cloud to sync (not the whole cloud).");
        }
        if (!localRoot.isDirectory() && !localRoot.mkdirs()) {
            throw new IOException("Could not create the folder " + localRoot.getPath());
        }
        Map<String, Known> known = loadState(stateFile);
        Runner run = new Runner(cloud, cfg, root, localRoot, known, l, result);
        try {
            run.ensureRemoteDir(root);
            run.execute();
        } finally {
            saveState(stateFile, known);
        }
        return result;
    }

    // ------------------------------------------------------------------ the work

    private static final class Runner {
        final CloudClient cloud;
        final Config cfg;
        final String root;
        final File localRoot;
        final Map<String, Known> known;
        final Listener listener;
        final Result result;
        final Map<String, Remote> remote = new TreeMap<>();
        final Map<String, File> local = new TreeMap<>();
        final Set<String> unreadable = new HashSet<>();

        Runner(CloudClient cloud, Config cfg, String root, File localRoot, Map<String, Known> known, Listener listener, Result result) {
            this.cloud = cloud;
            this.cfg = cfg;
            this.root = root;
            this.localRoot = localRoot;
            this.known = known;
            this.listener = listener;
            this.result = result;
        }

        void ensureRemoteDir(String path) throws IOException {
            String p = Paths.normalize(path);
            if (p.equals("/")) return;
            try {
                cloud.stat(p);
                return;
            } catch (CloudException e) {
                if (!e.isNotFound()) throw e;
            }
            ensureRemoteDir(Paths.parent(p));
            try {
                cloud.mkdir(p);
            } catch (CloudException e) {
                // someone else made it in the meantime: fine as long as it is there now
                cloud.stat(p);
            }
        }

        File localFile(String rel) {
            File f = localRoot;
            for (String seg : rel.split("/")) f = new File(f, seg);
            return f;
        }

        void fail(String message) {
            SyncEngine.fail(result, message);
        }

        String remotePathOf(String rel) {
            Remote r = remote.get(rel);
            return r != null ? r.remotePath : Paths.join(root, rel);
        }

        // ---- scanning

        void scanRemote(String remoteDir, String relDir) {
            if (listener.cancelled()) return;
            listener.progress("Reading " + (relDir.isEmpty() ? Paths.name(root) : relDir));
            List<CloudEntry> entries;
            try {
                entries = cloud.list(remoteDir);
            } catch (IOException e) {
                unreadable.add(relDir);
                fail("Could not read " + (relDir.isEmpty() ? remoteDir : relDir) + ": " + message(e));
                return;
            }
            for (CloudEntry e : entries) {
                String rel = relDir.isEmpty() ? localName(e.name()) : relDir + "/" + localName(e.name());
                if (remote.containsKey(rel)) {
                    fail("Skipped \"" + e.name() + "\": another item in the same folder has the same name on the phone");
                    continue;
                }
                Remote r = new Remote();
                r.rel = rel;
                r.remotePath = e.path;
                r.dir = e.dir;
                r.size = e.size;
                r.modified = e.modified;
                remote.put(rel, r);
                if (e.dir) scanRemote(e.path, rel);
            }
        }

        void scanLocal(File dir, String relDir) {
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (f.getName().endsWith(PART)) continue;
                String rel = relDir.isEmpty() ? f.getName() : relDir + "/" + f.getName();
                local.put(rel, f);
                if (f.isDirectory()) scanLocal(f, rel);
            }
        }

        boolean underUnreadable(String rel) {
            for (String u : unreadable) {
                if (u.isEmpty() || rel.equals(u) || rel.startsWith(u + "/")) {
                    // the folder itself (u) is known to exist; only what is inside it is unknown
                    if (!u.isEmpty() && rel.equals(u)) continue;
                    return true;
                }
            }
            return false;
        }

        // ---- planning

        boolean sameRemote(Remote r, Known k) {
            return r.size == k.remoteSize && r.modified == k.remoteModified;
        }

        boolean sameLocal(File f, Known k) {
            return f.length() == k.localSize && f.lastModified() == k.localModified;
        }

        List<Action> plan() {
            List<Action> actions = new ArrayList<>();
            Set<String> keys = new TreeSet<>();
            keys.addAll(remote.keySet());
            keys.addAll(local.keySet());
            keys.addAll(known.keySet());
            for (String rel : keys) {
                if (underUnreadable(rel)) continue;
                Remote r = remote.get(rel);
                File l = local.get(rel);
                Known k = known.get(rel);
                boolean rDir = r != null && r.dir;
                boolean lDir = l != null && l.isDirectory();
                if (r != null && l != null && rDir != lDir) {
                    fail("\"" + rel + "\" is a folder on one side and a file on the other; left alone");
                    continue;
                }
                if (r != null && k != null && k.dir != rDir || l != null && k != null && k.dir != lDir) {
                    known.remove(rel);      // the kind of thing changed since the last run: treat it as new
                    k = null;
                }
                boolean isDir = r != null ? rDir : l != null ? lDir : k != null && k.dir;
                if (isDir) planDir(actions, rel, r, l, k);
                else planFile(actions, rel, r, l, k);
            }
            return actions;
        }

        void planDir(List<Action> a, String rel, Remote r, File l, Known k) {
            if (r != null && l != null) {
                if (k == null) a.add(new Action(Kind.RECORD, rel, true));
            } else if (r != null) {                                   // only in the cloud
                if (k != null && cfg.mirrorDeletes) a.add(new Action(Kind.DELETE_REMOTE, rel, true));
                else a.add(new Action(Kind.MKDIR_LOCAL, rel, true));
            } else if (l != null) {                                   // only on the phone
                if (k != null) a.add(new Action(Kind.DELETE_LOCAL, rel, true));   // removed in the cloud
                else a.add(new Action(Kind.MKDIR_REMOTE, rel, true));
            } else if (k != null) {
                a.add(new Action(Kind.FORGET, rel, true));
            }
        }

        void planFile(List<Action> a, String rel, Remote r, File l, Known k) {
            if (r != null && l == null) {
                if (k == null) {
                    a.add(new Action(Kind.DOWNLOAD, rel, false));
                } else if (!sameRemote(r, k)) {
                    a.add(new Action(Kind.DOWNLOAD, rel, false));      // changed in the cloud since it was deleted here
                } else if (cfg.mirrorDeletes) {
                    a.add(new Action(Kind.DELETE_REMOTE, rel, false));  // deleted on the phone
                } else {
                    a.add(new Action(Kind.DOWNLOAD, rel, false));      // the cloud is the master: bring it back
                }
            } else if (r == null && l != null) {
                if (k == null) a.add(new Action(Kind.UPLOAD, rel, false));
                else if (sameLocal(l, k)) a.add(new Action(Kind.DELETE_LOCAL, rel, false));   // deleted in the cloud
                else a.add(new Action(Kind.UPLOAD, rel, false));                              // edited here: keep it
            } else if (r != null) {
                if (k == null) {
                    if (r.size >= 0 && r.size == l.length()) a.add(new Action(Kind.RECORD, rel, false));
                    else a.add(new Action(Kind.CONFLICT, rel, false));
                } else {
                    boolean rc = !sameRemote(r, k);
                    boolean lc = !sameLocal(l, k);
                    if (rc && lc) a.add(new Action(Kind.CONFLICT, rel, false));
                    else if (rc) a.add(new Action(Kind.DOWNLOAD, rel, false));
                    else if (lc) a.add(new Action(Kind.UPLOAD, rel, false));
                }
            } else if (k != null) {
                a.add(new Action(Kind.FORGET, rel, false));
            }
        }

        // ---- execution

        void execute() throws IOException {
            scanRemote(root, "");
            if (listener.cancelled()) {
                result.cancelled = true;
                return;
            }
            scanLocal(localRoot, "");
            List<Action> actions = plan();

            int deletions = 0;
            for (Action a : actions) {
                if (a.kind == Kind.DELETE_LOCAL || a.kind == Kind.DELETE_REMOTE) deletions++;
            }
            boolean skipDeletes = deletions > 10 && deletions * 2 > Math.max(1, known.size());
            if (skipDeletes) {
                result.aborted = true;
                result.messages.add("Not deleting " + deletions + " items: that is more than half of everything synced, which usually means "
                        + "something went wrong (for example the wrong cloud folder or a failed sign-in). Check it and sync again.");
            }

            List<Action> removals = new ArrayList<>();
            int done = 0;
            for (Action a : actions) {
                if (listener.cancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (a.kind == Kind.DELETE_LOCAL || a.kind == Kind.DELETE_REMOTE) {
                    if (!skipDeletes) removals.add(a);
                    continue;
                }
                listener.progress(describe(a));
                try {
                    perform(a);
                } catch (IOException | RuntimeException e) {
                    fail(describe(a) + " failed: " + message(e));
                }
                done++;
            }
            if (!result.cancelled) {
                Collections.reverse(removals);   // children before their folders
                for (Action a : removals) {
                    if (listener.cancelled()) {
                        result.cancelled = true;
                        break;
                    }
                    listener.progress(describe(a));
                    try {
                        perform(a);
                    } catch (IOException | RuntimeException e) {
                        fail(describe(a) + " failed: " + message(e));
                    }
                }
            }
        }

        String describe(Action a) {
            switch (a.kind) {
                case DOWNLOAD:
                    return "Downloading " + a.rel;
                case UPLOAD:
                    return "Uploading " + a.rel;
                case CONFLICT:
                    return "Resolving " + a.rel;
                case DELETE_LOCAL:
                    return "Removing " + a.rel + " from the phone";
                case DELETE_REMOTE:
                    return "Removing " + a.rel + " from the cloud";
                default:
                    return "Preparing " + a.rel;
            }
        }

        void perform(Action a) throws IOException {
            switch (a.kind) {
                case MKDIR_LOCAL: {
                    File d = localFile(a.rel);
                    if (!d.isDirectory() && !d.mkdirs()) throw new IOException("could not create the folder");
                    Remote r = remote.get(a.rel);
                    remember(a.rel, true, r, d);
                    listener.localChanged(d);
                    break;
                }
                case MKDIR_REMOTE:
                    ensureRemoteDir(Paths.join(root, a.rel));
                    remember(a.rel, true, null, localFile(a.rel));
                    break;
                case DOWNLOAD:
                    download(a.rel);
                    break;
                case UPLOAD:
                    upload(a.rel, a.rel);
                    break;
                case CONFLICT:
                    conflict(a.rel);
                    break;
                case RECORD:
                    remember(a.rel, a.dir, remote.get(a.rel), localFile(a.rel));
                    break;
                case FORGET:
                    known.remove(a.rel);
                    break;
                case DELETE_LOCAL: {
                    File f = localFile(a.rel);
                    if (a.dir) {
                        File[] kids = f.listFiles();
                        if (kids == null || kids.length == 0) {
                            if (f.delete()) result.deletedLocal++;
                            known.remove(a.rel);
                            listener.localChanged(f);
                        }   // something new is inside: keep the folder, its files are handled on their own
                    } else {
                        if (f.delete() || !f.exists()) {
                            result.deletedLocal++;
                            known.remove(a.rel);
                            listener.localChanged(f);
                        } else {
                            throw new IOException("could not delete the file");
                        }
                    }
                    break;
                }
                case DELETE_REMOTE: {
                    try {
                        if (a.dir && !cloud.list(remotePathOf(a.rel)).isEmpty()) break;   // something new is inside: keep it
                        cloud.delete(remotePathOf(a.rel), a.dir);
                    } catch (CloudException e) {
                        if (!e.isNotFound()) throw e;
                    }
                    result.deletedRemote++;
                    known.remove(a.rel);
                    break;
                }
                default:
                    break;
            }
        }

        void remember(String rel, boolean dir, Remote r, File l) {
            Known k = new Known();
            k.dir = dir;
            if (r != null) {
                k.remoteSize = r.size;
                k.remoteModified = r.modified;
            }
            if (l != null && l.exists()) {
                k.localSize = l.length();
                k.localModified = l.lastModified();
            }
            known.put(rel, k);
        }

        void download(String rel) throws IOException {
            Remote r = remote.get(rel);
            if (r.size > cfg.maxFileBytes) {
                result.skipped++;
                return;
            }
            File target = localFile(rel);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("could not create the folder");
            File part = new File(parent, target.getName() + PART);
            try {
                cloud.download(r.remotePath, part);
                if (r.size >= 0 && part.length() != r.size) {
                    throw new IOException("the downloaded file has the wrong size (" + part.length() + " instead of " + r.size + ")");
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            if (r.modified > 0) {
                //noinspection ResultOfMethodCallIgnored
                target.setLastModified(r.modified);
            }
            remember(rel, false, r, target);
            result.downloaded++;
            listener.localChanged(target);
        }

        /** Uploads the phone's file {@code rel} to the cloud as {@code remoteRel}. */
        void upload(String rel, String remoteRel) throws IOException {
            File f = localFile(rel);
            if (f.length() > cfg.maxFileBytes) {
                result.skipped++;
                return;
            }
            String path = Paths.join(root, remoteRel);
            ensureRemoteDir(Paths.parent(path));
            cloud.upload(path, f, Paths.mimeFor(f.getName()));
            Remote r = new Remote();
            r.remotePath = path;
            r.size = f.length();
            r.modified = 0;
            try {
                CloudEntry e = cloud.stat(path);
                r.size = e.size;
                r.modified = e.modified;
            } catch (IOException ignored) {
                // the next run will see the real values and may upload once more; harmless
            }
            remember(rel, false, r, f);
            result.uploaded++;
        }

        void conflict(String rel) throws IOException {
            File mine = localFile(rel);
            File copy = uniqueCopy(mine);
            String copyRel = rel.substring(0, rel.length() - mine.getName().length()) + copy.getName();
            if (!mine.renameTo(copy)) throw new IOException("could not keep the phone's version");
            listener.localChanged(mine);
            download(rel);
            if (copy.exists()) {
                upload(copyRel, copyRel);
                listener.localChanged(copy);
            }
            result.conflicts++;
        }
    }

    private static File uniqueCopy(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File candidate = new File(f.getParentFile(), base + " (phone copy)" + ext);
        for (int i = 2; candidate.exists(); i++) candidate = new File(f.getParentFile(), base + " (phone copy " + i + ")" + ext);
        return candidate;
    }

    /** A cloud file name made safe to use as a file name on the phone. */
    public static String localName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        String s = sb.toString();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "_";
        if (s.endsWith(PART)) s = s + "_";
        return s;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ remembered state

    private static Map<String, Known> loadState(File file) {
        Map<String, Known> out = new TreeMap<>();
        if (file == null || !file.isFile()) return out;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = Json.parseObject(text, "sync state");
            Map<String, Object> files = Json.obj(root, "files");
            if (files == null) return out;
            for (Map.Entry<String, Object> e : files.entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                Known k = new Known();
                k.dir = Json.num(v, "d", 0) == 1;
                k.remoteSize = Json.num(v, "rs", -1);
                k.remoteModified = Json.num(v, "rm", 0);
                k.localSize = Json.num(v, "ls", -1);
                k.localModified = Json.num(v, "lm", 0);
                out.put(e.getKey(), k);
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable memory is treated as no memory: the next run compares both sides from scratch
            out.clear();
        }
        return out;
    }

    private static void saveState(File file, Map<String, Known> known) throws IOException {
        if (file == null) return;
        StringBuilder sb = new StringBuilder("{\"v\":1,\"files\":{");
        boolean first = true;
        for (Map.Entry<String, Known> e : known.entrySet()) {
            Known k = e.getValue();
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(e.getKey())).append(":{\"d\":").append(k.dir ? 1 : 0)
                    .append(",\"rs\":").append(k.remoteSize).append(",\"rm\":").append(k.remoteModified)
                    .append(",\"ls\":").append(k.localSize).append(",\"lm\":").append(k.localModified).append('}');
        }
        sb.append("}}");
        File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void fail(Result r, String message) {
        r.errors++;
        r.messages.add(message);
    }
}
