package dev.noicehax.stickytodo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import java.util.List;

/** Launcher screen: server address, a connection test, and how to add the widget. */
public class MainActivity extends Activity {
    private EditText url;
    private TextView result;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        url = findViewById(R.id.url);
        result = findViewById(R.id.result);
        url.setText(Api.baseUrl(this));
        if (TodoWidget.ids(this).length > 0) UnlockSyncService.start(this);

        findViewById(R.id.save).setOnClickListener(v -> {
            Api.setBaseUrl(this, url.getText().toString());
            TodoWidget.rebuildAll(this);
            test();
        });
        findViewById(R.id.pin).setOnClickListener(v -> {
            android.appwidget.AppWidgetManager mgr = android.appwidget.AppWidgetManager.getInstance(this);
            if (mgr.isRequestPinAppWidgetSupported()) {
                mgr.requestPinAppWidget(new android.content.ComponentName(this, TodoWidget.class), null, null);
            } else {
                android.widget.Toast.makeText(this, R.string.pin_unsupported, android.widget.Toast.LENGTH_LONG).show();
            }
        });
        android.widget.CheckBox tags = findViewById(R.id.showTags);
        tags.setChecked(Api.showTags(this));
        tags.setOnCheckedChangeListener((v, on) -> {
            Api.setShowTags(this, on);
            TodoWidget.refreshAll(this);
        });
        findViewById(R.id.open).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Api.baseUrl(this) + "/"))));
        test();
    }

    private void test() {
        result.setText(R.string.checking);
        new Thread(() -> {
            String msg;
            try {
                List<Api.Todo> all = Api.list(this);
                int open = 0;
                for (Api.Todo t : all) if (!t.done) open++;
                msg = getString(R.string.connected, open);
                TodoWidget.refreshAll(this);
            } catch (Exception e) {
                msg = getString(R.string.not_connected, String.valueOf(e.getMessage()));
            }
            String m = msg;
            runOnUiThread(() -> result.setText(m));
        }).start();
    }
}
