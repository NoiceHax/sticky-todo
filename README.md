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

- **Home-screen widget**: a scrolling list of tasks. Tap a task to tick it off, or use the header
  buttons to refresh or add one.
- **Refreshes when you unlock the phone**, so the widget is current the moment you look at it. Android
  only delivers the unlock event to a running app, so a small foreground service keeps that listener
  alive. It does nothing between unlocks and starts again after a reboot.
- **Server address is set in the app.** Open it, paste your server's URL and press save, then use
  *Pin widget* to place it.

Build it yourself with `cd android && ./gradlew assembleRelease` (Android SDK, JDK 17).

## Windows startup window

`windows/open-todo.ps1` waits up to 45 seconds for the server to answer (the network and
Tailscale are often not up yet at login). It then opens the note in Brave's app mode as a
360×520 window in the top-right corner, using a separate browser profile so it never mixes with your
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

**There is no login.** Anyone who can reach the port can read and edit the list. Keep it on
an address only you can reach. I bind it to my Tailscale and LAN addresses and never expose it to the
internet.

## API

| Method | Path | Body |
|---|---|---|
| `GET` | `/api/todos` | – |
| `POST` | `/api/todos` | `{"text": "..."}` (max 500 chars) |
| `PATCH` | `/api/todos/<id>` | `{"text": "..."}` and/or `{"done": true}` |
| `POST` | `/api/todos/<id>/update` | same as `PATCH`, for clients that can't send `PATCH` |
| `DELETE` | `/api/todos/<id>` | – |
| `POST` | `/api/todos/reorder` | `{"ids": [...]}` |
| `POST` | `/api/todos/clear-done` | – |
| `GET` | `/healthz` | – |

## Works with class-notes

class-notes turns recorded lectures into written notes. When a lecture's notes are published it
`POST`s a task like *"Revise DSA: Graphs 2 https://…"* to `/api/todos`, with a public link to that
lecture's notes. If the notes are regenerated later, it updates the same task instead of adding a
second one. Anything that can make an HTTP request can add tasks the same way.
