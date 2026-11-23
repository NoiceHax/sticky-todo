package dev.noicehax.stickytodo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Supplies the rows for one of the widget's two lists, picked by the intent's "section" extra:
 * "deadlines" (dated tasks, soonest first) or "tasks" (undated, in the order set on the web).
 * Tasks ticked off in the last day stay at the bottom of their list, struck through.
 * onDataSetChanged runs off the main thread, so it can hit the network.
 */
public class TodoWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        boolean deadlines = TodoWidget.SECTION_DEADLINES.equals(intent.getStringExtra(TodoWidget.EXTRA_SECTION));
        return new Factory(getApplicationContext(), deadlines);
    }

    private static final Comparator<Api.Todo> BY_DUE = Comparator
            .comparing((Api.Todo t) -> t.due)
            .thenComparing(t -> t.dueTime != null ? t.dueTime : "99")
            .thenComparingInt(t -> Style.priorityRank(t.priority));

    private static final class Factory implements RemoteViewsFactory {
        /** Done tasks stay visible (struck through) this long, so a mis-tap is easy to undo. */
        private static final double KEEP_DONE_SECS = 24 * 3600;

        private final Context ctx;
        private final boolean deadlines;
        private List<Api.Todo> rows = new ArrayList<>();

        Factory(Context ctx, boolean deadlines) {
            this.ctx = ctx;
            this.deadlines = deadlines;
        }

        @Override
        public void onDataSetChanged() {
            try {
                List<Api.Todo> all = Api.list(ctx);
                List<Api.Todo> dated = new ArrayList<>();
                List<Api.Todo> undated = new ArrayList<>();
                List<Api.Todo> recentDone = new ArrayList<>();
                double cutoff = System.currentTimeMillis() / 1000.0 - KEEP_DONE_SECS;
                for (Api.Todo t : all) {
                    boolean hasDate = Style.date(t.due) != null;
                    if (t.done) {
                        if (t.doneAt > cutoff && hasDate == deadlines) recentDone.add(t);
                    } else if (hasDate) {
                        dated.add(t);
                    } else {
                        undated.add(t);
                    }
                }
                dated.sort(BY_DUE);
                recentDone.sort((a, b) -> Double.compare(b.doneAt, a.doneAt));

                List<Api.Todo> next = new ArrayList<>(deadlines ? dated : undated);
                next.addAll(recentDone);
                rows = next;

                // Both lists fetch the same data, so either can update the shared header.
                int overdue = 0, today = 0;
                for (Api.Todo t : dated) {
                    long n = Style.daysUntil(t.due);
                    if (n < 0) overdue++;
                    else if (n == 0) today++;
                }
                TodoWidget.setHeader(ctx, dated.size() + undated.size(), overdue, null);
                TodoWidget.setSectionCounts(ctx, dated.size(), today, undated.size());
            } catch (Exception e) {
                // Keep showing the last list we had; just flag that it's stale.
                if (deadlines) TodoWidget.setStatus(ctx, ctx.getString(R.string.offline_status));
                else {
                    int open = 0;
                    for (Api.Todo t : rows) if (!t.done) open++;
                    TodoWidget.setHeader(ctx, open, 0, ctx.getString(R.string.offline_status));
                }
            }
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_item);
            if (position >= rows.size()) return rv;
            Api.Todo t = rows.get(position);

            int stripe = Style.color(t.color);
            rv.setInt(R.id.stripe, "setBackgroundColor", stripe != 0 ? stripe : Color.TRANSPARENT);
            // A deadline within a week gets a red tint (the web note's red glow); otherwise the
            // row is lightly tinted with its colour label.
            int bg = Color.TRANSPARENT;
            if (!t.done && Style.urgent(t.due)) bg = 0x33DC2626;
            else if (stripe != 0 && !t.done) bg = (stripe & 0x00FFFFFF) | 0x1F000000;
            rv.setInt(R.id.row, "setBackgroundColor", bg);

            if (t.done) {
                SpannableString s = new SpannableString(t.text);
                s.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new ForegroundColorSpan(Style.MUTED), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                rv.setTextViewText(R.id.text, s);
                rv.setImageViewResource(R.id.check, R.drawable.ic_checked);
            } else {
                rv.setTextViewText(R.id.text, t.text);
                rv.setImageViewResource(R.id.check, R.drawable.ic_unchecked);
            }

            CharSequence meta = t.done ? null : meta(t);
            if (meta != null) {
                rv.setTextViewText(R.id.meta, meta);
                rv.setViewVisibility(R.id.meta, View.VISIBLE);
            } else {
                rv.setViewVisibility(R.id.meta, View.GONE);
            }

            Intent fill = new Intent()
                    .putExtra(TodoWidget.EXTRA_ID, t.id)
                    .putExtra(TodoWidget.EXTRA_DONE, !t.done);
            rv.setOnClickFillInIntent(R.id.row, fill);
            return rv;
        }

        /** Second line: "● High  Today 23:59 · NLP & Transformers", coloured like the web pills. */
        private CharSequence meta(Api.Todo t) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            String prio = Style.priorityLabel(t.priority);
            if (prio != null) {
                append(b, "● " + prio, Style.priorityColor(t.priority), true);
            }
            String due = Style.dueLabel(t.due, t.dueTime);
            if (due != null) {
                if (b.length() > 0) b.append("  ");
                append(b, due, Style.dueColor(t.due), Style.urgent(t.due));
            }
            if (t.course != null && !t.course.isEmpty()) {
                if (b.length() > 0) b.append(" · ");
                append(b, t.course, Style.MUTED, false);
            }
            for (String tag : Api.showTags(ctx) ? t.tags : java.util.Collections.<String>emptyList()) {
                if (b.length() > 0) b.append(" ");
                append(b, "#" + tag, Style.ACCENT, false);
            }
            if (t.effortMin > 0) {
                if (b.length() > 0) b.append("  ");
                append(b, "~" + Style.effort(t.effortMin), Style.MUTED, false);
            }
            return b.length() > 0 ? b : null;
        }

        private static void append(SpannableStringBuilder b, String s, int color, boolean bold) {
            int start = b.length();
            b.append(s);
            b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) b.setSpan(new StyleSpan(Typeface.BOLD), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
