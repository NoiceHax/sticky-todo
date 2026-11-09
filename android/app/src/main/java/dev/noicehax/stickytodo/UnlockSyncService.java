package dev.noicehax.stickytodo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Refreshes the widget every time the phone is unlocked.
 *
 * Android only delivers the unlock broadcast (ACTION_USER_PRESENT) to receivers registered at
 * runtime by a live process, so this foreground service exists just to keep that receiver alive.
 * It does no work between unlocks. The app never asks for notification permission, so on
 * Android 13+ its required notification stays out of the shade.
 */
public class UnlockSyncService extends Service {
    private static final String TAG = "StickyTodo";
    private static final String CHANNEL = "sync";
    private static final long MIN_GAP_MS = 15_000;
    private long lastRefresh;

    private final BroadcastReceiver onUnlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastRefresh < MIN_GAP_MS) return;
            lastRefresh = now;
            if (TodoWidget.ids(ctx).length > 0) TodoWidget.refreshAll(ctx);
        }
    };

    /** Start (or keep) the service; safe to call from anywhere, never throws. */
    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, UnlockSyncService.class));
        } catch (Exception e) {
            Log.w(TAG, "Couldn't start unlock sync", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.sync_channel), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle(getString(R.string.sync_notification))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onUnlock, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(onUnlock, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(onUnlock);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
