package dev.noicehax.stickytodo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Blocking client for the sticky-todo server. Never call on the main thread. */
final class Api {
    static final String DEFAULT_URL = "https://homelab.tail3ed9c7.ts.net:3103";
    private static final String PREFS = "sticky-todo";

    static final class Todo {
        final String id;
        final String text;
        final boolean done;
        final double doneAt;
        /** v2 fields; null when unset (or when talking to a v1 server). */
        final String due, dueTime, priority, color, course;
        /** Tags and time estimate (minutes, 0 if none) the user added. */
        final List<String> tags = new ArrayList<>();
        final int effortMin;

        Todo(JSONObject o) {
            id = o.optString("id");
            text = o.optString("text");
            done = o.optBoolean("done");
            doneAt = o.optDouble("doneAt", 0);
            due = str(o, "due");
            dueTime = str(o, "due_time");
            priority = str(o, "priority");
            color = str(o, "color");
            course = str(o, "course");
            JSONArray tg = o.optJSONArray("tags");
            if (tg != null) for (int i = 0; i < tg.length(); i++) tags.add(tg.optString(i));
            effortMin = o.optInt("effort_min", 0);
        }

        private static String str(JSONObject o, String k) {
            return o.isNull(k) ? null : o.optString(k, null);
        }
    }

    private Api() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String baseUrl(Context c) {
        String u = prefs(c).getString("url", DEFAULT_URL).trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** Tags show in the widget unless switched off in the app (matches the web note). */
    static boolean showTags(Context c) {
        return prefs(c).getBoolean("showTags", true);
    }

    static void setShowTags(Context c, boolean show) {
        prefs(c).edit().putBoolean("showTags", show).apply();
    }

    static void setBaseUrl(Context c, String url) {
        prefs(c).edit().putString("url", url.trim()).apply();
    }

    static List<Todo> list(Context c) throws IOException {
        return parse(request(c, "GET", "/api/todos", null));
    }

    static void add(Context c, String text) throws IOException {
        add(c, text, null, null, null);
    }

    /** due is YYYY-MM-DD, priority high/medium/low, color one of the server's six; each may be null. */
    static void add(Context c, String text, String due, String priority, String color) throws IOException {
        JSONObject body = json("text", text);
        try {
            if (due != null) body.put("due", due);
            if (priority != null) body.put("priority", priority);
            if (color != null) body.put("color", color);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        request(c, "POST", "/api/todos", body);
    }

    static void setDone(Context c, String id, boolean done) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("done", done);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        request(c, "POST", "/api/todos/" + id + "/update", body);
    }

    private static JSONObject json(String k, String v) throws IOException {
        try {
            return new JSONObject().put(k, v);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static List<Todo> parse(String body) throws IOException {
        try {
            JSONArray arr = new JSONObject(body).getJSONArray("todos");
            List<Todo> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) out.add(new Todo(arr.getJSONObject(i)));
            return out;
        } catch (JSONException e) {
            throw new IOException("Bad response from server", e);
        }
    }

    private static String request(Context c, String method, String path, JSONObject body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl(c) + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setUseCaches(false);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("Server said " + code);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }
}
