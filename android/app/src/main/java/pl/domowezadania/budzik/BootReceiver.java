package pl.domowezadania.budzik;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are forgotten on reboot, time or zone change, and app update: schedule them again. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Alarms.schedule(c);
        PendingResult r = goAsync();
        Alarms.syncAsync(c, r::finish);
    }
}
