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
import com.cloudbridge.app.core.GoogleDriveClient;
import com.cloudbridge.app.core.GoogleOAuth;
import com.cloudbridge.app.core.HttpTransport;
import com.cloudbridge.app.core.LoopbackReceiver;
import com.cloudbridge.app.core.MegaClient;
import com.cloudbridge.app.core.TeraBoxClient;
import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Manage the connected cloud accounts. Everything else happens in the system Files app. */
public class MainActivity extends Activity {
    private static final HttpTransport TRANSPORT = new OkHttpTransport();
    private static final int BLUE = Color.parseColor("#1E88E5");
    private static final int GREY = Color.parseColor("#5F6368");

    private static final int REQUEST_TERABOX = 4711;

    private LinearLayout accountList;
    private LinearLayout bridgeBox;
    private java.util.function.Consumer<String> pendingCookie;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TERABOX && resultCode == RESULT_OK && data != null && pendingCookie != null) {
            String cookie = data.getStringExtra(TeraBoxLoginActivity.EXTRA_COOKIE);
            if (cookie != null && !cookie.isEmpty()) pendingCookie.accept(cookie);
        }
    }

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
        TextView intro = label("Connect Google Drive, MEGA, TeraBox (experimental), a WebDAV server (Nextcloud, ownCloud, Synology and others) or "
                + "an S3-compatible bucket (AWS S3, Cloudflare R2, Backblaze B2, MinIO and others). It then appears in "
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

        bridgeBox = new LinearLayout(this);
        bridgeBox.setOrientation(LinearLayout.VERTICAL);
        bridgeBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        bridgeBox.setBackgroundColor(Color.parseColor("#E8F0FE"));
        LinearLayout.LayoutParams bridgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bridgeParams.topMargin = dp(20);
        bridgeBox.setLayoutParams(bridgeParams);
        page.addView(bridgeBox);

        TextView notes = label("Good to know\n"
                + "• A file is downloaded to your phone when you open it, and uploaded when the app that edited it "
                + "closes it.\n"
                + "• Passwords and keys are stored encrypted with the Android Keystore.\n"
                + "• Google Drive needs a free client ID from your own Google Cloud project (the Add screen "
                + "explains how).\n"
                + "• MEGA signs in with your e-mail and password; its files are decrypted on your phone.\n"
                + "• TeraBox is unofficial and experimental: it has no public API, so it may stop working "
                + "whenever TeraBox changes its website.\n"
                + "• OneDrive and Dropbox have their own apps that already appear in the Files app.", 13, false, GREY);
        notes.setPadding(0, dp(24), 0, 0);
        page.addView(notes);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        refreshBridge();
    }

    // ------------------------------------------------------------------ the bridge for other file managers

    private void refreshBridge() {
        bridgeBox.removeAllViews();
        boolean on = BridgeSettings.enabled(this);
        final int port = BridgeService.port();
        bridgeBox.addView(label("Use your clouds in other file managers", 17, true, Color.BLACK));
        TextView what = label("Most file managers cannot list the storages of the Files app, but many can connect to "
                + "an FTP or WebDAV server. Switch this on and add the address below in your file manager: every cloud "
                + "above then shows up as a folder. It works only on this phone, needs no root, and covers every cloud "
                + "type, TeraBox included.", 13, false, GREY);
        what.setPadding(0, dp(6), 0, dp(6));
        bridgeBox.addView(what);

        CheckBox toggle = new CheckBox(this);
        toggle.setText("Share my clouds with other file managers");
        toggle.setChecked(on);
        toggle.setOnClickListener(v -> {
            boolean wanted = ((CheckBox) v).isChecked();
            if (wanted) {
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
                BridgeService.start(this);
            } else {
                BridgeService.stop(this);
            }
            refreshBridge();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshBridge, 800);
        });
        bridgeBox.addView(toggle);
        if (!on) return;

        if (port == 0) {
            String err = BridgeService.lastError();
            bridgeBox.addView(label(err.isEmpty() ? "Starting..." : "Could not start: " + err, 13, false, GREY));
            return;
        }
        final String password = BridgeSettings.password(this);
        final String address = "http://127.0.0.1:" + port + "/";
        TextView details = label("Server (host): 127.0.0.1\nUser name: " + BridgeSettings.USER + "\nPassword: " + password
                + "\n\nFTP (for built-in file managers)\nPort: " + BridgeService.ftpPort()
                + "\n\nWebDAV (for Solid Explorer, X-plore ...)\nPort: " + port + "\nAddress: " + address, 14, false, Color.BLACK);
        details.setPadding(0, dp(8), 0, dp(4));
        details.setTextIsSelectable(true);
        bridgeBox.addView(details);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton("Copy address", v -> copy("Address", address)));
        buttons.addView(smallButton("Copy password", v -> copy("Password", password)));
        buttons.addView(smallButton("New password", v -> {
            BridgeSettings.newPassword(this);
            BridgeService.restart(this);   // so the running server uses the new password
            refreshBridge();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshBridge, 800);
        }));
        bridgeBox.addView(buttons);
        TextView how = label("In your file manager look for Remote, Network or FTP (built-in managers such as Xiaomi's) "
                + "or for a WebDAV option (Solid Explorer, X-plore ...). Add a new server with the host, port, user name "
                + "and password above, choose plain FTP / HTTP (no SSL, no TLS) and type the host as 127.0.0.1, not localhost. "
                + "Large files are downloaded to the phone before they start to open. Keep the notification "
                + "that appears: it is what keeps the bridge alive.", 12, false, GREY);
        how.setPadding(0, dp(6), 0, 0);
        bridgeBox.addView(how);
    }

    private void copy(String what, String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(what, text));
        toast(what + " copied");
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
                        "S3-compatible  (AWS S3, R2, B2, MinIO...)",
                        "Google Drive  (sign in with Google)",
                        "MEGA  (e-mail and password)",
                        "TeraBox  (experimental, unofficial)"}));
        type.setSelection(a.type == Account.Type.S3 ? 1 : a.type == Account.Type.GOOGLE_DRIVE ? 2
                : a.type == Account.Type.MEGA ? 3 : a.type == Account.Type.TERABOX ? 4 : 0);
        final boolean wasTera = a.type == Account.Type.TERABOX;
        final String[] teraCookie = {wasTera ? a.secret : ""};
        final boolean wasMega = a.type == Account.Type.MEGA;
        final String originalEmail = wasMega ? a.username : "";
        final String originalPassword = wasMega ? a.secret : "";
        final String[] megaSession = {wasMega ? a.clientSecret : ""};
        final boolean wasGoogle = a.type == Account.Type.GOOGLE_DRIVE;
        // The Google sign-in lives in these two, so the password field never shows a token.
        final String[] refreshToken = {wasGoogle ? a.secret : ""};
        final String[] email = {wasGoogle ? a.username : ""};
        form.addView(type);

        final EditText name = field("Name shown in the Files app", a.name, InputType.TYPE_CLASS_TEXT);
        final EditText url = field("", a.url, InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        final TextView teraWarn = label("Experimental and unofficial. TeraBox has no public API, so CloudBridge uses "
                + "the website's own requests, which TeraBox can change at any time. Automated access may be against "
                + "TeraBox's terms, so use it at your own risk. You sign in on TeraBox's real page; CloudBridge never "
                + "sees your password.", 13, false, Color.parseColor("#B3261E"));
        teraWarn.setPadding(0, dp(8), 0, dp(4));
        final Button teraSignIn = new Button(this);
        teraSignIn.setText("Sign in on the TeraBox website");
        teraSignIn.setAllCaps(false);
        final TextView teraStatus = label(teraCookie[0].isEmpty() ? "Not signed in yet." : "Signed in.", 14, false, GREY);
        teraStatus.setPadding(0, dp(4), 0, 0);
        final EditText user = field("", wasGoogle || wasTera ? "" : a.username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText secret = field("", wasGoogle || wasTera ? "" : a.secret,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText region = field("Region (e.g. us-east-1; Cloudflare R2: auto)",
                a.region == null || a.region.isEmpty() ? "us-east-1" : a.region, InputType.TYPE_CLASS_TEXT);
        final EditText bucket = field("Bucket name", a.bucket, InputType.TYPE_CLASS_TEXT);
        final CheckBox pathStyle = new CheckBox(this);
        pathStyle.setText("Path-style addressing (leave on unless your provider needs bucket.host)");
        pathStyle.setChecked(a.pathStyle);

        final EditText googleId = field("Google client ID (ends in .apps.googleusercontent.com)", a.clientId,
                InputType.TYPE_CLASS_TEXT);
        final EditText googleSecret = field("Google client secret", a.clientSecret,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText megaCode = field("Two-factor code (only if your MEGA account uses one)", "",
                InputType.TYPE_CLASS_NUMBER);
        final EditText megaKey = field("MEGA app key (optional; only if MEGA asks for one)", wasMega ? a.clientId : "",
                InputType.TYPE_CLASS_TEXT);
        final Button howTo = new Button(this);
        howTo.setText("How do I get these two?");
        howTo.setAllCaps(false);
        howTo.setOnClickListener(v -> showGoogleHelp());
        final Button signIn = new Button(this);
        signIn.setText("Sign in with Google");
        signIn.setAllCaps(false);
        final TextView googleStatus = label(refreshToken[0].isEmpty() ? "Not signed in yet."
                : "Signed in" + (email[0].isEmpty() ? "." : " as " + email[0]), 14, false, GREY);
        googleStatus.setPadding(0, dp(4), 0, 0);

        final TextView status = label("", 13, false, Color.parseColor("#B3261E"));
        status.setPadding(0, dp(8), 0, 0);

        form.addView(name);
        form.addView(url);
        form.addView(user);
        form.addView(secret);
        form.addView(region);
        form.addView(bucket);
        form.addView(pathStyle);
        form.addView(teraWarn);
        form.addView(teraSignIn);
        form.addView(teraStatus);
        form.addView(megaCode);
        form.addView(megaKey);
        form.addView(googleId);
        form.addView(googleSecret);
        form.addView(howTo);
        form.addView(signIn);
        form.addView(googleStatus);
        form.addView(status);

        final View[] s3Only = {region, bucket, pathStyle};
        // With a client built into the app the user only presses "Sign in with Google".
        final boolean builtInGoogle = !BuildConfig.GOOGLE_CLIENT_ID.isEmpty() && !BuildConfig.GOOGLE_CLIENT_SECRET.isEmpty();
        final View[] googleOnly = builtInGoogle ? new View[]{signIn, googleStatus}
                : new View[]{googleId, googleSecret, howTo, signIn, googleStatus};
        final View[] megaOnly = {megaCode, megaKey};
        final View[] teraOnly = {teraWarn, teraSignIn, teraStatus};
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean s3 = position == 1;
                boolean google = position == 2;
                boolean mega = position == 3;
                for (View v : googleOnly) v.setVisibility(google ? View.VISIBLE : View.GONE);
                boolean tera = position == 4;
                for (View v : megaOnly) v.setVisibility(mega ? View.VISIBLE : View.GONE);
                for (View v : teraOnly) v.setVisibility(tera ? View.VISIBLE : View.GONE);
                user.setVisibility(google || tera ? View.GONE : View.VISIBLE);
                secret.setVisibility(google || tera ? View.GONE : View.VISIBLE);
                url.setVisibility(google || mega ? View.GONE : View.VISIBLE);
                if (tera && url.getText().toString().trim().isEmpty()) url.setText(TeraBoxClient.DEFAULT_SITE);
                url.setHint(s3 ? "Endpoint, e.g. https://s3.amazonaws.com" : tera
                        ? "TeraBox website (normally https://www.1024terabox.com)"
                        : "Server URL, e.g. https://cloud.example.com/remote.php/dav/files/me");
                user.setHint(s3 ? "Access key ID" : mega ? "MEGA e-mail address" : "Username (leave empty if none)");
                secret.setHint(s3 ? "Secret access key" : "Password");
                for (View v : s3Only) v.setVisibility(s3 ? View.VISIBLE : View.GONE);
                status.setText("");
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
            int position = type.getSelectedItemPosition();
            a.type = position == 1 ? Account.Type.S3 : position == 2 ? Account.Type.GOOGLE_DRIVE
                    : position == 3 ? Account.Type.MEGA : position == 4 ? Account.Type.TERABOX : Account.Type.WEBDAV;
            if (a.type == Account.Type.GOOGLE_DRIVE) {
                a.url = "";
                a.username = email[0];
                a.secret = refreshToken[0];
                a.clientId = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_ID : googleId.getText().toString().trim();
                a.clientSecret = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_SECRET : googleSecret.getText().toString().trim();
            } else if (a.type == Account.Type.TERABOX) {
                a.url = url.getText().toString().trim();
                a.username = "";
                a.secret = teraCookie[0];
                a.clientId = "";
                a.clientSecret = "";
            } else if (a.type == Account.Type.MEGA) {
                a.url = "";
                a.username = user.getText().toString().trim();
                a.secret = secret.getText().toString();
                a.clientId = megaKey.getText().toString().trim();
                // A saved MEGA session only belongs to the e-mail and password it was made with.
                boolean same = wasMega && a.username.equalsIgnoreCase(originalEmail) && a.secret.equals(originalPassword);
                a.clientSecret = same ? megaSession[0] : "";
            } else {
                a.url = url.getText().toString().trim();
                a.username = user.getText().toString().trim();
                a.secret = secret.getText().toString();
                a.clientId = "";
                a.clientSecret = "";
            }
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

        // ---- TeraBox sign-in: the real website in a web view; we keep only the session cookie it sets.
        teraSignIn.setOnClickListener(v -> {
            String site = url.getText().toString().trim();
            if (site.isEmpty()) site = TeraBoxClient.DEFAULT_SITE;
            if (!site.contains("://")) site = "https://" + site;
            pendingCookie = cookie -> {
                teraCookie[0] = cookie;
                teraStatus.setText("Signed in. Now press \"Test & save\".");
                status.setText("");
            };
            Intent i = new Intent(this, TeraBoxLoginActivity.class);
            i.putExtra(TeraBoxLoginActivity.EXTRA_SITE, site);
            startActivityForResult(i, REQUEST_TERABOX);
        });

        // ---- Google sign-in: system browser + a loopback redirect that only this phone can receive.
        final LoopbackReceiver[] receiver = {null};
        dialog.setOnDismissListener(d -> {
            if (receiver[0] != null) receiver[0].close();
        });
        signIn.setOnClickListener(v -> {
            final String clientId = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_ID : googleId.getText().toString().trim();
            final String clientSecret = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_SECRET : googleSecret.getText().toString().trim();
            if (clientId.isEmpty() || clientSecret.isEmpty()) {
                status.setTextColor(Color.parseColor("#B3261E"));
                status.setText("Enter the client ID and client secret first (tap \"How do I get these two?\").");
                return;
            }
            if (receiver[0] != null) receiver[0].close();
            final LoopbackReceiver rx;
            try {
                rx = new LoopbackReceiver();
            } catch (IOException e) {
                status.setTextColor(Color.parseColor("#B3261E"));
                status.setText("Could not start the sign-in: " + e.getMessage());
                return;
            }
            receiver[0] = rx;
            final String verifier = GoogleOAuth.newVerifier();
            final String state = GoogleOAuth.newState();
            final String redirect = rx.redirectUri();
            String authUrl = GoogleOAuth.authUrl(clientId, redirect, state, GoogleOAuth.challenge(verifier));
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
            } catch (Exception e) {
                rx.close();
                status.setTextColor(Color.parseColor("#B3261E"));
                status.setText("Could not open the browser: " + e.getMessage());
                return;
            }
            status.setTextColor(GREY);
            status.setText("Finish signing in in your browser, then return to CloudBridge...");
            signIn.setEnabled(false);
            new Thread(() -> {
                String failure = null;
                String token = null;
                String mail = "";
                String warning = null;
                try {
                    String code = rx.awaitCode(state, 5 * 60 * 1000L);
                    GoogleOAuth.Tokens t = GoogleOAuth.exchange(TRANSPORT, GoogleOAuth.TOKEN_URL, clientId,
                            clientSecret, code, verifier, redirect);
                    token = t.refreshToken;
                    try {
                        mail = new GoogleDriveClient(TRANSPORT, clientId, clientSecret, token).signedInEmail();
                    } catch (Exception e) {
                        warning = e.getMessage();
                    }
                } catch (Exception e) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                } finally {
                    rx.close();
                }
                final String fFailure = failure, fToken = token, fMail = mail, fWarning = warning;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    signIn.setEnabled(true);
                    if (fFailure != null) {
                        status.setTextColor(Color.parseColor("#B3261E"));
                        status.setText(fFailure);
                        return;
                    }
                    refreshToken[0] = fToken;
                    email[0] = fMail;
                    googleStatus.setText("Signed in" + (fMail.isEmpty() ? "." : " as " + fMail));
                    if (name.getText().toString().trim().isEmpty()) {
                        name.setText(fMail.isEmpty() ? "Google Drive" : "Google Drive (" + fMail + ")");
                    }
                    status.setTextColor(GREY);
                    status.setText(fWarning == null ? "Signed in. Now press \"Test & save\"."
                            : "Signed in, but Google Drive answered: " + fWarning);
                });
            }).start();
        });

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
            final String twoFactor = megaCode.getText().toString().trim();
            final String[] newSession = {null};
            new Thread(() -> {
                String failure = null;
                try {
                    if (snapshot.type == Account.Type.MEGA) {
                        // Sign in for real (this is where a two-factor code is used) and keep the session.
                        newSession[0] = MegaClient.signIn(TRANSPORT, snapshot.username, snapshot.secret, twoFactor,
                                snapshot.clientId);
                        snapshot.clientSecret = newSession[0];
                    }
                    CloudClients.create(snapshot, TRANSPORT).list("/");
                } catch (Exception e) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String result = failure;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    testAndSave.setEnabled(true);
                    if (result == null) {
                        if (newSession[0] != null) a.clientSecret = newSession[0];
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
        if (a.type == Account.Type.MEGA) return "MEGA (" + a.username + ")";
        if (a.type == Account.Type.TERABOX) return "TeraBox";
        if (a.type == Account.Type.GOOGLE_DRIVE) {
            return a.username == null || a.username.isEmpty() ? "Google Drive" : "Google Drive (" + a.username + ")";
        }
        try {
            String host = new URI(a.url.contains("://") ? a.url : "https://" + a.url).getHost();
            if (host != null && !host.isEmpty()) {
                return a.type == Account.Type.S3 && !a.bucket.isEmpty() ? a.bucket + " (" + host + ")" : host;
            }
        } catch (Exception ignored) {
        }
        return a.type == Account.Type.S3 ? "S3 storage" : "WebDAV storage";
    }

    private void showGoogleHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Getting a Google client ID")
                .setMessage("Google only lets apps reach your Drive with a client ID, and CloudBridge has none of "
                        + "its own, so you make a free one (about 5 minutes, once).\n\n"
                        + "1. On console.cloud.google.com create a project.\n"
                        + "2. APIs & Services > Library > search \"Google Drive API\" > Enable.\n"
                        + "3. APIs & Services > OAuth consent screen (Google Auth platform): choose External, fill in "
                        + "an app name and your e-mail, add yourself as a test user.\n"
                        + "4. On that screen press \"Publish app\" so the status is \"In production\". (In "
                        + "\"Testing\" mode Google logs you out every 7 days.)\n"
                        + "5. Credentials > Create credentials > OAuth client ID > Application type \"Desktop app\".\n"
                        + "6. Copy the client ID and client secret into CloudBridge.\n\n"
                        + "When you sign in, Google warns that the app is \"not verified\". That is expected: it is "
                        + "your own app. Tap Advanced > Go to (app name) and allow access.")
                .setPositiveButton("OK", null)
                .show();
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
