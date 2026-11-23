# sticky-todo

A to-do list that behaves like a sticky note. It opens on your PC when you log in and sits on your
phone's home screen as a widget. Both show the same list, synced through a tiny server you host
yourself.

## Why I built it

College work shows up from everywhere: a lecture ends and there's revision to do, an assignment
goes up, a lab needs a writeup, a friend sends a link you'll need tomorrow. A to-do app you have to
open is one you stop opening. A note that's already on screen when you sit down, and already on your
home screen when you unlock your phone, is one you actually read.

It also takes tasks from other tools. My [class-notes](#works-with-class-notes) pipeline drops a
"revise" task with a link to the lecture notes onto the list as soon as they're written, so
revising becomes something that's waiting for you instead of something you have to remember.

## What's in here

| Part | What it is |
|---|---|
| `server.py` + `static/` | The server and the note itself. Standard-library Python only, one JSON file for storage. |
| `android/` | Source for the Android app: a home-screen widget plus a quick-add screen. |
| `static/sticky-todo.apk` | The built Android app. The server also serves it at `/sticky-todo.apk`. |
| `windows/open-todo.ps1` | Opens the note as a small window at login and keeps it in front. |

## The note

- **Two sections.** *Deadlines* holds every task with a due date, sorted soonest first, with
  labels like *Overdue*, *Today 23:59*, *Tomorrow* or *Wednesday*. *Tasks* holds everything else in
  the order you drag it into. They're stacked one above the other, each in its own scrolling box,
  so a long task list never pushes your deadlines off screen.
- **Calendar** (the calendar button in the header). Days with deadlines get a dot. Tap a day to show
  only that day's deadlines, and anything you add while it's picked is due that day.
- **Priority** (High, Med, Low) and a **colour label** (six colours) on any task. Set them when
  adding with ⚑, or later with a task's ⋯ menu, which also sets or clears the due date and time.
- **AI triage with Claude.** Every new or edited task goes to Claude (Opus 5.5, low effort) in
  the background. Claude picks a priority, a category (which sets a colour), and a due date when
  the text names one. "By friday" and "till the 5th" mean that day; "<15th" and "before the 1st"
  mean the day before. It only fills in priority, date and colour while they're empty or were
  set by Claude; anything you pick stays yours. A ✨ marks what Claude set.
- **Edit button (✎)** on every task: due date and time, priority, colour, tags and a time
  estimate (`45m`, `2h`, `1h30m`). The ⚑ button next to the add box sets the same things on a
  new task.
- **Tags and time estimates are yours.** They only appear when you add them. Click a tag (or use
  the tag bar under the add box) to show only that tag. *Show tags* in the gear menu hides them
  on that device.
- **Deadlines a week out turn red**: the date pill goes red and the row glows, and in the last
  two days (and once overdue) the glow pulses.
- The Deadlines header counts what's due today and what's overdue: *Deadlines (8) Today (4)*.
- Add, edit, tick off, reorder by dragging and clear done tasks. Links inside a task become clickable.
- Works as an installable web app, with a manifest and service worker.
- Polls every 5 seconds and only re-renders when the list's `version` number changes, so a task
  ticked off on your phone disappears from the PC on its own.
- Each change is written to a temporary file that then replaces `todos.json` in one step, so a crash
  mid-write can't corrupt the list.

## Android app

Download it from the [releases page](../../releases) or from `/sticky-todo.apk` on your own
server, and sideload it. It's signed with a debug key, so Android will ask you to allow installs from
that source.

- **Home-screen widget**: two lists stacked one above the other, *Deadlines* (soonest first) and
  *Tasks*, each scrolling on its own. Each task shows its colour as a stripe, and a second line with its priority, due date
  (red when overdue, orange for today and tomorrow) and course. The header counts overdue tasks.
  Tap a task to tick it off, or use the header buttons to refresh or add one.
- **Quick add** (the + button) takes an optional due date, priority and colour.
- **Refreshes when you unlock the phone**, so the widget is current the moment you look at it. Android
  only delivers the unlock event to a running app, so a small foreground service keeps that listener
  alive. It does nothing between unlocks and starts again after a reboot.
- **Server address is set in the app.** Open it, paste your server's URL and press save, then use
  *Pin widget* to place it.

Build it yourself with `cd android && ./gradlew assembleRelease` (Android SDK, JDK 17).

## Windows startup window

`windows/open-todo.ps1` waits up to 45 seconds for the server to answer (the network and
Tailscale are often not up yet at login). It then opens the note in Brave's app mode as a
380×720 window (shorter on small screens) in the top-right corner, using a separate browser profile so it never mixes with your
normal windows. For two minutes afterwards it raises the note again whenever Windows reopens your
other Brave windows on top of it.

Edit the `$Urls` list at the top, then register it to run at login:

```powershell
$a = New-ScheduledTaskAction -Execute powershell.exe -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PWD\windows\open-todo.ps1`""
Register-ScheduledTask -TaskName StickyTodo -Action $a -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME)
```

## Run the server

```bash
docker compose up -d --build
```

Or without Docker: `DATA_DIR=./data python3 server.py` (the port defaults to 8000).

AI tagging needs the Claude Code CLI (the Docker image installs it) and a
`CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token` in the environment; compose reads it from
`secrets/claude.env`. Without them the app works the same, minus tagging. `CLAUDE_TAG_MODEL`
(default `claude-opus-5-5`) and `CLAUDE_TAG_EFFORT` (default `low`) tune it. Each task costs
one short Claude Code run (about 4 seconds) on your subscription.

**There is no login.** Anyone who can reach the port can read and edit the list. Keep it on
an address only you can reach. I bind it to my Tailscale and LAN addresses and never expose it to the
internet.

## API

`/api/todos` is the original API, and `/api/tasks` is the same list under the name class-notes v2
expects. Both accept the v2 fields, and responses are the full state (`{"version", "todos": [...]}`),
plus `"id"` of the task touched on create and update.

| Method | Path | Body |
|---|---|---|
| `GET` | `/api/todos` | – |
| `POST` | `/api/todos` | `{"text": "..."}` (max 500 chars) plus any fields below |
| `POST` | `/api/tasks` | `{"title": "..."}` plus any fields below. With `external_id`, an existing task with that id is updated instead of a new one being added |
| `PATCH` | `/api/todos/<id>` | any of `text`/`title`, `done`, and the fields below; `404` if the task is gone |
| `POST` | `/api/todos/<id>/update` | same as `PATCH`, for clients that can't send `PATCH` |
| `DELETE` | `/api/todos/<id>` | – |
| `POST` | `/api/todos/reorder` | `{"ids": [...]}` |
| `POST` | `/api/todos/clear-done` | – |
| `POST` | `/api/todos/<id>/retag` | – (ask Claude again; fields you set stay) |
| `GET` | `/api/ai` | – (`{"enabled", "model", "queued"}`) |
| `GET` | `/healthz` | – |

Every `/api/todos/...` path also works as `/api/tasks/...`.

Task fields (all optional, `null` clears):

| Field | Value |
|---|---|
| `due` | `YYYY-MM-DD`. Tasks with a due date go in Deadlines |
| `due_time` | `HH:MM`, only kept when `due` is set |
| `priority` | `high`, `medium` or `low` |
| `color` | `yellow`, `pink`, `blue`, `green`, `purple` or `orange` |
| `course`, `kind`, `details`, `link`, `source`, `external_id` | text; `link` must be http(s) |
| `tags` | list, or a comma-separated string |
| `effort_min` | time estimate in minutes, or a string like `"45m"`, `"2h"`, `"1h30m"` |

Set by the AI (read-only): `category`, `ai_set` (which of `priority`, `due`, `color` Claude
filled in), `ai` (model and time of the last run).

Old one-line deadlines from class-notes v1 (`📌 Course: Title · due Fri 28 Nov 23:59`) are split
into `course`, `text`, `due` and `due_time`, both for existing tasks on first start and for new
ones posted in that format. Task ids don't change.

## Works with class-notes

class-notes turns recorded lectures into written notes and pulls out deadlines. With
`STICKY_TODO_API=v2` it `POST`s each one to `/api/tasks` with its due date, course, details and a
public link to that lecture's notes, so it lands in Deadlines and on the calendar. If a deadline
changes later, it updates the same task instead of adding a second one. In v1 mode it sends a single
line of text, which the server splits into the same fields. Anything that can make an HTTP request can add tasks the same way.
