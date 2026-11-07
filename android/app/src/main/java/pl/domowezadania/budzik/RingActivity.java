package pl.domowezadania.budzik;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Full screen alarm, also over the lock screen. The child turns a real alarm (or a parent's message) off right here,
 * by solving the challenge: the same questions the server checks as in the web app, but without Chrome, so it works
 * even when Family Link has paused Chrome, and without unlocking the phone.
 */
public class RingActivity extends Activity {
    private final BroadcastReceiver stopped = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };
    private TextView progress, question, status;
    private EditText input;
    private Button check;
    private boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.rgb(17, 24, 39));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 24); root.setPadding(p, p, p, p);
        sv.addView(root);

        TextView clock = Ui.text(this, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 64, Color.WHITE, true);
        clock.setGravity(Gravity.CENTER);
        TextView head = Ui.text(this, RingService.title, 28, Color.WHITE, true);
        head.setGravity(Gravity.CENTER);
        root.addView(clock); root.addView(head);
        String msg = RingService.message;
        if (msg != null && !RingService.test) {
            // The parent's message, big and readable, like on the web app's ring screen
            TextView m = Ui.text(this, msg, 24, Color.rgb(17, 24, 39), true);
            m.setGravity(Gravity.CENTER);
            m.setBackground(round(Color.WHITE));
            int q = Ui.dp(this, 16); m.setPadding(q, q, q, q);
            root.addView(m);
        }

        if (RingService.test) {
            TextView info = Ui.text(this, "To próbny budzik. Tak głośno zadzwoni rano, także przy wyciszonym telefonie.", 18, Color.rgb(209, 213, 219), false);
            info.setGravity(Gravity.CENTER);
            root.addView(info);
            root.addView(Ui.button(this, "Wyłącz próbny budzik", Ui.RED, Color.WHITE,
                    v -> startService(new Intent(this, RingService.class).setAction(RingService.ACTION_STOP_TEST))));
        } else {
            TextView info = Ui.text(this, (msg != null ? "Alarm" : "Budzik") + " ucichnie, gdy rozwiążesz zadania.", 18, Color.rgb(209, 213, 219), false);
            info.setGravity(Gravity.CENTER);
            progress = Ui.text(this, "", 16, Color.rgb(209, 213, 219), false);
            progress.setGravity(Gravity.CENTER);
            question = Ui.text(this, "Pobieram zadanie…", 40, Color.rgb(17, 24, 39), true);
            question.setGravity(Gravity.CENTER);
            question.setBackground(round(Color.WHITE));
            int q = Ui.dp(this, 18); question.setPadding(q, q, q, q);
            input = new EditText(this);
            input.setTextSize(30); input.setGravity(Gravity.CENTER); input.setTextColor(Color.rgb(17, 24, 39));
            input.setBackground(round(Color.WHITE)); input.setPadding(q, q / 2, q, q / 2);
            input.setSingleLine(true); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setOnEditorActionListener((v, action, ev) -> {
                if (action == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { answer(); return true; }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 12);
            input.setLayoutParams(lp);
            check = Ui.button(this, "Sprawdź", Ui.ACCENT, Color.WHITE, v -> answer());
            status = Ui.text(this, "", 17, Color.WHITE, true);
            status.setGravity(Gravity.CENTER);
            root.addView(info); root.addView(progress); root.addView(question); root.addView(input); root.addView(check); root.addView(status);
            load();
        }
        setContentView(sv);

        IntentFilter f = new IntentFilter(RingService.ACTION_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopped, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stopped, f);
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(Ui.dp(this, 18)); return d;
    }

    /** The current question from the server (never the answer). */
    private void load() {
        String id = RingService.alarmId;
        if (id == null) return;
        call("dz_alarm_challenge_link", id, null);
    }

    private void answer() {
        String v = input.getText().toString().trim();
        String id = RingService.alarmId;
        if (v.isEmpty() || id == null || busy) return;
        call("dz_alarm_answer_link", id, v);
    }

    private void call(String fn, String id, String answer) {
        busy = true; check.setEnabled(false);
        new Thread(() -> {
            JSONObject r = null;
            try {
                JSONObject args = new JSONObject().put("p_alarm", id);
                if (answer != null) args.put("p_answer", answer);
                r = Alarms.rpc(this, fn, args);
            } catch (Exception ignored) {}
            JSONObject res = r;
            runOnUiThread(() -> { busy = false; check.setEnabled(true); show(res, answer != null); });
        }).start();
    }

    private void show(JSONObject c, boolean answered) {
        if (isFinishing()) return;
        if (c == null) { status.setText("Brak internetu. Sprawdź połączenie i spróbuj jeszcze raz."); return; }
        if (!c.optBoolean("ringing")) {
            // Solved (or already off): the server turned it off, stop ringing now
            status.setText(RingService.message != null ? "Alarm wyłączony 👍" : "Budzik wyłączony. Dzień dobry! ☀️");
            startService(new Intent(this, RingService.class).setAction(RingService.ACTION_SOLVED));
            return;
        }
        boolean code = "code".equals(c.optString("kind"));
        int solved = c.optInt("solved"), total = c.optInt("total");
        progress.setText((code ? "Kod " : "Zadanie ") + (solved + 1) + " z " + total);
        String q = c.optString("q");
        question.setText(code ? q.replaceAll("(.{3})(?=.)", "$1 ") : q + " = ?");
        question.setTextSize(code ? 32 : 40);
        input.setText("");
        input.setHint(code ? "Wpisz kod" : "Wynik");
        input.setInputType(code ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_NUMBER);
        input.requestFocus();
        status.setText(!answered ? "" : c.optBoolean("correct") ? "Dobrze! Następne." : "Nie tym razem. Masz nowe zadanie.");
    }

    @Override protected void onResume() { super.onResume(); if (!RingService.ringing) finish(); }

    // Back does not turn the alarm off
    @Override public void onBackPressed() { if (RingService.test) super.onBackPressed(); }

    @Override protected void onDestroy() { try { unregisterReceiver(stopped); } catch (Exception ignored) {} super.onDestroy(); }
}
