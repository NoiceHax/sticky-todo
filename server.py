"""sticky-todo: a tiny to-do list served as a sticky note.

Stdlib only. State lives in one JSON file ($DATA_DIR/todos.json), written atomically.
Every mutation bumps `version`, so clients can poll cheaply and pick up changes made
on another device.

v2 adds structured fields to a task (due date/time, priority, colour, course, link, ...).
`text` stays the task's title, so v1 clients (the Android widget, class-notes in v1 mode)
keep working unchanged.
"""
import datetime
import json
import os
import queue
import re
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import tagger

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
DB = DATA_DIR / "todos.json"
STATIC = Path(__file__).parent / "static"
LOCK = threading.Lock()
MAX_TEXT = 500

COLORS = {"yellow", "pink", "blue", "green", "purple", "orange"}
PRIORITIES = {"high", "medium", "low"}
PRIORITY_ALIASES = {"med": "medium", "mid": "medium", "normal": "medium", "urgent": "high"}
# Short free-text fields and their max length.
STR_FIELDS = {"course": 80, "kind": 40, "details": 2000, "source": 80, "external_id": 200}

# class-notes v1 writes deadlines as one line: "📌 Course: Title · due Fri 28 Nov 23:59".
V1_DEADLINE = re.compile(
    r"^📌\s*(?P<course>.+?):\s*(?P<title>.+?)\s*·\s*"
    r"(?:due \w{3} (?P<day>\d{1,2}) (?P<mon>[A-Za-z]{3})(?: (?P<time>\d{1,2}:\d{2}))?|due date not given)\s*$"
)
MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}


def clean_date(v):
    if not v:
        return None
    try:
        return datetime.date.fromisoformat(str(v)[:10]).isoformat()
    except ValueError:
        raise ValueError("due must be YYYY-MM-DD")


def clean_time(v):
    if not v:
        return None
    m = re.fullmatch(r"(\d{1,2}):(\d{2})", str(v).strip())
    if not m or int(m[1]) > 23 or int(m[2]) > 59:
        raise ValueError("due_time must be HH:MM")
    return f"{int(m[1]):02d}:{m[2]}"


def parse_v1_deadline(t):
    """Turn a class-notes v1 one-liner into structured fields. No-op for any other text."""
    m = V1_DEADLINE.match(t["text"])
    if not m:
        return
    t["text"] = m["title"]
    t["course"] = m["course"]
    t["kind"] = t.get("kind") or "deadline"
    t["source"] = t.get("source") or "class-notes"
    t["due"] = t["due_time"] = None
    mon = MONTHS.get((m["mon"] or "").lower())
    if m["day"] and mon:
        # The line has no year: pick the one that puts the date closest to when the task was made.
        ref = datetime.date.fromtimestamp(t.get("created") or time.time())
        options = []
        for y in (ref.year - 1, ref.year, ref.year + 1):
            try:
                options.append(datetime.date(y, mon, int(m["day"])))
            except ValueError:
                pass
        if options:
            t["due"] = min(options, key=lambda d: abs((d - ref).days)).isoformat()
            t["due_time"] = clean_time(m["time"]) if m["time"] else None


def parse_effort(v):
    """User's time estimate -> minutes. Accepts 90, "90", "45m", "2h", "1.5h", "1h30m"; empty clears."""
    if v in (None, "", 0):
        return None
    if isinstance(v, (int, float)):
        mins = int(v)
    else:
        m = re.fullmatch(r"\s*(?:(\d+(?:\.\d+)?)\s*h)?\s*(?:(\d+)\s*m?)?\s*", str(v).lower())
        if not m or not (m[1] or m[2]):
            raise ValueError("time estimate must look like 30m, 2h or 1h30m")
        mins = round(float(m[1] or 0) * 60) + int(m[2] or 0)
    if not 1 <= mins <= 100 * 60:
        raise ValueError("time estimate must be between 1 minute and 100 hours")
    return mins


