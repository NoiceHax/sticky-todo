package dev.noicehax.stickytodo;

import android.graphics.Color;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Colours and due-date labels, matching the web app. */
final class Style {
    static final int INK = Color.parseColor("#3B3417");
    static final int MUTED = Color.parseColor("#8A7D3F");
    static final int OVERDUE = Color.parseColor("#B91C1C");
    static final int SOON = Color.parseColor("#C2410C");
    static final int ACCENT = Color.parseColor("#B45309");

    static final String[] COLORS = {"yellow", "pink", "blue", "green", "purple", "orange"};

    private Style() {}

    /** Label colour for a task, or 0 for none. */
    static int color(String name) {
        if (name == null) return 0;
        switch (name) {
            case "yellow": return Color.parseColor("#EAB308");
            case "pink": return Color.parseColor("#EC4899");
            case "blue": return Color.parseColor("#3B82F6");
            case "green": return Color.parseColor("#22C55E");
            case "purple": return Color.parseColor("#8B5CF6");
            case "orange": return Color.parseColor("#F97316");
            default: return 0;
        }
    }

    static int priorityColor(String p) {
        if (p == null) return 0;
        switch (p) {
            case "high": return Color.parseColor("#DC2626");
            case "medium": return Color.parseColor("#D97706");
            case "low": return Color.parseColor("#65A30D");
            default: return 0;
        }
    }

    static String priorityLabel(String p) {
        if (p == null) return null;
        switch (p) {
            case "high": return "High";
            case "medium": return "Med";
            case "low": return "Low";
            default: return null;
        }
    }

    static int priorityRank(String p) {
        if ("high".equals(p)) return 0;
        if ("medium".equals(p)) return 1;
        if ("low".equals(p)) return 2;
        return 3;
    }

    static LocalDate date(String iso) {
        try {
            return iso == null ? null : LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /** Days from today to the due date (negative = overdue). */
    static long daysUntil(String iso) {
        LocalDate d = date(iso);
        return d == null ? Long.MAX_VALUE : d.toEpochDay() - LocalDate.now().toEpochDay();
    }

    static String shortDate(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
    }

    /** "Overdue · Mon 23 Nov", "Today 23:59", "Tomorrow", "Wednesday", "Fri 4 Dec". */
    static String dueLabel(String due, String time) {
        LocalDate d = date(due);
        if (d == null) return null;
        long n = daysUntil(due);
        String t = time != null ? " " + time : "";
        if (n < 0) return "Overdue · " + shortDate(d);
        if (n == 0) return "Today" + t;
        if (n == 1) return "Tomorrow" + t;
        if (n < 7) return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault()) + t;
        if (n == 7) return "In a week · " + shortDate(d) + t;
        return shortDate(d) + t;
    }

    /** 45 -> "45m", 90 -> "1.5h", 120 -> "2h". */
    static String effort(int min) {
        if (min < 60) return min + "m";
        return min % 60 == 0 ? (min / 60) + "h" : String.format(Locale.US, "%.1fh", min / 60.0);
    }

    /** Deadlines turn red a week out, like the web note. */
    static final int URGENT_DAYS = 7;

    static boolean urgent(String due) {
        return date(due) != null && daysUntil(due) <= URGENT_DAYS;
    }

    static int dueColor(String due) {
        return urgent(due) ? OVERDUE : MUTED;
    }
}
