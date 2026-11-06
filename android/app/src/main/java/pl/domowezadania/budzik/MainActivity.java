package pl.domowezadania.budzik;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Status screen: who is linked, the alarms, what the phone still has to allow, and a test. */
public class MainActivity extends Activity {
    private static final String[] WD = {"nd", "pn", "wt", "śr", "cz", "pt", "sb"};
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20); box.setPadding(p, p, p, p);
        sv.addView(box);
        setContentView(sv);
        RingService.channel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    @Override protected void onResume() { super.onResume(); render(); Alarms.syncAsync(this, () -> runOnUiThread(this::render)); }

    /** dzbudzik://link?s=SECRET from the web app's button. */
    private void handle(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"dzbudzik".equals(u.getScheme())) return;
        String s = u.getQueryParameter("s");
        if (s == null || s.length() < 32) return;
        Alarms.prefs(this).edit().putString("secret", s).remove("feed").putBoolean("unlinked", false).apply();
        Toast.makeText(this, "Połączono z Domowymi Zadaniami.", Toast.LENGTH_LONG).show();
        Alarms.syncAsync(this, () -> runOnUiThread(this::render));
    }

    private void render() {
        box.removeAllViews();
        box.addView(Ui.text(this, "⏰ Budzik DZ", 28, Ui.INK, true));
        JSONObject feed = Alarms.cached(this);
        boolean linked = Alarms.secret(this) != null && !Alarms.prefs(this).getBoolean("unlinked", false);

        if (!linked) {
            box.addView(Ui.text(this, "Budzik nie jest jeszcze połączony z Domowymi Zadaniami.", 17, Ui.INK, false));
            box.addView(Ui.text(this, "Otwórz Domowe Zadania w Chrome na tym telefonie, w części „Mój budzik” stuknij „📲 Budzik na Androida”.", 16, Ui.MUTED, false));
            box.addView(Ui.button(this, "Otwórz Domowe Zadania", Ui.ACCENT, Color.WHITE, v -> openApp()));
            return;
        }

        String name = feed == null ? "" : feed.optString("name", "");
        box.addView(Ui.text(this, feed == null ? "Pobieram budziki…" : "Budziki: " + name, 18, Ui.INK, true));
        JSONArray list = feed == null ? null : feed.optJSONArray("alarms");
        if (list != null && list.length() == 0) box.addView(Ui.text(this, "Rodzic nie ustawił jeszcze budzika.", 16, Ui.MUTED, false));
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            StringBuilder d = new StringBuilder();
            JSONArray days = a.optJSONArray("days");
            for (int k = 1; k <= 7; k++) if (Alarms.hasDay(a, k % 7)) d.append(d.length() > 0 ? ", " : "").append(WD[k % 7]);
            box.addView(Ui.text(this, a.optString("at") + "   " + d + (a.optBoolean("enabled") ? "" : "   (wyłączony)"), 17, a.optBoolean("enabled") ? Ui.INK : Ui.MUTED, false));
        }
        Alarms.Next n = Alarms.next(feed);
        if (n != null) {
            ZonedDateTime now = ZonedDateTime.now(n.when.getZone());
            long days = ChronoUnit.DAYS.between(now.toLocalDate(), n.when.toLocalDate());
            String when = days == 0 ? "dziś" : days == 1 ? "jutro" : WD[Alarms.dow(n.when.toLocalDate())];
            box.addView(Ui.text(this, "Następny: " + when + " o " + n.when.format(DateTimeFormatter.ofPattern("HH:mm")), 18, Ui.ACCENT, true));
        }

        // What the phone must allow, each with a button straight to the right setting
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled())
            warn("Włącz powiadomienia, inaczej budzik nie pokaże się na zablokowanym ekranie.", "Włącz powiadomienia",
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent())
            warn("Pozwól na budzik na cały ekran.", "Zezwól na pełny ekran",
                    new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
        if (!Alarms.canExact(this))
            warn("Pozwól na dokładne alarmy, żeby budzik dzwonił co do minuty.", "Zezwól na alarmy",
                    new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
            warn("Wyłącz oszczędzanie baterii dla budzika, żeby telefon go nie usypiał.", "Wyłącz oszczędzanie",
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));

        box.addView(Ui.text(this, "Dźwięk budzika: " + soundName(), 16, Ui.INK, false));
        box.addView(Ui.button(this, "🎵 Wybierz dźwięk telefonu", Ui.LIGHT, Ui.ACCENT, v -> pickTone()));
        box.addView(Ui.button(this, "🎶 Wybierz własną piosenkę", Ui.LIGHT, Ui.ACCENT, v -> pickSong()));
        if (Alarms.sound(this) != null)
            box.addView(Ui.button(this, "Wróć do domyślnego dźwięku alarmu", Ui.LIGHT, Ui.MUTED, v -> { setSound(null); }));

        box.addView(Ui.button(this, "🔔 Próbny budzik za 10 sekund", Ui.LIGHT, Ui.ACCENT, v -> {
            Alarms.setClock(this, System.currentTimeMillis() + 10_000, Alarms.ringIntent(this, "test", "", true));
            Toast.makeText(this, "Zablokuj ekran. Za 10 sekund zadzwoni próbny budzik.", Toast.LENGTH_LONG).show();
        }));
        box.addView(Ui.button(this, "Odśwież budziki", Ui.LIGHT, Ui.ACCENT, v -> Alarms.syncAsync(this, () -> runOnUiThread(() -> {
            render(); Toast.makeText(this, "Budziki odświeżone.", Toast.LENGTH_SHORT).show();
        }))));
        box.addView(Ui.button(this, "Otwórz Domowe Zadania", Ui.LIGHT, Ui.ACCENT, v -> openApp()));
        long synced = Alarms.prefs(this).getLong("synced", 0);
        if (synced > 0) box.addView(Ui.text(this, "Ostatnio pobrane: " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(synced)) + ". Zmiany rodzica dochodzą w ciągu 15 minut.", 14, Ui.MUTED, false));
    }

    private static final int PICK_TONE = 11, PICK_SONG = 12;

    private void pickTone() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Dźwięk budzika")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alarms.sound(this) != null ? Alarms.sound(this) : Settings.System.DEFAULT_ALARM_ALERT_URI);
        try { startActivityForResult(i, PICK_TONE); } catch (Exception e) { Toast.makeText(this, "Ten telefon nie ma listy dźwięków. Wybierz własną piosenkę.", Toast.LENGTH_LONG).show(); }
    }

    private void pickSong() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        try { startActivityForResult(i, PICK_SONG); } catch (Exception e) { Toast.makeText(this, "Nie udało się otworzyć plików.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == PICK_TONE) {
            Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            // "Default" means: follow the phone's alarm sound
            setSound(u == null || Settings.System.DEFAULT_ALARM_ALERT_URI.equals(u) ? null : u);
        } else if (req == PICK_SONG && data.getData() != null) {
            Uri u = data.getData();
            // Keep the right to read the song after a restart
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            setSound(u);
        }
    }

    private void setSound(Uri u) {
        Alarms.prefs(this).edit().putString("sound", u == null ? null : u.toString()).apply();
        render();
        Toast.makeText(this, "Zapisano. Sprawdź próbnym budzikiem.", Toast.LENGTH_SHORT).show();
    }

    private String soundName() {
        Uri u = Alarms.sound(this);
        if (u == null) return "domyślny dźwięk alarmu telefonu";
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) >= 0) {
                String n = c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME));
                if (n != null) return n;
            }
        } catch (Exception ignored) {}
        try { String t = RingtoneManager.getRingtone(this, u).getTitle(this); if (t != null) return t; } catch (Exception ignored) {}
        return "wybrany dźwięk";
    }

    private void warn(String msg, String label, Intent settings) {
        box.addView(Ui.text(this, "⚠️ " + msg, 16, Ui.WARN, true));
        box.addView(Ui.button(this, label, Ui.WARN, Color.WHITE, v -> {
            try { startActivity(settings); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        }));
    }

    private void openApp() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Alarms.APP_URL + "#moje"))); } catch (Exception ignored) {}
    }
}
