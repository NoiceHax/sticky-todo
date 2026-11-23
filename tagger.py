"""AI triage for sticky-todo: asks Claude for a category (which picks a colour), a priority and,
when the text names one, a due date. Tags and time estimates are the user's to add, not Claude's.

Claude runs as a headless Claude Code agent (`claude -p`, tools disabled), authenticated with
CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token`: the same setup class-notes uses. The server
owns the rules for what a result may overwrite; this module only asks and validates the answer.
"""
import datetime
import json
import os
import re
import shutil
import subprocess
import tempfile

MODEL = os.environ.get("CLAUDE_TAG_MODEL", "claude-opus-5-5")
EFFORT = os.environ.get("CLAUDE_TAG_EFFORT", "low")  # a short classification; low effort is plenty
TIMEOUT = float(os.environ.get("CLAUDE_TAG_TIMEOUT", "180"))

# Bump when the prompt changes in a way that should re-tag existing tasks. It's part of each
# task's tagging signature, so a bump re-queues every open task once (user-set fields still stay).
PROMPT_VERSION = 3

CATEGORIES = ["assignment", "exam", "study", "project", "work", "personal", "errand",
              "finance", "health", "social", "other"]
# Category -> colour label, applied only when the user hasn't picked a colour.
CATEGORY_COLORS = {
    "assignment": "orange", "exam": "pink", "study": "yellow", "project": "purple",
    "work": "blue", "personal": "blue", "finance": "green", "errand": "green",
    "health": "green", "social": "pink",
}

SYSTEM = """You label items on a college student's to-do list. Reply with one JSON object and nothing else: no prose, no code fence.

Keys:
- "category": exactly one of: """ + ", ".join(CATEGORIES) + """.
- "priority": "high", "medium" or "low". High: graded work or an exam, a deadline within about
  two days, or real consequences if missed (payments, cancellations, renewals). Low: nice-to-have.
- "due": "YYYY-MM-DD" only if the task text itself states or clearly implies a date, resolved
  against today. Otherwise null. Never guess a date that isn't in the text.
  "on X", "X", "by X" and "till X" mean X itself ("dec 14th" -> Dec 14, "by friday" -> Friday).
  "before X", "<X" and "until before X" mean the last day is the day BEFORE X
  ("<15th dec" -> Dec 14, "before the 1st" -> the last day of the previous month).
- "due_time": "HH:MM" (24h) only if the text states a time, otherwise null.
"""


class TaggerError(Exception):
    pass


def enabled():
    return bool(os.environ.get("CLAUDE_CODE_OAUTH_TOKEN") or os.environ.get("ANTHROPIC_API_KEY")) \
        and shutil.which("claude") is not None


def _prompt(task, today):
    item = {k: task.get(k) for k in ("text", "course", "kind", "details") if task.get(k)}
    if task.get("due"):
        item["already_due"] = task["due"]
    return json.dumps({
        "today": f"{today.isoformat()} ({today:%A})",
        "task": item,
    }, ensure_ascii=False)


def _extract_json(text):
    m = re.search(r"\{.*\}", text, flags=re.S)
    if not m:
        raise TaggerError("no JSON in Claude's reply")
    return json.loads(m.group(0))


def _clean_tag(t):
    t = re.sub(r"[^a-z0-9 &+.\-]", "", str(t).lower().lstrip("#")).strip()
    return re.sub(r"\s+", " ", t)[:24]


def _clean(raw, today):
    out = {}
    cat = str(raw.get("category") or "").lower().strip()
    out["category"] = cat if cat in CATEGORIES else "other"
    p = str(raw.get("priority") or "").lower().strip()
    out["priority"] = p if p in ("high", "medium", "low") else None
    out["due"] = out["due_time"] = None
    try:
        if raw.get("due"):
            d = datetime.date.fromisoformat(str(raw["due"])[:10])
            # A date in the past or over a year out is almost always a misread.
            if today - datetime.timedelta(days=1) <= d <= today + datetime.timedelta(days=400):
                out["due"] = d.isoformat()
    except ValueError:
        pass
    m = re.fullmatch(r"(\d{1,2}):(\d{2})", str(raw.get("due_time") or "").strip())
    if out["due"] and m and int(m[1]) < 24 and int(m[2]) < 60:
        out["due_time"] = f"{int(m[1]):02d}:{m[2]}"
    return out


def _run_claude(user):
    cmd = ["claude", "-p", "--model", MODEL, "--system-prompt", SYSTEM, "--tools", "",
           "--disable-slash-commands", "--no-session-persistence", "--output-format", "text"]
    if EFFORT:
        cmd += ["--effort", EFFORT]
    # Empty cwd and HOME so the agent never picks up project files or leftover settings.
    with tempfile.TemporaryDirectory() as home:
        env = dict(os.environ, HOME=home)
        try:
            proc = subprocess.run(cmd, input=user, capture_output=True, text=True,
                                  timeout=TIMEOUT, cwd=home, env=env)
        except subprocess.TimeoutExpired:
            raise TaggerError("claude timed out")
    out = (proc.stdout or "").strip()
    if proc.returncode != 0 or not out:
        detail = ((proc.stderr or "") + out)[:300].strip() or f"exit code {proc.returncode}"
        raise TaggerError(f"claude failed: {detail}")
    return out


def classify(task, today=None):
    """Return (result dict, model name). Raises TaggerError."""
    today = today or datetime.date.today()
    reply = _run_claude(_prompt(task, today))
    try:
        return _clean(_extract_json(reply), today), MODEL
    except (ValueError, TaggerError) as e:
        raise TaggerError(f"unusable reply ({e}): {reply[:200]}")
