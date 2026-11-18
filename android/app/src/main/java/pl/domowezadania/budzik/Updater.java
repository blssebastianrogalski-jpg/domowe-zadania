package pl.domowezadania.budzik;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from inside the app, without the browser: checks the latest GitHub release (the workflow tags it
 * budzik-N, N = the build number = this app's versionCode), downloads domowe-zadania.apk and hands it to the
 * system installer (PackageInstaller). Android then shows its own "Zainstalować aktualizację?" window: one tap.
 * Installing with no tap at all is possible only for Google Play or company-managed phones, not for an APK app.
 * The first time Android asks to allow this app to install apps ("Instaluj nieznane aplikacje").
 */
final class Updater {
    static final String RELEASE_API = "https://api.github.com/repos/blssebastianrogalski-jpg/domowe-zadania/releases/latest";
    static final String APK_URL = "https://github.com/blssebastianrogalski-jpg/domowe-zadania/releases/latest/download/domowe-zadania.apk";
    static final String ACTION_INSTALLED = "pl.domowezadania.budzik.UPDATE_STATUS";
    static final String CHANNEL = "aktualizacje";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final int NOTIF = 9;
    private static volatile boolean installing = false;

    private Updater() {}

    static long current(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Newer build number known from the last check, or 0. */
    static long available(Context c) {
        long n = Alarms.prefs(c).getLong("update_to", 0);
        return n > current(c) ? n : 0;
    }

    /** Asks GitHub for the latest release (blocking). Returns the newer build number, or 0. */
    static long check(Context c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(8000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            if (h.getResponseCode() != 200) return available(c);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            String tag = new JSONObject(b.toString("UTF-8")).optString("tag_name", "");
            long n = Long.parseLong(tag.replaceAll("[^0-9]", "").isEmpty() ? "0" : tag.replaceAll("[^0-9]", ""));
            Alarms.prefs(c).edit().putLong("update_to", n).putLong("update_checked", System.currentTimeMillis()).apply();
            return n > current(c) ? n : 0;
        } catch (Exception e) {
            return available(c);
        } finally { if (h != null) h.disconnect(); }
    }

    /** From the background sync: at most every 6 hours; one notification per new version. */
    static void maybeCheck(Context c) {
        if (System.currentTimeMillis() - Alarms.prefs(c).getLong("update_checked", 0) < CHECK_EVERY_MS) return;
        long n = check(c);
        if (n == 0 || Alarms.prefs(c).getLong("update_notified", 0) >= n) return;
        Alarms.prefs(c).edit().putLong("update_notified", n).apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Aktualizacje aplikacji", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 7, new Intent(c, SetupActivity.class).putExtra(SetupActivity.EXTRA_UPDATE, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            nm.notify(NOTIF, new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Nowa wersja Domowych Zadań")
                    .setContentText("Stuknij, żeby zaktualizować (jedno stuknięcie, bez pobierania ze strony).")
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    /** Downloads the newest APK and starts the system installer. Call from a visible screen. */
    static void install(Activity a) {
        if (installing) { Toast.makeText(a, "Aktualizacja już się pobiera…", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Pozwól tej aplikacji instalować aplikacje (włącz przełącznik), wróć i stuknij „Aktualizuj” jeszcze raz.", Toast.LENGTH_LONG).show();
            try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
            catch (Exception e) { Phone.open(a, Phone.appDetails(a)); }
            return;
        }
        installing = true;
        Toast.makeText(a, "Pobieram aktualizację…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            String err = null;
            HttpURLConnection h = null;
            PackageInstaller.Session s = null;
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(app.getPackageName());
                int id = pi.createSession(p);
                s = pi.openSession(id);
                h = (HttpURLConnection) new URL(APK_URL).openConnection(); // GitHub redirects to its file host (https -> https)
                h.setInstanceFollowRedirects(true);
                h.setConnectTimeout(15000); h.setReadTimeout(30000);
                if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("app.apk", 0, -1)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(app, UpdateReceiver.class).setAction(ACTION_INSTALLED);
                PendingIntent cb = PendingIntent.getBroadcast(app, 8, done, PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                s.commit(cb.getIntentSender());
                s.close(); s = null;
            } catch (Exception e) {
                err = "Nie udało się pobrać aktualizacji. Sprawdź internet i spróbuj jeszcze raz.";
                if (s != null) try { s.abandon(); } catch (Exception ignored) {}
            } finally {
                if (h != null) h.disconnect();
                installing = false;
            }
            if (err != null) { String m = err; a.runOnUiThread(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show()); }
        }).start();
    }

    /** The installer's answer: asks the person to confirm (the system window), or reports a failure. */
    public static class UpdateReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) {}
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Aktualizacja się nie udała" + (msg == null ? "." : ": " + msg), Toast.LENGTH_LONG).show();
            }
        }
    }
}
