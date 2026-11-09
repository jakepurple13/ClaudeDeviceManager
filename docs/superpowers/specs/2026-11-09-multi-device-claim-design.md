# Multi-device claim: design

Date: 2026-11-09
Branch: `worktree-multi-device-claim`

## Why

Today a session can hold at most one device per platform: one Android and one iOS. Some tests need several devices of the same platform at once, for example:

- two phones tested side by side;
- phones that send each other information, such as a message from one phone that arrives on another.

These tests are useless when only some of the devices are present. Holding one phone while waiting for the second phone also blocks other sessions, and two sessions can deadlock this way.

## What success looks like

- A session can ask for a set of devices in one `cdm acquire`. The set can contain several devices of one platform, and it can mix platforms (for example, one Android phone and two iPhones).
- The session waits until the manager can grant the whole set at once. It never holds part of the set while it waits.
- Each device in the set has a short name. Claude runs a command on one device with `cdm run <name> -- <command>`. The manager points that command at that device, and Claude never copies serials around.
- Sessions that hold one device per platform behave exactly as they do today.

## Goals

1. Request a set of devices in one `cdm acquire`, with a count for each platform.
2. Grant all or nothing. A set is granted only when every member can be granted at the same time.
3. Name the members of a set, and run any command on one member with `cdm run`.
4. Keep the guard that stops a session from using a device it doesn't hold.
5. Make iOS work the same way as Android.
6. Show sets in the app UI: who holds which devices, and what each queued session is waiting for.

## Non-goals

- Changes to the `:sample` app.
- Different idle timers for different members of one set.
- Adding a device to a set, or removing one device from a set, after the grant. To change a set, release it and acquire again.

## Anti-goals (a failure even if it technically works)

- A session holds part of a set while it waits for the rest.
- A set that is waiting is overtaken forever by smaller requests, so it starves.
- A device command from a session that holds several devices on a platform runs on an arbitrary one of those devices without warning.
- A change to how today's single-device sessions behave, including automatic pinning, grab on first use, and the `cdm` output that Claude already parses.

## Decided by the user

- A session that asks for several devices waits until it can get all of them together.
- Claude says which device each command is for with `cdm run <name> -- <command>`. The manager sets `ANDROID_SERIAL` (Android) or `CDM_IOS_UDID` (iOS) for that command. Claude does not pass raw serials around.
- iOS works the same way as Android.
- A session can wait for a mixed set, such as one Android phone and two iPhones, in one go.
- One command can use every device in the set at once (`cdm run all`).
- One set can mix device kinds on the same platform, such as an Android phone plus an Android watch, so each member is described on its own.

## Design

### Requesting a set

Each member is described on its own, so one set can mix platforms and kinds (user decision). Each `--android` / `--ios` flag adds one **member spec**. The flag takes an optional value: a count, a category, or `<count>:<category>` (syntax is my call):

```
cdm acquire --android 2 --category phone            # two Android phones
cdm acquire --android phone --android watch         # one Android phone and one Android watch
cdm acquire --android 2:phone --ios 1:phone         # two Android phones and one iPhone, granted together
cdm acquire --android                               # unchanged: one Android device
```

- A spec's category may be a comma list (`phone,watch`), which keeps today's meaning: either kind. A spec without a category uses `--category`, or the project default when `--category` is absent, as today.
- `--real` and `--virtual` apply to every member.
- `--device <serial|name>` adds one member spec for that exact device, and can be repeated.
- A command with one flag and a count of 1 (or only `--device` once) is today's single request, with today's behavior and output. A request is a **set request** when it describes two or more members. Today the script refuses `--ios` and `--android` together ("Pass only one of --ios and --android."). That restriction is lifted for set requests.
- The request model is a list of member specs (each one a `Wants`), not a count per platform. Fitting a set is an assignment problem: one device may fit several specs (a phone fits both `phone` and `phone,watch`). The allocator must find an assignment whenever one exists, for example with bipartite matching, not a first-fit that can fail when an assignment is possible.
- A set request is refused with exit status 2 if:
  - the session already holds any device (release first), or
  - no assignment exists over the listed, not-don't-use devices, so the request could never be met.
- No count cap beyond that.

### Granting and the queue (my call)

- A set is a single queue entry. It is granted only when every member spec can be assigned a free, connected, usable device, and every paired Apple Watch's partner is free too.
- **No starvation: reservations.** The first set in the queue reserves devices as they become free, as long as the reserved devices still lead toward a full assignment, until it has a device for every member spec. Then it is granted all at once. A reserved device is not a hold:
  - no command may run on it;
  - it does not count as activity;
  - later queue entries cannot take it.
  - The UI shows it as "reserved for <session>".
