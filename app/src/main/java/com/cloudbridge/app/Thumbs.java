package com.cloudbridge.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.FileKind;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Small previews of pictures and videos, made on background threads and remembered while the app runs. */
final class Thumbs {
    /** Cloud pictures bigger than this are not downloaded just for a preview. */
    static final long CLOUD_LIMIT = 8L * 1024 * 1024;

    interface Callback {
        void got(Bitmap b);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.max(8L * 1024 * 1024, Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8))) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Thumbs() {
    }

    static Bitmap cached(String key) {
        return CACHE.get(key);
    }

    /** Makes the preview unless {@code wanted} says the view has moved on; calls back on the main thread. */
    static void load(final String key, final BooleanSupplier wanted, final Maker maker, final Callback cb) {
        POOL.execute(() -> {
            if (!wanted.getAsBoolean()) return;
            Bitmap b = null;
            try {
                b = maker.make();
            } catch (Throwable ignored) {
                // A broken or huge file simply keeps its icon.
            }
            if (b == null) return;
            CACHE.put(key, b);
            final Bitmap fb = b;
            MAIN.post(() -> {
                if (wanted.getAsBoolean()) cb.got(fb);
            });
        });
    }

    interface Maker {
        Bitmap make() throws Exception;
    }

    // ------------------------------------------------------------------ makers

    static Maker forLocal(final File f, final FileKind kind, final int px) {
        return () -> kind == FileKind.VIDEO ? videoFrame(f, px) : decodeImage(f, px);
    }

    /** A cloud picture: downloaded once (small files only), shrunk, and kept as a small JPEG in the cache folder. */
    static Maker forCloud(final Context ctx, final String accountId, final CloudClient client, final String path,
                          final long size, final long modified, final int px) {
        return () -> {
            if (size < 0 || size > CLOUD_LIMIT) return null;
            File dir = new File(ctx.getCacheDir(), "thumbs");
            dir.mkdirs();
            File jpg = new File(dir, Integer.toHexString((accountId + "|" + path + "|" + modified + "|" + size).hashCode()) + ".jpg");
            if (jpg.isFile() && jpg.length() > 0) return BitmapFactory.decodeFile(jpg.getPath());
            File tmp = new File(dir, jpg.getName() + ".part");
            try {
                client.download(path, tmp);
                Bitmap b = decodeImage(tmp, px);
                if (b == null) return null;
                try (FileOutputStream out = new FileOutputStream(jpg)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 82, out);
                } catch (IOException e) {
                    jpg.delete();
                }
                return b;
            } finally {
                tmp.delete();
            }
        };
    }

    static Bitmap decodeImage(File f, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        int degrees = 0;
        try {
            int orientation = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (IOException ignored) {
        }
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (rotated != b) b.recycle();
            b = rotated;
        }
        return b;
    }

    static Bitmap videoFrame(File f, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            Bitmap b = r.getFrameAtTime();
            if (b == null) return null;
            int side = Math.min(b.getWidth(), b.getHeight());
            if (side > px * 2) {
                float k = (float) (px * 2) / side;
                Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                        Math.max(1, Math.round(b.getHeight() * k)), true);
                if (small != b) b.recycle();
                b = small;
            }
            return b;
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
