package dev.noicehax.stickytodo;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;

/** Small dialog opened by the widget's + button: text, plus an optional date, priority and colour. */
public class AddTaskActivity extends Activity {
    private static final String[][] PRIORITIES = {{null, "None"}, {"high", "High"}, {"medium", "Med"}, {"low", "Low"}};

    private EditText input;
    private Button addButton;
    private TextView dueView;
    private View clearDue;
    private LinearLayout priorityRow, colorRow;

    private LocalDate due;
    private String priority;
    private String color;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_add);
        setFinishOnTouchOutside(true);
        input = findViewById(R.id.input);
        addButton = findViewById(R.id.add);
        dueView = findViewById(R.id.due);
        clearDue = findViewById(R.id.clearDue);
        priorityRow = findViewById(R.id.priorities);
        colorRow = findViewById(R.id.colors);

        addButton.setOnClickListener(v -> submit());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submit();
                return true;
            }
            return false;
        });

        dueView.setOnClickListener(v -> pickDate());
        clearDue.setOnClickListener(v -> setDue(null));
        buildPriorityChips();
        buildColorSwatches();
        setDue(null);
        input.requestFocus();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void pickDate() {
        LocalDate d = due != null ? due : LocalDate.now();
        DatePickerDialog dlg = new DatePickerDialog(this, (view, y, m, day) -> setDue(LocalDate.of(y, m + 1, day)),
                d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        dlg.show();
    }

    private void setDue(LocalDate d) {
        due = d;
        dueView.setText(d == null ? "📅  " + getString(R.string.no_date)
                : "📅  " + Style.dueLabel(d.toString(), null));
        dueView.setTextColor(d == null ? Style.MUTED : Style.INK);
        clearDue.setVisibility(d == null ? View.GONE : View.VISIBLE);
    }

    private void buildPriorityChips() {
        for (String[] p : PRIORITIES) {
            TextView chip = new TextView(this);
            chip.setText(p[1]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd(dp(6));
            chip.setTag(p[0]);
            chip.setOnClickListener(v -> {
                priority = (String) v.getTag();
                stylePriorityChips();
            });
            priorityRow.addView(chip, lp);
        }
        stylePriorityChips();
    }

    private void stylePriorityChips() {
        for (int i = 0; i < priorityRow.getChildCount(); i++) {
            TextView chip = (TextView) priorityRow.getChildAt(i);
            String key = (String) chip.getTag();
            boolean on = key == null ? priority == null : key.equals(priority);
            int c = key == null ? Style.MUTED : Style.priorityColor(key);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setStroke(dp(1), c);
            bg.setColor(on ? c : Color.TRANSPARENT);
            chip.setBackground(bg);
            chip.setTextColor(on ? Color.WHITE : c);
        }
    }

    private void buildColorSwatches() {
        String[] names = new String[Style.COLORS.length + 1];
        System.arraycopy(Style.COLORS, 0, names, 1, Style.COLORS.length);  // names[0] = null: no colour
        for (String name : names) {
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMarginEnd(dp(8));
            sw.setTag(name);
            sw.setContentDescription(name == null ? "No colour" : name);
            sw.setOnClickListener(v -> {
                color = (String) v.getTag();
                styleColorSwatches();
            });
            colorRow.addView(sw, lp);
        }
        styleColorSwatches();
    }

    private void styleColorSwatches() {
        for (int i = 0; i < colorRow.getChildCount(); i++) {
            View sw = colorRow.getChildAt(i);
            String name = (String) sw.getTag();
            boolean on = name == null ? color == null : name.equals(color);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(name == null ? Color.TRANSPARENT : Style.color(name));
            bg.setStroke(dp(on ? 3 : 1), on ? Style.INK : Style.MUTED);
            sw.setBackground(bg);
        }
    }

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            finish();
            return;
        }
        addButton.setEnabled(false);
        input.setEnabled(false);
        String dueIso = due != null ? due.toString() : null;
        String p = priority, c = color;
        new Thread(() -> {
            try {
                Api.add(this, text, dueIso, p, c);
                TodoWidget.refreshAll(this);
                runOnUiThread(this::finish);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.offline_toast, Toast.LENGTH_LONG).show();
                    addButton.setEnabled(true);
                    input.setEnabled(true);
                });
            }
        }).start();
    }
}
