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

/**
 * Phone settings and the alarm clock: what the phone still has to allow (with Xiaomi's extra switches, each with a
 * button straight to the right screen), tests from the server and of the alarm, the alarms and their sound.
 * Opened from the web app (window.DZAndroid.openSetup) and once by itself right after the first link.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_FIRST = "first", EXTRA_UPDATE = "update";
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
        Inbox.channels(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override protected void onResume() {
        super.onResume();
        // Opened from the "new version" notification: start the update right away
        if (getIntent().getBooleanExtra(EXTRA_UPDATE, false)) { getIntent().removeExtra(EXTRA_UPDATE); Updater.install(this); }
        render(); Alarms.syncAsync(this, () -> runOnUiThread(this::render)); WaitService.start(this); }

    private void render() {
        box.removeAllViews();
        box.addView(Ui.text(this, "⚙️ Ustawienia telefonu", 28, Ui.INK, true));
        long upd = Updater.available(this);
        if (upd > 0) {
            box.addView(Ui.text(this, "Jest nowa wersja aplikacji (" + upd + ", masz " + Updater.current(this) + ").", 17, Ui.WARN, true));
            box.addView(Ui.button(this, "⬆️ Aktualizuj", Ui.WARN, Color.WHITE, v -> Updater.install(this)));
        }
        JSONObject feed = Alarms.cached(this);
        boolean linked = Alarms.linked(this);
        if (getIntent().getBooleanExtra(EXTRA_FIRST, false))
            box.addView(Ui.text(this, "Raz na tym telefonie: przejdź przez kroki poniżej, żeby przypomnienia i budzik przychodziły także przy zablokowanym ekranie. Potem wróć strzałką wstecz.", 16, Ui.INK, false));

        if (!linked) {
            box.addView(Ui.text(this, "Aplikacja nie jest jeszcze połączona. Wróć, dołącz do rodziny i wybierz, kim jesteś: połączy się sama.", 17, Ui.WARN, true));
        } else {
            String name = feed == null ? "" : feed.optString("name", "");
            box.addView(Ui.text(this, "Połączono" + (name.isEmpty() ? "" : ": " + name) + ".", 17, Ui.INK, true));
        }

        phoneSection();
        if (!linked) return;

        box.addView(Ui.text(this, "Sprawdź", 22, Ui.INK, true));
        box.addView(Ui.button(this, "🔔 Próba z serwera (zablokuj ekran)", Ui.ACCENT, Color.WHITE, v -> serverTest()));
        box.addView(Ui.text(this, "Serwer wyśle przypomnienie tą samą drogą co prawdziwe. Ma przyjść w ciągu ok. 30 sekund, z dźwiękiem, na zablokowanym ekranie.", 14, Ui.MUTED, false));
        box.addView(Ui.button(this, "⏰ Próbny budzik za 10 sekund", Ui.LIGHT, Ui.ACCENT, v -> {
            Alarms.setClock(this, System.currentTimeMillis() + 10_000, Alarms.ringIntent(this, "test", "", true));
            Toast.makeText(this, "Zablokuj ekran. Za 10 sekund zadzwoni próbny budzik.", Toast.LENGTH_LONG).show();
        }));
        long seen = Alarms.prefs(this).getLong("inbox_seen", 0);
        if (seen > 0) box.addView(Ui.text(this, "Ostatnie sprawdzenie przypomnień: " + time(seen) + ".", 14, Ui.MUTED, false));

        box.addView(Ui.text(this, "🔊 Dźwięk powiadomień", 22, Ui.INK, true));
        if (ParentSound.on(this)) {
            // Read only here: the parent changes it in the web app (panel rodzica), the child has no switch for it
            box.addView(Ui.text(this, "Ustawia rodzic: " + ParentSound.name(ParentSound.sound(this)) + ", głośność " + ParentSound.volume(this) + "%.", 16, Ui.INK, false));
            box.addView(Ui.text(this, "Aplikacja gra ten dźwięk sama, jak budzik, także przy wyciszonym telefonie.", 14, Ui.MUTED, false));
        } else {
            box.addView(Ui.text(this, "Decyduje telefon (dźwięk powiadomień w ustawieniach systemu). Rodzic może go ustawić w panelu rodzica.", 15, Ui.MUTED, false));
        }

        box.addView(Ui.text(this, "⏰ Budzik", 22, Ui.INK, true));
        String name = feed == null ? "" : feed.optString("name", "");
        box.addView(Ui.text(this, feed == null ? "Pobieram budziki…" : "Budziki: " + name, 18, Ui.INK, true));
        JSONArray list = feed == null ? null : feed.optJSONArray("alarms");
        int shown = 0;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null || Alarms.isMessage(a)) continue; // messages ring at once, they are not on the list
            shown++;
            StringBuilder d = new StringBuilder();
            JSONArray days = a.optJSONArray("days");
            for (int k = 1; k <= 7; k++) if (Alarms.hasDay(a, k % 7)) d.append(d.length() > 0 ? ", " : "").append(WD[k % 7]);
            box.addView(Ui.text(this, a.optString("at") + "   " + d + (a.optBoolean("enabled") ? "" : "   (wyłączony)"), 17, a.optBoolean("enabled") ? Ui.INK : Ui.MUTED, false));
        }
        if (list != null && shown == 0) box.addView(Ui.text(this, "Rodzic nie ustawił jeszcze budzika.", 16, Ui.MUTED, false));
        Alarms.Next n = Alarms.next(feed);
        if (n != null && !Alarms.isMessage(Alarms.alarm(feed, n.id))) {
            ZonedDateTime now = ZonedDateTime.now(n.when.getZone());
            long days = ChronoUnit.DAYS.between(now.toLocalDate(), n.when.toLocalDate());
            String when = days == 0 ? "dziś" : days == 1 ? "jutro" : WD[Alarms.dow(n.when.toLocalDate())];
            box.addView(Ui.text(this, "Następny: " + when + " o " + n.when.format(DateTimeFormatter.ofPattern("HH:mm")), 18, Ui.ACCENT, true));
        }

        box.addView(Ui.text(this, "Dźwięk budzika: " + soundName(), 16, Ui.INK, false));
        box.addView(Ui.button(this, "🎵 Wybierz dźwięk telefonu", Ui.LIGHT, Ui.ACCENT, v -> pickTone()));
        box.addView(Ui.button(this, "🎶 Wybierz własną piosenkę", Ui.LIGHT, Ui.ACCENT, v -> pickSong()));
        if (Alarms.sound(this) != null)
            box.addView(Ui.button(this, "Wróć do domyślnego dźwięku alarmu", Ui.LIGHT, Ui.MUTED, v -> { setSound(null); }));

        box.addView(Ui.button(this, "Odśwież budziki", Ui.LIGHT, Ui.ACCENT, v -> Alarms.syncAsync(this, () -> runOnUiThread(() -> {
            render(); Toast.makeText(this, "Budziki odświeżone.", Toast.LENGTH_SHORT).show();
        }))));
        box.addView(Ui.text(this, "Aplikacja", 22, Ui.INK, true));
        box.addView(Ui.text(this, "Wersja " + Updater.current(this) + ". Zmiany na stronie przychodzą same; nowa wersja aplikacji instaluje się przyciskiem, bez pobierania ze strony.", 14, Ui.MUTED, false));
        box.addView(Ui.button(this, "Sprawdź aktualizację", Ui.LIGHT, Ui.ACCENT, v -> {
            android.content.Context app = getApplicationContext();
            new Thread(() -> { long nv = Updater.check(app); runOnUiThread(() -> {
                Toast.makeText(app, nv > 0 ? "Jest nowa wersja " + nv + "." : "Masz najnowszą wersję.", Toast.LENGTH_SHORT).show(); render(); }); }).start();
        }));
        long synced = Alarms.prefs(this).getLong("synced", 0);
        if (synced > 0) box.addView(Ui.text(this, "Ostatnio pobrane: " + time(synced) + ". Zmiany rodzica dochodzą zwykle w kilka sekund, najpóźniej w 15 minut.", 14, Ui.MUTED, false));
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

    private static String time(long ms) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(ms));
    }

    /** What Android and Xiaomi must allow. Android's switches the app can read; Xiaomi's it cannot, so they are ticked by hand. */
    private void phoneSection() {
        box.addView(Ui.text(this, "Co telefon musi pozwolić", 22, Ui.INK, true));
        NotificationManager nm = getSystemService(NotificationManager.class);
        check(Phone.notificationsOk(this), "Powiadomienia włączone", "Włącz powiadomienia (także „Przypomnienia o zadaniach” i „Budzik”).", "Włącz powiadomienia", Phone.notifications(this));
        if (Build.VERSION.SDK_INT >= 34)
            check(nm.canUseFullScreenIntent(), "Budzik na cały ekran: dozwolony", "Pozwól na budzik na cały ekran.", "Zezwól na pełny ekran",
                    new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
        check(Alarms.canExact(this), "Dokładne alarmy: dozwolone", "Pozwól na dokładne alarmy, żeby budzik dzwonił co do minuty.", "Zezwól na alarmy",
                new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        check(getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()), "Optymalizacja baterii Androida: wyłączona",
                "Wyłącz optymalizację baterii, żeby telefon nie usypiał aplikacji.", "Wyłącz optymalizację",
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));

        if (ParentSound.on(this))
            check(nm.isNotificationPolicyAccessGranted(), "Wyjątek od „Nie przeszkadzać”: włączony",
                    "Rodzic: włącz tej aplikacji „Dostęp do Nie przeszkadzać”. Gdy dziecko włączy całkowitą ciszę, aplikacja przełączy ją na „Tylko alarmy” i dźwięk od rodzica zagra.",
                    "Otwórz dostęp do Nie przeszkadzać", Phone.dndAccess());
        if (Phone.isXiaomi()) {
            box.addView(Ui.text(this, "Xiaomi (MIUI / HyperOS)", 20, Ui.INK, true));
            box.addView(Ui.text(this, "Xiaomi ma własne blokady. Telefon nie mówi aplikacji, czy są włączone, więc po każdym kroku zaznacz „Zrobione”. Nazwy mogą się trochę różnić zależnie od wersji systemu.", 15, Ui.MUTED, false));
            manual("mi_autostart", "1. Autostart: włącz dla „" + getString(R.string.app_name) + "”.", "Otwórz Autostart", Phone.autostart());
            manual("mi_battery", "2. Oszczędzanie baterii: wybierz „Bez ograniczeń”.", "Otwórz oszczędzanie baterii", Phone.battery(this));
            manual("mi_perms", "3. Inne uprawnienia: włącz „Wyświetlaj na ekranie blokady” i „Wyświetlaj wyskakujące okna podczas działania w tle”.", "Otwórz inne uprawnienia", Phone.otherPermissions(this));
            manual("mi_channels", "4. Powiadomienia: w „Przypomnienia o zadaniach” i „Budzik” włącz „Ekran blokady”, „Dźwięk” i „Wyskakujące powiadomienia”.", "Otwórz powiadomienia", Phone.notifications(this));
            manual("mi_lock", "5. Ostatnie aplikacje: otwórz listę ostatnich aplikacji, przytrzymaj kartę „" + getString(R.string.app_name) + "” i stuknij kłódkę. Wtedy czyszczenie pamięci jej nie zamyka.", null, null);
        } else {
            box.addView(Ui.text(this, "Na niektórych telefonach (np. Samsung, Huawei, Oppo) też trzeba zdjąć ograniczenia baterii dla tej aplikacji w jej ustawieniach systemowych.", 15, Ui.MUTED, false));
            box.addView(Ui.button(this, "Ustawienia aplikacji w systemie", Ui.LIGHT, Ui.ACCENT, v -> Phone.open(this, Phone.appDetails(this))));
        }
        box.addView(Ui.text(this, "Telefon dziecka z Family Link: w Family Link ustaw tę aplikację jako „Zawsze dozwolona”, inaczej limit czasu ją zatrzyma.", 15, Ui.MUTED, false));
    }

    private void check(boolean ok, String okText, String msg, String label, Intent settings) {
        if (ok) { box.addView(Ui.text(this, "✅ " + okText, 16, Ui.INK, false)); return; }
        box.addView(Ui.text(this, "⚠️ " + msg, 16, Ui.WARN, true));
        box.addView(Ui.button(this, label, Ui.WARN, Color.WHITE, v -> Phone.open(this, settings)));
    }

    /** A step the app cannot check: a button to the screen and a "done" tick kept on this phone. */
    private void manual(String key, String msg, String label, Intent settings) {
        boolean done = Alarms.prefs(this).getBoolean(key, false);
        box.addView(Ui.text(this, (done ? "✅ " : "⚠️ ") + msg, 16, done ? Ui.INK : Ui.WARN, !done));
        if (settings != null) box.addView(Ui.button(this, label, done ? Ui.LIGHT : Ui.WARN, done ? Ui.ACCENT : Color.WHITE, v -> {
            if (!Phone.open(this, settings)) Toast.makeText(this, "Ten telefon nie ma tego ekranu. Otwieram ustawienia aplikacji: poszukaj tam podobnej opcji.", Toast.LENGTH_LONG).show();
        }));
        box.addView(Ui.button(this, done ? "Cofnij „Zrobione”" : "✓ Zrobione", Ui.LIGHT, Ui.MUTED, v -> {
            Alarms.prefs(this).edit().putBoolean(key, !done).apply();
            render();
        }));
    }

    /** Asks the server to send a test notification the real way (dz_native_test, migration 011) after 10 seconds. */
    private void serverTest() {
        Toast.makeText(this, "Zablokuj teraz ekran. Przypomnienie przyjdzie w ciągu ok. 30 sekund.", Toast.LENGTH_LONG).show();
        WaitService.start(this);
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
            String r = Alarms.rpcRaw(app, "dz_native_test", new JSONObject());
            if (!"true".equals(r)) runOnUiThread(() -> Toast.makeText(app,
                    r == null ? "Nie udało się połączyć z serwerem (brak internetu albo serwer nie jest jeszcze zaktualizowany)." : "Aplikacja nie jest połączona.",
                    Toast.LENGTH_LONG).show());
            // If the long wait (dz-czekaj) is not running, still pick it up a moment later
            try { Thread.sleep(20_000); } catch (InterruptedException ignored) {}
            Inbox.fetch(app);
        }).start();
    }
}
