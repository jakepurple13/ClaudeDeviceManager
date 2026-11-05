# Claude Device Manager — Design

Date: 2026-11-05
Status: Draft for approval

## Why

Jacob runs several Claude Code sessions at once, and often subagents inside them, but usually has only one or two Android devices connected. Sessions drive devices with `adb` commands, Gradle tasks, and wrapper scripts in Bash. When two sessions test different apps on the same device at the same time, they fight over the screen and both get stuck. This happened on 2026-11-05 with two sessions testing two different apps on one device.

The fix is a desktop app that hands out devices. Anything in Claude that is about to use a device checks in first. It gets a free device of the right kind, or it waits in a queue. Jacob watches and controls all of this from the app.

## Goals

- Only one **holder** uses a device at a time. A holder is a Claude session's main agent or any one of its subagents. Each subagent is treated as its own holder.
- A holder gets only a device of the kind it needs: a watch app gets a watch, a phone app gets a phone.
- A holder that can't get a device pauses until one frees up. It doesn't improvise, loop, or give up.
- Holders give devices back on their own when they stop testing, without losing the device during a normal fix-build-retest loop.
- Jacob can see the state at a glance and step in: release a device, block a holder, mark a device "don't use", change a device's category, reorder the queue, or hand a device to a waiting holder.
- Holders need almost no special knowledge. They use `adb`, Gradle, and scripts as normal, plus one check-in command.

## Non-goals (v1)

- Windows support. The target is macOS. Linux should work but is not tested.
- Managing ARTEMIS MCP tool calls. Those go through ARTEMIS's own device lock and bypass Bash hooks.
- Managing `adb` use from Jacob's own terminal. Only Claude Code goes through the hooks.
- A holder that needs two devices at once. Jacob's apps are all standalone, with no phone/watch companion pairs.
- Android or iOS builds of the app. This is Compose Multiplatform with the desktop/JVM target only.

## Anti-goals (failure even if it "works")

- **Blocking work when the app isn't running.** If the app is closed or unreachable, every command goes through untouched.
- **Taking a device away mid-test.** Long Gradle builds, flaky USB, and flaky Wi-Fi are normal. Neither those nor a quick code edit may cost a holder its device.
- **A slow Bash tool.** The hook sees every Bash command, so commands from holders with no device and no device intent must pass with no noticeable delay.
- **A holder acting on the wrong device.** Once a holder has a device, its commands, including wrapper scripts, can reach only that device.
- **Weakening Jacob's permission settings.** Hooks must not auto-approve commands that Jacob's normal permission rules would have prompted for. This is enforced by permission mirroring, described below.

## How it works

### Pieces

1. **Desktop app** (Compose Multiplatform, JVM). It holds all state and logic, shows the UI, and runs a local HTTP server on `127.0.0.1`. The default port is 47823, changeable in Settings.
2. **Claude Code hooks** of type `http`, which POST the hook's JSON straight to the app. Claude Code treats an unreachable server, a non-2xx reply, or a timeout as a non-blocking error, and the command proceeds. That gives the "app closed means pass-through" behavior for free. Do **not** set `onFailure: "block"`. Use a 2 s timeout on `PreToolUse`.
3. **`cdm` CLI**: a small shell script that uses `curl`. Holders run `cdm acquire`, `cdm release`, and `cdm status`. It reads the port from `~/.claude-device-manager/port`.

### Holder identity

The holder key is `session_id` plus `agent_id` from the hook input. `agent_id` is present when the hook fires inside a subagent or teammate. When it's absent, the holder is the session's main agent (`agent_id = "main"`). Display name: the cwd folder name, the first 4 characters of the session ID, and the agent type when it's a subagent. For example `gabb-messenger #a41f` or `gabb-messenger #a41f · general-purpose`.

**Verify first (spike, task 1 of the plan).** The docs don't say whether subagents share the parent's `session_id`, or whether forks carry an `agent_id`. Confirm with a real session before building on it. If forks have no `agent_id`, record that as a known gap. Don't invent a workaround without asking Jacob.

