package com.cloudbridge.app;

import android.content.Context;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.CloudException;
import com.cloudbridge.app.core.Paths;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloud files kept on the phone for opening and sharing. A file is fetched by one thread at a time (two threads
 * writing the same file made a broken copy), into a temporary name that becomes the real name only when the
 * whole file has arrived and looks like what its name says.
 */
final class CloudCache {
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private CloudCache() {
    }

    static File fetch(Context ctx, Account drive, CloudClient client, String path, String name, long size) throws IOException {
        String key = drive.id + "|" + path;
        Object lock = LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            File dir = new File(ctx.getCacheDir(), "open/" + Integer.toHexString(key.hashCode()));
            dir.mkdirs();
            File f = new File(dir, Paths.sanitizeName(name));
            if (f.isFile() && f.length() > 0 && (size < 0 || f.length() == size) && problem(f) == null) return f;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            File tmp = new File(dir, ".fetch-" + System.nanoTime());
            try {
                client.download(path, tmp);
                String why = problem(tmp);
                if (why != null) throw new CloudException(502, "\"" + name + "\" arrived damaged: " + why);
                if (!tmp.renameTo(f)) throw new IOException("Could not save \"" + name + "\"");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return f;
        }
    }

    /** Null when the file looks right; otherwise what is wrong with it (checked for pictures and PDFs, by their first bytes). */
    static String problem(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        String type;
        byte[] want;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            type = "JPEG";
            want = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        } else if (n.endsWith(".png")) {
            type = "PNG";
            want = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        } else if (n.endsWith(".gif")) {
            type = "GIF";
            want = new byte[]{'G', 'I', 'F', '8'};
        } else if (n.endsWith(".pdf")) {
            type = "PDF";
            want = new byte[]{'%', 'P', 'D', 'F'};
        } else {
            return null;
        }
        byte[] head = new byte[96];
        int got = 0;
        try (InputStream in = new FileInputStream(f)) {
            int k;
            while (got < head.length && (k = in.read(head, got, head.length - got)) > 0) got += k;
        } catch (IOException e) {
            return "it could not be read";
        }
        boolean ok = got >= want.length;
        for (int i = 0; ok && i < want.length; i++) ok = head[i] == want[i];
        if (ok) return null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < got; i++) text.append(head[i] >= 32 && head[i] < 127 ? (char) head[i] : '.');
        return "it is not a " + type + " file (" + f.length() + " bytes, starts with: " + text + ")";
    }
}
