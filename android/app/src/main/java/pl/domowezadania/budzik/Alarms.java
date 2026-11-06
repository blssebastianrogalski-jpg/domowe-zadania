package pl.domowezadania.budzik;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** The child's alarms: read from the server with the device secret, kept on the phone, scheduled as real alarm clocks. */
final class Alarms {
    static final String SUPABASE_URL = "https://jfirrtnbpshtijdqankt.supabase.co";
    static final String SUPABASE_KEY = "sb_publishable_UHW9kVafsscGUjYZdHXM-g_YxKN0E3N";
    static final String APP_URL = "https://blssebastianrogalski-jpg.github.io/domowe-zadania/";
    static final long RING_MINUTES = 60;

    static final String ACTION_RING = "pl.domowezadania.budzik.RING";
    static final String ACTION_SYNC = "pl.domowezadania.budzik.SYNC";
    static final String EXTRA_ID = "id", EXTRA_AT = "at", EXTRA_TEST = "test", EXTRA_START = "start";

    private Alarms() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("budzik", Context.MODE_PRIVATE); }
    /** Alarm sound chosen in the app, or null for the phone's default alarm sound. */
    static android.net.Uri sound(Context c) {
        String s = prefs(c).getString("sound", null);
        return s == null ? null : android.net.Uri.parse(s);
    }

    static String secret(Context c) { return prefs(c).getString("secret", null); }

    static JSONObject cached(Context c) {
        try { String s = prefs(c).getString("feed", null); return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    /** Fetches the feed (blocking, call off the main thread). Returns null without a network; keeps the last good copy. */
    static JSONObject fetch(Context c) {
        String secret = secret(c);
        if (secret == null) return null;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(SUPABASE_URL + "/rest/v1/rpc/dz_alarm_feed").openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(8000);
            h.setRequestMethod("POST");
            h.setRequestProperty("apikey", SUPABASE_KEY);
            h.setRequestProperty("Content-Type", "application/json");
            h.setDoOutput(true);
            try (OutputStream o = h.getOutputStream()) { o.write(new JSONObject().put("p_secret", secret).toString().getBytes(StandardCharsets.UTF_8)); }
            if (h.getResponseCode() != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            String body = b.toString("UTF-8").trim();
            if (body.equals("null")) { prefs(c).edit().putString("feed", null).putBoolean("unlinked", true).apply(); return null; }
            JSONObject feed = new JSONObject(body);
            prefs(c).edit().putString("feed", feed.toString()).putLong("synced", System.currentTimeMillis()).putBoolean("unlinked", false).apply();
            return feed;
        } catch (Exception e) {
            return null;
        } finally { if (h != null) h.disconnect(); }
    }

    static ZoneId zone(JSONObject feed) {
        try { return ZoneId.of(feed.optString("tz", "Europe/Warsaw")); } catch (Exception e) { return ZoneId.systemDefault(); }
    }

    static JSONObject alarm(JSONObject feed, String id) {
        if (feed == null) return null;
        JSONArray a = feed.optJSONArray("alarms");
        for (int i = 0; a != null && i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        return null;
    }

    static boolean hasDay(JSONObject a, int dow) {
        JSONArray d = a.optJSONArray("days");
        for (int i = 0; d != null && i < d.length(); i++) if (d.optInt(i) == dow) return true;
        return false;
    }

    /** Sunday = 0 like the web app and Postgres. */
    static int dow(LocalDate d) { return d.getDayOfWeek().getValue() % 7; }

    /** Next ring of all enabled alarms, or null. */
    static Next next(JSONObject feed) {
        if (feed == null) return null;
        ZoneId z = zone(feed);
        ZonedDateTime now = ZonedDateTime.now(z);
        JSONArray list = feed.optJSONArray("alarms");
        Next best = null;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null || !a.optBoolean("enabled")) continue;
            LocalTime t;
            try { t = LocalTime.parse(a.optString("at")); } catch (Exception e) { continue; }
            for (int k = 0; k < 8; k++) {
                LocalDate day = now.toLocalDate().plusDays(k);
                if (!hasDay(a, dow(day)) || day.toString().equals(a.optString("dismissed_day"))) continue;
                ZonedDateTime when = ZonedDateTime.of(day, t, z);
                if (!when.isAfter(now)) continue;
                if (best == null || when.isBefore(best.when)) best = new Next(a.optString("id"), a.optString("at"), when);
                break;
            }
        }
        return best;
    }

    static final class Next {
        final String id, at; final ZonedDateTime when;
        Next(String id, String at, ZonedDateTime when) { this.id = id; this.at = at; this.when = when; }
    }

    static PendingIntent ringIntent(Context c, String id, String at, boolean test) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_RING)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_AT, at).putExtra(EXTRA_TEST, test);
        return PendingIntent.getBroadcast(c, test ? 2 : 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent showIntent(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canExact(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    static void setClock(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (canExact(c)) am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(c)), pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Schedules the next alarm from the cached feed, and a sync every 15 minutes so the parent's changes arrive. */
    static Next schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Next n = next(cached(c));
        PendingIntent pi = ringIntent(c, n == null ? "" : n.id, n == null ? "" : n.at, false);
        if (n == null) am.cancel(pi);
        else setClock(c, n.when.toInstant().toEpochMilli(), pi);
        PendingIntent sync = PendingIntent.getBroadcast(c, 3, new Intent(c, AlarmReceiver.class).setAction(ACTION_SYNC), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (secret(c) != null) am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES, sync);
        else am.cancel(sync);
        return n;
    }

    /** Off the main thread: fetch, then schedule. */
    static void syncAsync(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        new Thread(() -> { fetch(app); schedule(app); if (done != null) done.run(); }).start();
    }
}
