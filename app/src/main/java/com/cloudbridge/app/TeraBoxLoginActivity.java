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
        hint.setText("This is TeraBox's desktop website; pinch to zoom. Sign in with your TeraBox e-mail and "
                + "password (Google sign-in is usually refused inside an app). When your files appear, press the button.");
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
        cookies.setAcceptThirdPartyCookies(web, true);
        // Start clean so an old sign-in in this web view is never mistaken for the account being added.
        cookies.removeAllCookies(value -> web.loadUrl(site + "/main"));

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Ask for TeraBox's desktop website instead of the phone version, and show it zoomed out and zoomable.
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                refreshButton();
            }
        });
    }

    /**
     * The cookies TeraBox set, gathered from its website address and from the "dm." server that answers the API
     * (the same sign-in can be stored under either, depending on where TeraBox redirected you).
     */
    private String currentCookie() {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        String host = java.net.URI.create(site).getHost();
        java.util.List<String> urls = new java.util.ArrayList<>();
        urls.add(site);
        if (host != null) {
            String root = host.startsWith("www.") ? host.substring(4) : host;
            if (!host.startsWith("dm.")) urls.add("https://dm." + root);
            urls.add("https://" + root);
        }
        for (String u : urls) {
            String c = cookies.getCookie(u);
            if (c == null) continue;
            for (String part : c.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && !merged.containsKey(p.substring(0, eq))) merged.put(p.substring(0, eq), p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String v : merged.values()) sb.append(sb.length() == 0 ? "" : "; ").append(v);
        return sb.toString();
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
