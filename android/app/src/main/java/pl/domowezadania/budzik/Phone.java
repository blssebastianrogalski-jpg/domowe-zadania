package pl.domowezadania.budzik;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * The phone's own blocks on background apps, mostly Xiaomi (MIUI / HyperOS, also Redmi and POCO).
 * Xiaomi adds switches Android has no API for: Autostart, "Bez ograniczeń" battery saver, "Wyświetlaj na ekranie
 * blokady" and "Wyświetlaj wyskakujące okna w tle". An app cannot read their state, it can only open the right
 * screen, so the setup screen lets the person tick each step off by hand.
 * The screens below are Xiaomi's internal activities: they are not documented and can move between system
 * versions, so every one falls back to the app's system settings page.
 */
final class Phone {
    private Phone() {}

    static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }

    static Intent autostart() {
        return new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
    }

    /** Battery saver for this app: the person picks "Bez ograniczeń" (No restrictions). */
    static Intent battery(Context c) {
        return new Intent().setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", c.getPackageName())
                .putExtra("package_label", c.getString(R.string.app_name));
    }

    /** "Inne uprawnienia": show on lock screen, open pop-up windows while running in the background. */
    static Intent otherPermissions(Context c) {
        return new Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", c.getPackageName());
    }

    /** "Dostęp do Nie przeszkadzać": the list where the app is turned on (no per-app screen exists for it). */
    static Intent dndAccess() { return new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS); }

    static Intent notifications(Context c) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
    }

    /** Notifications allowed for the app and the task reminder channel not silenced. */
    static boolean notificationsOk(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = nm.getNotificationChannel(Inbox.tasksChannel(c));
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** How loud the phone is right now, for the report to the server (Inbox.report): the things that silence a
     *  notification without the app knowing. */
    static org.json.JSONObject soundState(Context c) throws org.json.JSONException {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        NotificationChannel ch = nm.getNotificationChannel(Inbox.tasksChannel(c));
        NotificationChannel call = nm.getNotificationChannel(Inbox.callChannel(c));
        int mode = am.getRingerMode();
        int filter = nm.getCurrentInterruptionFilter();
        org.json.JSONObject o = new org.json.JSONObject()
                .put("enabled", nm.areNotificationsEnabled())
                .put("ringer", mode == android.media.AudioManager.RINGER_MODE_SILENT ? "silent"
                        : mode == android.media.AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal")
                .put("dnd", filter > NotificationManager.INTERRUPTION_FILTER_ALL)
                .put("vol_notif", am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION))
                .put("vol_alarm", am.getStreamVolume(android.media.AudioManager.STREAM_ALARM))
                .put("vol_alarm_max", am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        if (ch != null) o.put("channel_importance", ch.getImportance()).put("channel_sound", ch.getSound() != null);
        if (call != null) o.put("call_importance", call.getImportance());
        // Parent's sound (migration 014): "ps" tells the web app this version knows it
        o.put("ps", true).put("ps_sound", ParentSound.sound(c) == null ? org.json.JSONObject.NULL : ParentSound.sound(c))
                .put("ps_volume", ParentSound.volume(c))
                .put("dnd_filter", filter)
                .put("dnd_access", nm.isNotificationPolicyAccessGranted());
        try { o.put("battery_free", c.getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName())); } catch (Exception ignored) {}
        return o;
    }

    /** Opens the screen; if this phone does not have it, the app's system settings instead. True if the special one opened. */
    static boolean open(Context c, Intent i) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (Exception e) {
            try { c.startActivity(appDetails(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            return false;
        }
    }
}
