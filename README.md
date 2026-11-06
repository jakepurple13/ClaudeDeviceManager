# Claude Device Manager

<img src="composeApp/icons/app-icon.png" alt="Claude Device Manager icon" width="96" align="right">

A macOS desktop app that shares your connected Android devices, and on macOS your iPhones, iPads, Apple Watches and booted simulators, between multiple Claude Code sessions.

When several Claude sessions test on the same phone at once, they fight over the screen and get stuck. While the app is running, every session has to check in before it touches a device. It gets a free device of the right kind, or it waits in a queue. You can watch all of this and step in from the app.

## What it does

- **One session per device.** A session (or any one of its subagents) holds a device until it's done. Every Bash command from that session is pinned to its device with `ANDROID_SERIAL`, including Gradle tasks and wrapper scripts. iOS commands get `CDM_IOS_UDID` (see [iOS](#ios-macos-only)).
- **One Android and one iOS device per session.** A session can hold at most one device of each platform at the same time. Each hold has its own idle timer.
- **The right kind of device.** Devices are auto-detected as phone, tablet (iPad) or watch, and you can override that. Android tablets are never auto-detected as tablet; set it by hand. A session asks for `phone`, `watch`, `tablet`, `phone,watch` or `any`, and can limit the platform (`--android`, `--ios`) and the kind (`--real`, `--virtual`).
- **Emulators and simulators are labelled.** Android emulators show a dashed `emulator` chip and iOS simulators a dashed `simulator` chip, so nobody mistakes them for real hardware.
- **A queue.** If nothing fits, the session pauses until a device frees up. It doesn't improvise or give up.
- **Automatic release.** A device is released after 15 minutes without an `adb` or Gradle command, or 5 minutes once the session has moved on to editing code. Both times can be changed. A device that drops off USB or Wi-Fi stays held.
- **You stay in control.** From the app you can:
  - release a device, or release it and block that session;
  - mark a device "don't use";
  - change a device's category;
  - reorder the queue, or hand a device to a waiting session right away.
- **Nothing happens when it's closed.** The app adds its Claude Code hooks, the `cdm` command and a `CLAUDE.md` note when it starts, and removes them when it quits. With the app closed, Claude works exactly as before.

## Requirements

