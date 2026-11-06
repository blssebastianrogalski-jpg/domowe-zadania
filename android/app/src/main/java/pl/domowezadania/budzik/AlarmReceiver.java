package pl.domowezadania.budzik;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by AlarmManager: rings (the service checks with the server first) or refreshes the alarms. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Alarms.ACTION_RING.equals(i.getAction())) {
            // Start the service right away: an alarm clock may start a foreground service only within a few seconds
            Intent s = new Intent(c, RingService.class).putExtras(i).putExtra(Alarms.EXTRA_START, System.currentTimeMillis());
            c.startForegroundService(s);
        } else if (Alarms.ACTION_SYNC.equals(i.getAction())) {
            PendingResult r = goAsync();
            Alarms.syncAsync(c, r::finish);
        }
    }
}