- A reservation ends, and its devices are passed on through the queue, when the set leaves the queue. The set can leave because it was granted, its waiter detached past the queue grace, the user removed it, or a reserved device was marked don't-use or went offline. If a device goes offline, the set keeps its place and its remaining reservations, and the set reserves the next fitting device that becomes free.
- Only the first set in the queue reserves devices. A later set or single request can still take a device that the first set does not need, for example a device of another platform or category.
- Single requests behave as today.

### Holding a set (my call)

- Everything granted by one set request is one **set**, across all its platforms. A mixed set (1 Android + 2 iPhones) is one set, not two groups. The allocator needs a set ID above the members. A holder has at most one set. A single-device hold is today's hold, not a set.
- **Timers:** the set shares one idle timer and one editing timer. Activity on any member, on any platform, resets the timer for the whole set. A timeout releases the whole set.
- **Regrab after a timeout:** the released record keeps the set's member keys and names. The first `cdm run <old name>` (or explicit-serial command for an old member) tries an all-or-nothing regrab, like `tryRegrab` does today for single holds. If any former member was taken, the command is denied with "Your devices timed out and one was taken; run `cdm acquire` again." It does not queue.
- **Release:** `cdm release` releases the whole set. `cdm release --android` releases only the Android members. The user asked for this explicitly, so the remaining members stay held as a smaller set, with their names unchanged.
- **User actions in the app:**
  - Releasing one member releases the whole set, because a partial set is useless.
  - Hand-off is not supported for set members. The control is disabled, and its tooltip says why.
  - Don't-use with release on a member releases the whole set.

### Names (my call)

- Members are named by platform and grant order: `android1`, `android2`, …, `ios1`, `ios2`, …. A paired Apple Watch is part of its iPhone's member, and the pair is exposed as `CDM_IOS_PAIR_UDID`, as today.
- Names belong to the holder. They stay stable until the set is released.
- A single-device hold is named `android1` / `ios1` too, so `cdm run` works for every session.

### `cdm acquire` output for a set

One line per member, then a usage line:

```
Got your devices:
  android1  Pixel 8 (39071FDJH00A2K)
  android2  Galaxy S23 (R5CW1234ABC)
  ios1      iPhone 15 (00008120-001A2B3C4D5E6F)
Run commands on one device with `cdm run <name> -- <command>`, for example `cdm run android2 -- adb shell input tap 100 200`.
For iOS, still pass $CDM_IOS_UDID: `cdm run ios1 -- xcrun simctl launch $CDM_IOS_UDID com.example.app`.
```

The wire line becomes one `GRANTED <platform> <name> <serial> <device name>` per member, followed by `DONE`. The single-request wire format and script output stay as they are today. `cdm status` lists each member with its name.

### `cdm run <name> -- <command>`

- **`cdm run all -- <command>` (user decision):** runs one command with every member exported, for scripts, Gradle runs and Maestro flows that drive several devices at once:
  - `CDM_ANDROID1_SERIAL`, `CDM_ANDROID2_SERIAL`, … and `CDM_IOS1_UDID`, `CDM_IOS2_UDID`, … (plus `CDM_IOS1_PAIR_UDID` for a paired watch);
  - `ANDROID_SERIAL` as a comma list of every Android member, for Gradle connected tasks. The builder must verify that AGP's connected tasks accept a comma list in `ANDROID_SERIAL`. If they don't, the builder finds the supported way to limit a Gradle connected run to the set's devices, and documents it. A plain `adb` with a comma list fails with an error rather than reaching a random device, which is acceptable;
  - `CDM_IOS_UDID` is not set, because it can hold one device only. An iOS device command inside `cdm run all` must name a member (`$CDM_IOS2_UDID`). KMP Gradle iOS tests, which rely on `CDM_IOS_UDID`, are denied inside `cdm run all` with a message to use `cdm run ios<N>`.
  - Inside `cdm run all`, a command may name any member and no other device.
