package pl.domowezadania.budzik;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * The whole Domowe Zadania app, natively: the web app (GitHub Pages) in a WebView, so every feature stays the same,
 * while notifications, alarms and the phone's settings are native (Inbox, RingService, SetupActivity).
 * The web app talks to the app through window.DZAndroid (Bridge below): it links this phone to the person who
 * uses it by itself, and opens the setup screen. A WebView is not Chrome, so Family Link limits on Chrome and
 * Xiaomi's sleeping of Chrome do not stop it.
 */
public class MainActivity extends Activity {
    static final String EXTRA_PATH = "path";
    private WebView web;
    private boolean loadedOk = false;
    private long pausedAt = 0;
    private boolean resumedOnce = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.install(this);
        RingService.channel(this);
        Inbox.channels(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // the web app keeps its login and settings in localStorage
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // alarm and night mode sounds in the web app
        s.setUserAgentString(s.getUserAgentString() + " DZAndroid/" + version());
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "DZAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());
        setContentView(web);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if (!handleLink(getIntent())) load(pathOf(getIntent()));
        else load("#moje");
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (handleLink(i)) return;
        String path = pathOf(i);
        if (path != null) load(path);
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        // The child opened the app (also through Zrobione / Za 15 min): a ringing reminder has done its job
        ReminderRing.stop(this);
        WaitService.start(this);
        // After a long break (or a failed start) load the app afresh, so it does not show yesterday's list
        offerUpdate();
        if (resumedOnce && (!loadedOk || (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 6 * 3600_000L))) load(null);
        resumedOnce = true;
        pausedAt = 0;
    }

    /** A newer version on GitHub: ask once per version, then the Update button stays in Ustawienia telefonu. */
    private void offerUpdate() {
        Context app = getApplicationContext();
        new Thread(() -> {
            long n = System.currentTimeMillis() - Alarms.prefs(app).getLong("update_checked", 0) > 3600_000L ? Updater.check(app) : Updater.available(app);
            if (n == 0 || Alarms.prefs(app).getLong("update_asked", 0) >= n) return;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                Alarms.prefs(app).edit().putLong("update_asked", n).apply();
                new AlertDialog.Builder(this).setTitle("Nowa wersja aplikacji")
                        .setMessage("Zaktualizować teraz? Android pokaże okno instalacji, wystarczy je potwierdzić.")
                        .setPositiveButton("Aktualizuj", (d, w) -> Updater.install(this))
                        .setNegativeButton("Później", null).show();
            });
        }).start();
    }

    @Override protected void onPause() { web.onPause(); pausedAt = System.currentTimeMillis(); super.onPause(); }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    private static String pathOf(Intent i) { return i == null ? null : i.getStringExtra(EXTRA_PATH); }

    /** Loads the web app, optionally at "#moje" or "?task=ID&a=done#moje" (from a notification button). */
    private void load(String path) {
        web.loadUrl(Alarms.APP_URL + (path == null ? "" : path));
    }

    /** dzbudzik://link?s=SECRET: from the web app's button in Chrome (older way of linking, still works). */
    private boolean handleLink(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"dzbudzik".equals(u.getScheme())) return false;
        linkFromUri(u);
        return true;
    }

    private void linkFromUri(Uri u) {
        String s = u.getQueryParameter("s");
        if (s == null || s.length() < 32) return;
        Alarms.link(this, s, "");
        Toast.makeText(this, "Połączono z Domowymi Zadaniami.", Toast.LENGTH_LONG).show();
        afterLink();
    }

    private void afterLink() {
        WaitService.start(this);
        Alarms.syncAsync(this, null);
        // First link on this phone: walk through the phone's settings once (Xiaomi blocks background apps)
        if (!Alarms.prefs(this).getBoolean("setup_shown", false)) {
            Alarms.prefs(this).edit().putBoolean("setup_shown", true).apply();
            startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_FIRST, true));
        }
    }

    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String url = u.toString();
            if (url.startsWith(Alarms.APP_URL)) return false; // the web app itself stays inside
            if ("dzbudzik".equals(u.getScheme())) { linkFromUri(u); return true; }
            if ("intent".equals(u.getScheme())) {
                try {
                    Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    if (i.getData() != null && "dzbudzik".equals(i.getData().getScheme())) { linkFromUri(i.getData()); return true; }
                    String fallback = i.getStringExtra("browser_fallback_url");
                    if (fallback != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                } catch (Exception ignored) {}
                return true;
            }
            // Everything else (GitHub, e-dziennik, mail) opens outside, in the phone's browser or app
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
            return true;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (url != null && url.startsWith(Alarms.APP_URL)) loadedOk = true;
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            loadedOk = false;
            v.loadDataWithBaseURL(null, "<html><body style='font-family:sans-serif;padding:24px;font-size:18px'>"
                    + "<h2>Brak połączenia</h2><p>Domowe Zadania potrzebują internetu. Budzik i przypomnienia działają dalej.</p>"
                    + "<p><a href='" + Alarms.APP_URL + "' style='font-size:20px'>Spróbuj ponownie</a></p></body></html>",
                    "text/html", "UTF-8", null);
        }
    }

    /** window.DZAndroid in the web app. Called on a background thread by the WebView. */
    private final class Bridge {
        /** Member id this app is linked to, "" if none (then the web app links it). */
        @JavascriptInterface public String linkedMember() {
            return Alarms.linked(MainActivity.this) ? Alarms.prefs(MainActivity.this).getString("member", "") : "";
        }

        /** New device secret from the web app (dz_alarm_link) for the person using this phone. */
        @JavascriptInterface public void link(String secret, String member, String name) {
            if (secret == null || secret.length() < 32) return;
            Alarms.link(MainActivity.this, secret, member == null ? "" : member);
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Powiadomienia połączone" + (name == null || name.isEmpty() ? "." : ": " + name + "."), Toast.LENGTH_SHORT).show();
                afterLink();
            });
        }

        @JavascriptInterface public boolean notifOk() { return Phone.notificationsOk(MainActivity.this); }

        @JavascriptInterface public void openSetup() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, SetupActivity.class)));
        }

        @JavascriptInterface public String version() { return MainActivity.this.version(); }
    }
}
