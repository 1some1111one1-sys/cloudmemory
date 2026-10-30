package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The phone's own storage as a {@link CloudClient}, so that copying between the phone and a cloud, or between two
 * folders, is the same operation as between two clouds. Paths are relative to {@code root} ("/" is the root).
 */
public final class LocalClient implements CloudClient {
    private final File root;
    private final boolean showHidden;

    public LocalClient(File root, boolean showHidden) {
        this.root = root;
        this.showHidden = showHidden;
    }

    public File root() {
        return root;
    }

    /** The real file behind a path. Never leaves the root, whatever the path says. */
    public File fileOf(String path) {
        String p = Paths.normalize(path);
        return p.equals("/") ? root : new File(root, p.substring(1));
    }

    private CloudEntry entry(String path, File f) {
        return new CloudEntry(path, f.isDirectory(), f.isDirectory() ? -1 : f.length(), f.lastModified());
    }

    @Override
    public List<CloudEntry> list(String dirPath) throws IOException {
        String dir = Paths.normalize(dirPath);
        File d = fileOf(dir);
        if (!d.exists()) throw new CloudException(404, "Not found: " + dir);
        if (!d.isDirectory()) throw new CloudException(400, "Not a folder: " + dir);
        File[] kids = d.listFiles();
        if (kids == null) throw new CloudException(403, "This folder cannot be read (is \"All files access\" allowed?)");
        Arrays.sort(kids);
        List<CloudEntry> out = new ArrayList<>();
        for (File k : kids) {
            if (!showHidden && k.getName().startsWith(".")) continue;
            out.add(entry(Paths.join(dir, k.getName()), k));
        }
        return out;
    }

    /** How many items a folder holds (hidden ones are not counted unless they are shown), or -1 when unreadable. */
    public int countChildren(String dirPath) {
        File[] kids = fileOf(dirPath).listFiles();
        if (kids == null) return -1;
        int n = 0;
        for (File k : kids) {
            if (showHidden || !k.getName().startsWith(".")) n++;
        }
        return n;
    }

    @Override
    public CloudEntry stat(String path) throws IOException {
        String p = Paths.normalize(path);
        File f = fileOf(p);
        if (!f.exists()) throw new CloudException(404, "Not found: " + p);
        return entry(p, f);
    }

    @Override
    public void download(String path, File dest) throws IOException {
        File f = fileOf(path);
        if (!f.isFile()) throw new CloudException(404, "Not found: " + path);
        Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void upload(String path, File src, String mime) throws IOException {
        File dest = fileOf(path);
        File parent = dest.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new CloudException(409, "The destination folder does not exist");
        if (dest.isDirectory()) throw new CloudException(409, "A folder with that name already exists");
        File tmp = new File(parent, "." + dest.getName() + ".cbtmp");
        try {
            Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (src.lastModified() > 0) {
                //noinspection ResultOfMethodCallIgnored
                dest.setLastModified(src.lastModified());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        File d = fileOf(path);
        if (d.exists()) throw new CloudException(405, "\"" + d.getName() + "\" already exists");
        File parent = d.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new CloudException(409, "The parent folder does not exist");
        if (!d.mkdir()) throw new CloudException(403, "Could not create the folder \"" + d.getName() + "\"");
    }

    @Override
    public void delete(String path, boolean isDir) throws IOException {
        String p = Paths.normalize(path);
        if (p.equals("/")) throw new CloudException(400, "Cannot delete the root");
        File f = fileOf(p);
        if (!f.exists()) throw new CloudException(404, "Not found: " + p);
        deleteTree(f);
    }

    private static void deleteTree(File f) throws IOException {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        if (!f.delete() && f.exists()) throw new CloudException(403, "Could not delete \"" + f.getName() + "\"");
    }

    @Override
    public void move(String from, String to, boolean isDir) throws IOException {
        File src = fileOf(from);
        File dst = fileOf(to);
        if (!src.exists()) throw new CloudException(404, "Not found: " + from);
        if (dst.exists()) throw new CloudException(412, "\"" + dst.getName() + "\" already exists");
        File parent = dst.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new CloudException(409, "The destination folder does not exist");
        if (src.renameTo(dst)) return;
        // another storage volume: copy, then delete
        copyTree(src, dst);
        deleteTree(src);
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdir()) throw new CloudException(403, "Could not create \"" + dst.getName() + "\"");
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) copyTree(k, new File(dst, k.getName()));
            }
        } else {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    @Override
    public CloudQuota quota() {
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();
        if (total <= 0) return null;
        return new CloudQuota(total - free, total, free);
    }
}
