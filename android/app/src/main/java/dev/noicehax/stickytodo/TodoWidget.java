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
    static final String EXTRA_SECTION = "section";
    static final String SECTION_DEADLINES = "deadlines";
    static final String SECTION_TASKS = "tasks";
    /** Both scrolling lists; every refresh reloads both. */
    static final int[] LISTS = {R.id.list_deadlines, R.id.list_tasks};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, id));
        notifyLists(mgr, ids);
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

        list(ctx, rv, widgetId, R.id.list_deadlines, R.id.empty_deadlines, SECTION_DEADLINES);
        list(ctx, rv, widgetId, R.id.list_tasks, R.id.empty_tasks, SECTION_TASKS);

        // Row taps: one mutable template per list, each row fills in its id + new done state.
        Intent toggle = new Intent(ctx, TodoWidget.class).setAction(ACTION_TOGGLE);
        PendingIntent tap = PendingIntent.getBroadcast(ctx, 1, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        for (int list : LISTS) rv.setPendingIntentTemplate(list, tap);

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

    private static void list(Context ctx, RemoteViews rv, int widgetId, int listId, int emptyId, String section) {
        Intent svc = new Intent(ctx, TodoWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(EXTRA_SECTION, section);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))); // unique per widget + section
        rv.setRemoteAdapter(listId, svc);
        rv.setEmptyView(listId, emptyId);
    }

    private static void notifyLists(AppWidgetManager mgr, int[] ids) {
        for (int list : LISTS) mgr.notifyAppWidgetViewDataChanged(ids, list);
    }

    static int[] ids(Context ctx) {
        return AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, TodoWidget.class));
    }

    /** Re-fetch the list in every widget (the factory does the network call). */
    static void refreshAll(Context ctx) {
        notifyLists(AppWidgetManager.getInstance(ctx), ids(ctx));
    }

    /** Rebuild every widget from scratch, e.g. after the server URL changes. */
    static void rebuildAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = ids(ctx);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, id));
        notifyLists(mgr, ids);
    }

    /** Section labels above the two lists, e.g. "Deadlines (8)". */
    static void setSectionCounts(Context ctx, int deadlines, int today, int tasks) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.label_deadlines, today > 0
                ? ctx.getString(R.string.section_deadlines_today, deadlines, today)
                : ctx.getString(R.string.section_deadlines, deadlines));
        rv.setTextViewText(R.id.label_tasks, ctx.getString(R.string.section_tasks, tasks));
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids(ctx), rv);
    }

    static void setHeader(Context ctx, int openCount, int overdue, String error) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.title, overdue > 0 ? ctx.getString(R.string.title_count_overdue, openCount, overdue)
                : openCount > 0 ? ctx.getString(R.string.title_count, openCount) : ctx.getString(R.string.title));
        rv.setTextViewText(R.id.status, error != null ? error
                : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
        rv.setTextViewText(R.id.empty_tasks, ctx.getString(error != null ? R.string.empty_offline : R.string.empty));
        rv.setTextViewText(R.id.empty_deadlines, ctx.getString(error != null ? R.string.offline_status : R.string.empty_deadlines));
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids(ctx), rv);
    }

    static void setStatus(Context ctx, String text) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.status, text);
        AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids(ctx), rv);
    }
}
