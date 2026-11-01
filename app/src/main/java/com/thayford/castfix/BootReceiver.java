package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs the fix after every restart; BOOT_COMPLETED is one of the few moments a background app may start a service.
 * Also (re)schedules the watchdog, after a restart and after an update of this app.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Watchdog.schedule(context);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        Watchdog.schedule(context);
        context.startForegroundService(new Intent(context, CastFixService.class)
                .putExtra(CastFixService.EXTRA_TRIGGER, "restart"));
    }
}
