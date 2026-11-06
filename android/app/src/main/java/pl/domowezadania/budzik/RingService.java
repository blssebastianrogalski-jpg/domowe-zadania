package pl.domowezadania.budzik;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
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

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Rings like a real alarm clock: alarm sound in a loop on the alarm stream (plays on silent), at full alarm volume,
 * vibration, and a full screen alarm on the lock screen. A real alarm stops only when the child solved the challenge
 * in the web app (the server says dismissed), when the parent turned it off, or after 60 minutes.
 */
public class RingService extends Service {
    static final String ACTION_STOP_TEST = "pl.domowezadania.budzik.STOP_TEST";
    static final String ACTION_STOPPED = "pl.domowezadania.budzik.STOPPED";
    static final String CHANNEL = "budzik";
    static final int NOTIF = 7;
    static final long POLL_MS = 15_000, TEST_MS = 30_000;

    static volatile boolean ringing = false;
    static volatile String title = "⏰ Pobudka!";
    static volatile boolean test = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private int oldVolume = -1;
    private String id, at;
    private long until;
    private boolean active = false;
    private Notification current;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACTION_STOP_TEST.equals(i.getAction())) { if (test && active) stop(); return START_NOT_STICKY; }
        // Every start through startForegroundService must be answered with startForeground, also a repeated one
        if (current == null) current = notification("Budzik", "Sprawdzam budzik…", false);
        startForegroundCompat(current);
        if (active || i == null) return START_NOT_STICKY;
        active = true;
        id = i.getStringExtra(Alarms.EXTRA_ID);
        at = i.getStringExtra(Alarms.EXTRA_AT);
        test = i.getBooleanExtra(Alarms.EXTRA_TEST, false);
        long start = i.getLongExtra(Alarms.EXTRA_START, System.currentTimeMillis());
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "budzik:ring");
        wake.acquire((Alarms.RING_MINUTES + 2) * 60_000);
        if (test) {
            until = start + TEST_MS;
            title = "⏰ Próbny budzik";
            main.post(this::ring);
            return START_NOT_STICKY;
        }
        until = start + Alarms.RING_MINUTES * 60_000;
        new Thread(() -> {
            JSONObject feed = Alarms.fetch(this);
            if (feed == null) feed = Alarms.cached(this); // no network: ring anyway, better safe than late for school
            JSONObject a = Alarms.alarm(feed, id == null ? "" : id);
            boolean ok = a != null && stillRinging(feed, a);
            String name = feed == null ? "" : feed.optString("name", "");
            title = "⏰ Pobudka" + (name.isEmpty() ? "" : ", " + name) + "!";
            main.post(ok ? this::ring : this::stop);
        }).start();
        return START_NOT_STICKY;
    }

    /** Enabled, today is one of its days, set for this time, not turned off today, within its 60 minutes. */
    private boolean stillRinging(JSONObject feed, JSONObject a) {
        if (!a.optBoolean("enabled") || !a.optString("at").equals(at)) return false;
        ZonedDateTime now = ZonedDateTime.now(Alarms.zone(feed));
        LocalDate today = now.toLocalDate();
        if (!Alarms.hasDay(a, Alarms.dow(today)) || today.toString().equals(a.optString("dismissed_day"))) return false;
        try {
            ZonedDateTime from = ZonedDateTime.of(today, LocalTime.parse(at), now.getZone());
            return !now.isBefore(from.minusMinutes(2)) && now.isBefore(from.plusMinutes(Alarms.RING_MINUTES));
        } catch (Exception e) { return false; }
    }

    private void ring() {
        ringing = true;
        AudioManager am = getSystemService(AudioManager.class);
        try {
            oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) {}
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, alarmSound());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            try {
                player.reset();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                // The chosen song may be gone (deleted file): fall back to the phone's alarm sound
                player.setDataSource(this, systemAlarm());
                player.setLooping(true); player.prepare(); player.start();
            } catch (Exception ignored) {}
        }
        long[] pattern = {0, 900, 400, 900, 400, 900, 1200};
        vibrator = Build.VERSION.SDK_INT >= 31 ? getSystemService(VibratorManager.class).getDefaultVibrator() : (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try { vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()); } catch (Exception ignored) {}

        String body = test ? "Tak zadzwoni budzik. Wyłącz go przyciskiem na ekranie." : "Żeby wyłączyć budzik, rozwiąż zadania w aplikacji Domowe Zadania.";
        current = notification(title, body, true);
        getSystemService(NotificationManager.class).notify(NOTIF, current);
        try { startActivity(new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        main.postDelayed(this::poll, test ? 1000 : POLL_MS);
    }

    /** The sound chosen in the app (a phone sound or a song), else the phone's default alarm sound. */
    private Uri alarmSound() {
        Uri chosen = Alarms.sound(this);
        if (chosen != null) return chosen;
        return systemAlarm();
    }

    private Uri systemAlarm() {
        Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (u == null) u = Settings.System.DEFAULT_ALARM_ALERT_URI;
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        return u;
    }

    /** Every 15 s: has the child solved it in the web app, or did the parent turn it off? */
    private void poll() {
        if (!ringing) return;
        if (System.currentTimeMillis() >= until) { stop(); return; }
        if (test) { main.postDelayed(this::poll, 1000); return; }
        new Thread(() -> {
            JSONObject feed = Alarms.fetch(this);
            boolean off = false;
            if (feed != null) { JSONObject a = Alarms.alarm(feed, id); off = a == null || !stillRinging(feed, a); }
            boolean stopNow = off;
            main.post(() -> { if (stopNow) stop(); else if (ringing) main.postDelayed(this::poll, POLL_MS); });
        }).start();
    }

    private void stop() {
        ringing = false;
        main.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (vibrator != null) vibrator.cancel();
        if (oldVolume >= 0) { try { getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0); } catch (Exception ignored) {} }
        sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
        Alarms.syncAsync(this, null);
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { if (ringing) stop(); super.onDestroy(); }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF, n);
    }

    static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Budzik", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Dzwoniący budzik na cały ekran");
        ch.setSound(null, null); // the sound comes from the alarm player, in a loop
        ch.enableVibration(false);
        ch.setBypassDnd(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification notification(String title, String body, boolean fullScreen) {
        channel(this);
        PendingIntent open = PendingIntent.getActivity(this, 5, new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).setContentIntent(fullScreen ? open : null);
        if (fullScreen) b.setFullScreenIntent(open, true);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
