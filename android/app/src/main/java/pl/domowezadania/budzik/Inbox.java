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
    /** Silent twins of the two channels above, used when a parent chose the sound (ParentSound): the app plays it
     *  itself, so the channel's own sound (which the child can change) is not used at all. */
    static final String CHANNEL_TASKS_PS = "zadania_od_rodzica", CHANNEL_CALL_PS = "wolanie_od_rodzica";

    static String tasksChannel(Context c) { return ParentSound.on(c) ? CHANNEL_TASKS_PS : CHANNEL_TASKS; }
    static String callChannel(Context c) { return ParentSound.on(c) ? CHANNEL_CALL_PS : CHANNEL_CALL; }
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
        if (ParentSound.on(c)) {
            NotificationChannel tp = new NotificationChannel(CHANNEL_TASKS_PS, "Przypomnienia (dźwięk od rodzica)", NotificationManager.IMPORTANCE_HIGH);
            tp.setDescription("Przypomnienia o zadaniach. Dźwięk i głośność ustawia rodzic, gra je aplikacja.");
            tp.enableVibration(true); tp.setVibrationPattern(VIBRATE); tp.setSound(null, null);
            tp.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationChannel wp = new NotificationChannel(CHANNEL_CALL_PS, "Rodzic woła (dźwięk od rodzica)", NotificationManager.IMPORTANCE_HIGH);
            wp.setDescription("Rodzic woła dziecko. Dźwięk i głośność ustawia rodzic, gra je aplikacja.");
            wp.enableVibration(true); wp.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500}); wp.setSound(null, null);
            wp.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(tp);
            nm.createNotificationChannel(wp);
        }
    }

    /** Fetches and shows new notifications (blocking, call off the main thread). Never throws: whatever goes wrong
     *  is remembered (Diag) and reported with the next hello, and the hello itself always runs. */
    static void fetch(Context c) {
        Diag.install(c);
        try { fetchItems(c); } catch (Throwable e) { Diag.error(c, "fetch", e); }
        try { hello(c); } catch (Throwable e) { Diag.error(c, "hello", e); }
    }

    /** Several threads may fetch at once (the long wait, the 5 minute sync, the test button): one at a time, so
     *  the same reminder is not shown and rung twice. */
    private static final Object FETCH = new Object();

    private static void fetchItems(Context c) throws Exception {
        if (Alarms.secret(c) == null) return;
        synchronized (FETCH) {
            long after = Alarms.prefs(c).getLong("inbox_after", 0);
            long[] server = {0};
            String body = Alarms.rpcRaw(c, "dz_native_inbox_read", new JSONObject().put("p_after", after), server);
            if (body == null || body.equals("null")) { // no network, migration 011 missing, or unlinked
                Diag.error(c, "read", new java.io.IOException("no answer from dz_native_inbox_read"));
                return;
            }
            // "Now" by the server's clock: a phone set to the wrong time must not throw fresh reminders away as old
            long now = server[0] > 0 ? server[0] : System.currentTimeMillis();
            JSONObject r = new JSONObject(body);
            JSONArray items = r.optJSONArray("items");
            JSONArray shown = new JSONArray(), skipped = new JSONArray();
            JSONObject loud = null;
            boolean sound = false;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                long at = 0;
                try { at = OffsetDateTime.parse(it.optString("at")).toInstant().toEpochMilli(); } catch (Exception ignored) {}
                if (at > 0 && now - at > STALE_MS) { skipped.put(it.optLong("id")); continue; }
                JSONObject p = it.optJSONObject("p");
                if (p == null) { skipped.put(it.optLong("id")); continue; }
                // One broken notification must not stop the others (or the report and the sound)
                try { show(c, p); } catch (Throwable e) { Diag.error(c, "show " + p.optString("kind"), e); }
                shown.put(it.optLong("id"));
                String kind = p.optString("kind");
                if (!"settings".equals(kind) && !"help".equals(kind)) sound = true;
                if (p.optBoolean("loud") && !"help".equals(kind)) loud = p;
            }
            Alarms.prefs(c).edit().putLong("inbox_after", r.optLong("last", after)).putLong("inbox_seen", System.currentTimeMillis()).commit();
            Diag.fetched(c, items == null ? 0 : items.length(), shown.length(), skipped.length(), server[0] > 0 ? System.currentTimeMillis() - server[0] : 0);
            // Report first: if anything below goes wrong, the parent still sees that the reminder reached the phone
            if (shown.length() > 0 || skipped.length() > 0) report(c, shown, skipped, loud != null);
            // Then the sound, once for the whole batch: a loud reminder rings (the newest one), else the parent's sound
            boolean rang = false;
            if (loud != null) {
                rang = ReminderRing.start(c, loud.optString("title", "⏰ Przypomnienie"), loud.optString("body"), loud.optString("task"));
            }
            if (sound && !rang && ParentSound.on(c)) ParentSound.play(c);
        }
    }

    /** Every few minutes (on each fetch, at most once a minute): tells the server how the phone is set (silent,
     *  "Nie przeszkadzać", notifications off; the parent sees it in the web app) and what the app did lately (Diag),
     *  and gets the parent's sound (migration 014). Before migration 014 it fails quietly and nothing changes. */
    static void hello(Context c) {
        long last = Alarms.prefs(c).getLong("hello_at", 0), now = System.currentTimeMillis();
        // A time in the future (the phone's clock was wrong when it was saved) must not silence the hello for good
        if (last <= now && now - last < 60_000) return;
        try {
            JSONObject state = state(c);
            String body = Alarms.rpcRaw(c, "dz_native_hello", new JSONObject().put("p_state", state));
            if (body == null || body.equals("null") || !body.startsWith("{")) return;
            Alarms.prefs(c).edit().putLong("hello_at", now).apply();
            ParentSound.save(c, new JSONObject(body));
        } catch (Throwable e) { Diag.error(c, "hello", e); }
    }

    /** The phone's settings (Phone.soundState) plus Diag. Never throws: a part that fails is left out. */
    static JSONObject state(Context c) {
        JSONObject o;
        try { o = Phone.soundState(c); } catch (Throwable e) {
            o = new JSONObject();
            try { o.put("ps", true).put("state_err", String.valueOf(e)); } catch (Exception ignored) {}
        }
        try { o.put("app", Updater.current(c)); } catch (Throwable ignored) {}
        Diag.addTo(c, o);
        return o;
    }

    /** Tells the server what the phone did with the notifications and how the phone is set (migration 013), so
     *  "the phone stays silent" can be checked from the database instead of guessed. Before migration 013 the call
     *  fails quietly. */
    static void report(Context c, JSONArray shown, JSONArray skipped, boolean loud) {
        try {
            JSONObject info = state(c).put("loud", loud).put("skipped", skipped);
            Alarms.rpcRaw(c, "dz_native_inbox_seen", new JSONObject().put("p_ids", shown).put("p_info", info));
        } catch (Throwable e) { Diag.error(c, "report", e); }
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
        // The parent changed the sound (or asked for a test): no notification, only the setting
        if ("settings".equals(kind)) {
            ParentSound.save(c, p);
            if (p.optBoolean("play")) ParentSound.play(c);
            return;
        }
        if ("call".equals(kind) && !p.optString("call").isEmpty()) { showCall(c, p); return; }
        boolean parent = "help".equals(kind);
        int id = 1000 + (task.hashCode() & 0xffff);
        Notification.Builder b = new Notification.Builder(c, parent ? CHANNEL_PARENT : tasksChannel(c))
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
        Notification.Builder b = new Notification.Builder(c, callChannel(c))
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