- **Per-member variables everywhere:** every command from a set holder, with or without `cdm run`, also gets the `CDM_<PLATFORM><N>_…` variables exported. A script can then find the devices the same way in every case.
- **In the hook (main path):** the pre-tool-use hook recognizes a segment of the form `cdm run <name> -- <rest>`. It resolves `<name>` against the holder's members. It decides `<rest>` with today's rules, with the target fixed to that member, so a `-s`, `ANDROID_SERIAL` or `-destination` that names any other device is denied. It then rewrites the segment to `( export ANDROID_SERIAL=<serial>; <rest> )` (Android) or `( export CDM_IOS_UDID=<udid>; [export CDM_IOS_PAIR_UDID=<partner>;] <rest> )` (iOS). The subshell keeps one segment's pin from leaking into the next chained segment. `export` (not a bare `VAR=x cmd` prefix) is needed so `$CDM_IOS_UDID` in `<rest>`'s own arguments expands to the member. It applies today's `booted` rewrite and KMP init script rule to `<rest>`.
- **Permissions:** `PermissionMirror` judges the command with every `cdm run <name> --` prefix removed, so an allow rule such as `Bash(cdm:*)` never auto-allows what runs inside `cdm run`. The inner command gets the same allow/ask answer it would get on its own.
- A `cdm run` with an unknown name is denied with the holder's member names. So is a `cdm run` from a session that holds nothing.
- `cdm run` counts as device activity for the member's group.
- **In the script (fallback, my call):** when the hook is present it rewrites `cdm run` away, so the script only sees `cdm run` when there is no hook, and so no holder identity. The script handles `run` before its holder check: it prints "Device Manager not running; running without a device lock." and runs `<rest>` unchanged, matching today's `not_running` behavior. No new endpoint.
- Chaining (`cdm run android1 -- a && cdm run android2 -- b`) is allowed. Each segment is decided against its own member.

### Commands without `cdm run`

- If a session holds **more than one** member on a platform, there is no automatic pin for that platform. A device command for that platform that does not go through `cdm run` is denied, with a message that lists the member names and the `cdm run` form. Every other command (`CommandKind.Other`: `ls`, `git`, `./run-test.sh`, …) still runs, because denying it would block ordinary shell work. Today the hook pins those commands too. For a set holder it instead exports a sentinel, `ANDROID_SERIAL=use-cdm-run` (and `CDM_IOS_UDID=use-cdm-run` when the set has more than one iOS member). A script that calls `adb` or Gradle without `cdm run` then fails with "device 'use-cdm-run' not found" instead of reaching another session's device (my call, replacing an earlier draft that denied these commands). One exception: a command that names a member explicitly with `-s <serial>`, `ANDROID_SERIAL=…` or `-destination id=…` is allowed and pinned to that member. Claude may have copied the serial from `cdm devices`, and that is not a reason to block it.
- If a session holds **one** member on a platform, the automatic pin works as today.
- Grab on first use stays as it is for a session that holds nothing. It never creates a set.
- Platform-wide commands that today are allowed unpinned (`adb devices`, `adb version` and similar safe global commands) stay allowed.

### UI

- **Sessions and Devices screens:** a holder's members show with their names. Members of one set are visibly grouped.
- **Queue screen:** a set entry shows what it is waiting for (for example "2 × Android phone, 1 × iOS phone") and how many devices it has reserved so far (for example "1 of 3 reserved").
- **Reserved devices:** these show as "reserved for <session>", with a chip distinct from "held".
- New rows and state changes animate, as elsewhere in the app.

### Docs and installed instructions

- The CLAUDE.md block written by `Installer.kt` gets one more sentence: to hold several devices, run for example `cdm acquire --android 2`, then run each command with `cdm run <name> -- <command>`. The existing single-device wording stays.
- README: document set requests, `cdm run`, reservations and the deny rule.

## Constraints

- The allocator is not thread-safe. It stays behind the service's Mutex.
- `HookDecider` must keep checking every segment (Ruling G) and must keep deferring events across a decision (`deferEvents` / `undoGrab`).
- Holds are not persisted in Room today (`AppDatabase` stores device config, projects, settings, activity and blocked holders), so sets and reservations stay in memory and need no migration.
- Existing tests (about 262) must keep passing unchanged, except where they assert "at most one per platform" for set requests.

## Left to the builder

- How the allocator models groups internally. For example, `holdings` keyed by platform with a list of members, or a new `Group` type. The public snapshot must expose members, names and reservations.
- Whether `Wants` grows a count per platform, or a new `SetWants` type wraps one `Wants` per platform.
- How the classifier parses `cdm run <name> -- <rest>`, including quoting.
- Test layout. Allocator rules (all or nothing, reservations, starvation, timeouts of a group, regrab) need unit tests. The hook needs tests for `cdm run` rewrite, deny and chaining.

