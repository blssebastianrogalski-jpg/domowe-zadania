package pl.domowezadania.budzik;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.media.RingtoneManager;

/**
 * A task reminder that the child ignored for a while (the server marks it "loud", see dz-reminders) rings for a
 * short moment like the alarm clock: alarm sound on the alarm stream, so it plays also on a silent or vibrating
 * phone, plus vibration. It stops by itself after RING_MS, when the app is opened (a tap on the notification or
 * its buttons Zrobione / Za 15 min), or when the next loud reminder takes over. No challenge to solve: a reminder
 * is not a punishment, snoozing stays free.
 */
public class ReminderRing extends Service {
    static final String EXTRA_TITLE = "title", EXTRA_BODY = "body", EXTRA_TASK = "task";
    static final String ACTION_STOP = "pl.domowezadania.budzik.REMINDER_STOP";
    static final int NOTIF = 9;
    static final long RING_MS = 20_000;
    /** The alarm stream is raised to at least this share of its maximum while ringing, then put back. */
    private static final float MIN_VOLUME = 0.6f;

    static volatile boolean ringing = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private int oldVolume = -1;

    /** Rings once for a loud reminder. Does nothing while the real alarm clock rings. True if it started. */
    static boolean start(Context c, String title, String body, String task) {
        if (RingService.ringing) return false;
        try {
            c.startForegroundService(new Intent(c, ReminderRing.class)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body).putExtra(EXTRA_TASK, task));
            return true;
        } catch (Exception e) { return false; }
    }

    static void stop(Context c) {
        if (!ringing) return;
        try { c.startService(new Intent(c, ReminderRing.class).setAction(ACTION_STOP)); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String title = i == null ? null : i.getStringExtra(EXTRA_TITLE);
        Notification n = notification(title == null ? "⏰ Przypomnienie" : title, i == null ? "" : i.getStringExtra(EXTRA_BODY),
                i == null ? "" : i.getStringExtra(EXTRA_TASK));
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF, n);
        if (i == null || ACTION_STOP.equals(i.getAction())) { finish(); return START_NOT_STICKY; }
        main.removeCallbacksAndMessages(null);
        if (!ringing) ring();
        main.postDelayed(this::finish, RING_MS);
        return START_NOT_STICKY;
    }

    private void ring() {
        ringing = true;
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "budzik:przypomnienie");
        wake.acquire(RING_MS + 5_000);
        AudioManager am = getSystemService(AudioManager.class);
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM), now = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int want = Math.round(max * MIN_VOLUME);
            if (now < want) { oldVolume = now; am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0); }
        } catch (Exception ignored) {}
        AudioAttributes alarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(alarm);
            player.setDataSource(this, sound());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        vibrator = Build.VERSION.SDK_INT >= 31 ? getSystemService(VibratorManager.class).getDefaultVibrator() : (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try { vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 300, 700, 300, 700, 1500}, 0), alarm); } catch (Exception ignored) {}
    }

    /** The sound chosen for the alarm clock in the app, else the phone's alarm sound. */
    private Uri sound() {
        Uri u = Alarms.sound(this);
        if (u == null) u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (u == null) u = Settings.System.DEFAULT_ALARM_ALERT_URI;
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }

    private void finish() {
        ringing = false;
        main.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) {} vibrator = null; }
        if (oldVolume >= 0) { try { getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0); } catch (Exception ignored) {} oldVolume = -1; }
        if (wake != null && wake.isHeld()) wake.release();
        // The reminder itself stays in the notification shade (Inbox shows it separately), only this one goes
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { if (ringing) finish(); super.onDestroy(); }

    private Notification notification(String title, String body, String task) {
        RingService.channel(this);
        int id = 5000 + ((task == null ? "" : task).hashCode() & 0xffff);
        Notification.Builder b = new Notification.Builder(this, RingService.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(Inbox.open(this, "#moje", id));
        if (task != null && !task.isEmpty()) {
            b.addAction(new Notification.Action.Builder(null, "Zrobione", Inbox.open(this, "?task=" + task + "&a=done#moje", id * 4 + 1)).build());
            b.addAction(new Notification.Action.Builder(null, "Za 15 min", Inbox.open(this, "?task=" + task + "&a=snooze15#moje", id * 4 + 2)).build());
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
