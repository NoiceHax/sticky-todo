"""sticky-todo: a tiny to-do list served as a sticky note.

Stdlib only. State lives in one JSON file ($DATA_DIR/todos.json), written atomically.
Every mutation bumps `version`, so clients can poll cheaply and pick up changes made
on another device.
"""
import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
DB = DATA_DIR / "todos.json"
STATIC = Path(__file__).parent / "static"
LOCK = threading.Lock()
MAX_TEXT = 500


def load():
    try:
        return json.loads(DB.read_text("utf-8"))
    except FileNotFoundError:
        return {"version": 0, "todos": []}


def save(state):
    state["version"] += 1
    tmp = DB.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=1), "utf-8")
    os.replace(tmp, DB)


def find(state, tid):
    for t in state["todos"]:
        if t["id"] == tid:
            return t
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "sticky-todo"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode())

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 10_000:
            raise ValueError("body too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/todos":
            with LOCK:
                return self._json(200, load())
        if path == "/healthz":
            return self._send(200, b"ok", "text/plain")
        files = {
            "/": ("index.html", "text/html; charset=utf-8"),
            "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"),
            "/icon.svg": ("icon.svg", "image/svg+xml"),
            "/icon-192.png": ("icon-192.png", "image/png"),
            "/icon-512.png": ("icon-512.png", "image/png"),
            "/sw.js": ("sw.js", "text/javascript"),
            "/sticky-todo.apk": ("sticky-todo.apk", "application/vnd.android.package-archive"),
        }
        if path in files:
            name, ctype = files[path]
            return self._send(200, (STATIC / name).read_bytes(), ctype)
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        path = self.path.split("?")[0]
        # POST /api/todos/<id>/update == PATCH /api/todos/<id> (Android's HttpURLConnection can't PATCH).
        if path.startswith("/api/todos/") and path.endswith("/update"):
            self.path = path.removesuffix("/update")
            return self.do_PATCH()
        try:
            body = self._body()
        except ValueError:
            return self._json(400, {"error": "bad body"})
        with LOCK:
            state = load()
            if path == "/api/todos":
                text = str(body.get("text", "")).strip()[:MAX_TEXT]
                if not text:
                    return self._json(400, {"error": "empty"})
                state["todos"].insert(0, {
                    "id": uuid.uuid4().hex[:12], "text": text,
                    "done": False, "created": time.time(),
                })
            elif path == "/api/todos/clear-done":
                state["todos"] = [t for t in state["todos"] if not t["done"]]
            elif path == "/api/todos/reorder":
                order = body.get("ids") or []
                pos = {tid: i for i, tid in enumerate(order)}
                state["todos"].sort(key=lambda t: pos.get(t["id"], len(pos)))
            else:
                return self._json(404, {"error": "not found"})
            save(state)
            return self._json(200, state)

    def do_PATCH(self):
        tid = self.path.split("?")[0].removeprefix("/api/todos/")
        try:
            body = self._body()
        except ValueError:
            return self._json(400, {"error": "bad body"})
        with LOCK:
            state = load()
            t = find(state, tid)
            if not t:
                return self._json(404, {"error": "no such todo"})
            if "text" in body:
                text = str(body["text"]).strip()[:MAX_TEXT]
                if text:
                    t["text"] = text
            if "done" in body:
                t["done"] = bool(body["done"])
                t["doneAt"] = time.time() if t["done"] else None
            save(state)
            return self._json(200, state)

    def do_DELETE(self):
        tid = self.path.split("?")[0].removeprefix("/api/todos/")
        with LOCK:
            state = load()
            before = len(state["todos"])
            state["todos"] = [t for t in state["todos"] if t["id"] != tid]
            if len(state["todos"]) != before:
                save(state)
            return self._json(200, state)


if __name__ == "__main__":
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    port = int(os.environ.get("PORT", "8000"))
    print(f"sticky-todo on :{port}, data in {DB}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
