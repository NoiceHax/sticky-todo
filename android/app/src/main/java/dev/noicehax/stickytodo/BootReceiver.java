package dev.noicehax.stickytodo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the unlock-sync service after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (TodoWidget.ids(ctx).length > 0) UnlockSyncService.start(ctx);
    }
}
