package com.cloudbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClient;
import com.cloudbridge.app.core.CloudEntry;
import com.cloudbridge.app.core.CloudQuota;
import com.cloudbridge.app.core.FileKind;
import com.cloudbridge.app.core.Format;
import com.cloudbridge.app.core.LocalClient;
import com.cloudbridge.app.core.Paths;
import com.cloudbridge.app.core.Transfer;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The Files page: the phone's storage with every connected cloud as a folder in it. Big icons in a grid (pictures
 * show themselves), a path bar, long-press to select, and copy / move / download / delete between any two places.
 */
final class FilesPage extends Page {
    private static final String PREFS = "cloudbridge_ui";
    private static final String[] SORTS = {"Name (A to Z)", "Name (Z to A)", "Newest first", "Oldest first",
            "Largest first", "Smallest first", "Type"};

    /** One cell of the grid. */
    static final class Row {
        String name;
        String path;
        boolean dir;
        long size = -1;
        long modified;
        int count = -1;
        Account drive;          // set for the cloud-drive folders in the phone's top level
        FileKind kind;

        String key() {
            return drive != null ? "drive:" + drive.id : path;
        }
    }

    /** Items waiting to be pasted somewhere. */
    private static final class Clip {
        Account from;           // null = the phone
        List<String> paths = new ArrayList<>();
        boolean move;
        String where;
    }

    private interface TaskBody {
        String run(Transfer.Progress progress) throws Exception;
    }

    /** Where the page was last; kept so that rebuilding the screen (for the look) does not lose the place. */
    private static String lastDrive;
    private static String lastPath = "/";

    private final Host host;
    private final Activity act;
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final TopBar bar;
    private final EditText search;
    private final HorizontalScrollView crumbScroll;
    private final LinearLayout crumbs;
    private final LinearLayout permCard;
    private final LinearLayout pasteBar;
    private final TextView pasteText;
    private final GridView grid;
    private final TextView message;
    private final LinearLayout selBar;
    private final View copyCell, moveCell, shareCell, downloadCell, deleteCell, renameCell;
    private final Adapter adapter = new Adapter();
    private final int cols;
    private final int cell;
    private final String tz = TimeZone.getDefault().getID();

    private Account drive;                // null = the phone
    private String path = "/";
    private List<Row> all = new ArrayList<>();
    private List<Row> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;
    private boolean searching;
    private Clip clip;
    private int sort;
    private boolean showHidden;
    private int listToken;
    private boolean hadAccess;

    FilesPage(Host host) {
        this.host = host;
        this.act = host.activity();
        prefs = act.getSharedPreferences(PREFS, 0);
        sort = prefs.getInt("sort", 0);
        showHidden = prefs.getBoolean("hidden", false);

        int screen = act.getResources().getDisplayMetrics().widthPixels;
        cols = Math.max(4, screen / Ui.dp(act, 96));
        cell = screen / cols;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.PAGE);