def apply_fields(t, body):
    """Validate and copy editable fields from a request body onto task t. Raises ValueError.

    A field the user sets here stops being AI-managed: it's dropped from t["ai_set"], so a later
    re-tag never overwrites what the user chose."""
    ai_set = set(t.get("ai_set") or [])
    for key in ("priority", "color"):
        if key in body:
            ai_set.discard(key)
    if "due" in body or "due_time" in body:
        ai_set.discard("due")
    t["ai_set"] = sorted(ai_set)
    if "tags" in body:
        tags = body["tags"] or []
        if isinstance(tags, str):
            tags = tags.split(",")
        clean = []
        for x in tags:
            x = tagger._clean_tag(x)
            if x and x not in clean:
                clean.append(x)
        t["tags"] = clean[:8]
    for key in ("title", "text"):
        if key in body:
            text = str(body[key] or "").strip()[:MAX_TEXT]
            if text:
                t["text"] = text
                if text.startswith("📌"):
                    parse_v1_deadline(t)
    if "due" in body:
        t["due"] = clean_date(body["due"])
        if not t["due"]:
            t["due_time"] = None
    if "due_time" in body:
        t["due_time"] = clean_time(body["due_time"]) if t.get("due") else None
    if "priority" in body:
        p = str(body["priority"] or "").strip().lower()
        p = PRIORITY_ALIASES.get(p, p)
        if p and p not in PRIORITIES:
            raise ValueError("priority must be high, medium or low")
        t["priority"] = p or None
    if "color" in body:
        c = str(body["color"] or "").strip().lower()
        if c and c not in COLORS:
            raise ValueError("color must be one of " + ", ".join(sorted(COLORS)))
        t["color"] = c or None
    if "link" in body:
        link = str(body["link"] or "").strip()
        if link and not re.match(r"https?://", link):
            raise ValueError("link must be http(s)")
        t["link"] = link[:1000] or None
    for key, n in STR_FIELDS.items():
        if key in body:
            t[key] = str(body[key] or "").strip()[:n] or None
    if "effort_min" in body:
        t["effort_min"] = parse_effort(body["effort_min"])
    if "done" in body:
        t["done"] = bool(body["done"])
        t["doneAt"] = time.time() if t["done"] else None


def new_task():
    return {"id": uuid.uuid4().hex[:12], "text": "", "done": False, "created": time.time(),
            "due": None, "due_time": None, "priority": None, "color": None}


def load():
    try:
        state = json.loads(DB.read_text("utf-8"))
    except FileNotFoundError:
        return {"version": 0, "schema": 3, "todos": []}
    if state.get("schema", 1) < 2:  # v1 -> v2: add fields, pull dates out of class-notes lines
        for t in state["todos"]:
            for k in ("due", "due_time", "priority", "color"):
                t.setdefault(k, None)
            if t["text"].startswith("📌"):
                parse_v1_deadline(t)
        state["schema"] = 2
    if state["schema"] < 3:
        # v3: tags and time estimates are only what the user adds. Earlier ones were written by
        # the AI tagger, so they're cleared.
        for t in state["todos"]:
            for k in ("tags", "effort_min", "show_effort", "tags_manual"):
                t.pop(k, None)
        state["schema"] = 3
    return state


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


# ---------------------------------------------------------------- AI tagging
# New and edited open tasks are queued; one background thread asks Claude for a category,
# priority and due date (see tagger.py) and merges the answer in. The request that
# added the task never waits on Claude. A sweep re-queues anything still untagged, so tasks
# survive a restart or a failed call.

AI_QUEUE = queue.Queue()
AI_PENDING = set()
AI_RETRY_AT = {}  # id -> time before which a failed task isn't retried
AI_RETRY_SECS = 600
AI_SWEEP_SECS = 300


def ai_signature(t):
    parts = [f"v{tagger.PROMPT_VERSION}"] + [str(t.get(k) or "") for k in ("text", "course", "details")]
    return "\x1f".join(parts)


def needs_ai(t):
    return not t.get("done") and (t.get("ai") or {}).get("for") != ai_signature(t)


def ai_enqueue(tid):
    if tagger.enabled() and tid not in AI_PENDING:
        AI_PENDING.add(tid)
        AI_QUEUE.put(tid)


def apply_ai(t, r, model):
    ai_set = set(t.get("ai_set") or [])
    t["category"] = r["category"]
    # Priority, due date and colour are only filled in when empty, or when the AI set them last
    # time. Anything the user picked stays.
    if r["priority"] and (not t.get("priority") or "priority" in ai_set):
        t["priority"] = r["priority"]
        ai_set.add("priority")
    if r["due"] and (not t.get("due") or "due" in ai_set):
        t["due"], t["due_time"] = r["due"], r["due_time"]
        ai_set.add("due")
    color = tagger.CATEGORY_COLORS.get(r["category"])
    if "color" in ai_set or not t.get("color"):
        t["color"] = color
        ai_set.discard("color")
        if color:
            ai_set.add("color")
    t["ai_set"] = sorted(ai_set)
    t["ai"] = {"model": model, "at": time.time(), "for": ai_signature(t)}


