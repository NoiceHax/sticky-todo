package dev.noicehax.stickytodo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

/**
 * Home-screen widget: header (title + refresh + add) over a scrolling list of tasks.
 * Tapping a task toggles it done/undone on the server.
 */
public class TodoWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "dev.noicehax.stickytodo.TOGGLE";
    static final String ACTION_REFRESH = "dev.noicehax.stickytodo.REFRESH";
    static final String EXTRA_ID = "id";
    static final String EXTRA_DONE = "done";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, id));
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.list);
        UnlockSyncService.start(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        // Last widget removed: nothing left to refresh.
        ctx.stopService(new Intent(ctx, UnlockSyncService.class));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            setStatus(ctx, ctx.getString(R.string.syncing));
            refreshAll(ctx);
        } else if (ACTION_TOGGLE.equals(action)) {
            String id = intent.getStringExtra(EXTRA_ID);
            boolean done = intent.getBooleanExtra(EXTRA_DONE, true);
            if (id == null) return;
            PendingResult pending = goAsync();
            Context app = ctx.getApplicationContext();
            new Thread(() -> {
                try {
                    Api.setDone(app, id, done);
                } catch (Exception e) {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> Toast.makeText(app, app.getString(R.string.offline_toast), Toast.LENGTH_SHORT).show());
                } finally {
                    refreshAll(app);
                    pending.finish();
                }
            }).start();
        }
    }

    private static RemoteViews build(Context ctx, int widgetId) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);

        Intent svc = new Intent(ctx, TodoWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))); // unique per widget
        rv.setRemoteAdapter(R.id.list, svc);
        rv.setEmptyView(R.id.list, R.id.empty);

        // Row taps: one mutable template, each row fills in its id + new done state.
        Intent toggle = new Intent(ctx, TodoWidget.class).setAction(ACTION_TOGGLE);
        rv.setPendingIntentTemplate(R.id.list, PendingIntent.getBroadcast(ctx, 1, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        Intent refresh = new Intent(ctx, TodoWidget.class).setAction(ACTION_REFRESH);
        rv.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(ctx, 2, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent add = new Intent(ctx, AddTaskActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rv.setOnClickPendingIntent(R.id.add, PendingIntent.getActivity(ctx, 3, add,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(Api.baseUrl(ctx) + "/"));
        rv.setOnClickPendingIntent(R.id.title, PendingIntent.getActivity(ctx, 4, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return rv;
    }

    static int[] ids(Context ctx) {
        return AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, TodoWidget.class));
    }

    /** Re-fetch the list in every widget (the factory does the network call). */
    static void refreshAll(Context ctx) {
        AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(ids(ctx), R.id.list);
    }

    /** Rebuild every widget from scratch, e.g. after the server URL changes. */
    static void rebuildAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = ids(ctx);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, id));
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.list);
    }

    static void setHeader(Context ctx, int openCount, String error) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.title, openCount > 0
                ? ctx.getString(R.string.title_count, openCount) : ctx.getString(R.string.title));
        rv.setTextViewText(R.id.status, error != null ? error
                : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
        rv.setTextViewText(R.id.empty, ctx.getString(error != null ? R.string.empty_offline : R.string.empty));
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids(ctx), rv);
    }

    private static void setStatus(Context ctx, String text) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.status, text);
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids(ctx), rv);
    }
}