        bar = new TopBar(act);
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP));

        search = new EditText(act);
        search.setHint("Search in this folder");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        root.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP));

        crumbScroll = new HorizontalScrollView(act);
        crumbs = new LinearLayout(act);
        crumbs.setOrientation(LinearLayout.HORIZONTAL);
        crumbs.setPadding(Ui.dp(act, 12), Ui.dp(act, 4), Ui.dp(act, 12), Ui.dp(act, 8));
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll, Ui.lp(Ui.MATCH, Ui.WRAP));

        permCard = new LinearLayout(act);
        permCard.setOrientation(LinearLayout.VERTICAL);
        permCard.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 12));
        Ui.round(permCard, Ui.CHIP, 12);
        permCard.addView(Ui.text(act, "CloudBridge needs \"All files access\" to show and move the files on your phone.",
                14, false, Ui.INK));
        TextView allow = Ui.text(act, "Allow access", 15, true, Ui.ACCENT);
        allow.setPadding(0, Ui.dp(act, 10), 0, 0);
        allow.setOnClickListener(v -> requestAccess());
        permCard.addView(allow);
        root.addView(permCard, Ui.lpMargin(Ui.MATCH, Ui.WRAP, Ui.dp(act, 12), 0, Ui.dp(act, 12), Ui.dp(act, 8)));

        pasteBar = new LinearLayout(act);
        pasteBar.setOrientation(LinearLayout.HORIZONTAL);
        pasteBar.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        pasteBar.setBackgroundColor(Ui.SELECTED);
        pasteText = Ui.text(act, "", 14, false, Ui.INK);
        pasteBar.addView(pasteText, Ui.weight(0, Ui.WRAP, 1f));
        TextView cancel = Ui.text(act, "Cancel", 15, true, Ui.SUB);
        cancel.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        cancel.setOnClickListener(v -> {
            clip = null;
            updatePaste();
        });
        TextView paste = Ui.text(act, "Paste here", 15, true, Ui.ACCENT);
        paste.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        paste.setOnClickListener(v -> paste());
        pasteBar.addView(cancel);
        pasteBar.addView(paste);
        root.addView(pasteBar, Ui.lp(Ui.MATCH, Ui.WRAP));

        FrameLayout body = new FrameLayout(act);
        grid = new GridView(act);
        grid.setNumColumns(cols);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(act, 6));
        grid.setSelector(new ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onTap(shown.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            onHold(shown.get(pos));
            return true;
        });
        body.addView(grid, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        message = Ui.text(act, "", 15, false, Ui.SUB);
        message.setGravity(Gravity.CENTER);
        message.setPadding(Ui.dp(act, 32), Ui.dp(act, 32), Ui.dp(act, 32), Ui.dp(act, 32));
        body.addView(message, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        root.addView(body, Ui.weight(Ui.MATCH, 0, 1f));

        selBar = new LinearLayout(act);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setBackgroundColor(Ui.PAGE);
        copyCell = actionCell(IconButton.Glyph.COPY, "Copy", () -> startClip(false));
        moveCell = actionCell(IconButton.Glyph.MOVE, "Move", () -> startClip(true));
        shareCell = actionCell(IconButton.Glyph.SHARE, "Share", this::shareSelected);
        downloadCell = actionCell(IconButton.Glyph.DOWNLOAD, "Download", this::downloadSelected);
        deleteCell = actionCell(IconButton.Glyph.DELETE, "Delete", this::confirmDelete);
        renameCell = actionCell(IconButton.Glyph.RENAME, "Rename", this::renameSelected);
        for (View v : new View[]{copyCell, moveCell, shareCell, downloadCell, renameCell, deleteCell}) {
            selBar.addView(v, Ui.weight(0, Ui.WRAP, 1f));
        }
        root.addView(selBar, Ui.lp(Ui.MATCH, Ui.WRAP));

        if (lastDrive != null) drive = AccountStore.find(act, lastDrive);
        if (drive != null || lastDrive == null) path = Paths.normalize(lastPath);
        hadAccess = hasAccess();
        rebuild();
        load();
    }

    @Override
    View view() {
        return root;
    }

    @Override
    void onShow() {
        boolean now = hasAccess();
        if (now != hadAccess) {
            hadAccess = now;
            load();
        }
        updateChrome();
    }

    @Override
    boolean onBack() {
        if (selecting) {
            exitSelection();
            return true;
        }
        if (searching) {
            closeSearch();
            return true;
        }
        return up();
    }

    /** Accounts were added, edited or removed. */
    void accountsChanged() {
        if (drive != null && AccountStore.find(act, drive.id) == null) go(null, "/");
        else load();
    }

    /** Opens a drive (or the phone) at its top. */
    void open(Account d) {
        go(d, "/");
    }

    // ------------------------------------------------------------------ navigation

    private boolean atRoot() {
        return drive == null && path.equals("/");
    }

    private void go(Account d, String p) {
        exitSelectionQuietly();
        closeSearchQuietly();
        drive = d;
        path = Paths.normalize(p);
        lastDrive = d == null ? null : d.id;
        lastPath = path;
        rebuild();
        load();
    }

    private boolean up() {
        if (!path.equals("/")) {
            go(drive, Paths.parent(path));
            return true;
        }
        if (drive != null) {
            go(null, "/");
            return true;
        }
        return false;
    }

    private CloudClient client() {
        return drive == null ? Clients.phone() : Clients.get(drive);
    }

    private void rebuild() {
        rebuildCrumbs();
        updateChrome();
    }

    private void rebuildCrumbs() {
        crumbs.removeAllViews();
        List<String> labels = new ArrayList<>();
        List<Runnable> targets = new ArrayList<>();
        labels.add("Internal storage");
        targets.add(() -> go(null, "/"));
        if (drive != null) {
            final Account d = drive;
            labels.add(d.name);
            targets.add(() -> go(d, "/"));
        }
        String acc = "";
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            acc = Paths.join(acc.isEmpty() ? "/" : acc, seg);
            final String target = acc;
            final Account d = drive;
            labels.add(seg);
            targets.add(() -> go(d, target));
        }
        for (int i = 0; i < labels.size(); i++) {
            boolean last = i == labels.size() - 1;
            if (i > 0) {
                TextView sep = Ui.text(act, ">", 14, true, Ui.SUB);
                sep.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
                sep.setGravity(Gravity.CENTER_VERTICAL);
                crumbs.addView(sep, Ui.lp(Ui.WRAP, Ui.dp(act, 32)));
            }
            TextView chip = Ui.text(act, labels.get(i), 14, last && i > 0, last && i > 0 ? Ui.CURRENT_TEXT : Ui.CHIP_TEXT);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
            Ui.round(chip, last && i > 0 ? Ui.CURRENT : Ui.CHIP, 16);
            final Runnable target = targets.get(i);
            chip.setOnClickListener(v -> target.run());
            crumbs.addView(chip, Ui.lp(Ui.WRAP, Ui.dp(act, 32)));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    // ------------------------------------------------------------------ top bar, banners

    private void updateChrome() {
        if (selecting) {
            bar.setNav(IconButton.Glyph.CLOSE, this::exitSelection);
            bar.setTitle(selected.size() + (selected.size() == 1 ? " item selected" : " items selected"));
            bar.setActions(bar.action(IconButton.Glyph.SELECT_ALL, this::selectAll));
        } else {
            if (atRoot()) bar.setNav(IconButton.Glyph.MENU, host::openDrawer);
            else bar.setNav(IconButton.Glyph.BACK, () -> {
                if (searching) closeSearch();
                else up();
            });
            bar.setTitle(atRoot() ? "Files" : drive != null && path.equals("/") ? drive.name : Paths.name(path));
            bar.setActions(
                    bar.action(IconButton.Glyph.SEARCH, this::toggleSearch),
                    bar.action(IconButton.Glyph.NEW_FOLDER, this::newFolder),
                    bar.action(IconButton.Glyph.SORT, this::chooseSort),
                    bar.action(IconButton.Glyph.MORE, this::moreMenu));
        }
        selBar.setVisibility(selecting && !selected.isEmpty() ? View.VISIBLE : View.GONE);
        downloadCell.setVisibility(drive != null ? View.VISIBLE : View.GONE);
        renameCell.setVisibility(selected.size() == 1 ? View.VISIBLE : View.GONE);
        permCard.setVisibility(drive == null && !hasAccess() ? View.VISIBLE : View.GONE);
        updatePaste();
    }

    private void updatePaste() {
        if (clip == null) {
            pasteBar.setVisibility(View.GONE);
            return;
        }
        pasteBar.setVisibility(View.VISIBLE);
        int n = clip.paths.size();
        pasteText.setText((clip.move ? "Moving " : "Copying ") + n + (n == 1 ? " item" : " items") + " from " + clip.where);
    }

    private View actionCell(IconButton.Glyph g, String label, Runnable onClick) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 8));
        IconButton b = new IconButton(act, g);
        b.setClickable(false);
        c.addView(b, Ui.lp(Ui.dp(act, 36), Ui.dp(act, 36)));
        TextView t = Ui.text(act, label, 11, false, Ui.INK);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        c.setOnClickListener(v -> onClick.run());
        return c;
    }

    // ------------------------------------------------------------------ permission

    private boolean hasAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        return act.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == 0
                && act.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == 0;
    }

    private void requestAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Uri pkg = Uri.parse("package:" + act.getPackageName());
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg));
            } catch (Exception e) {
                try {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    toast("Open Settings > Apps > CloudBridge > Permissions and allow all files access.");
                }
            }
        } else {
            act.requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, 21);
        }
    }

    // ------------------------------------------------------------------ listing

    private void load() {
        final int token = ++listToken;
        final Account d = drive;
        final String p = path;
        message.setText("Loading...");
        message.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final List<Row> rows = new ArrayList<>();
            String error = null;
            try {
                if (d == null) {
                    LocalClient local = Clients.phone();
                    String localError = null;
                    try {
                        for (CloudEntry e : local.list(p)) {
                            if (!showHidden && e.name().startsWith(".")) continue;
                            Row r = row(e);
                            if (r.dir) r.count = countVisible(local, r.path);
                            rows.add(r);
                        }
                    } catch (Exception e) {
                        localError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                    if (!p.equals("/") && localError != null) throw new java.io.IOException(localError);
                    if (p.equals("/")) {
                        for (Account a : AccountStore.load(act)) {
                            Row r = new Row();
                            r.name = a.name;
                            r.path = "/";
                            r.dir = true;
                            r.drive = a;
                            r.kind = FileKind.FOLDER;
                            rows.add(r);
                        }
                        // Without "All files access" the phone's top level cannot be read, but the drives still show.
                        if (rows.isEmpty() && localError != null) throw new java.io.IOException(localError);
                    }
                } else {
                    for (CloudEntry e : Clients.get(d).list(p)) rows.add(row(e));
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String err = error;
            act.runOnUiThread(() -> {
                if (token != listToken) return;
                all = rows;
                if (err != null) {
                    shown = new ArrayList<>();
                    adapter.notifyDataSetChanged();
                    message.setText(err);
                    message.setVisibility(View.VISIBLE);
                    return;
                }
                applyFilter();
            });
        }).start();
    }

    private int countVisible(LocalClient local, String dir) {
        if (showHidden) return local.countChildren(dir);
        File[] kids = local.fileOf(dir).listFiles();
        if (kids == null) return -1;
        int n = 0;
        for (File k : kids) if (!k.getName().startsWith(".")) n++;
        return n;
    }

    private static Row row(CloudEntry e) {
        Row r = new Row();
        r.name = e.name();
        r.path = e.path;
        r.dir = e.dir;
        r.size = e.size;
        r.modified = e.modified;
        r.kind = FileKind.of(r.name, e.dir);
        return r;
    }

    private void applyFilter() {
        String q = searching ? search.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        List<Row> out = new ArrayList<>();
        for (Row r : all) {
            if (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q)) out.add(r);
        }
        Collections.sort(out, comparator());
        shown = out;
        adapter.notifyDataSetChanged();
        if (out.isEmpty()) {
            message.setText(q.isEmpty() ? "This folder is empty." : "Nothing matches \"" + q + "\".");
            message.setVisibility(View.VISIBLE);
        } else {
            message.setVisibility(View.GONE);
        }
    }

    private Comparator<Row> comparator() {
        final int mode = sort;
        return (a, b) -> {
            if ((a.drive != null) != (b.drive != null)) return a.drive != null ? -1 : 1;
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int c;
            switch (mode) {
                case 1:
                    c = b.name.compareToIgnoreCase(a.name);
                    break;
                case 2:
                    c = Long.compare(b.modified, a.modified);
                    break;
                case 3:
                    c = Long.compare(a.modified, b.modified);
                    break;
                case 4:
                    c = Long.compare(b.size, a.size);
                    break;
                case 5:
                    c = Long.compare(a.size, b.size);
                    break;
                case 6:
                    c = a.kind.compareTo(b.kind);
                    break;
                default:
                    c = a.name.compareToIgnoreCase(b.name);
            }
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        };
    }

    // ------------------------------------------------------------------ the grid

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            View v = convertView;
            if (v == null) {
                LinearLayout c = new LinearLayout(act);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(Ui.dp(act, 2), Ui.dp(act, 4), Ui.dp(act, 2), Ui.dp(act, 6));
                h = new Holder();
                int side = Math.round(cell * 0.78f);
                h.icon = new FileIconView(act);
                c.addView(h.icon, Ui.lp(side, side));
                h.name = Ui.text(act, "", 12, true, Ui.INK);
                h.name.setMaxLines(2);
                h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                h.name.setGravity(Gravity.CENTER);
                c.addView(h.name, Ui.lp(Ui.MATCH, Ui.WRAP));
                h.info = Ui.text(act, "", 11, false, Ui.SUB);
                h.info.setGravity(Gravity.CENTER);
                c.addView(h.info, Ui.lp(Ui.MATCH, Ui.WRAP));
                c.setTag(h);
                v = c;
            } else {
                h = (Holder) v.getTag();
            }
            bind(h, v, shown.get(position));
            return v;
        }
    }

    private static final class Holder {
        FileIconView icon;
        TextView name;
        TextView info;
        String key = "";
    }

    private void bind(final Holder h, View cellView, Row r) {
        h.name.setText(r.name);
        h.info.setText(infoOf(r));
        boolean checked = selected.contains(r.key());
        cellView.setBackgroundColor(checked ? Ui.SELECTED : 0);
        h.icon.set(r.kind, null, r.drive != null);
        h.icon.setSelection(selecting && r.drive == null, checked);
        final String key = (drive == null ? "phone" : drive.id) + "|" + r.path + "|" + r.modified + "|" + r.size;
        h.key = key;
        if (r.drive != null || r.dir || !(r.kind == FileKind.IMAGE || (r.kind == FileKind.VIDEO && drive == null))) return;
        Bitmap cached = Thumbs.cached(key);
        if (cached != null) {
            h.icon.setThumb(cached);
            return;
        }
        int px = Math.round(cell * 0.78f);
        Thumbs.Maker maker = drive == null
                ? Thumbs.forLocal(Clients.phone().fileOf(r.path), r.kind, px)
                : Thumbs.forCloud(act, drive.id, Clients.get(drive), r.path, r.size, r.modified, px);
        Thumbs.load(key, () -> h.key.equals(key), maker, b -> h.icon.setThumb(b));
    }

    private String infoOf(Row r) {
        if (r.drive != null) {
            CloudQuota q = Clients.cachedQuota(r.drive.id);
            return q != null && q.free >= 0 ? "Cloud · " + Format.storage(q.free) + " free" : "Cloud drive";
        }
        if (r.dir) return r.count < 0 ? "" : r.count + (r.count == 1 ? " item" : " items");
        String date = Format.date(r.modified, System.currentTimeMillis(), TimeZone.getTimeZone(tz));
        String size = Format.size(r.size);
        return date.isEmpty() ? size : size.isEmpty() ? date : date + "\n" + size;
    }

    // ------------------------------------------------------------------ taps and selection

    private void onTap(Row r) {
        if (selecting) {
            if (r.drive == null) toggle(r);
            return;
        }
        if (r.drive != null) {
            go(r.drive, "/");
        } else if (r.dir) {
            go(drive, r.path);
        } else {
            openFile(r);
        }
    }

    private void onHold(Row r) {
        if (r.drive != null) {
            toast("Manage cloud drives in \"Connected drives\" in the sidebar.");
            return;
        }
        if (!selecting) {
            selecting = true;
            selected.clear();
        }
        toggle(r);
    }

    private void toggle(Row r) {
        if (!selected.remove(r.key())) selected.add(r.key());
        if (selected.isEmpty()) {
            exitSelection();
            return;
        }
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void selectAll() {
        for (Row r : shown) if (r.drive == null) selected.add(r.key());
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void exitSelection() {
        exitSelectionQuietly();
        updateChrome();
        adapter.notifyDataSetChanged();
    }

    private void exitSelectionQuietly() {
        selecting = false;
        selected.clear();
    }

    private List<Row> selectedRows() {
        List<Row> out = new ArrayList<>();
        for (Row r : all) if (selected.contains(r.key())) out.add(r);
        return out;
    }

    private List<String> selectedPaths() {
        List<String> out = new ArrayList<>();
        for (Row r : selectedRows()) out.add(r.path);
        return out;
    }

    // ------------------------------------------------------------------ search, sort, menu

    private void toggleSearch() {
        if (searching) {
            closeSearch();
            return;
        }
        searching = true;
        search.setText("");
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
    }

    private void closeSearch() {
        closeSearchQuietly();
        applyFilter();
    }

    private void closeSearchQuietly() {
        searching = false;
        search.setText("");
        search.setVisibility(View.GONE);
    }

    private void chooseSort() {
        new AlertDialog.Builder(act).setTitle("Sort by")
                .setSingleChoiceItems(SORTS, sort, (d, which) -> {
                    sort = which;
                    prefs.edit().putInt("sort", sort).apply();
                    d.dismiss();
                    applyFilter();
                }).setNegativeButton("Cancel", null).show();
    }

    private void moreMenu() {
        final String[] items = {showHidden ? "Hide hidden files" : "Show hidden files", "Refresh"};
        new AlertDialog.Builder(act).setItems(items, (d, which) -> {
            if (which == 0) {
                showHidden = !showHidden;
                prefs.edit().putBoolean("hidden", showHidden).apply();
            }
            load();
        }).show();
    }

    // ------------------------------------------------------------------ new folder, rename

    private void newFolder() {
        final EditText name = new EditText(act);
        name.setHint("Folder name");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        askText("New folder", name, "Create", () -> {
            final String n = Paths.sanitizeName(name.getText().toString());
            final String target = Paths.join(path, n);
            runTask("Creating folder", p -> {
                client().mkdir(target);
                return "Folder \"" + n + "\" created";
            });
        });
    }

    private void renameSelected() {
        List<Row> rows = selectedRows();
        if (rows.size() != 1) return;
        final Row r = rows.get(0);
        final EditText name = new EditText(act);
        name.setText(r.name);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        askText("Rename", name, "Rename", () -> {
            final String n = Paths.sanitizeName(name.getText().toString());
            if (n.equals(r.name)) return;
            exitSelectionQuietly();
            runTask("Renaming", p -> {
                client().move(r.path, Paths.join(Paths.parent(r.path), n), r.dir);
                return "Renamed to \"" + n + "\"";
            });
        });
    }

    private void askText(String title, EditText field, String ok, final Runnable onOk) {
        LinearLayout box = new LinearLayout(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        box.addView(field, Ui.lp(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(act).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(ok, (d, w) -> onOk.run())
                .show();
    }

    // ------------------------------------------------------------------ copy, move, paste

    private String here() {
        return drive == null ? "your phone" : drive.name;
    }

    private void startClip(boolean move) {
        if (selected.isEmpty()) return;
        Clip c = new Clip();
        c.from = drive;
        c.paths = selectedPaths();
        c.move = move;
        c.where = here();
        clip = c;
        exitSelection();
        toast("Open the folder you want, then tap \"Paste here\".");
    }

    private CloudClient clipClient(Clip c) {
        return c.from == null ? Clients.phone() : Clients.get(c.from);
    }

    private void paste() {
        final Clip c = clip;
        if (c == null) return;
        final String toPath = path;
        final Account toDrive = drive;
        final CloudClient from = clipClient(c);
        final CloudClient to = client();
        if (from == to) {
            for (String p : c.paths) {
                if (p.equals(toPath) || Paths.isUnder(p, toPath)) {
                    toast("A folder cannot be copied or moved into itself.");
                    return;
                }
            }
        }
        clip = null;
        updatePaste();
        runTask(c.move ? "Moving" : "Copying", progress -> {
            Transfer.Result r = Transfer.copy(from, c.paths, to, toPath, c.move, tmp(), progress);
            if (toDrive == null) scan(Clients.phoneRoot(), r.created);
            if (c.move && c.from == null) scan(Clients.phoneRoot(), c.paths);
            return report(r, c.move ? "moved" : "copied");
        });
    }

    private void downloadSelected() {
        if (drive == null || selected.isEmpty()) return;
        final CloudClient from = client();
        final List<String> paths = selectedPaths();
        final File dir = Clients.downloads();
        exitSelectionQuietly();
        runTask("Downloading", progress -> {
            Transfer.Result r = Transfer.copy(from, paths, new LocalClient(dir, true), "/", false, tmp(), progress);
            scan(dir, r.created);
            return report(r, "saved to the Download folder");
        });
    }

    /** Sends the selected files to another app. Cloud files are downloaded to the cache first. */
    private void shareSelected() {
        final List<Row> files = new ArrayList<>();
        int folders = 0;
        for (Row r : selectedRows()) {
            if (r.dir) folders++;
            else files.add(r);
        }
        if (files.isEmpty()) {
            toast("Folders cannot be shared. Select files instead.");
            return;
        }
        final Account d = drive;
        final CloudClient c = client();
        final int skipped = folders;
        final List<File> ready = new ArrayList<>();
        exitSelectionQuietly();
        runTask("Preparing to share", progress -> {
            for (Row r : files) {
                if (progress.cancelled()) return "Stopped";
                if (d == null) {
                    ready.add(Clients.phone().fileOf(r.path));
                } else {
                    File dir = new File(act.getCacheDir(), "share/" + Integer.toHexString((d.id + r.path).hashCode()));
                    dir.mkdirs();
                    File f = new File(dir, Paths.sanitizeName(r.name));
                    progress.item(r.name);
                    if (!f.isFile() || f.length() != r.size) c.download(r.path, f);
                    ready.add(f);
                }
            }
            return null;
        }, result -> {
            updateChrome();
            adapter.notifyDataSetChanged();
            if (result != null) {
                toast(result);
                return;
            }
            if (skipped > 0) toast("Folders were left out: only files can be shared.");
            send(ready);
        });
    }

    private void send(List<File> files) {
        List<Uri> uris = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        for (File f : files) {
            uris.add(OpenProvider.uriFor(f));
            mimes.add(Paths.mimeFor(f.getName()));
        }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        i.setType(Paths.commonMime(mimes));
        // The clip is what carries the read permission to the receiving app.
        ClipData clip = ClipData.newRawUri("files", uris.get(0));
        for (int n = 1; n < uris.size(); n++) clip.addItem(new ClipData.Item(uris.get(n)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            toast("No app on this phone can receive these files.");
        }
    }

    private void confirmDelete() {
        if (selected.isEmpty()) return;
        final List<String> paths = selectedPaths();
        int n = paths.size();
        String what = n == 1 ? "\"" + selectedRows().get(0).name + "\"" : n + " items";
        String from = drive == null ? "your phone" : "\"" + drive.name + "\" (the cloud)";
        new AlertDialog.Builder(act).setTitle("Delete " + what + "?")
                .setMessage("It is deleted from " + from + ". This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    final CloudClient c = client();
                    final boolean onPhone = drive == null;
                    exitSelectionQuietly();
                    runTask("Deleting", progress -> {
                        Transfer.Result r = Transfer.delete(c, paths, progress);
                        if (onPhone) scan(Clients.phoneRoot(), paths);
                        return report(r, "deleted");
                    });
                }).show();
    }

    private File tmp() {
        File d = new File(act.getCacheDir(), "transfer");
        d.mkdirs();
        return d;
    }

    private static String report(Transfer.Result r, String verb) {
        String s = r.summary(verb);
        if (!r.errors.isEmpty()) s += "\n" + r.errors.get(0);
        return s;
    }

    /** Tells the gallery about files that appeared or disappeared on the phone. */
    private void scan(File root, List<String> paths) {
        List<String> abs = new ArrayList<>();
        for (String p : paths) abs.add(new File(root, p).getPath());
        if (abs.isEmpty()) return;
        try {
            android.media.MediaScannerConnection.scanFile(act, abs.toArray(new String[0]), null, null);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ opening a file

    private void openFile(final Row r) {
        if (drive == null) {
            File f = Clients.phone().fileOf(r.path);
            if (!OpenProvider.view(act, f)) toast("No app on this phone can open this file.");
            return;
        }
        final Account d = drive;
        final CloudClient c = client();
        final File dir = new File(act.getCacheDir(), "open/" + Integer.toHexString((d.id + r.path).hashCode()));
        final File f = new File(dir, Paths.sanitizeName(r.name));
        runTask("Opening " + r.name, progress -> {
            dir.mkdirs();
            progress.item(r.name);
            if (!f.isFile() || f.length() != r.size) c.download(r.path, f);
            return null;
        }, result -> {
            if (result != null) toast(result);
            else if (!OpenProvider.view(act, f)) toast("No app on this phone can open this file.");
        });
    }

    // ------------------------------------------------------------------ running things in the background

    private void runTask(String title, TaskBody body) {
        runTask(title, body, result -> {
            if (result != null) toast(result);
            load();
        });
    }

    /** Runs {@code body} on a thread behind a small "working" dialog with a Stop button; {@code done} gets its text. */
    private void runTask(String title, final TaskBody body, final java.util.function.Consumer<String> done) {
        final boolean[] stop = {false};
        final TextView msg = Ui.text(act, "Working...", 14, false, Ui.SUB);
        msg.setPadding(Ui.dp(act, 24), Ui.dp(act, 16), Ui.dp(act, 24), Ui.dp(act, 8));
        final AlertDialog dlg = new AlertDialog.Builder(act).setTitle(title).setView(msg).setCancelable(false)
                .setNegativeButton("Stop", null).create();
        dlg.show();
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            stop[0] = true;
            msg.setText("Stopping...");
        });
        new Thread(() -> {
            String result;
            try {
                result = body.run(new Transfer.Progress() {
                    @Override
                    public void item(String name) {
                        act.runOnUiThread(() -> msg.setText(name));
                    }

                    @Override
                    public boolean cancelled() {
                        return stop[0];
                    }
                });
            } catch (Exception e) {
                result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String finalResult = result;
            act.runOnUiThread(() -> {
                if (dlg.isShowing()) dlg.dismiss();
                done.accept(finalResult);
            });
        }).start();
    }

    private void toast(String text) {
        Toast.makeText(act, text, Toast.LENGTH_LONG).show();
    }
}