### Hooks

| Hook | Matcher | What the app does |
|---|---|---|
| `PreToolUse` | `Bash` | Classifies the command and the holder's state (below). Returns no decision, a rewrite, or a deny with a reason Claude can read. |
| `PreToolUse` | `Edit\|Write\|MultiEdit\|NotebookEdit` | Marks the holder as "editing", which switches its idle timer to the shorter one. Never blocks. |
| `SubagentStop` | (all) | Releases that subagent's device and removes it from the queue. |
| `SessionEnd` | — | Releases every holder in that session and removes them from the queue. |

### Pinning commands to the held device

While a holder has a device, **every** Bash command from that holder is rewritten with `updatedInput` to start with `export ANDROID_SERIAL=<serial>;`. Every command is pinned, not only `adb` ones, so wrapper scripts, scrcpy, and anything else that honors `ANDROID_SERIAL` reach the right device. `adb` and Gradle both honor it.

**Permission mirroring (decided by Jacob on 2026-11-05 after the spike).** The spike (`docs/superpowers/spikes/2026-11-05-hook-findings.md`) showed that once a command is rewritten, Claude Code no longer matches it against allow rules. That held for every prefix form. So the app makes the permission call itself, by mirroring Jacob's rules against the **original** command:

- **Project root, not hook cwd (final review, Ruling X).** The hook's `cwd` follows the Bash tool's `cd`s (checked empirically, see the spike doc's "Hook cwd"). The project root is the nearest ancestor of the hook `cwd` (itself included) that contains `.git` or `.claude`, stopping below `$HOME`; with none, the `cwd` itself. The project default category and the Projects row are keyed on the project root.
- The app reads `permissions.ask` and `permissions.deny` from the managed settings file, `~/.claude/settings.json`, and `.claude/settings.json` / `.claude/settings.local.json` of every directory from the hook `cwd` up to (not including) `$HOME`. It reads `permissions.allow` only from `~/.claude/settings.json` and the project root's `.claude/settings.json` / `.claude/settings.local.json`.
- A read-only built-in counts only while it stays inside the project: any non-flag argument that is absolute, starts with `~`, or resolves (after the command's own `cd`s) outside the project root makes it ask. A bare `cd`, `cd -`, or `cd` to such a path does too.
- **Allow + rewrite** when the original command would have run without a prompt. That's the case when the session's `permission_mode` is `bypassPermissions`, or when every subcommand of the original either matches an allow rule or is a read-only built-in (the set Claude Code auto-allows: `ls`, `cat`, `pwd`, `grep`, and so on), and no ask or deny rule matches.
- **Ask + rewrite** in every other case. Jacob sees a normal prompt showing the pinned command.
- Deny rules are still enforced by Claude Code itself, and the mirror also never returns allow when a deny rule matches.
- In auto mode, commands Jacob's rules don't cover get an ask prompt instead of going to auto mode's check. Jacob accepted that tradeoff.
- `cdm` rewrites are allowed outright only when the whole command consists of `cdm` invocations. Otherwise they go through the mirror.

The prefix form is `export ANDROID_SERIAL=<serial>; `, the form the spike proved executes.

Commands from holders with no device are never rewritten, except for `cdm` (below).

### Bash command classification

In order:

1. **`cdm …`:** rewrite to add `--holder <session_id>:<agent_id> --cwd <cwd>`. A holder can't learn its own agent ID any other way.
2. **`adb` commands that aren't about one device** (`devices`, `version`, `help`, `connect`, `pair`, `reconnect`): let them through. `connect` and `reconnect` from a holder with a device reset its idle timer.
3. **`adb` commands that affect every device** (`kill-server`, `start-server`, and `disconnect` without an address or with another holder's device): deny with "This affects other sessions' devices. Ask the user to do it." My call; Jacob can loosen it later.
4. **Device commands:** `adb …` or a Gradle device task (`install*`, `uninstall*`, `connected*`). Then:
   - **Holder has a device:** pin it as above, reset the idle timer, and clear "editing". If the command passes `-s <serial>`, accept the device's own serial or its other transport's serial (same `ro.serialno`). Deny any other serial with "you hold <name> (<serial>)".
   - **`ANDROID_SERIAL` on any command** (final review): an `ANDROID_SERIAL=<serial>` anywhere in the command text (inline, `export`, `env`, inside a subshell), on any command, not only `adb`, overrides the pin. A holder with a device is denied with "you hold …" when the value isn't one of its device's serials. A holder with no device is treated as if it ran `adb -s <serial>`: grab that device if free, else deny naming its holder.
   - **Wrappers** (`timeout`, `gtimeout`, `nohup`, `nice`, `stdbuf`, `time`, `env`, `command`, `exec`, `sudo`, and `(` / `{` grouping) are seen through, so a wrapped `adb -s` is checked like a bare one. `adb -t <id>` is always denied: a transport ID can't be checked against the held device ("use -s or no flag").
   - **Every segment must agree.** When a fresh grab picks a device from one segment's `-s`/`-d`/`-e`/`ANDROID_SERIAL`, every other segment's target must land on the same device, or the command is denied.
   - **`cdm acquire` chained with other work** (for example `cdm acquire --category watch && adb …`) from a holder with no device is denied with "Run `cdm acquire …` on its own first, then your device commands." The hook runs before the acquire, so it would otherwise grab by the project default for the `adb` part.
   - **Holder auto-released by a timer earlier:** if its last device is still free, quietly re-grab it and pin. If someone else has it, deny with "Your <device> was released after <N> min idle and is now in use. Run `cdm acquire --category <category>` to wait for a device."
   - **Holder lost its device through Jacob** (release, "release + block", "hand it now" given to someone else, or "don't use"): deny with "Your device was released from Device Manager. Run `cdm acquire` to rejoin the queue." If the holder is blocked, say so, and say that `cdm acquire` will refuse until Jacob unblocks it.
   - **Holder never had a device:** if the command names a device (`-s`, `-d`, `-e`), grab that device if it's free and not marked don't use. Otherwise deny and say who holds it. With no device named, grab a free device in the project's default category ("any" if unset) and pin. If none is free, deny with "No <category> device is free. Run `cdm acquire --category <category>` to wait for one."
5. **Gradle commands that aren't device tasks:** pin if the holder has a device, and reset the idle timer. Builds count as testing activity.
6. **Everything else:** pin if the holder has a device. Otherwise return nothing.

Keep the lists of `adb` subcommands and Gradle task patterns in one place so they're easy to change. Wrapper scripts run by a holder with no device can't be detected. The `CLAUDE.md` snippet covers that gap by telling every agent to run `cdm acquire` before any device work.

### `cdm acquire` (waiting in the queue)

`cdm acquire --category phone|watch|phone,watch|any`:

- Grants a free device of a matching category at once, if one exists.
- Otherwise joins the queue and long-polls the app. It prints its position each time it changes ("waiting for watch, position 2"). It returns as soon as a device is granted and prints the serial and name.
- Gives up after about 9.5 minutes, under Bash's 10-minute limit. It prints "Still queued at position N. Run `cdm acquire` again to keep waiting." The holder keeps its queue spot for 2 minutes so it can re-call.
- Refuses at once if the holder is blocked.
- **Switching category** (final review, Ruling W): if the holder already holds a device whose category doesn't fit the requested one, that device is released (as a self-release) and the holder grabs or queues for the requested category. A device that fits is kept.
- If Jacob removes the holder from the queue while it waits, it prints "The user removed you from the device queue. Ask them before trying again." and exits 1.
- If the app goes away while it's waiting, it retries the connection for 30 s. Then it exits 0 with "Device Manager not running; proceeding without a device lock."

`cdm release` gives the device back. `cdm status` prints what the holder has, or its queue position.

A `CLAUDE.md` snippet tells every agent, subagents included, three things: run `cdm acquire --category …` before any device work, including wrapper scripts; run `cdm release` when done; and expect that `cdm acquire` may pause for a while. Settings shows the snippet with a Copy button and an "Append to ~/.claude/CLAUDE.md" button that asks for confirmation first.

### Automatic install and uninstall (Jacob, 2026-11-05)

The app installs itself into Claude Code only while it is running, so Claude never tries to reach it when it's closed.

- **On startup**, after the server binds, the app:
  - merges its hooks into `~/.claude/settings.json`, backing the file up first;
  - installs `cdm` into `~/.local/bin`;
  - adds the `CLAUDE.md` snippet.
  All three steps are idempotent.
- **On any clean exit** (window close, Cmd+Q, or SIGTERM through a shutdown hook), the app removes only its own hook entries. It does not restore the backup, so any other settings Jacob changed meanwhile are kept. It also removes the snippet block and deletes `~/.local/bin/cdm`.
- **After a crash**, stale entries can be left behind. They are harmless: the hooks fail open, and `cdm` reports that the app isn't running. The next startup cleans them up.
- **Backups** are pruned to the newest 5.
- **Settings** shows the install state, with a Repair button. There is no opt-out in v1.
- **Limit:** Claude Code reads hooks and `CLAUDE.md` when a session starts. A session started before the app launched won't use it until that session restarts. A session started while the app was running keeps its hooks after the app closes; they fail open, which is harmless.

### Allocation rules

- There is one global queue, ordered first-come first-served. Jacob can reorder it.
- When a device frees up, the first queued holder whose wanted categories include that device's category gets it. Holders it doesn't fit are skipped, not blocked behind.
- Devices marked "don't use" are never handed out. Marking a device that's in use asks whether to release it now or after the current holder releases it.
- **Hand it a device now** (from the Queue detail): releases the device's current holder, the same as a Jacob release, and grants the device to the selected holder.

### Auto-release

- **Idle timer:** 15 minutes since the holder's last `adb` or Gradle command. It resets on each such command.
- **Editing timer:** 5 minutes, used once the holder's latest tool use was a file edit. The next `adb` or Gradle command switches back to the 15-minute timer.
- Both timers can be changed in Settings.
- A device that disconnects stays held. The UI marks it "offline · held". The timers keep running, but the holder's reconnect attempts are `adb` commands, so they keep resetting the timer.
- After an auto-release, the holder's last device is remembered so it can be quietly re-grabbed (see classification).

### Devices

- Discover devices by running `adb track-devices` or polling `adb devices -l` about every 2 s (builder's choice). Read the model, Android version, and transport (USB or Wi-Fi).
- **Identity:** key devices on `ro.serialno`, not the `adb` serial. A device on both USB and Wi-Fi then shows up once, and its settings follow it when it reconnects over a different transport. A holder is handed the `adb` serial of a transport that is currently connected.
- **Category:** auto-detected. `pm has-feature android.hardware.type.watch` means watch, otherwise phone. Emulators are detected the same way. Jacob can override the category in the UI, and the override persists.
- The category set is phone and watch to start, stored as data so more can be added later.

### Persistence (Room 3)

Persisted:

- devices: identity, display name, category override, don't-use flag, last seen
- projects: folder path and default category
- settings: port and timers
- activity log: grants, releases, timeouts, queue joins, and Jacob's actions, with time and reason
- blocked holders

Not persisted (my call): current holds, the queue, and last-device memory live in memory. If the app restarts, every device is free and the queue is empty. Waiting `cdm acquire` calls retry the connection and re-join. Holders that had a device re-grab it on their next device command, if it's still free.

## UI

Clickable mockups from the brainstorming session are in `.superpowers/brainstorm/*/content/prototype.html`. Jacob approved that layout. The mockups say "session", but each row is a holder; subagents appear as their own rows.

- **Shell:** a left navigation rail with Overview, Devices, Queue, Sessions, Projects, and Settings. The rail is there so more sections can be added later. The Queue item shows a badge with the number of waiting holders.
- **Overview:** count tiles (free, in use, don't use, waiting). Below them, a compact device list (status dot, name, category chip, holder, how long held, idle or editing state), "Up next" (the top of the queue), and a recent activity feed.
- **Devices:** a list-detail layout. The detail panel shows serial, Android version, transport, and connection state. It also has a category dropdown marked auto or overridden, and a holder card: project, session short ID, agent type, cwd, time held, last device command, and time until auto-release. The card has Release and "Release + block" buttons. Below that are the holder's recent device commands and a "Don't use this device" toggle.
- **Queue:** a list-detail layout. The list is drag-to-reorder and shows position, holder, wanted categories, and time waited. The detail panel shows the cwd, a "why it's waiting" list (each eligible device with its holder, or "don't use"), and buttons for Move to top, Hand it <device> now, and Remove from queue.
- **Sessions:** every session seen recently, with its holders (the main agent plus subagents) nested under it. Each holder shows its state (holding, waiting, idle, ended, or blocked) and an Unblock button where it applies. Builder's call on detail.
- **Projects:** known project folders with their default category. Rows are created automatically when a new folder first checks in, with category "any".
- **Settings:** port, the two timers, hook install status with an Install/Repair button that merges into `~/.claude/settings.json` (back up the file first, leave other hooks alone, and rewrite the hook URLs when the port changes), `cdm` install to `~/.local/bin`, and the CLAUDE.md snippet.

The mockups use a dark theme. Following the system light/dark setting is fine.

**Custom title bar (Jewel), requested by Jacob.** The main window uses Jewel's `DecoratedWindow` and `TitleBar` (`org.jetbrains.jewel:jewel-int-ui-decorated-window`), coloured from the app's Material 3 scheme so the bar and the nav rail read as one surface. Model it on ScrcpyViewerV2's `ui/window/AppWindow.kt`, `WindowChrome.kt`, and `TitleBarColors.kt` (github.com/jakepurple13/ScrcpyViewerV2).

- Jewel's decorated window needs JetBrains Runtime 25, so run and packaging tasks use a JBR 25 toolchain. Compilation stays on JDK 21.
- On a runtime that can't load it, the app falls back to the normal system title bar.
- The bar shows the app name and a compact live status, for example "2 in use · 1 waiting". My call.

**Animation matters to Jacob.** Motion is a requirement, not polish:

- Navigation transitions between rail sections and when a detail pane opens or closes. Use Navigation 3 transition specs.
- List items animate when they appear, disappear, or move: devices connecting and disconnecting, holders joining and leaving the queue, reorders, and new activity entries.
- State changes animate in place: status dots and chips change color, holder cards switch between held and free, count tiles and the queue badge change value, and the don't-use dimming fades.
- Keep motion quick and calm, roughly 150–300 ms. Nothing should block input or slow down repeated actions.

## Tech constraints (Jacob's choices)

- Compose Multiplatform, desktop/JVM target only.
- Navigation 3 for all navigation. Devices and Queue use a Navigation 3 list-detail scene.
- ViewModels for every screen.
- Room 3 for persistence.
- Koin for dependency injection.

## Builder's calls

- The HTTP server library. Ktor server is the natural fit.
- Versions, Gradle layout, and package structure.
- Exact JSON shapes between `cdm` and the app. Hook replies follow Claude Code's documented `PreToolUse` contract. `updatedInput` replaces the whole tool input, so carry over unchanged fields.
- `adb` polling versus `track-devices`.
- Tests. The allocator (queue, categories, timers, don't-use, blocks, re-grab after timeout) is pure logic and should be fully unit-tested. Command classification needs table-driven tests.

## Calls made during design (for Jacob to check)

- Holds and the queue are not persisted across app restarts.
- `adb kill-server`, `start-server`, and broad `disconnect` are denied for Claude, with a message telling it to ask Jacob.
- `-s <serial>` from a holder with no device grabs that exact device if it's free.
- Every command from a holder with a device is pinned with `ANDROID_SERIAL`, not only `adb` ones, so wrapper scripts are covered.
- Hooks use Claude Code's `http` hook type, with no extra script.
