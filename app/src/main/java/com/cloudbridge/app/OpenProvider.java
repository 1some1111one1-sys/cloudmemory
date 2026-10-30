package com.cloudbridge.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import com.cloudbridge.app.core.Paths;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Hands one file on the phone to the app that opens it. It is not exported, so only an app that was given the
 * link by CloudBridge (with a one-time read grant) can read, and only files on the shared storage or in this
 * app's cache can be named.
 */
public class OpenProvider extends ContentProvider {
    static final String AUTHORITY = "com.cloudbridge.app.open";

    static Uri uriFor(File f) {
        String encoded = Base64.encodeToString(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return Uri.parse("content://" + AUTHORITY + "/f/" + encoded + "/" + Uri.encode(f.getName()));
    }

    /** Opens {@code f} in whatever app the person has for it. Returns false when there is none. */
    static boolean view(Context ctx, File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uriFor(f), Paths.mimeFor(f.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 2 || !"f".equals(seg.get(0))) throw new FileNotFoundException("bad link");
        File f;
        try {
            f = new File(new String(Base64.decode(seg.get(1), Base64.URL_SAFE), StandardCharsets.UTF_8)).getCanonicalFile();
        } catch (IOException | IllegalArgumentException e) {
            throw new FileNotFoundException("bad link");
        }
        if (!f.isFile()) throw new FileNotFoundException("not a file");
        String p = f.getPath();
        boolean allowed = p.startsWith(Clients.phoneRoot().getPath() + "/") || p.startsWith("/storage/")
                || p.startsWith(getContext().getCacheDir().getPath() + "/");
        if (!allowed) throw new FileNotFoundException("not allowed");
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{"_display_name", "_size"});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return seg.size() >= 3 ? Paths.mimeFor(seg.get(seg.size() - 1)) : Paths.MIME_DEFAULT;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
