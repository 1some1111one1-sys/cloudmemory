package com.cloudbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.cloudbridge.app.core.Account;
import com.cloudbridge.app.core.CloudClients;
import com.cloudbridge.app.core.HttpTransport;
import java.net.URI;
import java.util.List;

/** Manage the connected cloud accounts. Everything else happens in the system Files app. */
public class MainActivity extends Activity {
    private static final HttpTransport TRANSPORT = new OkHttpTransport();
    private static final int BLUE = Color.parseColor("#1E88E5");
    private static final int GREY = Color.parseColor("#5F6368");

    private LinearLayout accountList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("CloudBridge");

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(page);
        // Targeting Android 15+ draws behind the navigation bar; keep the bottom of the page reachable.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        page.addView(label("Your cloud storage, as a folder on your phone", 20, true, Color.BLACK));
        TextView intro = label("Connect a WebDAV server (Nextcloud, ownCloud, Synology and others) or an "
                + "S3-compatible bucket (AWS S3, Cloudflare R2, Backblaze B2, MinIO and others). It then appears in "
                + "the Files app and in every app's \"choose file\" screen, where you can browse, open, copy, "
                + "rename, delete and save files.", 14, false, GREY);
        intro.setPadding(0, dp(8), 0, dp(16));
        page.addView(intro);

        Button add = new Button(this);
        add.setText("Add cloud storage");
        add.setOnClickListener(v -> showAccountDialog(null));
        page.addView(add);

        accountList = new LinearLayout(this);
        accountList.setOrientation(LinearLayout.VERTICAL);
        accountList.setPadding(0, dp(16), 0, 0);
        page.addView(accountList);

        TextView notes = label("Good to know\n"
                + "• A file is downloaded to your phone when you open it, and uploaded when the app that edited it "
                + "closes it.\n"
                + "• Passwords and keys are stored encrypted with the Android Keystore.\n"
                + "• Google Drive, OneDrive and Dropbox have their own apps that already appear in the Files app. "
                + "TeraBox has no public API, so it cannot be connected.", 13, false, GREY);
        notes.setPadding(0, dp(24), 0, 0);
        page.addView(notes);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------ account list

