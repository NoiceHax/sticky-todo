package dev.noicehax.stickytodo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Supplies the widget's rows. onDataSetChanged runs off the main thread, so it can hit the network. */
public class TodoWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    private static final class Factory implements RemoteViewsFactory {
        /** Done tasks stay visible (struck through) this long, so a mis-tap is easy to undo. */
        private static final double KEEP_DONE_SECS = 24 * 3600;

        private final Context ctx;
        private List<Api.Todo> rows = new ArrayList<>();

        Factory(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onDataSetChanged() {
            try {
                List<Api.Todo> all = Api.list(ctx);
                List<Api.Todo> open = new ArrayList<>();
                List<Api.Todo> recentDone = new ArrayList<>();
                double cutoff = System.currentTimeMillis() / 1000.0 - KEEP_DONE_SECS;
                for (Api.Todo t : all) {
                    if (!t.done) open.add(t);
                    else if (t.doneAt > cutoff) recentDone.add(t);
                }
                recentDone.sort((a, b) -> Double.compare(b.doneAt, a.doneAt));
                List<Api.Todo> next = new ArrayList<>(open);
                next.addAll(recentDone);
                rows = next;
                TodoWidget.setHeader(ctx, open.size(), null);
            } catch (Exception e) {
                // Keep showing the last list we had; just flag that it's stale.
                int open = 0;
                for (Api.Todo t : rows) if (!t.done) open++;
                TodoWidget.setHeader(ctx, open, ctx.getString(R.string.offline_status));
            }
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_item);
            if (position >= rows.size()) return rv;
            Api.Todo t = rows.get(position);
            if (t.done) {
                SpannableString s = new SpannableString(t.text);
                s.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new ForegroundColorSpan(Color.parseColor("#8A7D3F")), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                rv.setTextViewText(R.id.text, s);
                rv.setImageViewResource(R.id.check, R.drawable.ic_checked);
            } else {
                rv.setTextViewText(R.id.text, t.text);
                rv.setImageViewResource(R.id.check, R.drawable.ic_unchecked);
            }
            Intent fill = new Intent()
                    .putExtra(TodoWidget.EXTRA_ID, t.id)
                    .putExtra(TodoWidget.EXTRA_DONE, !t.done);
            rv.setOnClickFillInIntent(R.id.row, fill);
            return rv;
        }

        @Override public void onCreate() {}
        @Override public void onDestroy() { rows.clear(); }
        @Override public int getCount() { return rows.size(); }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return rows.get(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
    }
}
