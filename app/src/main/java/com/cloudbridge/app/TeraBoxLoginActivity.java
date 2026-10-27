package com.cloudbridge.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Signs in on the real TeraBox website inside a web view. CloudBridge never sees the password: it only keeps
 * the session cookie that the website sets once you are signed in.
 */
public class TeraBoxLoginActivity extends Activity {
    public static final String EXTRA_SITE = "site";
    public static final String EXTRA_COOKIE = "cookie";

    private WebView web;
    private CookieManager cookies;
    private String site;
    private Button done;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sign in to TeraBox");
        site = getIntent().getStringExtra(EXTRA_SITE);
        if (site == null || site.isEmpty()) site = "https://www.1024terabox.com";

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // Targeting Android 15+ draws behind the system bars.
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        hint = new TextView(this);
        hint.setPadding(dp(14), dp(10), dp(14), dp(10));
        hint.setTextSize(14);
        hint.setTextColor(Color.parseColor("#5F6368"));
        hint.setText("Sign in below with your TeraBox e-mail and password (Google sign-in is usually refused inside "
                + "an app). When your files appear, press the button.");
        page.addView(hint);

        web = new WebView(this);
        page.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        done = new Button(this);
        done.setText("I'm signed in");
        done.setAllCaps(false);
        done.setEnabled(false);
        done.setOnClickListener(v -> finishWithCookie());
        page.addView(done);
        setContentView(page);

        cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookie(web, true);
        // Start clean so an old sign-in in this web view is never mistaken for the account being added.
        cookies.removeAllCookies(value -> web.loadUrl(site + "/main"));

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                refreshButton();
            }
        });
    }

    private String currentCookie() {
        String c = cookies.getCookie(site);
        return c == null ? "" : c;
    }

    private void refreshButton() {
        boolean signedIn = currentCookie().contains("ndus=");
        done.setEnabled(signedIn);
        if (signedIn) hint.setText("TeraBox accepted your sign-in. Press \"I'm signed in\" to continue.");
    }

    private void finishWithCookie() {
        String c = currentCookie();
        if (!c.contains("ndus=")) {
            refreshButton();
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_COOKIE, c);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
