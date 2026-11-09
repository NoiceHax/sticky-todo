package dev.noicehax.stickytodo;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/** Small dialog opened by the widget's + button. */
public class AddTaskActivity extends Activity {
    private EditText input;
    private Button addButton;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_add);
        setFinishOnTouchOutside(true);
        input = findViewById(R.id.input);
        addButton = findViewById(R.id.add);
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
        input.requestFocus();
    }

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            finish();
            return;
        }
        addButton.setEnabled(false);
        input.setEnabled(false);
        new Thread(() -> {
            try {
                Api.add(this, text);
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
