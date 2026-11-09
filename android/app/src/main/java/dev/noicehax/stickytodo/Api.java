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

        Todo(JSONObject o) {
            id = o.optString("id");
            text = o.optString("text");
            done = o.optBoolean("done");
            doneAt = o.optDouble("doneAt", 0);
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

    static void setBaseUrl(Context c, String url) {
        prefs(c).edit().putString("url", url.trim()).apply();
    }

    static List<Todo> list(Context c) throws IOException {
        return parse(request(c, "GET", "/api/todos", null));
    }

    static void add(Context c, String text) throws IOException {
        request(c, "POST", "/api/todos", json("text", text));
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
