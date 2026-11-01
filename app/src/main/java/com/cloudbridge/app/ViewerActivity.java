package com.cloudbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.Format;
import com.cloudbridge.app.core.Paths;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the pictures of a folder one by one, like a gallery: the date on top, swipe for the next picture, pinch
 * to zoom, and Share / Favourite / Delete / More below. Pictures on a cloud drive are fetched first.
 */
public class ViewerActivity extends Activity {
    /** One picture of the list. */
    static final class Item {
        String name;
        String path;
        long size = -1;
        long modified;
    }

    // What to show; handed over by static fields because a long list does not fit in an Intent.
    private static List<Item> pending;
    private static int pendingIndex;
    private static String pendingDrive;

    /** Set when a picture was deleted here, so the Files page reloads when it comes back. */
    static boolean changed;

    static void start(Context ctx, Account drive, List<Item> items, int index) {
        pending = items;
        pendingIndex = index;
        pendingDrive = drive == null ? null : drive.id;
        ctx.startActivity(new Intent(ctx, ViewerActivity.class));
    }

    private static final int MAX_SIDE = 4096;

    private List<Item> items = new ArrayList<>();
    private int index;
    private Account drive;
    private CloudClient client;
    private volatile int generation;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final TimeZone tz = TimeZone.getDefault();

