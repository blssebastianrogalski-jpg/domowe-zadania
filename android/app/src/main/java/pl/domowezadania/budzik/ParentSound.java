package pl.domowezadania.budzik;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * The notification sound a parent chose for this child in the web app (migration 014: dz_set_sound), with its volume.
 * When it is set, the app plays the sound itself on the alarm stream at the parent's volume for every reminder,
 * call and summary, and the notification channels are silent. So the child cannot turn the sound down in the
 * channel settings, and the ringer switch (silent / vibrate) does not mute it either, because alarms play through.
 * What an app cannot prevent: the child turning off the alarm volume between two sounds (the app sets it back each
 * time), "Nie przeszkadzać" set to total silence (only with the access the app asks for, see exceptDnd),
 * force-stopping or uninstalling the app. Those the phone reports (Phone.soundState) and the parent sees them.
 */
final class ParentSound {
    private ParentSound() {}

    static final String[] KEYS = {"glos_wykonaj", "glos_czas", "dzwonek", "gong", "budzik", "melodia", "syrena", "telefon"};
    static final String[] NAMES = {"Głos pani: „Hej! Wykonaj zadanie!”", "Głos pana: „Hej! Czas na zadanie!”", "Dzwonek do drzwi", "Gong", "Budzik z dzwonkami", "Wesoła melodyjka", "Wesoła syrena", "Dźwięk alarmu telefonu"};

    private static MediaPlayer player;
    private static int oldVolume = -1;

    /** The parent's choice, or null when the phone decides (the old way). */
    static String sound(Context c) { return Alarms.prefs(c).getString("ps_sound", null); }
    static int volume(Context c) { return Alarms.prefs(c).getInt("ps_volume", 80); }
    static boolean on(Context c) { return sound(c) != null; }

    static String name(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return "?";
    }

    /** Saves what the server says (dz_native_hello or a "settings" item). Changes the channels when needed. */
    static void save(Context c, JSONObject o) {
        if (o == null) return;
        String s = o.isNull("sound") ? null : o.optString("sound", null);
        if (s != null && s.isEmpty()) s = null;
        int v = o.isNull("volume") ? 80 : o.optInt("volume", 80);
        boolean was = on(c);
        Alarms.prefs(c).edit().putString("ps_sound", s).putInt("ps_volume", Math.max(10, Math.min(100, v))).apply();
        if (was != (s != null)) Inbox.channels(c);
    }

    /** The sound file: one of the app's own sounds (also in the web app's sounds/ folder, so the parent hears the
     *  same thing), or the phone's alarm sound. */
    static Uri uri(Context c) {
        String s = sound(c);
        if (s == null) return null;
        if (!"telefon".equals(s)) {
            int id = c.getResources().getIdentifier("dz_" + s, "raw", c.getPackageName());
            if (id != 0) return Uri.parse("android.resource://" + c.getPackageName() + "/" + id);
        }
        Uri u = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM);
        if (u == null) u = Settings.System.DEFAULT_ALARM_ALERT_URI;
        return u;
    }

    /** The alarm stream at the parent's volume. Returns the volume before, or -1 when nothing changed. */
    static int setVolume(Context c, int percent) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM), now = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int want = Math.max(1, Math.round(max * percent / 100f));
            if (want == now) return -1;
            am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
            return now;
        } catch (Exception e) { return -1; }
    }

    static void restoreVolume(Context c, int old) {
        if (old < 0) return;
        try { c.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, old, 0); } catch (Exception ignored) {}
    }

    /** Plays the parent's sound once (a few seconds), like a notification but on the alarm stream. Plays also when
     *  the child turned the app's notifications off: the sound does not need the notification. */
    static synchronized void play(Context c) {
        Uri u = uri(c);
        // The alarm clock or a loud reminder is already ringing: no second sound over it
        if (u == null || RingService.ringing || ReminderRing.busy()) return;
        Context app = c.getApplicationContext();
        exceptDnd(app);
        stop(app);
        oldVolume = setVolume(app, volume(app));
        MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            p.setDataSource(app, u);
            p.setOnCompletionListener(mp -> stop(app));
            p.prepare();
            p.start();
            player = p;
            // The phone's alarm sound can be long or looping: cut it after 6 seconds
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (player == p) stop(app); }, 6_000);
            Diag.sound(app, "ps " + sound(app) + " " + volume(app));
        } catch (Throwable e) {
            try { p.release(); } catch (Throwable ignored) {}
            restoreVolume(app, oldVolume); oldVolume = -1;
            Diag.error(app, "play", e);
        }
    }

    static synchronized void stop(Context c) {
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} try { player.release(); } catch (Exception ignored) {} player = null; }
        restoreVolume(c, oldVolume); oldVolume = -1;
    }

    /** With "Dostęp do Nie przeszkadzać" given to the app (a parent turns it on once, SetupActivity): when the child
     *  set total silence, switch it to "alarms only", so the parent's sound (an alarm) still plays and everything
     *  else stays quiet. Without the access the app cannot touch it and only reports it. */
    static boolean exceptDnd(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (!nm.isNotificationPolicyAccessGranted()) return false;
            if (nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
                Alarms.prefs(c).edit().putLong("ps_dnd_fixed", System.currentTimeMillis()).apply();
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
