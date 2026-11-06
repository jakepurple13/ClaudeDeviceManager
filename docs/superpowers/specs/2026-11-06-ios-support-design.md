# iOS devices, simulators and emulator labels — design

Date: 2026-11-06 · Branch: `worktree-ios-support` · Mockup: https://claude.ai/artifact/DmqKyYJnefcSqw1VKAzV26 (approved)

## Intent

Claude Device Manager (CDM) today shares Android devices between Claude Code sessions. Sessions also do iOS work
(Xcode projects, KMP iOS targets, Maestro, XCUITest) and fight over iPhones, iPads, Apple Watches and simulators the
same way. CDM should manage those too, with the same queue, holds, timers and controls.

While doing this, virtual devices must be obvious: Android emulators and iOS simulators get a visible label so nobody
confuses them with real hardware.

## Goals

1. Detect and manage on macOS:
   - real iPhones, iPads and Apple Watches;
   - booted iOS, iPadOS and watchOS simulators.
2. Label every virtual device: Android **emulator**, iOS **simulator**.
3. Add a **tablet** category. iPads are detected as tablet. Android tablets get it only by manual override.
4. Let a session ask for a platform (`--ios` / `--android`) and real/virtual (`--real` / `--virtual`) only when it needs
   to. With no flag, any platform and either kind fits (user decision).
5. Pin a holding session's iOS commands to its device. Deny iOS commands that would reach another session's device.

## Non-goals

- iOS on Windows or Linux. On those OSes the app stays Android-only and shows no iOS section (user decision).
- Booting or creating simulators. CDM manages simulators that are already booted.
- Driving iOS UI. ARTEMIS is Android-only.

## Anti-goals

- A session's iOS command lands on a simulator or device another session holds.
- The device list fills with every simulator Xcode has installed (dozens of shutdown sims).
- Android behaviour changes for existing users.

## Decided

| Topic | Decision | By |
|---|---|---|
| Platforms | iPhone, iPad, Apple Watch, plus simulators | user |
| Simulators | managed, and visibly labelled | user |
| Android emulators | visibly labelled | user |
| OS scope | iOS only on macOS | user |
| Default fit | session that doesn't specify gets real or virtual, any platform | user |
| Explicit ask | `--real` / `--virtual` / `--ios` / `--android` are honoured strictly | user |
| UI | as in the mockup: Android / iOS group headers in the device list; dashed `emulator`/`simulator` chip with a monitor icon; OS version in simulator names; watch shows "paired with <iPhone>"; detail shows Platform, Kind, Runtime, UDID; category choices phone/tablet/watch; queue rows show platform and real/virtual limits | user approved mockup |
| Category name | `tablet` (not `ipad`) | Claude, shown in mockup |
| Request syntax | flags on the existing `--category` form: `cdm acquire --category phone --ios --real` | Claude (the mockup showed `cdm acquire phone`; the real CLI already uses `--category`) |
| Holds per session | one Android **and** one iOS device at once (one per platform per holder) | user |
| Real watch + iPhone | reserved together: granting a real watch also holds its paired iPhone, and vice versa | user |
| Android tablets | no auto-detection change: existing Android devices keep phone/watch; `tablet` reaches Android only by manual override | Claude (protects the "no Android change" anti-goal) |
| Platform of auto-grab | inferred from the command: `adb`/Android Gradle tasks grab Android, iOS tools grab iOS. Project default stays a `Category` (no schema change) | Claude |
| Untracked UDIDs | `simctl boot <udid>` is allowed; the sim then appears free and must be acquired. Any other command naming a UDID CDM doesn't track is denied | Claude |
| Held sim disappears | the hold stays, like an offline Android device ("offline · held") | Claude |

## Design

### Model

