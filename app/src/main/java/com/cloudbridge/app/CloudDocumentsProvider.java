package com.cloudbridge.app;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.widget.Toast;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.CloudClients;
import com.cloudbridge.app.core.GoogleDriveClient;
import com.cloudbridge.app.core.MegaClient;
import com.cloudbridge.app.core.TeraBoxClient;
import com.cloudbridge.app.core.CloudEntry;
import com.cloudbridge.app.core.CloudException;
import com.cloudbridge.app.core.HttpTransport;
import com.cloudbridge.app.core.Ids;
import com.cloudbridge.app.core.Paths;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes every configured cloud account show up as a storage location in the system Files app and in the
 * file picker of every other app. Files are downloaded to a cache when opened and uploaded when an app
 * finishes writing them.
 */
public class CloudDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.cloudbridge.app.documents";
    private static final String TAG = "CloudBridge";
    private static final long ENTRY_TTL_MS = 60_000;
    private static final long CACHE_LIMIT_BYTES = 512L * 1024 * 1024;
    private static final HttpTransport TRANSPORT = new OkHttpTransport();

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
    };

    private static final class CachedEntry {
        final CloudEntry entry;
        final long time = System.currentTimeMillis();

        CachedEntry(CloudEntry entry) {
            this.entry = entry;
        }
    }

    /** A cursor that can carry extras (e.g. an error message for the Files app to show). */
    private static final class ExtrasCursor extends MatrixCursor {
        Bundle extras = Bundle.EMPTY;

        ExtrasCursor(String[] columns) {
            super(columns);
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }
    }

    private static final class Resolved {
        final Account account;
        final CloudEntry entry;

        Resolved(Account account, CloudEntry entry) {
            this.account = account;
            this.entry = entry;
        }
    }

    /** Shared with the settings screen so edits take effect immediately. */
    private static final ConcurrentHashMap<String, CachedEntry> ENTRY_CACHE = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private File downloadDir;
    private File uploadDir;
    private Handler uploadHandler;
    private Handler mainHandler;

    public static void clearCaches() {
        ENTRY_CACHE.clear();
        GoogleDriveClient.clearCaches();
        MegaClient.clearCaches();
        TeraBoxClient.clearCaches();
    }

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        downloadDir = new File(ctx.getCacheDir(), "dl");
        uploadDir = new File(ctx.getCacheDir(), "up");
        //noinspection ResultOfMethodCallIgnored
        downloadDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        uploadDir.mkdirs();
        File[] leftovers = uploadDir.listFiles();   // from uploads interrupted by the process being killed
        if (leftovers != null) {
            for (File f : leftovers) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        HandlerThread thread = new HandlerThread("cloudbridge-upload");
        thread.start();
        uploadHandler = new Handler(thread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        return true;
    }

    // ------------------------------------------------------------------ roots and documents

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        for (Account a : AccountStore.load(getContext())) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(Root.COLUMN_ROOT_ID, a.id);
            row.add(Root.COLUMN_DOCUMENT_ID, Ids.encode(a.id, "/"));
            row.add(Root.COLUMN_TITLE, a.name);
            row.add(Root.COLUMN_SUMMARY, a.type == Account.Type.S3 ? "S3 · " + a.bucket
                    : a.type == Account.Type.GOOGLE_DRIVE ? "Google Drive" : a.type == Account.Type.MEGA ? "MEGA"
                    : a.type == Account.Type.TERABOX ? "TeraBox (experimental)" : "WebDAV");
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
            row.add(Root.COLUMN_ICON, R.drawable.ic_cloud);
            row.add(Root.COLUMN_MIME_TYPES, "*/*");
        }
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        Resolved r = resolve(documentId);
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        addRow(c, r.account, r.entry);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        Account a = account(parentDocumentId);
        String path = pathOf(parentDocumentId);
        ExtrasCursor c = new ExtrasCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        try {
            List<CloudEntry> children = client(a).list(path);
            for (CloudEntry e : children) {
                ENTRY_CACHE.put(Ids.encode(a.id, e.path), new CachedEntry(e));
                addRow(c, a, e);
            }
        } catch (IOException e) {
            Log.w(TAG, "Listing " + path + " failed", e);
            Bundle extras = new Bundle();
            extras.putString(DocumentsContract.EXTRA_ERROR, message(e));   // shown by the Files app
            c.extras = extras;
        }
        c.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId));
        return c;
    }

    private void addRow(MatrixCursor c, Account a, CloudEntry e) {
        boolean root = e.path.equals("/");
        int flags;
        if (e.dir) {
            flags = Document.FLAG_DIR_SUPPORTS_CREATE;
            if (!root) flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_MOVE;
        } else {
            flags = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE
                    | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_MOVE;
        }
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, Ids.encode(a.id, e.path));
        row.add(Document.COLUMN_DISPLAY_NAME, root ? a.name : e.name());
        row.add(Document.COLUMN_MIME_TYPE, e.mime());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, e.size >= 0 ? Long.valueOf(e.size) : null);
        row.add(Document.COLUMN_LAST_MODIFIED, e.modified > 0 ? Long.valueOf(e.modified) : null);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            return Ids.accountOf(parentDocumentId).equals(Ids.accountOf(documentId))
                    && Paths.isUnder(Ids.pathOf(parentDocumentId), Ids.pathOf(documentId));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reading and writing file contents

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        Account a = account(documentId);
        String path = pathOf(documentId);
        if (path.equals("/")) throw new FileNotFoundException("The storage root is a folder");
        CloudClient client = client(a);
        try {
            if (mode.indexOf('w') < 0) return openForRead(a, client, documentId, path, signal);
            return openForWrite(a, client, path, mode);
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "Opening " + path + " failed", e);
            throw fnf(e);
        }
    }

    private ParcelFileDescriptor openForRead(Account a, CloudClient client, String documentId, String path,
                                             CancellationSignal signal) throws IOException {
        CloudEntry entry = resolve(documentId).entry;
        if (entry.dir) throw new FileNotFoundException("That is a folder");
        File f = new File(downloadDir, sha1(a.id + "|" + a.url + "|" + a.bucket + "|" + path)
                + "_" + entry.size + "_" + entry.modified);
        Object lock = locks.computeIfAbsent(f.getName(), k -> new Object());
        synchronized (lock) {
            if (!f.isFile()) {
                if (signal != null) signal.throwIfCanceled();
                client.download(path, f);
                pruneCache();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   // most recently used survives pruning
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private ParcelFileDescriptor openForWrite(Account a, final CloudClient client, final String path, String mode)
            throws IOException {
        final File tmp = File.createTempFile("up_", ".tmp", uploadDir);
        try {
            boolean keepExisting = mode.indexOf('a') >= 0 || (mode.indexOf('r') >= 0 && mode.indexOf('t') < 0);
            if (keepExisting) {
                try {
                    client.download(path, tmp);
                } catch (CloudException e) {
                    if (!e.isNotFound()) throw e;
                }
            }
            final String mime = Paths.mimeFor(Paths.name(path));
            final String parentDocId = Ids.encode(a.id, Paths.parent(path));
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode), uploadHandler,
                    new ParcelFileDescriptor.OnCloseListener() {
                        @Override
                        public void onClose(IOException e) {
                            try {
                                if (e != null) {
                                    Log.w(TAG, "Write to " + path + " was aborted", e);
                                    return;
                                }
                                client.upload(path, tmp, mime);
                                changed(parentDocId);
                            } catch (IOException | RuntimeException ex) {
                                Log.e(TAG, "Upload of " + path + " failed", ex);
                                toast("Upload failed (" + Paths.name(path) + "): " + message(ex));
                            } finally {
                                //noinspection ResultOfMethodCallIgnored
                                tmp.delete();
                            }
                        }
                    });
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    // ------------------------------------------------------------------ changes

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        Account a = account(parentDocumentId);
        String parent = pathOf(parentDocumentId);
        CloudClient client = client(a);
        try {
            String name = uniqueName(client, parent, Paths.sanitizeName(displayName));
            String path = Paths.join(parent, name);
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                client.mkdir(path);
            } else {
                File empty = File.createTempFile("new_", ".tmp", uploadDir);
                try {
                    client.upload(path, empty, mimeType == null ? Paths.mimeFor(name) : mimeType);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    empty.delete();
                }
            }
            changed(parentDocumentId);
            return Ids.encode(a.id, path);
        } catch (IOException e) {
            Log.w(TAG, "Creating " + displayName + " failed", e);
            throw fnf(e);
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Resolved r = resolve(documentId);
        if (r.entry.path.equals("/")) throw new FileNotFoundException("The storage root cannot be deleted");
        try {
            client(r.account).delete(r.entry.path, r.entry.dir);
        } catch (IOException e) {
            Log.w(TAG, "Deleting " + r.entry.path + " failed", e);
            throw fnf(e);
        }
        changed(Ids.encode(r.account.id, Paths.parent(r.entry.path)));
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        Resolved r = resolve(documentId);
        String from = r.entry.path;
        if (from.equals("/")) throw new FileNotFoundException("The storage root cannot be renamed");
        String to = Paths.join(Paths.parent(from), Paths.sanitizeName(displayName));
        if (to.equals(from)) return null;
        CloudClient client = client(r.account);
        try {
            requireFree(client, to);
            client.move(from, to, r.entry.dir);
        } catch (IOException e) {
            Log.w(TAG, "Renaming " + from + " failed", e);
            throw fnf(e);
        }
        changed(Ids.encode(r.account.id, Paths.parent(from)));
        return Ids.encode(r.account.id, to);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        Resolved r = resolve(sourceDocumentId);
        if (!r.account.id.equals(accountIdOf(targetParentDocumentId))) {
            throw new UnsupportedOperationException("Moving between different storage accounts is not supported");
        }
        String from = r.entry.path;
        String to = Paths.join(pathOf(targetParentDocumentId), Paths.name(from));
        if (to.equals(from)) return sourceDocumentId;
        CloudClient client = client(r.account);
        try {
            requireFree(client, to);
            client.move(from, to, r.entry.dir);
        } catch (IOException e) {
            Log.w(TAG, "Moving " + from + " failed", e);
            throw fnf(e);
        }
        changed(sourceParentDocumentId);
        changed(targetParentDocumentId);
        return Ids.encode(r.account.id, to);
    }

    // ------------------------------------------------------------------ helpers

    private static String accountIdOf(String documentId) throws FileNotFoundException {
        try {
            return Ids.accountOf(documentId);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Unknown document " + documentId);
        }
    }

    private static String pathOf(String documentId) throws FileNotFoundException {
        try {
            return Ids.pathOf(documentId);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Unknown document " + documentId);
        }
    }

    private Account account(String documentId) throws FileNotFoundException {
        Account a = AccountStore.find(getContext(), accountIdOf(documentId));
        if (a == null) throw new FileNotFoundException("This storage was removed from CloudBridge");
        return a;
    }

    private CloudClient client(Account a) throws FileNotFoundException {
        try {
            return CloudClients.create(a, TRANSPORT);
        } catch (IllegalArgumentException e) {
            throw fnf(e);
        }
    }

    private Resolved resolve(String documentId) throws FileNotFoundException {
        Account a = account(documentId);
        String path = pathOf(documentId);
        if (path.equals("/")) return new Resolved(a, new CloudEntry("/", true, -1, 0));
        CachedEntry cached = ENTRY_CACHE.get(documentId);
        if (cached != null && System.currentTimeMillis() - cached.time < ENTRY_TTL_MS) {
            return new Resolved(a, cached.entry);
        }
        try {
            CloudEntry e = client(a).stat(path);
            ENTRY_CACHE.put(documentId, new CachedEntry(e));
            return new Resolved(a, e);
        } catch (IOException e) {
            throw fnf(e);
        }
    }

    /** Forget cached listings and tell the Files app that a folder's contents changed. */
    private void changed(String parentDocumentId) {
        ENTRY_CACHE.clear();
        Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId);
        getContext().getContentResolver().notifyChange(uri, null);
    }

    private static void requireFree(CloudClient client, String path) throws IOException {
        boolean exists = true;
        try {
            client.stat(path);
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
            exists = false;
        }
        if (exists) throw new IOException("\"" + Paths.name(path) + "\" already exists in that folder");
    }

    /** "name.ext" -> "name (1).ext" -> "name (2).ext" ... until nothing with that name exists. */
    private static String uniqueName(CloudClient client, String parent, String name) throws IOException {
        Set<String> existing = new HashSet<>();
        try {
            for (CloudEntry e : client.list(parent)) existing.add(e.name());
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
        if (!existing.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!existing.contains(candidate)) return candidate;
        }
        throw new IOException("Too many files with the same name");
    }

    /** Keeps the download cache under CACHE_LIMIT_BYTES by deleting the least recently used files. */
    private void pruneCache() {
        File[] files = downloadDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= CACHE_LIMIT_BYTES) return;
        Arrays.sort(files, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        long recent = System.currentTimeMillis() - 60_000;
        for (File f : files) {
            if (total <= CACHE_LIMIT_BYTES) break;
            if (f.lastModified() > recent) continue;   // probably being read right now
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    private void toast(final String text) {
        mainHandler.post(() -> Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show());
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static FileNotFoundException fnf(Throwable t) {
        FileNotFoundException e = new FileNotFoundException(message(t));
        e.initCause(t);
        return e;
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
}