    private void refresh() {
        accountList.removeAllViews();
        List<Account> accounts = AccountStore.load(this);
        if (accounts.isEmpty()) {
            accountList.addView(label("Nothing connected yet.", 14, false, GREY));
            return;
        }
        for (final Account a : accounts) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(8));
            card.setBackgroundColor(Color.parseColor("#F1F3F4"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            card.setLayoutParams(lp);

            card.addView(label(a.name, 17, true, Color.BLACK));
            card.addView(label(a.summary(), 13, false, GREY));

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.addView(smallButton("Open in Files", v -> openInFiles(a)));
            buttons.addView(smallButton("Edit", v -> showAccountDialog(a)));
            buttons.addView(smallButton("Remove", v -> confirmRemove(a)));
            card.addView(buttons);
            accountList.addView(card);
        }
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void openInFiles(Account a) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        // Built by hand: DocumentsContract.buildRootUri is not public API on every Android version.
        i.setDataAndType(Uri.parse("content://" + CloudDocumentsProvider.AUTHORITY + "/root/" + Uri.encode(a.id)),
                DocumentsContract.Root.MIME_TYPE_ITEM);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Open the Files app, tap the menu (☰) and choose \"" + a.name + "\".");
        }
    }

    private void confirmRemove(final Account a) {
        new AlertDialog.Builder(this)
                .setTitle("Remove \"" + a.name + "\"?")
                .setMessage("It disappears from the Files app. Your files in the cloud are not deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    try {
                        AccountStore.remove(this, a.id);
                    } catch (RuntimeException e) {
                        toast("Could not remove: " + e.getMessage());
                        return;
                    }
                    storageChanged();
                })
                .show();
    }

    private void storageChanged() {
        CloudDocumentsProvider.clearCaches();
        getContentResolver().notifyChange(Uri.parse("content://" + CloudDocumentsProvider.AUTHORITY + "/root"), null);
        refresh();
    }

    // ------------------------------------------------------------------ add / edit dialog

    private void showAccountDialog(final Account existing) {
        final boolean isNew = existing == null;
        final Account a = isNew ? new Account() : existing.copy();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        final Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"WebDAV  (Nextcloud, ownCloud, Synology...)",
                        "S3-compatible  (AWS S3, R2, B2, MinIO...)"}));
        type.setSelection(a.type == Account.Type.S3 ? 1 : 0);
        form.addView(type);

        final EditText name = field("Name shown in the Files app", a.name, InputType.TYPE_CLASS_TEXT);
        final EditText url = field("", a.url, InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        final EditText user = field("", a.username, InputType.TYPE_CLASS_TEXT);
        final EditText secret = field("", a.secret,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText region = field("Region (e.g. us-east-1; Cloudflare R2: auto)",
                a.region == null || a.region.isEmpty() ? "us-east-1" : a.region, InputType.TYPE_CLASS_TEXT);
        final EditText bucket = field("Bucket name", a.bucket, InputType.TYPE_CLASS_TEXT);
        final CheckBox pathStyle = new CheckBox(this);
        pathStyle.setText("Path-style addressing (leave on unless your provider needs bucket.host)");
        pathStyle.setChecked(a.pathStyle);

        final TextView status = label("", 13, false, Color.parseColor("#B3261E"));
        status.setPadding(0, dp(8), 0, 0);

        form.addView(name);
        form.addView(url);
        form.addView(user);
        form.addView(secret);
        form.addView(region);
        form.addView(bucket);
        form.addView(pathStyle);
        form.addView(status);

        final View[] s3Only = {region, bucket, pathStyle};
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean s3 = position == 1;
                url.setHint(s3 ? "Endpoint, e.g. https://s3.amazonaws.com" : "Server URL, e.g. https://cloud.example.com/remote.php/dav/files/me");
                user.setHint(s3 ? "Access key ID" : "Username (leave empty if none)");
                secret.setHint(s3 ? "Secret access key" : "Password");
                for (View v : s3Only) v.setVisibility(s3 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(form);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Add cloud storage" : "Edit \"" + a.name + "\"")
                .setView(sv)
                .setPositiveButton("Test & save", null)
                .setNeutralButton("Save without testing", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        final Button testAndSave = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        final Button saveOnly = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);

        // Read the form into `a`. Returns an error message, or null when the settings are usable.
        final java.util.concurrent.Callable<String> readForm = () -> {
            a.type = type.getSelectedItemPosition() == 1 ? Account.Type.S3 : Account.Type.WEBDAV;
            a.url = url.getText().toString().trim();
            a.username = user.getText().toString().trim();
            a.secret = secret.getText().toString();
            a.region = region.getText().toString().trim();
            a.bucket = bucket.getText().toString().trim();
            a.pathStyle = pathStyle.isChecked();
            a.name = name.getText().toString().trim();
            if (a.name.isEmpty()) a.name = defaultName(a);
            try {
                CloudClients.create(a, TRANSPORT);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            return null;
        };

        saveOnly.setOnClickListener(v -> {
            String error = readOrError(readForm);
            if (error != null) {
                status.setText(error);
                return;
            }
            if (save(a, isNew)) dialog.dismiss();
        });

        testAndSave.setOnClickListener(v -> {
            String error = readOrError(readForm);
            if (error != null) {
                status.setText(error);
                return;
            }
            status.setTextColor(GREY);
            status.setText("Testing the connection...");
            testAndSave.setEnabled(false);
            final Account snapshot = a.copy();
            new Thread(() -> {
                String failure = null;
                try {
                    CloudClients.create(snapshot, TRANSPORT).list("/");
                } catch (Exception e) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String result = failure;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    testAndSave.setEnabled(true);
                    if (result == null) {
                        if (save(a, isNew)) dialog.dismiss();
                    } else {
                        status.setTextColor(Color.parseColor("#B3261E"));
                        status.setText(result + "\n\nYou can still keep these settings with \"Save without testing\".");
                    }
                });
            }).start();
        });
    }

    private static String readOrError(java.util.concurrent.Callable<String> readForm) {
        try {
            return readForm.call();
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private boolean save(Account a, boolean isNew) {
        if (isNew) a.id = AccountStore.newId();
        try {
            AccountStore.upsert(this, a);
        } catch (RuntimeException e) {
            toast("Could not save: " + e.getMessage());
            return false;
        }
        storageChanged();
        if (a.url.toLowerCase().startsWith("http://")) {
            toast("Saved. Note: http:// is not encrypted, so your password is sent in clear text.");
        } else {
            toast("Saved. Find \"" + a.name + "\" in the Files app.");
        }
        return true;
    }

    private static String defaultName(Account a) {
        try {
            String host = new URI(a.url.contains("://") ? a.url : "https://" + a.url).getHost();
            if (host != null && !host.isEmpty()) {
                return a.type == Account.Type.S3 && !a.bucket.isEmpty() ? a.bucket + " (" + host + ")" : host;
            }
        } catch (Exception ignored) {
        }
        return a.type == Account.Type.S3 ? "S3 storage" : "WebDAV storage";
    }

    // ------------------------------------------------------------------ small UI helpers

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        // setInputType already makes the field single-line. Calling setSingleLine(true) afterwards would
        // replace the password masking, so it is deliberately not called.
        e.setInputType(inputType);
        return e;
    }

    private TextView label(String text, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