- macOS (Linux should work but isn't tested).
- JDK 21 to build.
- For iOS (macOS only): Xcode, with `xcrun` working. Without it the app is Android-only and shows no iOS section.
- Claude Code.

JetBrains Runtime 25 is downloaded automatically the first time you run or package the app. The custom title bar needs it.

### adb

The app ships with Google's `adb` (platform-tools r37.0.2) for macOS, Windows and Linux x86-64, so you don't need to install it. Settings → adb picks which adb the app runs:

- **Automatic** (default): your own adb when one is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`, the Android Studio SDK folder, Homebrew), otherwise the bundled copy.
- **Bundled**: always the copy shipped with the app.
- **Custom**: an adb file you choose.

Every adb on a computer shares one adb server. A client of a different server version restarts it, which drops device connections for a moment, so Automatic prefers the adb your Claude sessions already run. Claude sessions still run their own `adb` from `PATH`. The bundled copy is for the app's device scan.

The build downloads platform-tools for the OS it runs on (`./gradlew :composeApp:downloadAdb`), checks its SHA-256, and keeps only `adb`, the two Windows DLLs and Google's `NOTICE.txt`. To update it, change `platformToolsVersion` and the checksums in `composeApp/build.gradle.kts`. Linux arm64 builds ship no adb, because Google publishes none.

## Install

Download the installer for your OS from the [latest release](https://github.com/jakepurple13/ClaudeDeviceManager/releases/latest): `.dmg` (macOS), `-nsis.exe` (Windows), `.deb` or `.AppImage` (Linux).

The builds are unsigned. On macOS the first launch is blocked: open System Settings → Privacy & Security and click **Open Anyway**. On Windows, SmartScreen asks once.

After that the app updates itself. It checks GitHub releases at launch and every 6 hours, and Settings → Updates has **Check now**. When an update is found, a banner offers **Download**, then **Restart to update**. Nothing restarts on its own, because a restart releases every held device.

## Run it

```bash
./gradlew :composeApp:run
```

Build the installers for the current OS:

```bash
./gradlew :composeApp:packageDistributionForCurrentOS -PappVersion=1.2.0
```

Run the tests:

```bash
./gradlew :composeApp:jvmTest
```

## How it works

```
Claude Code session ──(http hooks)──▶ Claude Device Manager ◀── adb (device scan)
        │                                   ▲
        └── cdm acquire / release ──────────┘  (local HTTP on 127.0.0.1:47823)
```

1. **On startup** the app binds `127.0.0.1:47823` and writes the port to `~/.claude-device-manager/port`. It then installs these:
   - Four `http` hooks in `~/.claude/settings.json`: `PreToolUse` for Bash, `PreToolUse` for file edits, `SubagentStop` and `SessionEnd`. Your settings file is backed up first, and your other hooks are left alone.
   - `~/.local/bin/cdm`.
   - `~/.claude-device-manager/ios-pin.gradle`, a Gradle init script that pins KMP iOS simulator tests.
   - A short marked block in `~/.claude/CLAUDE.md` telling Claude to run `cdm acquire` before device work.
2. **Before every Bash command** the hook asks the app what to do:
   - If the session holds a device, the command is rewritten to `export ANDROID_SERIAL=<serial>; <command>`.
   - A device command from a session with no device grabs a free one, or is denied with a message telling Claude to run `cdm acquire`.
   - Commands that would reach someone else's device (`-s <other>`, `ANDROID_SERIAL=<other>`, `adb kill-server`, …) are denied.
3. **Subagents are separate holders.** Each one is identified by session ID plus agent ID, and gets its own device or queue spot.
4. **On quit** (window close, Cmd+Q or SIGTERM) the app removes only its own hook entries, the `CLAUDE.md` block and `cdm`. A crash leaves the entries behind. They fail open (Claude carries on as normal) and are cleaned up the next time the app starts.

> Claude Code reads hooks and `CLAUDE.md` when a session **starts**. Start (or restart) sessions after the app is running for them to use it.

### The `cdm` command

Claude runs this itself through its Bash tool, which adds the session identity:

```bash
cdm acquire --category phone             # wait for a phone (also: watch, tablet, phone,watch, any)
cdm acquire --category phone --ios       # only an iPhone (or an iPhone simulator)
cdm acquire --category watch --android --virtual   # only a Wear OS emulator
cdm acquire --category tablet --ios --real         # only a real iPad
cdm devices                              # every device: serial, name, platform, kind, category, free/in use
cdm acquire --device 39071FDJH00A2K      # that one device (serial, UDID or exact name); waits if it's in use
cdm release                              # give back every device this session holds
cdm release --ios                        # give back only the iOS device (or --android)
cdm status                               # what this session holds, and its queue position
```

`--android` / `--ios` pick the platform and `--real` / `--virtual` pick a real device or an emulator/simulator. Without them, any platform and either kind fits. To hold one device of each platform, run `cdm acquire` once with each flag. A session waits in the queue for one platform at a time. `--device` asks for one specific device and waits in the queue for that device only; it takes the serial `cdm devices` prints, a UDID, or an exact device name, and implies its platform and any category. A device that doesn't exist, is marked don't use, or conflicts with the other flags is refused with exit status 2. An unknown flag prints the usage and exits with status 2.

`cdm acquire` waits up to about 110 seconds, which keeps it under Claude Code's default 2-minute Bash timeout. If it's still queued, it says so and Claude runs it again. If the app isn't running, it prints `Device Manager not running; proceeding without a device lock.` and exits 0.

### Integrations (other apps)

Apps and scripts other than Claude Code can share devices through the same server, for example a screen-mirror app that needs a phone. Open **Settings → Integrations → Open integration guide**, or go to `http://127.0.0.1:<port>/`. The guide shows live device status, the full protocol with examples, and a brief to hand to a coding agent. The same brief is plain text at `/llms.txt`.

An integration picks a stable holder ID such as `myapp:main` and uses these endpoints:

| Endpoint | What it does |
|---|---|
| `POST /cdm/acquire` | Waits in the queue for a device and streams `QUEUED` lines, then `GRANTED <platform> <serial> <name>` |
| `POST /cdm/renew` | Resets the idle timer. Call it every 60 seconds while you use the device, because the app can't see an integration's adb commands |
| `POST /cdm/release` | Gives the device back |
| `GET /cdm/status` | Shows what the holder has and its place in the queue |
| `GET /cdm/devices` | Lists every device as JSON, with its holder and the queue |
| `GET /cdm/events` | Sends the same JSON as server-sent events on every change |

The server listens on `127.0.0.1` only and has no authentication: any local process can use it.

## iOS (macOS only)

On macOS with Xcode, the app also manages:

- real iPhones, iPads and Apple Watches that are paired with this Mac (`xcrun devicectl`);
- booted iOS, iPadOS and watchOS simulators (`xcrun simctl`). Shut-down simulators are not listed, and the app never boots or creates one.

The device list groups devices under **Android** and **iOS**. A simulator's name includes its runtime, for example "iPhone 16 Pro · iOS 18.2". On Windows and Linux there is no iOS section.

There is no `ANDROID_SERIAL` for iOS tools, so pinning works differently:

- A session that holds an iOS device gets `export CDM_IOS_UDID=<udid>; ` in front of its commands. **Claude must pass it to the tool itself:** `xcodebuild … -destination id=$CDM_IOS_UDID`, `xcrun simctl <command> $CDM_IOS_UDID …`, `xcrun devicectl … --device $CDM_IOS_UDID`, `maestro --device $CDM_IOS_UDID …`. The `CLAUDE.md` note tells Claude this.
- `booted` in `xcrun simctl <command> booted …` is rewritten to the held UDID.
- KMP iOS simulator test tasks (`iosSimulatorArm64Test` and the like) are pinned automatically: the hook adds `--init-script ~/.claude-device-manager/ios-pin.gradle`, which points the test task at `$CDM_IOS_UDID`.
- A command that names another session's device, or a UDID the app doesn't track, is denied. `xcrun simctl boot <udid>` is the exception: the simulator then shows up as free and can be acquired.
- An iOS device command that names no device (`xcodebuild test` without `-destination id=…`, or with only a simulator name) is denied with a message telling Claude to pass `$CDM_IOS_UDID`. `xcodebuild test` for macOS or Mac Catalyst destinations is allowed.
- `xcrun simctl shutdown all`, `simctl erase all`, `simctl delete` and `killall Simulator` are denied while another session holds an iOS device.
- Maestro runs on Android unless `--device` names a UDID or `$CDM_IOS_UDID`. A session that holds only an iOS device must pass `--device` to Maestro.

### Known limits

- Real iPhone, iPad and Apple Watch support has not been tested on hardware. Detection follows the `xcrun devicectl` output format, and simulators are tested end to end.
- Apple Watch ↔ iPhone pairing isn't detected yet, so a real watch and its iPhone are held separately (the pair reservation is in place but dormant).
- A session waits in the queue for one platform at a time.
- These tools aren't recognised as device commands, so they aren't pinned or checked: `simctl` run by its full path instead of through `xcrun`, `ios-deploy`, `flutter run -d`, `react-native run-ios --udid`, `xctrace --device`.

## Permissions

Rewriting a command stops Claude Code from matching it against your allow rules. You choose how pinned commands are approved in **Settings → Pinned commands**:

| Mode | Behavior |
|---|---|
| **Follow my Claude permission rules** (default) | The app checks your rules against the *original* command, from your user, project and managed settings files. It approves the command only if your rules would have. Otherwise you get the normal prompt. It's deliberately conservative: anything with `sudo`, env assignments, globs, `$` expansion or paths outside the project prompts. |
| **Always allow** | Pinned commands run without a prompt. This skips your allow and ask rules and auto mode's check. Your deny rules still apply. |
| **Always ask** | Every pinned command needs your approval. |

Commands that are only `cdm` are always allowed.

## The app

<!-- Screens follow the mockups in .superpowers/brainstorm (git-ignored). -->

- **Overview:** counts, devices with their holders and timers, who's up next, and recent activity.
- **Devices:** detail for each device, including platform, kind (Real, Emulator or Simulator), OS version, serial or UDID, category, holder, recent commands, release, release and block, and "don't use".
- **Queue:** drag to reorder, see why a session is waiting, hand it a device now, or remove it.
- **Sessions:** every session with its subagents, and their state. Blocked sessions can be unblocked here.
- **Projects:** a default device category for each project.
- **Settings:** port, release timers, pinned-command permissions, which adb to use, install status with Repair, and the `CLAUDE.md` snippet.

## Troubleshooting

- **A session is denied with "You're blocked" while the device is free.** Someone used *Release + block* on it. Blocks last across restarts. Unblock it under **Sessions**.
- **A session ignores the app.** It was started before the app. Restart the session.
- **"Port 47823 is in use".** Another copy of the app is probably running. Close it, or change the port in Settings. The integration is only installed after the server binds.
- **Leftover backups.** `~/.claude/settings.json.cdm-backup-*` files are kept, up to the newest 5. They're safe to delete.

## Tech stack

Kotlin 2.4 · Compose Multiplatform (desktop/JVM) · Navigation 3 (list-detail scenes) · Room 3 (bundled SQLite) · Koin · Ktor server (CIO) · Jewel decorated window · reorderable.

## Project layout

```
composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/
  core/       command classifier, allocator (holds, queue, timers), models
  service/    DeviceManagerService, hook decisions, permission mirror
  server/     Ktor routes for hooks and cdm, server lifecycle
  install/    settings.json / cdm / CLAUDE.md install and uninstall
  devices/    adb and iOS (simctl, devicectl) device tracking
  data/       Room database and repositories
  ui/         Navigation 3 shell, screens, ViewModels, Jewel window
composeApp/src/jvmMain/resources/cdm    the cdm script
docs/superpowers/                       design spec, implementation plan, hook findings
```

## Development notes

- `CDM_START_ROUTE=devices:<serial>` (or `queue:<holder>`, or a bare section name) opens the app on that screen. It's useful for screenshots.
- `spikes/hook-echo.py` is a tiny hook echo server for inspecting real Claude Code hook payloads.
- Tests never touch your real `~/.claude`. Running the app does: it installs and uninstalls as described above.
- `CDM_UPDATE_URL=http://127.0.0.1:8770` makes a packaged build read updates from a local folder (served with `python3 -m http.server 8770 --bind 127.0.0.1`) holding `latest-mac.yml` and the ZIP and blockmap from `composeApp/build/compose/binaries/main/zip`, instead of GitHub.

## Releasing

Push a tag: `git tag -a v1.2.0 -m v1.2.0 && git push origin v1.2.0`. `.github/workflows/release.yml` runs the tests, builds the installers on macOS, Windows and Linux, writes the `latest-*.yml` update files, and publishes the GitHub release. Each tag must be a higher version than the last, or installed apps won't offer it. A `-beta.N` or `-alpha.N` tag is published as a pre-release on that channel.