def ai_worker():
    while True:
        tid = AI_QUEUE.get()
        AI_PENDING.discard(tid)
        if AI_RETRY_AT.get(tid, 0) > time.time():
            continue
        with LOCK:
            state = load()
            t = find(state, tid)
            if not t or not needs_ai(t):
                continue
            snapshot, sig = dict(t), ai_signature(t)
        try:
            result, model = tagger.classify(snapshot)
        except Exception as e:  # noqa: BLE001 - any failure just means "try again later"
            AI_RETRY_AT[tid] = time.time() + AI_RETRY_SECS
            print(f"tagging {tid} failed: {e}", flush=True)
            continue
        AI_RETRY_AT.pop(tid, None)
        with LOCK:
            state = load()
            t = find(state, tid)
            # Skip if the task was deleted, ticked off or edited while Claude was thinking;
            # an edit has already queued it again.
            if t and not t.get("done") and ai_signature(t) == sig:
                apply_ai(t, result, model)
                save(state)


def ai_sweeper():
    while True:
        with LOCK:
            ids = [t["id"] for t in load()["todos"] if needs_ai(t)]
        for tid in ids:
            ai_enqueue(tid)
        time.sleep(AI_SWEEP_SECS)


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
        body = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(body, dict):
            raise ValueError("body must be an object")
        return body

    def _path(self):
        return self.path.split("?")[0]

    def do_GET(self):
        path = self._path()
        if path in ("/api/todos", "/api/tasks"):
            with LOCK:
                return self._json(200, load())
        if path == "/api/ai":
            return self._json(200, {"enabled": tagger.enabled(), "model": tagger.MODEL,
                                    "queued": len(AI_PENDING)})
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
        path = self._path()
        # POST .../<id>/update == PATCH .../<id> (Android's HttpURLConnection can't PATCH).
        if path.startswith(("/api/todos/", "/api/tasks/")) and path.endswith("/update"):
            self.path = path.removesuffix("/update")
            return self.do_PATCH()
        try:
            body = self._body()
        except ValueError as e:
            return self._json(400, {"error": str(e) or "bad body"})
        with LOCK:
            state = load()
            try:
                if path in ("/api/todos", "/api/tasks"):
                    v2 = path == "/api/tasks"
                    if not str(body.get("title" if v2 else "text") or "").strip():
                        return self._json(400, {"error": "empty"})
                    # v2: external_id makes create idempotent (an existing task is updated instead).
                    ext = v2 and body.get("external_id")
                    t = ext and next((x for x in state["todos"] if x.get("external_id") == ext), None)
                    if not t:
                        t = new_task()
                        apply_fields(t, body)
                        state["todos"].insert(0, t)
                    else:
                        apply_fields(t, body)
                    save(state)
                    if needs_ai(t):
                        ai_enqueue(t["id"])
                    return self._json(200, {"id": t["id"], **state})
                elif path.endswith("/retag") and path.startswith(("/api/todos/", "/api/tasks/")):
                    # Ask Claude again (priority/date/colour it set get refreshed; yours stay).
                    tid = path.split("/")[3]
                    t = find(state, tid)
                    if not t:
                        return self._json(404, {"error": "no such todo"})
                    t.pop("ai", None)
                    AI_RETRY_AT.pop(tid, None)
                    save(state)
                    ai_enqueue(tid)
                    return self._json(200, state)
                elif path in ("/api/todos/clear-done", "/api/tasks/clear-done"):
                    state["todos"] = [t for t in state["todos"] if not t["done"]]
                elif path in ("/api/todos/reorder", "/api/tasks/reorder"):
                    order = body.get("ids") or []
                    pos = {tid: i for i, tid in enumerate(order)}
                    state["todos"].sort(key=lambda t: pos.get(t["id"], len(pos)))
                else:
                    return self._json(404, {"error": "not found"})
            except ValueError as e:
                return self._json(400, {"error": str(e)})
            save(state)
            return self._json(200, state)

    def do_PATCH(self):
        tid = self._path().removeprefix("/api/todos/").removeprefix("/api/tasks/")
        try:
            body = self._body()
        except ValueError as e:
            return self._json(400, {"error": str(e) or "bad body"})
        with LOCK:
            state = load()
            t = find(state, tid)
            if not t:
                return self._json(404, {"error": "no such todo"})
            try:
                apply_fields(t, body)
            except ValueError as e:
                return self._json(400, {"error": str(e)})
            save(state)
            if needs_ai(t):
                ai_enqueue(t["id"])
            return self._json(200, {"id": t["id"], **state})

    def do_DELETE(self):
        tid = self._path().removeprefix("/api/todos/").removeprefix("/api/tasks/")
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
    if tagger.enabled():
        threading.Thread(target=ai_worker, daemon=True).start()
        threading.Thread(target=ai_sweeper, daemon=True).start()
        print(f"AI tagging on ({tagger.MODEL}, effort {tagger.EFFORT or 'default'})", flush=True)
    else:
        print("AI tagging off (no CLAUDE_CODE_OAUTH_TOKEN or no claude CLI)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
