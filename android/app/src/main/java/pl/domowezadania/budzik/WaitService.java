package pl.domowezadania.budzik;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Waits for the parent's messages (komunikaty) all the time, so one rings within a few seconds of being sent.
 * It keeps one long request open to the dz-czekaj function, which answers as soon as the child's alarms change.
 * Runs as a quiet foreground service (a small permanent notification), so Android keeps it and its network
 * also while the phone sleeps. The 5 minute sync in Alarms stays as a fallback.
 */
public class WaitService extends Service {
    static final String CHANNEL = "nasluch";
    static final int NOTIF = 8;
    private volatile boolean running = false;
    private Thread loop;

    /** Starts the service if the app is linked. Safe to call often; Android may refuse it from the background. */
    static void start(Context c) {
        if (Alarms.secret(c) == null || Alarms.prefs(c).getBoolean("unlinked", false)) return;
        try { c.startForegroundService(new Intent(c, WaitService.class)); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF, n);
        if (!running) {
            running = true;
            loop = new Thread(this::run, "komunikaty");
            loop.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { running = false; if (loop != null) loop.interrupt(); super.onDestroy(); }

    private void run() {
        PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "budzik:komunikat");
        String since = "";
        long backoff = 2_000;
        while (running) {
            String secret = Alarms.secret(this);
            if (secret == null) break;
            JSONObject r = waitForChange(secret, since);
            if (r == null) {
                // No network or a server error: try again a bit later, up to every minute
                try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
                backoff = Math.min(backoff * 2, 60_000);
                continue;
            }
            backoff = 2_000;
            if (r.optBoolean("unlinked")) { Alarms.prefs(this).edit().putBoolean("unlinked", true).apply(); break; }
            boolean first = since.isEmpty();
            since = r.optString("at", since);
            if (r.optBoolean("changed") || first) {
                // Something changed (or we just started): fetch the alarms; a fresh message is scheduled to ring at once
                wake.acquire(20_000);
                try { Alarms.fetch(this); Alarms.schedule(this); } finally { if (wake.isHeld()) wake.release(); }
            }
        }
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** One long request: returns {"changed", "at"} or {"unlinked"}, or null on a network error. */
    private JSONObject waitForChange(String secret, String since) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(Alarms.SUPABASE_URL + "/functions/v1/dz-czekaj").openConnection();
            h.setConnectTimeout(10_000); h.setReadTimeout(75_000);
            h.setRequestMethod("POST");
            h.setRequestProperty("apikey", Alarms.SUPABASE_KEY);
            h.setRequestProperty("Content-Type", "application/json");
            h.setDoOutput(true);
            try (OutputStream o = h.getOutputStream()) {
                o.write(new JSONObject().put("secret", secret).put("since", since).toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = h.getResponseCode();
            InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) try (InputStream s = in) { byte[] buf = new byte[2048]; int n; while ((n = s.read(buf)) > 0) b.write(buf, 0, n); }
            if (code == 403) return new JSONObject().put("unlinked", true);
            if (code != 200) return null;
            return new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally { if (h != null) h.disconnect(); }
    }

    private Notification notification() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Czekanie na komunikaty", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Budzik DZ czeka na wiadomości od rodzica, żeby zadzwonić od razu");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle("Budzik DZ czeka na komunikaty")
                .setContentText("Wiadomość od rodzica zadzwoni od razu.")
                .setContentIntent(Alarms.showIntent(this)).setOngoing(true).build();
    }
}
