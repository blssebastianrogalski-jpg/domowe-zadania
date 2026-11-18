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
    static final String CHANNEL_TASKS = "zadania", CHANNEL_PARENT = "rodzic", CHANNEL_CALL = "wolanie";
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
        NotificationChannel w = new NotificationChannel(CHANNEL_CALL, "Rodzic woła", NotificationManager.IMPORTANCE_HIGH);
        w.setDescription("Rodzic woła dziecko (np. „Tata cię woła”), z przyciskami Idę! i Za 5 minut");
        w.enableVibration(true); w.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
        w.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
        w.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(t);
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(w);
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
            JSONArray shown = new JSONArray(), skipped = new JSONArray();
            boolean loud = false;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                long at = 0;
                try { at = OffsetDateTime.parse(it.optString("at")).toInstant().toEpochMilli(); } catch (Exception ignored) {}
                if (at > 0 && System.currentTimeMillis() - at > STALE_MS) { skipped.put(it.optLong("id")); continue; }
                JSONObject p = it.optJSONObject("p");
                if (p == null) continue;
                show(c, p);
                shown.put(it.optLong("id"));
                // Several loud reminders at once ring once: the first one starts the sound
                if (!loud && p.optBoolean("loud") && !"help".equals(p.optString("kind"))) {
                    loud = ReminderRing.start(c, p.optString("title", "⏰ Przypomnienie"), p.optString("body"), p.optString("task"));
                }
            }
            Alarms.prefs(c).edit().putLong("inbox_after", r.optLong("last", after)).putLong("inbox_seen", System.currentTimeMillis()).apply();
            if (shown.length() > 0 || skipped.length() > 0) report(c, shown, skipped, loud);
        } catch (Exception ignored) {}
    }

    /** Tells the server what the phone did with the notifications and how the phone is set (migration 013), so
     *  "the phone stays silent" can be checked from the database instead of guessed. Before migration 013 the call
     *  fails quietly. */
    static void report(Context c, JSONArray shown, JSONArray skipped, boolean loud) {
        try {
            JSONObject info = Phone.soundState(c).put("loud_started", loud).put("skipped", skipped)
                    .put("app", Updater.current(c));
            Alarms.rpcRaw(c, "dz_native_inbox_seen", new JSONObject().put("p_ids", shown).put("p_info", info));
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
        if ("call".equals(kind) && !p.optString("call").isEmpty()) { showCall(c, p); return; }
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
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // A repeat of the same task replaces the old one in the shade. Removing it first makes it a new
        // notification, so it rings and pops up again instead of being updated in place without a sound.
        try { nm.cancel(id); } catch (Exception ignored) {}
        try { nm.notify(id, b.build()); } catch (Exception ignored) {}
    }

    static int callNotifId(String call) { return 3000 + (call.hashCode() & 0xffff); }

    /** A parent calls ("📣 Tata cię woła", migration 012): answer buttons work without opening the app (CallReceiver);
     *  swiping it away tells the parent the child saw it, a tap opens the app. */
    static void showCall(Context c, JSONObject p) {
        String call = p.optString("call");
        int id = callNotifId(call);
        Notification.Builder b = new Notification.Builder(c, CHANNEL_CALL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(p.optString("title", "📣 Rodzic cię woła"))
                .setContentText(p.optString("body"))
                .setStyle(new Notification.BigTextStyle().bigText(p.optString("body")))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(open(c, "#moje", id))
                .setDeleteIntent(CallReceiver.intent(c, call, "seen", id * 4 + 3))
                .addAction(new Notification.Action.Builder(null, "🏃 Idę!", CallReceiver.intent(c, call, "coming", id * 4 + 1)).build())
                .addAction(new Notification.Action.Builder(null, "⏳ Za 5 minut", CallReceiver.intent(c, call, "soon", id * 4 + 2)).build());
        try { c.getSystemService(NotificationManager.class).notify(id, b.build()); } catch (Exception ignored) {}
    }
}
