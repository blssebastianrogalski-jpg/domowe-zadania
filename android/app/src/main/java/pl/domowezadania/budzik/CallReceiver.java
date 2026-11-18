package pl.domowezadania.budzik;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * The child's answer to a parent's call (Inbox.showCall): "Idę!" or "Za 5 minut" from the notification's buttons,
 * or "seen" when the notification is swiped away. Sent with the device secret (dz_call_answer_link, migration 012),
 * without opening the app; the parent who called gets the answer as a notification.
 */
public class CallReceiver extends BroadcastReceiver {
    static final String EXTRA_CALL = "call", EXTRA_ANSWER = "answer";

    static PendingIntent intent(Context c, String call, String answer, int code) {
        Intent i = new Intent(c, CallReceiver.class).putExtra(EXTRA_CALL, call).putExtra(EXTRA_ANSWER, answer);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context c, Intent i) {
        String call = i.getStringExtra(EXTRA_CALL), answer = i.getStringExtra(EXTRA_ANSWER);
        if (call == null || answer == null) return;
        Context app = c.getApplicationContext();
        int id = Inbox.callNotifId(call);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        // A button does not close the notification by itself
        if (!"seen".equals(answer)) nm.cancel(id);
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String r = Alarms.rpcRaw(app, "dz_call_answer_link", new JSONObject().put("p_call", call).put("p_answer", answer));
                if (!"seen".equals(answer)) {
                    // Short confirmation, or a hint when there was no network
                    boolean ok = r != null;
                    Notification n = new Notification.Builder(app, Inbox.CHANNEL_TASKS)
                            .setSmallIcon(R.drawable.ic_stat)
                            .setContentTitle(ok ? "Wysłano: " + ("coming".equals(answer) ? "🏃 Idę!" : "⏳ Za 5 minut") : "Nie udało się wysłać odpowiedzi")
                            .setContentText(ok ? "Rodzic już wie." : "Brak internetu. Otwórz aplikację i odpowiedz tam.")
                            .setContentIntent(Inbox.open(app, "#moje", id))
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .setTimeoutAfter(ok ? 8_000 : 120_000)
                            .build();
                    nm.notify(id, n);
                }
            } catch (Exception ignored) {
            } finally { pr.finish(); }
        }, "odpowiedz").start();
    }
}