    private TextView title, subtitle, status, counter;
    private PhotoView photo;
    private IconButton heart;
    private View header, bottom;
    private boolean bars = true;
    private SharedPreferences prefs;
    private Set<String> favourites = new HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        setTheme(Ui.dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        if (pending == null || pending.isEmpty()) {
            finish();
            return;
        }
        items = pending;
        index = Math.max(0, Math.min(pendingIndex, items.size() - 1));
        if (pendingDrive != null) {
            drive = com.cloudbridge.app.AccountStore.find(this, pendingDrive);
            if (drive == null) {
                finish();
                return;
            }
            client = Clients.get(drive);
        } else {
            client = Clients.phone();
        }
        prefs = getSharedPreferences("cloudbridge_ui", 0);
        favourites = new HashSet<>(prefs.getStringSet("favourites", new HashSet<>()));
        buildScreen();
        show(index);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ the screen

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.PAGE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBackgroundColor(Ui.PAGE);
        IconButton back = new IconButton(this, IconButton.Glyph.BACK);
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 64)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(this, "", 20, true, Ui.INK);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = Ui.text(this, "", 14, true, Ui.SUB);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(title);
        names.addView(subtitle);
        head.addView(names, Ui.weight(0, Ui.dp(this, 64), 1f));
        IconButton rotate = new IconButton(this, IconButton.Glyph.ROTATE);
        rotate.setOnClickListener(v -> photo.rotate());
        head.addView(rotate, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 64)));
        header = head;
        root.addView(head, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Ui.CARD);
        photo = new PhotoView(this);
        photo.setListener(new PhotoView.Listener() {
            @Override
            public void onTap() {
                toggleBars();
            }

            @Override
            public boolean canSwipe(int dir) {
                int next = index + dir;
                return next >= 0 && next < items.size();
            }

            @Override
            public void onSwipe(int dir) {
                show(index + dir);
            }
        });
        stage.addView(photo, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        status = Ui.text(this, "", 15, false, Ui.SUB);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH);
        stage.addView(status, sp);
        counter = Ui.text(this, "", 12, true, Ui.SUB);
        counter.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        Ui.round(counter, Ui.PAGE, 12);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        cp.gravity = Gravity.BOTTOM | Gravity.END;
        cp.setMargins(0, 0, Ui.dp(this, 16), Ui.dp(this, 16));
        stage.addView(counter, cp);
        root.addView(stage, Ui.weight(Ui.MATCH, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Ui.PAGE);
        row.addView(action(IconButton.Glyph.SHARE_UP, this::shareCurrent), Ui.weight(0, Ui.dp(this, 60), 1f));
        heart = action(IconButton.Glyph.HEART, this::toggleFavourite);
        row.addView(heart, Ui.weight(0, Ui.dp(this, 60), 1f));
        row.addView(action(IconButton.Glyph.DELETE, this::confirmDelete), Ui.weight(0, Ui.dp(this, 60), 1f));
        row.addView(action(IconButton.Glyph.MORE_CIRCLE, this::showMore), Ui.weight(0, Ui.dp(this, 60), 1f));
        bottom = row;
        root.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);
    }

    private IconButton action(IconButton.Glyph g, Runnable r) {
        IconButton b = new IconButton(this, g);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void toggleBars() {
        bars = !bars;
        header.setVisibility(bars ? View.VISIBLE : View.GONE);
        bottom.setVisibility(bars ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ showing a picture

    private Item current() {
        return items.get(index);
    }

    private void show(int i) {
        if (i < 0 || i >= items.size()) return;
        index = i;
        final Item it = items.get(i);
        final int mine = ++generation;
        String date = Format.longDate(it.modified, tz);
        String time = Format.clock(it.modified, tz);
        title.setText(date.isEmpty() ? it.name : date);
        subtitle.setText(date.isEmpty() ? "" : time + " | " + it.name);
        counter.setText((i + 1) + " / " + items.size());
        heart.setGlyph(favourites.contains(keyOf(it)) ? IconButton.Glyph.HEART_FILLED : IconButton.Glyph.HEART);
        photo.setImage(null);
        status.setText("Loading...");
        status.setVisibility(View.VISIBLE);
        pool.execute(() -> {
            if (mine != generation) return;
            String problem = null;
            Bitmap bitmap = null;
            try {
                File f = fileFor(it);
                bitmap = decode(f);
                if (bitmap == null) {
                    problem = "This file cannot be shown as a picture.\n" + describe(f);
                    if (drive != null) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                }
            } catch (Exception e) {
                problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final Bitmap result = bitmap;
            final String error = problem;
            runOnUiThread(() -> {
                if (mine != generation || isFinishing()) return;
                if (result != null) {
                    status.setVisibility(View.GONE);
                    photo.setImage(result);
                } else {
                    status.setText(error == null ? "This picture cannot be shown." : error);
                }
            });
            if (result != null) prefetch(i + 1);
        });
    }

    /** Fetches the next cloud picture ahead of time, so the swipe feels instant. */
    private void prefetch(int i) {
        if (drive == null || i >= items.size()) return;
        final Item next = items.get(i);
        pool.execute(() -> {
            try {
                fileFor(next);
            } catch (Exception ignored) {
            }
        });
    }

    /** The picture as a file on the phone: the phone's own file, or the cloud file kept in the cache. */
    private File fileFor(Item it) throws IOException {
        if (drive == null) return Clients.phone().fileOf(it.path);
        return CloudCache.fetch(this, drive, client, it.path, it.name, it.size);
    }

    private static Bitmap decode(File f) {
        Bitmap b = Thumbs.decodeImage(f, 1440);
        if (b == null) return null;
        int big = Math.max(b.getWidth(), b.getHeight());
        if (big > MAX_SIDE) {
            float k = (float) MAX_SIDE / big;
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                    Math.max(1, Math.round(b.getHeight() * k)), true);
            if (small != b) b.recycle();
            b = small;
        }
        return b;
    }

    /** What the file looks like, for a person to tell what a server really sent. */
    private static String describe(File f) {
        StringBuilder sb = new StringBuilder(f.length() + " bytes");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[120];
            int n = 0, k;
            while (n < buf.length && (k = in.read(buf, n, buf.length - n)) > 0) n += k;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int c = buf[i] & 0xff;
                text.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            if (n > 0) sb.append(", starts with: ").append(text);
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the buttons

    private static String driveKey(String driveId, Item it) {
        return (driveId == null ? "phone" : driveId) + "|" + it.path;
    }

    private String keyOf(Item it) {
        return driveKey(drive == null ? null : drive.id, it);
    }

    private void toggleFavourite() {
        String k = keyOf(current());
        boolean on = favourites.add(k);
        if (!on) favourites.remove(k);
        prefs.edit().putStringSet("favourites", new HashSet<>(favourites)).apply();
        heart.setGlyph(on ? IconButton.Glyph.HEART_FILLED : IconButton.Glyph.HEART);
    }

    private interface WithFile {
        void run(File f);
    }

    /** Gets the current picture as a file (downloading it if needed) and then calls {@code next} on the main thread. */
    private void withFile(final String busy, final WithFile next) {
        final Item it = current();
        toast(busy);
        pool.execute(() -> {
            try {
                final File f = fileFor(it);
                runOnUiThread(() -> {
                    if (!isFinishing()) next.run(f);
                });
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> toast(m));
            }
        });
    }

    private void shareCurrent() {
        withFile("Preparing to share...", f -> {
            List<File> one = new ArrayList<>();
            one.add(f);
            if (!OpenProvider.share(this, one)) toast("No app on this phone can receive this picture.");
        });
    }

    private void confirmDelete() {
        final Item it = current();
        String from = drive == null ? "your phone" : "\"" + drive.name + "\" (the cloud)";
        new AlertDialog.Builder(this).setTitle("Delete \"" + it.name + "\"?")
                .setMessage("It is deleted from " + from + ". This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> delete(it)).show();
    }

    private void delete(final Item it) {
        toast("Deleting...");
        pool.execute(() -> {
            try {
                client.delete(it.path, false);
                if (drive == null) {
                    try {
                        android.media.MediaScannerConnection.scanFile(this,
                                new String[]{Clients.phone().fileOf(it.path).getPath()}, null, null);
                    } catch (RuntimeException ignored) {
                    }
                }
                runOnUiThread(() -> {
                    changed = true;
                    int at = items.indexOf(it);
                    if (at >= 0) items.remove(at);
                    if (items.isEmpty()) {
                        finish();
                        return;
                    }
                    show(Math.min(Math.max(at, 0), items.size() - 1));
                });
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> toast("Could not delete: " + m));
            }
        });
    }

    private void showMore() {
        final List<String> labels = new ArrayList<>();
        labels.add("Details");
        if (drive != null) labels.add("Save to phone");
        labels.add("Open in another app");
        new AlertDialog.Builder(this).setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            String what = labels.get(which);
            if (what.equals("Details")) showDetails();
            else if (what.equals("Save to phone")) saveToPhone();
            else openElsewhere();
        }).show();
    }

    private void showDetails() {
        Item it = current();
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(it.name).append('\n');
        if (it.size >= 0) sb.append("Size: ").append(Format.size(it.size)).append('\n');
        String date = Format.longDate(it.modified, tz);
        if (!date.isEmpty()) sb.append("Date: ").append(date).append(", ").append(Format.clock(it.modified, tz)).append('\n');
        sb.append("Where: ").append(drive == null ? "Phone" : drive.name).append(" · ").append(it.path);
        TextView t = Ui.text(this, sb.toString(), 15, false, Ui.INK);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        new AlertDialog.Builder(this).setTitle("Details").setView(t).setPositiveButton("Close", null).show();
    }

    private void saveToPhone() {
        final Item it = current();
        toast("Saving...");
        pool.execute(() -> {
            try {
                File from = fileFor(it);
                File dir = Clients.downloads();
                File to = new File(dir, Paths.sanitizeName(it.name));
                String base = to.getName();
                String ext = "";
                int dot = base.lastIndexOf('.');
                if (dot > 0) {
                    ext = base.substring(dot);
                    base = base.substring(0, dot);
                }
                for (int n = 1; to.exists(); n++) to = new File(dir, base + " (" + n + ")" + ext);
                try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                try {
                    android.media.MediaScannerConnection.scanFile(this, new String[]{to.getPath()}, null, null);
                } catch (RuntimeException ignored) {
                }
                runOnUiThread(() -> toast("Saved to the Download folder"));
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> toast(m));
            }
        });
    }

    private void openElsewhere() {
        withFile("Opening...", f -> {
            if (!OpenProvider.view(this, f)) toast("No app on this phone can open this file.");
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