- `Category` gains `TABLET = Category("tablet")`. `chipStyleFor` gets a tablet style (`#23364A` / `#B8D8FF`).
- New `enum class Platform { ANDROID, IOS }`.
- `TrackedDevice` and `AllocDevice` gain `platform: Platform` and `virtual: Boolean`.
- `DeviceKey` for iOS is the UDID. This works for both real devices and simulators, and doesn't collide with Android serials.
- Saved per-device config (`DeviceConfig`: category override, don't-use, display name) works unchanged keyed by UDID.
- Wants: the allocator's `wants: Set<Category>` becomes a `Wants` value:
  `Wants(categories: Set<Category>, platform: Platform?, virtual: Boolean?)`.
  `null` means "don't care". `fits()` checks all three.
- Queue persistence and events carry the full `Wants`.

### One hold per platform

A holder may hold at most one Android device and at most one iOS device. This is a core allocator change:

- Holds: `holds: Map<DeviceKey, HolderId>` stays. The "one device per holder" check (`MORE_THAN_ONE`) becomes "one
  device per platform per holder".
- Status: `HolderStatus.Holding` becomes per platform. Either `statusOf(holder, platform)`, or `Holding` carries a
  `Map<Platform, Hold>`. The builder picks one; every caller (`serialFor`, timers, hand-off, release, UI) must handle
  two holds.
- Timers: idle and edit timers run per hold. Each device's idle timer resets only on commands for its platform.
  Editing affects both.
- Release: `cdm release` releases everything the holder has. `cdm release --ios` / `--android` releases one.
  `SessionEnd` and `SubagentStop` release all.
- Queue: a holder may queue for one platform while holding the other. A queue entry is per (holder, platform).
- Hook pinning: Android commands pin `ANDROID_SERIAL` from the Android hold; iOS commands pin `CDM_IOS_UDID` from
  the iOS hold. A command mixing both (`adb …; xcodebuild …`) gets both exports. An Android command from a holder with
  only an iOS hold grabs an Android device as a fresh grab, and the same the other way round.
- UI: the Sessions screen shows up to two devices per holder.

### Watch–iPhone pairs (real devices)

- A real Apple Watch and its paired iPhone form a pair. Granting either one also holds the other for the same holder.
  The iOS hold then covers two devices: the requested one is primary and the partner is attached.
- A pair is grantable only when both devices are free and not "don't use".
- Release, hand-off and timeouts act on the pair as a unit.
- `CDM_IOS_UDID` is the requested device. The partner's UDID is exported as `CDM_IOS_PAIR_UDID`. The deny rule treats
  both as the holder's own.
- Both cards show the pairing: the watch shows "paired with <iPhone>", and the iPhone shows "paired with <watch>".
  When held, both show the same holder.
- Simulator watch/phone pairs are **not** coupled. Simulators are cheap and the user can create more.
- If the pairing can't be read from devicectl, the devices stay independent and the "paired with" line is hidden.

### Detection

New `IosClient` interface with a process-backed implementation. It runs only when `os.name` is macOS and `xcrun` resolves.

- **Simulators:** `xcrun simctl list devices booted --json`. Include only `state == "Booted"`.
  - Category comes from the device type: iPad → tablet, Apple Watch → watch, else phone.
  - Name is `<name> · <runtime>`, e.g. "iPhone 16 Pro · iOS 18.2". The runtime name is parsed from the runtime key.
  - `virtual = true`.
- **Real devices:** `xcrun devicectl list devices --json-output <tmpfile>`. Include connected, paired devices.
  - Category comes from `hardwareProperties.deviceType`: iPhone, iPad or appleWatch.
  - Use `udid` as the key and `virtual = false`.
  - A real watch records its companion iPhone UDID, if devicectl exposes one. The card then shows "paired with <name>".
- New `DeviceSource` merges the Android tracker and `IosTracker` into one `List<TrackedDevice>`.
  - iOS scan failures are isolated: Xcode missing or a timeout gives an empty iOS list and a log line, never a broken Android scan.
  - Use the same 30 s failure backoff as Android.
- Android emulators already detect via `isEmulatorSerial`. Set `virtual = true` for them.

### Pinning and the hook (iOS)

There is no `ANDROID_SERIAL` for iOS, so pinning is layered:

1. **Export.** A holding session's iOS commands get `export CDM_IOS_UDID=<udid>; ` prefixed. A session that holds an
   Android device keeps `ANDROID_SERIAL` exactly as today.
2. **Rewrite `booted`.** In `xcrun simctl <sub> booted …`, `booted` is replaced by the held UDID.
3. **Deny another UDID.** Any UDID-shaped token in an iOS command that names a device CDM tracks but the session
   doesn't hold is denied, with the same style of message as `adb -s <other>`. Covers:
   - `-destination 'id=<udid>'`
   - `simctl … <udid>`
   - `devicectl … --device <udid>`
   - `maestro --device <udid>`
   - `idevice* -u <udid>`
4. **Deny unpinned device-reaching commands.** An iOS device command that names no device and isn't pinnable is denied, with a message
   telling Claude to pass `$CDM_IOS_UDID`. Examples:
   - `xcodebuild test` without `-destination id=`
   - `maestro test` without `--device` (the env export can't steer Maestro)

   `-destination 'platform=iOS Simulator,name=…'` names no UDID, so it counts as unpinned and is denied.
5. **Grab on first use.** Like Android, a session with no iOS hold that runs an iOS device command grabs a free iOS
   device that fits the project default's category. The platform comes from the command. It is denied with a
   `cdm acquire` hint if it can't.
6. **Untracked UDIDs.** `simctl boot <udid>` is allowed. The sim then shows up as free and must be acquired. Any other
   command naming a UDID CDM doesn't track (a shutdown sim, an unknown device) is denied.

`CommandClassifier` gets iOS kinds. Commands that reach a device:
- `xcodebuild` with `test`, `test-without-building` or a device `-destination`;
- `xcrun simctl` subcommands other than `list` and `help`;
- `xcrun devicectl` subcommands other than `list`;
- `maestro`;
- `idevice*`;
- `fastlane` with `scan`, `snapshot` or `run_tests`;
- Gradle tasks matching `ios\w*Test` or `\w*Simulator\w*Test`.

Global-safe, no device: `simctl list`, `devicectl list devices`, `xcodebuild -list`, `xcodebuild build` without a
destination, `xcrun --find`.

Dangerous, always denied while other sessions hold iOS devices: `simctl shutdown all`, `simctl erase all`,
`simctl delete`, `killall Simulator`.

**KMP Gradle iOS tests.** The Kotlin Gradle plugin picks the simulator through the test task's `device` property, which
defaults to a fixed model name. The builder must spike whether an init script can set the property from
`CDM_IOS_UDID`: the hook would then add `--init-script ~/.claude-device-manager/ios-pin.gradle` (installed and removed
with `cdm`). If it can't, KMP iOS test tasks are denied unless the command carries
`-Pkotlin.native.simulator.device=$CDM_IOS_UDID` (the exact property comes from the spike) and the `CLAUDE.md` note says so.

### `cdm` and CLAUDE.md

- `cdm acquire` accepts `--ios`, `--android`, `--real` and `--virtual`, plus category `tablet`. The flags pass through
  `/cdm/acquire` into `Wants`.
- On grant, `cdm acquire` prints the platform and the identifier: `ANDROID_SERIAL=…` or `CDM_IOS_UDID=…`.
- The `CLAUDE.md` block:
  - names iOS tools (xcodebuild, simctl, devicectl, Maestro, KMP iOS tests) as device work;
  - says to pass `$CDM_IOS_UDID` explicitly: `-destination id=$CDM_IOS_UDID`, `simctl … $CDM_IOS_UDID`, `maestro --device $CDM_IOS_UDID`;
  - lists the new flags.

### UI (per mockup)

- **Devices list:** grouped under "Android" and "iOS" headers. On non-macOS the iOS group is absent. The header shows
  only when it has devices.
- **Device card:** a dashed `emulator` or `simulator` chip with a monitor icon after the category chip when `virtual`.
- **Device detail:** Platform, Kind (Real / Emulator / Simulator), Runtime (OS version) and UDID or serial. Category
  choices are phone / tablet / watch.
- **Queue card:** shows the `Wants` limits as chips: platform (`iOS`/`Android`), `real only`, and dashed `virtual only`.
- Wait reasons cover the new limits, e.g. "no Wear OS emulator running".
- Animations follow the existing patterns (chips fade, rows `animateItem`).

## Left to the builder

- Exact JSON field paths for simctl and devicectl output. Verify against the installed Xcode, and capture fixtures for tests.
- How a watch's paired iPhone is found. If devicectl doesn't expose it, drop the "paired with" line.
- The KMP Gradle pin mechanism (spike above).
- The Room migration for the new `Wants` / platform columns.
- Test layout: follow the existing `jvmTest` structure. Use fake `IosClient`s with recorded JSON. Add classifier cases
  for every command form listed here.
