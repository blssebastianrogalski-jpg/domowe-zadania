package pl.domowezadania.budzik;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.OffsetDateTime;

/**
 * Notifications from the server without Chrome: task reminders, the afternoon summary, a parent's "help" and
 * "done" news. dz-reminders puts them in dz_native_inbox (migration 011); the app picks them up with
 * dz_native_inbox_read when dz-czekaj says something changed (within seconds) and on the 5 minute sync.
 * A reminder of the same task replaces the previous one, like in the web app.
 */
final class Inbox {
    static final String CHANNEL_TASKS = "zadania", CHANNEL_PARENT = "rodzic";
    /** Older than this when it finally arrives (the phone slept): skipped, a stale reminder only confuses. */
    private static final long STALE_MS = 15 * 60_000;
    private static final long[] VIBRATE = {0, 600, 250, 600, 250, 900};

    private Inbox() {}

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        NotificationChannel t = new NotificationChannel(CHANNEL_TASKS, "Przypomnienia o zadaniach", NotificationManager.IMPORTANCE_HIGH);
        t.setDescription("Przypomnienia o zadaniach i podsumowania na dziś");
        t.enableVibration(true); t.setVibrationPattern(VIBRATE);
        t.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
        t.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel p = new NotificationChannel(CHANNEL_PARENT, "Wiadomości dla rodzica", NotificationManager.IMPORTANCE_HIGH);
        p.setDescription("Dziecko prosi o pomoc, zrobiło zadanie albo nie wyłączyło budzika");
        p.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
        p.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(t);
        nm.createNotificationChannel(p);
    }

    /** Fetches and shows new notifications (blocking, call off the main thread). */
    static void fetch(Context c) {
        if (Alarms.secret(c) == null) return;
        long after = Alarms.prefs(c).getLong("inbox_after", 0);
        String body;
        try { body = Alarms.rpcRaw(c, "dz_native_inbox_read", new JSONObject().put("p_after", after)); }
        catch (Exception e) { return; }
        if (body == null || body.equals("null")) return; // no network, migration 011 missing, or unlinked
        try {
            JSONObject r = new JSONObject(body);
            JSONArray items = r.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                long at = 0;
                try { at = OffsetDateTime.parse(it.optString("at")).toInstant().toEpochMilli(); } catch (Exception ignored) {}
                if (at > 0 && System.currentTimeMillis() - at > STALE_MS) continue;
                JSONObject p = it.optJSONObject("p");
                if (p != null) show(c, p);
            }
            Alarms.prefs(c).edit().putLong("inbox_after", r.optLong("last", after)).putLong("inbox_seen", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    /** Opens the app (WebView) at a path of the web app, e.g. "?task=ID&a=done#moje". */
    static PendingIntent open(Context c, String path, int code) {
        Intent i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, JSONObject p) {
        channels(c);
        String kind = p.optString("kind"), task = p.optString("task", "x");
        boolean parent = "help".equals(kind);
        int id = 1000 + (task.hashCode() & 0xffff);
        Notification.Builder b = new Notification.Builder(c, parent ? CHANNEL_PARENT : CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(p.optString("title", "Domowe Zadania"))
                .setContentText(p.optString("body"))
                .setStyle(new Notification.BigTextStyle().bigText(p.optString("body")))
                .setCategory(parent ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true);
        if ("test".equals(kind)) {
            b.setContentIntent(PendingIntent.getActivity(c, id, new Intent(c, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE));
        } else {
            b.setContentIntent(open(c, parent ? "#rodzic" : "#moje", id));
        }
        // Task reminders: the same no-penalty buttons as in the web app
        if (("remind".equals(kind) || "snooze".equals(kind)) && !task.isEmpty() && !"x".equals(task)) {
            b.addAction(new Notification.Action.Builder(null, "Zrobione", open(c, "?task=" + task + "&a=done#moje", id * 4 + 1)).build());
            b.addAction(new Notification.Action.Builder(null, "Za 15 min", open(c, "?task=" + task + "&a=snooze15#moje", id * 4 + 2)).build());
        }
        if ("nudge".equals(kind) && p.has("count")) b.setNumber(p.optInt("count"));
        try { c.getSystemService(NotificationManager.class).notify(id, b.build()); } catch (Exception ignored) {}
    }
}
