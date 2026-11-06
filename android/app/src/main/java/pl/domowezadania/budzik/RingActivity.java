package pl.domowezadania.budzik;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Full screen alarm, also over the lock screen. A real alarm is turned off in the web app, by solving the challenge. */
public class RingActivity extends Activity {
    private final BroadcastReceiver stopped = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(17, 24, 39));
        int p = Ui.dp(this, 28); root.setPadding(p, p, p, p);

        TextView clock = Ui.text(this, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 72, Color.WHITE, true);
        clock.setGravity(Gravity.CENTER);
        TextView head = Ui.text(this, RingService.title, 30, Color.WHITE, true);
        head.setGravity(Gravity.CENTER);
        TextView info = Ui.text(this, RingService.test
                ? "To próbny budzik. Tak głośno zadzwoni rano, także przy wyciszonym telefonie."
                : "Budzik ucichnie, gdy rozwiążesz zadania w aplikacji Domowe Zadania.", 18, Color.rgb(209, 213, 219), false);
        info.setGravity(Gravity.CENTER);
        root.addView(clock); root.addView(head); root.addView(info);

        if (RingService.test) {
            root.addView(Ui.button(this, "Wyłącz próbny budzik", Ui.RED, Color.WHITE,
                    v -> startService(new Intent(this, RingService.class).setAction(RingService.ACTION_STOP_TEST))));
        } else {
            root.addView(Ui.button(this, "Rozwiąż zadania i wyłącz", Ui.ACCENT, Color.WHITE, v -> openWebApp()));
        }
        setContentView(root);

        IntentFilter f = new IntentFilter(RingService.ACTION_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopped, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stopped, f);
    }

    @Override protected void onResume() { super.onResume(); if (!RingService.ringing) finish(); }

    private void openWebApp() {
        Runnable open = () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Alarms.APP_URL + "#moje")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        };
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km.isKeyguardLocked()) km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { open.run(); }
        });
        else open.run();
    }

    // Back does not turn the alarm off
    @Override public void onBackPressed() { if (RingService.test) super.onBackPressed(); }

    @Override protected void onDestroy() { try { unregisterReceiver(stopped); } catch (Exception ignored) {} super.onDestroy(); }
}
