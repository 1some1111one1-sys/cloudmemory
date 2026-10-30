package com.cloudbridge.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import com.cloudbridge.app.core.PhotoBackup;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Every photo and video on the phone, as the gallery knows them. */
final class MediaScan {
    private MediaScan() {
    }

    static boolean hasPermission(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == 0
                    && c.checkSelfPermission("android.permission.READ_MEDIA_VIDEO") == 0;
        }
        return c.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == 0;
    }

    static String[] permissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.POST_NOTIFICATIONS"};
        }
        return new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
    }

    static List<PhotoBackup.Item> scan(Context c) {
        List<PhotoBackup.Item> out = new ArrayList<>();
        read(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, out);
        read(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, out);
        return out;
    }

    private static void read(Context c, Uri uri, List<PhotoBackup.Item> out) {
        String[] cols = {"_data", "_display_name", "_size", "datetaken", "date_added", "bucket_display_name"};
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String path = cur.getString(0);
                String name = cur.getString(1);
                if (path == null || name == null || name.startsWith(".")) continue;
                if (path.contains("/.trashed-") || path.contains("/.pending-")) continue;
                File f = new File(path);
                if (!f.isFile()) continue;
                long taken = cur.getLong(3);
                if (taken <= 0) taken = cur.getLong(4) * 1000L;
                String bucket = cur.getString(5);
                out.add(new PhotoBackup.Item(f, bucket == null || bucket.isEmpty() ? "Other" : bucket, name, f.length(), taken));
            }
        } catch (RuntimeException e) {
            // No permission or no media store: back up nothing.
        }
    }
}
