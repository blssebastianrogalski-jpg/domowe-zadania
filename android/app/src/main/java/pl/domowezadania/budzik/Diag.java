package pl.domowezadania.budzik;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * What the phone did with the last reminders, sent with dz_native_hello (Inbox.hello) so a silent phone can be
 * explained from the database instead of guessed: the last error, the last crash of the app, the last fetch and
 * the last sound. Everything is short (the server keeps at most 4000 characters of the phone's state).
 */
final class Diag {
    private Diag() {}

    private static volatile boolean installed = false;

    /** Remembers a crash of the app (any thread) before Android closes it. Safe to call often. */
    static void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Alarms.prefs(app).edit().putString("diag_crash", t.getName() + ": " + trace(e))
                        .putLong("diag_crash_at", System.currentTimeMillis()).commit();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static void error(Context c, String where, Throwable e) {
        try {
            Alarms.prefs(c).edit().putString("diag_err", where + ": " + trace(e))
                    .putLong("diag_err_at", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    static void fetched(Context c, int items, int shown, int skipped, long skewMs) {
        try {
            Alarms.prefs(c).edit().putLong("diag_fetch_at", System.currentTimeMillis())
                    .putString("diag_fetch", items + "/" + shown + "/" + skipped).putLong("diag_skew", skewMs).apply();
        } catch (Throwable ignored) {}
    }

    static void sound(Context c, String what) {
        try {
            Alarms.prefs(c).edit().putString("diag_sound", what).putLong("diag_sound_at", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    /** Adds the remembered facts to the phone's state. Times are seconds ago, so the phone's clock does not matter. */
    static void addTo(Context c, JSONObject o) {
        try {
            SharedPreferences p = Alarms.prefs(c);
            long now = System.currentTimeMillis();
            put(o, p, now, "err", "diag_err", "diag_err_at");
            put(o, p, now, "crash", "diag_crash", "diag_crash_at");
            put(o, p, now, "fetch", "diag_fetch", "diag_fetch_at");
            put(o, p, now, "sound", "diag_sound", "diag_sound_at");
            if (p.contains("diag_skew")) o.put("skew_s", p.getLong("diag_skew", 0) / 1000);
            o.put("wait", WaitService.alive);
        } catch (Throwable ignored) {}
    }

    private static void put(JSONObject o, SharedPreferences p, long now, String key, String text, String at) throws Exception {
        String s = p.getString(text, null);
        if (s == null) return;
        o.put(key, s.length() > 400 ? s.substring(0, 400) : s);
        o.put(key + "_s", (now - p.getLong(at, now)) / 1000);
    }

    private static String trace(Throwable e) {
        StringWriter w = new StringWriter();
        e.printStackTrace(new PrintWriter(w));
        String s = w.toString().replace("\tat ", "").replace("\n", " | ");
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
