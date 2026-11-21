package pl.domowezadania.budzik;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

/**
 * A task reminder that the child ignored for a while (the server marks it "loud", see dz-reminders) rings for a
 * short moment like the alarm clock: the parent's sound (or the alarm sound) on the alarm stream, so it plays also
 * on a silent or vibrating phone, plus vibration. It stops by itself after RING_MS, when the app is opened (a tap on
 * the notification or its buttons Zrobione / Za 15 min), or when the next loud reminder takes over. No challenge to
 * solve: a reminder is not a punishment, snoozing stays free.
 *
 * It plays inside the app's process, kept alive by WaitService. Until version 24 it was a separate foreground
 * service: starting one from the background is refused or fails on newer Android (and on Xiaomi), and on the
 * children's phones the app stopped right when the first loud reminder came, after which nothing sounded any more.
 * The reminder notification itself (with the buttons) is shown by Inbox, so no extra notification is needed.
 */
final class ReminderRing {
    private ReminderRing() {}

    static final long RING_MS = 20_000;
    /** The alarm stream is raised to at least this share of its maximum while ringing, then put back. */
    private static final float MIN_VOLUME = 0.6f;

    static volatile boolean ringing = false;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static MediaPlayer player;
    private static Vibrator vibrator;
    private static PowerManager.WakeLock wake;
    private static int oldVolume = -1;
    private static long startedAt = 0;

    /** Rings once for a loud reminder. Does nothing while the real alarm clock rings. True if a sound started. */
    static synchronized boolean start(Context c, String title, String body, String task) {
        if (RingService.ringing) return false;
        Context app = c.getApplicationContext();
        stopNow(app);
        ParentSound.stop(app);
        try {
            wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "budzik:przypomnienie");
            wake.acquire(RING_MS + 5_000);
        } catch (Throwable ignored) { wake = null; }
        AudioManager am = app.getSystemService(AudioManager.class);
        if (ParentSound.on(app)) {
            // The parent chose the volume (ParentSound): exactly that, also when the child turned the alarm volume up or down
            ParentSound.exceptDnd(app);
            oldVolume = ParentSound.setVolume(app, ParentSound.volume(app));
        } else try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM), now = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int want = Math.round(max * MIN_VOLUME);
            if (now < want) { oldVolume = now; am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0); }
        } catch (Throwable ignored) {}
        AudioAttributes alarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioAttributes(alarm);
            p.setDataSource(app, sound(app));
            p.setLooping(true);
            p.prepare();
            p.start();
            player = p;
        } catch (Throwable e) {
            try { p.release(); } catch (Throwable ignored) {}
            Diag.error(app, "ring", e);
        }
        try {
            vibrator = Build.VERSION.SDK_INT >= 31 ? app.getSystemService(VibratorManager.class).getDefaultVibrator()
                    : (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 300, 700, 300, 700, 1500}, 0), alarm);
        } catch (Throwable ignored) { vibrator = null; }
        ringing = player != null;
        startedAt = System.currentTimeMillis();
        main.removeCallbacksAndMessages(TOKEN);
        main.postAtTime(() -> stop(app), TOKEN, android.os.SystemClock.uptimeMillis() + RING_MS);
        Diag.sound(app, (player != null ? "ring" : "ring_failed") + (ParentSound.on(app) ? " ps " + ParentSound.sound(app) : ""));
        if (player == null) restore(app);
        return player != null;
    }

    static synchronized void stop(Context c) {
        main.removeCallbacksAndMessages(TOKEN);
        stopNow(c.getApplicationContext());
    }

    private static void stopNow(Context app) {
        if (player != null) { try { player.stop(); } catch (Throwable ignored) {} try { player.release(); } catch (Throwable ignored) {} player = null; }
        if (vibrator != null) { try { vibrator.cancel(); } catch (Throwable ignored) {} vibrator = null; }
        restore(app);
        if (wake != null) { try { if (wake.isHeld()) wake.release(); } catch (Throwable ignored) {} wake = null; }
        ringing = false;
    }

    private static void restore(Context app) {
        if (oldVolume >= 0) {
            try { app.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0); } catch (Throwable ignored) {}
            oldVolume = -1;
        }
    }

    /** True while it rings; also false when a ring outlived its time (so it never blocks the parent's sound for good). */
    static boolean busy() {
        return ringing && System.currentTimeMillis() - startedAt < RING_MS + 5_000;
    }

    /** The parent's sound (ParentSound), else the sound chosen for the alarm clock in the app, else the phone's alarm sound. */
    private static Uri sound(Context c) {
        Uri u = ParentSound.uri(c);
        if (u == null) u = Alarms.sound(c);
        if (u == null) u = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM);
        if (u == null) u = Settings.System.DEFAULT_ALARM_ALERT_URI;
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }
}
