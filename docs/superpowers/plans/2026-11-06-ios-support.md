# iOS Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CDM manages real iPhones, iPads and Apple Watches plus booted simulators on macOS, and labels every virtual device (Android emulator, iOS simulator).

**Architecture:**
- A new `IosTracker` (backed by `xcrun simctl` / `xcrun devicectl` JSON) runs next to the Android `DeviceTracker`. A `DeviceSource` merges both into one `List<TrackedDevice>`.
- The `Allocator` changes from "one device per holder" to "one device per platform per holder". Real watch and iPhone pairs are granted as one unit.
- `HookDecider` pins iOS commands with `CDM_IOS_UDID`, rewrites `booted`, and denies foreign or unpinned UDIDs.
- The UI follows the approved mockup.

**Tech Stack:** Kotlin 2.x, Compose Multiplatform Desktop, Koin, Room (KMP), Ktor server, kotlinx.serialization JSON, bash (`cdm` script), JUnit/kotlin.test (`:composeApp:jvmTest`).

**Spec:** `docs/superpowers/specs/2026-11-06-ios-support-design.md` (read it first). Mockup: https://claude.ai/artifact/DmqKyYJnefcSqw1VKAzV26

## Global Constraints

- iOS detection runs only when `System.getProperty("os.name")` starts with `Mac` and `xcrun` resolves. Elsewhere the iOS list is always empty.
- Android behaviour for existing users must not change. Every existing `jvmTest` must still pass, with only mechanical signature updates.
- An iOS `DeviceKey` is the UDID string.
- `Category.TABLET = Category("tablet")`. Android tablets are never auto-detected as tablet.
- Env var names: `CDM_IOS_UDID`, `CDM_IOS_PAIR_UDID`. Android keeps `ANDROID_SERIAL`.
- `cdm` flags: `--ios`, `--android`, `--real`, `--virtual` (acquire); `--ios`, `--android` (release).
- Chip colours: tablet `#23364A` / `#B8D8FF`. The virtual chip is dashed `1dp #6E7787` with text `#CFD6E4`, a monitor icon and the text `emulator` or `simulator`.
- No Room schema change is needed. Queue and holds are in memory, and `device_config` is keyed by string. Do not bump the DB version.
- Every process call has a 5 s timeout, the same as `ProcessAdbClient`. iOS failures back off for 30 s and never break the Android scan.
- UI changes animate like existing code: chips fade (`AnimatedVisibility` with `fadeIn`/`fadeOut`), list rows use `animateItem()`.
- Run tests with `./gradlew :composeApp:jvmTest` from the repo root.

## Review Focus

1. **Xcode missing or `xcrun` hangs.** The Android scan and UI keep working, the iOS group is hidden, and one log line is written per backoff window. Test in Task 4.
2. **A KMP command mixing platforms** (`./gradlew :app:installDebug && xcodebuild test -destination id=$CDM_IOS_UDID`). Both exports are present and each half is pinned to that holder's device on its platform. Test in Task 7.
3. **A simulator shut down while held.** The hold stays (`offline · held`). The UDID is still treated as the holder's own, so commands naming it are not denied as foreign. Test in Task 7.
4. **A pair partner set to "don't use" or disconnected while the pair is held.** The pair stays held. It is not handed out again until both partners are usable. Test in Task 3.
5. **`simctl` with `booted` while the holder has no iOS hold.** It grabs on first use, then rewrites `booted` to the granted UDID. It must never pass `booted` through unpinned. Test in Task 7.

---

### Task 1: Model — Platform, tablet, Wants, virtual flags

**Files:**
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/core/model/Models.kt`
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/core/allocator/AllocatorTypes.kt`
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/devices/DeviceTracker.kt` (`TrackedDevice`, emulator `virtual = true`)
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/ui/components/Chips.kt` (`ChipStyle.Tablet`)
- Modify: every compile error caller (tests included), mechanically
- Test: `src/jvmTest/kotlin/com/programmersbox/cdm/core/model/WantsTest.kt` (new), `DeviceTrackerTest.kt`

**Interfaces — Produces:**
```kotlin
enum class Platform { ANDROID, IOS }                         // Models.kt
Category.TABLET                                               // Category("tablet")
data class Wants(val categories: Set<Category>, val platform: Platform? = null, val virtual: Boolean? = null) {
    fun fits(d: AllocDevice): Boolean
    companion object { val ANY = Wants(setOf(Category.ANY)) }
}                                                             // AllocatorTypes.kt
data class AllocDevice(key, name, category, dontUse, connectedSerials,
    val platform: Platform = Platform.ANDROID, val virtual: Boolean = false, val partner: DeviceKey? = null)
data class TrackedDevice(key, serials, model, androidVersion /* rename: osVersion */, transport, detectedCategory,
    val platform: Platform = Platform.ANDROID, val virtual: Boolean = false, val partner: DeviceKey? = null)
HolderStatus.Queued(position: Int, wants: Wants)
HolderStatus.Holding(device, since, lastActivity, editing, val platform: Platform = Platform.ANDROID)
AllocatorEvent.Queued(at, holder, wants: Wants)
ChipStyle.Tablet(Color(0xFF23364A), Color(0xFFB8D8FF)); chipStyleFor(TABLET) = Tablet
```
Rename `androidVersion` to `osVersion` across callers.

- [ ] **Step 1: Write failing tests**

```kotlin
// WantsTest.kt
@Test fun anyCategoryFitsEveryPlatformAndKind() {
    assertTrue(Wants.ANY.fits(dev(platform = IOS, virtual = true)))
    assertTrue(Wants.ANY.fits(dev(platform = ANDROID, virtual = false)))
}
@Test fun platformLimitIsStrict() { assertFalse(Wants(setOf(PHONE), platform = IOS).fits(dev(ANDROID))) }
@Test fun realOnlyRejectsSimulator() { assertFalse(Wants(setOf(PHONE), virtual = false).fits(dev(IOS, virtual = true))) }
@Test fun virtualOnlyRejectsReal() { assertFalse(Wants(setOf(WATCH), virtual = true).fits(dev(ANDROID, category = WATCH))) }
@Test fun tabletIsItsOwnCategory() { assertFalse(Wants(setOf(PHONE)).fits(dev(IOS, category = TABLET))) }
// DeviceTrackerTest.kt
@Test fun emulatorIsVirtualAndUsbDeviceIsNot()  // scan() of emulator-5554 + USB serial → virtual true / false
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*WantsTest*' --tests '*DeviceTrackerTest*'`. Expected: compile FAIL (unresolved `Wants`, `Platform`).
- [ ] **Step 3: Implement the types above.** `Allocator.fits` delegates to `Wants.fits`. Change the `enqueue`/`tryGrab` parameter types from `Set<Category>` to `Wants`. Callers that passed `Set<Category>` wrap it: `Wants(set)`. `DeviceTracker` sets `virtual = isEmulatorSerial(serial)`.
- [ ] **Step 4: Run the full suite** `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: Platform, tablet category, Wants and virtual device flags`

---

### Task 2: Allocator — one hold per platform per holder

**Files:**
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/core/allocator/Allocator.kt`, `AllocatorTypes.kt`
- Test: `src/jvmTest/kotlin/com/programmersbox/cdm/core/allocator/AllocatorPlatformTest.kt` (new); keep `AllocatorQueueTest`/`AllocatorTimerTest` green

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
```kotlin
data class AllocatorSnapshot(devices, holds: Map<DeviceKey, HolderId>, queue: List<HolderId>,
    statuses: Map<HolderId, HolderStatus>,            // unchanged meaning: Blocked > Queued > first Holding > Released > None
    holdings: Map<HolderId, List<HolderStatus.Holding>>) // every hold, ≤1 per platform
fun Allocator.holdingsOf(holder: HolderId): List<HolderStatus.Holding>
fun Allocator.tryGrab(holder: HolderId, wants: Wants, specific: DeviceKey? = null): GrabResult
fun Allocator.release(holder: HolderId, reason: ReleaseReason, platform: Platform? = null) // null = all
fun Allocator.serialFor(holder: HolderId, platform: Platform): String?
fun Allocator.recordDeviceActivity(holder: HolderId, platform: Platform)  // resets only that hold's idle timer
fun Allocator.recordEdit(holder: HolderId)                                // marks every hold editing
fun Allocator.tryRegrab(holder: HolderId, platform: Platform): GrabResult
```
- Internals (builder's choice, but the following must hold): Released status is kept per (holder, platform) so a regrab after a timeout works per platform. `handOff(device, to)` refuses only when `to` already holds a device of that device's platform.

Rules for `tryGrab`:
- If the holder already holds a device that `wants` fits, return Granted for it, as today.
- Otherwise grant a free device that fits `wants`, whose platform the holder doesn't hold yet.
- `specific` keeps its current behaviour, but is refused (`NoneFree`) if the holder already holds that platform.

`enqueue` stays one entry per holder, with `Wants`. `passToQueue` skips holders who already hold the device's platform.

- [ ] **Step 1: Write failing tests** in `AllocatorPlatformTest`:
  - `holdsOneAndroidAndOneIosAtOnce`: grab with `Wants.ANY` twice and get two different platforms; `holdingsOf` has size 2.
  - `secondGrabSamePlatformReturnsExistingHold`
  - `releaseByPlatformKeepsTheOther`: `release(h, SELF_RELEASE, IOS)` leaves the Android hold.
  - `releaseAllFreesBoth`
  - `idleTimeoutIsPerHold`: Android activity at t+10m and none on iOS; at 16m only the iOS hold is released (IDLE_TIMEOUT).
  - `editMarksBothHolds`
  - `queuedHolderWithAndroidHoldGetsIosOnRelease`: holder A holds Android and queues `Wants(PHONE, IOS)`; B releases an iPhone; A now holds both.
  - `passToQueueSkipsHolderAlreadyHoldingPlatform`
  - `handOffRefusedOnlyForSamePlatform`
  - `snapshotStatusesUnchangedForSingleHold`: an existing single-hold scenario gives an identical `statuses` map.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*Allocator*'`. Expected: new tests FAIL, old ones PASS.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite. Fix callers mechanically: `serialFor(h)` becomes `serialFor(h, platform)`; `HookDecider` passes `Platform.ANDROID` until Task 7. Expected: PASS.
- [ ] **Step 5: Commit** `feat(allocator): one hold per platform per holder`

---

### Task 3: Allocator — watch/iPhone pairs

**Files:**
- Modify: `Allocator.kt`
- Test: `src/jvmTest/kotlin/com/programmersbox/cdm/core/allocator/AllocatorPairTest.kt` (new)

**Interfaces:**
- Consumes: `AllocDevice.partner`.
- Produces: `HolderStatus.Holding.partner: DeviceKey?`. `snapshot.holds` maps **both** keys to the holder.

Rules:
- A device with `partner != null` is grantable only when the partner is in `devices`, connected, not `dontUse` and not held. Granting puts both keys in `holds`. The requested key is `Holding.device` and the other is `Holding.partner`.
- `free`, `releaseDevice`, `handOff` and timeouts act on both keys.
- `passToQueue(partnerKey)` tries the pair.
- `setDontUse(partner, true, releaseNow = false)` keeps the pair held.

- [ ] **Step 1: Write failing tests:**
  - `grantingWatchHoldsPairedPhone`
  - `grantingPhoneHoldsPairedWatch`
  - `pairNotGrantedWhenPartnerHeld` (returns NoneFree)
  - `pairNotGrantedWhenPartnerDontUse`
  - `releaseFreesBoth`
  - `releaseDeviceOnPartnerFreesPair`
  - `handOffMovesBoth`
  - `partnerDisconnectWhileHeldKeepsPair` (Review Focus 4)
  - `pairReturnsToQueueOnlyWhenBothUsable` (Review Focus 4)
- [ ] **Step 2: Run** `--tests '*AllocatorPairTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(allocator): reserve watch and paired iPhone together`

---

### Task 4: iOS detection and merged device source

**Files:**
- Create: `src/jvmMain/kotlin/com/programmersbox/cdm/devices/IosClient.kt` (interface + `ProcessIosClient`)
- Create: `src/jvmMain/kotlin/com/programmersbox/cdm/devices/IosTracker.kt`
- Create: `src/jvmMain/kotlin/com/programmersbox/cdm/devices/DeviceSource.kt`
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/service/DeviceManagerService.kt` (takes `DeviceSource` instead of `DeviceTracker`; `AllocDevice` gets platform/virtual/partner)
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/di/*` (Koin wiring; follow the koin skill)
- Test: `src/jvmTest/kotlin/com/programmersbox/cdm/devices/IosTrackerTest.kt`, `DeviceSourceTest.kt`
- Fixtures: `src/jvmTest/resources/ios/simctl-booted.json`, `devicectl-devices.json`

**Interfaces — Produces:**
```kotlin
interface IosClient {
    suspend fun bootedSimulatorsJson(): String   // xcrun simctl list devices booted --json
    suspend fun physicalDevicesJson(): String    // xcrun devicectl list devices --json-output <tmp>; returns file text
}
class IosTracker(client: IosClient, clock: () -> Long = System::currentTimeMillis) { suspend fun scan(): List<TrackedDevice> }
class DeviceSource(android: DeviceTracker, ios: IosTracker?) { suspend fun scan(): List<TrackedDevice> }
fun iosSupported(): Boolean  // macOS && `xcrun` resolves (`/usr/bin/xcrun --find simctl` exits 0)
```
Mapping:
- **Simulators:**
  - `key = udid`, `platform = IOS`, `virtual = true`, `transport = "simulator"`.
  - `model = "<name> · <runtime>"`, where the runtime is parsed from the key `com.apple.CoreSimulator.SimRuntime.iOS-18-2` into `iOS 18.2`. watchOS and tvOS are parsed the same way.
  - Category comes from `deviceTypeIdentifier`: contains `iPad` → TABLET, `Watch` → WATCH, else PHONE. tvOS and visionOS are skipped.
- **Real devices:**
  - Include only devices that are connected and paired.
  - `key = hardwareProperties.udid`, `virtual = false`.
  - `deviceType` `iPhone` → PHONE, `iPad` → TABLET, `appleWatch` → WATCH.
  - `partner`: the watch's companion iPhone UDID, if the JSON has one. Set it on both sides. If it's absent, use `null`.
- `osVersion` comes from the runtime or OS version.
- A failure in either call returns `[]` for that part, logs one line, and backs off for 30 s.

**Step 0 (spike, no commit):**
- Run `xcrun simctl list devices booted --json` and `xcrun devicectl list devices --json-output /tmp/d.json` on this Mac.
- Save sanitized outputs as the fixtures. If no real device is attached, hand-write the devicectl fixture from Apple's schema as observed. Note in the PR that it is unverified.
- Record whether watch→iPhone pairing is exposed, and under which field.

- [ ] **Step 1: Write failing tests:**
  - `parsesBootedSimulatorsWithRuntimeInName`: "iPhone 16 Pro · iOS 18.2", PHONE, virtual.
  - `ipadSimulatorIsTablet`
  - `watchSimulatorIsWatch`
  - `shutdownSimulatorsAreIgnored` (fixture includes one Shutdown)
  - `parsesPhysicalIphoneIpadWatch`
  - `pairedWatchAndPhoneHaveEachOtherAsPartner` (only if Step 0 found the field; else `partnerIsNullWhenNotExposed`)
  - `clientFailureYieldsEmptyAndBacksOff30s` (Review Focus 1)
  - `DeviceSourceTest.iosFailureDoesNotDropAndroidDevices`
  - `DeviceSourceTest.nullIosTrackerMeansAndroidOnly`
- [ ] **Step 2: Run** `--tests '*IosTracker*' --tests '*DeviceSource*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Parse with `kotlinx.serialization.json.Json { ignoreUnknownKeys = true }` into a `JsonElement` tree. `ProcessIosClient` mirrors `ProcessAdbClient.run` (5 s timeout). Koin builds `IosTracker` only when `iosSupported()`. In `scanOnce`, `AllocDevice` copies `platform`, `virtual` and `partner`.
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(devices): detect iOS devices and booted simulators on macOS`

---

### Task 5: Classifier — iOS command kinds

**Files:**
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/core/classify/CommandClassifier.kt`
- Test: `src/jvmTest/kotlin/com/programmersbox/cdm/core/classify/IosCommandClassifierTest.kt` (new)

**Interfaces — Produces:**
```kotlin
CommandKind.IosDevice(val udids: List<String>, val usesBooted: Boolean, val pinnable: Boolean) // reaches a device
CommandKind.IosGlobalSafe                      // no device
CommandKind.IosGlobalDangerous(val what: String)
fun CommandClassifier.platformsOf(command: String): Set<Platform> // ANDROID if any Android Device/Gradle kind, IOS if any Ios* device kind
fun CommandClassifier.rewriteBooted(command: String, udid: String): String // only in `xcrun simctl … booted` positions
```
- `pinnable = true` when the command carries the device UDID itself (`id=<udid>`, `--device <udid>`, `-u <udid>`, a positional UDID after a simctl subcommand), or names `$CDM_IOS_UDID` / `${CDM_IOS_UDID}`, or uses `booted`.
- `xcodebuild` with `-destination 'platform=…,name=…'` and no `id=` is `pinnable = false`. So are `maestro test` without `--device`, and KMP Gradle iOS test tasks with no pin property (Task 8 changes that).
- UDID shape: `[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}`, plus real-device form `[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}`.
- `rank`: IosGlobalDangerous = 6, IosDevice = 4, IosGlobalSafe = 2.

The kind lists are in the spec ("`CommandClassifier` gets iOS kinds"). Copy them exactly.

- [ ] **Step 1: Write failing tests**, one per form. Each asserts the kind plus `udids`, `usesBooted` and `pinnable`:
  - `xcodebuild test -destination 'id=<U>'` → IosDevice([U], false, true)
  - `xcodebuild test -destination 'platform=iOS Simulator,name=iPhone 16'` → IosDevice([], false, false)
  - `xcodebuild -list` → IosGlobalSafe
  - `xcodebuild build -scheme App` → IosGlobalSafe
  - `xcrun simctl install booted App.app` → IosDevice([], true, true)
  - `xcrun simctl boot <U>` → IosDevice([U], false, true)
  - `xcrun simctl list` → IosGlobalSafe
  - `xcrun simctl shutdown all` → IosGlobalDangerous
  - `xcrun simctl erase all` → IosGlobalDangerous
  - `xcrun simctl delete <U>` → IosGlobalDangerous
  - `killall Simulator` → IosGlobalDangerous
  - `xcrun devicectl device install app --device <U> x.app` → IosDevice([U], false, true)
  - `xcrun devicectl list devices` → IosGlobalSafe
  - `maestro --device $CDM_IOS_UDID test f.yaml` → pinnable
  - `maestro test f.yaml` → not pinnable
  - `idevicesyslog -u <U>` → IosDevice
  - `fastlane scan` → IosDevice, not pinnable
  - `./gradlew :shared:iosSimulatorArm64Test` → IosDevice, not pinnable
  - `adb install x.apk && xcrun simctl install booted A.app` → `platformsOf` == {ANDROID, IOS}
  - `rewriteBooted("xcrun simctl install booted A.app", U)` == `"xcrun simctl install $U A.app"`
  - `rewriteBooted("echo booted", U)` leaves the command unchanged.
- [ ] **Step 2: Run** `--tests '*IosCommandClassifier*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Reuse `segments`, `words` and the wrapper stripping. Add a `when` branch on `firstWord` for `xcodebuild`, `xcrun`, `maestro`, `fastlane`, `killall`, `idevice*`. Extend the Gradle branch with `ios\w*Test` and `\w*Simulator\w*Test` → IosDevice.
- [ ] **Step 4: Run** the full suite. Expected: PASS (Android classifier tests untouched).
- [ ] **Step 5: Commit** `feat(classify): recognise iOS device commands`

---

### Task 6: KMP Gradle iOS test pinning (spike → mechanism)

**Files:**
- Create: `src/jvmMain/resources/ios-pin.gradle` (if the init-script route works)
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/install/Installer.kt` (install/remove `~/.claude-device-manager/ios-pin.gradle`)
- Modify: `CommandClassifier.kt` (a Gradle iOS task with `--init-script …ios-pin.gradle` or the pin property counts as pinnable)
- Test: `InstallerTest` (file installed and removed), `IosCommandClassifierTest` (pinnable cases)

**Step 0 (spike):**
- Make a scratch KMP project in the scratchpad with an `iosSimulatorArm64` target and one test.
- Check whether an init script can set `KotlinNativeSimulatorTest.device` (or the current property name) from `System.getenv("CDM_IOS_UDID")`:
  ```groovy
  allprojects { tasks.withType(org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest).configureEach { if (System.getenv("CDM_IOS_UDID")) device.set(System.getenv("CDM_IOS_UDID")) } }
  ```
- Confirm with `xcrun simctl list devices booted` that the test ran on that UDID.
- Record the outcome in the PR description.

**Branches:**
- **The init script works.** The hook appends `--init-script ~/.claude-device-manager/ios-pin.gradle` to Gradle segments that contain an iOS test task. That rewrite belongs in Task 7. Here, add `fun CommandClassifier.addIosInitScript(command: String, path: String): String`, plus tests.
- **It doesn't work.** Gradle iOS test tasks stay `pinnable = false`. The deny message (Task 7) tells Claude which `-P` property to pass. Document the property in the CLAUDE.md text (Task 8).

- [ ] **Step 1:** Spike, then pick a branch. Write the failing tests for that branch.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat: pin KMP iOS simulator tests to the held simulator`

---

### Task 7: HookDecider — per-platform pinning and iOS rules

**Files:**
- Modify: `src/jvmMain/kotlin/com/programmersbox/cdm/service/HookDecider.kt`, `HookReply.kt`
- Modify: `DeviceManagerService.kt` (`preToolUse` records activity per platform from `platformsOf`)
- Test: `src/jvmTest/kotlin/com/programmersbox/cdm/service/HookDeciderIosTest.kt` (new); `HookDeciderTest` stays green

**Interfaces:**
- Consumes: Task 2 allocator API, Task 5 classifier, Task 6 `addIosInitScript` if that branch was taken.
- Produces: `fun pinPrefix(androidSerial: String?, iosUdid: String?, iosPairUdid: String?): String`. It emits `export ANDROID_SERIAL=…; ` and/or `export CDM_IOS_UDID=…; ` and `export CDM_IOS_PAIR_UDID=…; `. Keep the old `pinPrefix(serial)` as a delegate.

Rules, in order, for the iOS part of a command. The Android part keeps today's logic, but with the Android hold:

1. Any `IosGlobalDangerous` while another holder holds an iOS device → Deny. The message names the command.
2. Any UDID in `udids` that belongs to a device another holder holds → Deny, with a message in the style of `differentDevice`.
3. Any UDID that CDM doesn't track (and isn't the holder's own offline-held device) → Deny, unless the segment is `simctl boot <udid>`.
4. A segment with `pinnable = false` → Deny: "Pass the device explicitly: -destination id=$CDM_IOS_UDID / --device $CDM_IOS_UDID".
5. The holder has no iOS hold → fresh grab with `Wants(defaultCategory(cwd).members(), platform = IOS)`. On NoneFree or HeldByOther, Deny with `cdm acquire --category <c> --ios`.
6. Rewrite: `rewriteBooted` with the held UDID, then the pin prefix carrying every platform the command touches.

An Android segment from a holder with only an iOS hold does a fresh Android grab (`platform = ANDROID`).

- [ ] **Step 1: Write failing tests** in `HookDeciderIosTest` (fake allocator setup like `HookDeciderTest`):
  - `iosCommandGetsUdidExport`
  - `bootedRewrittenToHeldUdid`
  - `bootedWithoutIosHoldGrabsThenRewrites` (Review Focus 5)
  - `foreignUdidDenied`
  - `untrackedUdidDeniedExceptSimctlBoot`
  - `unpinnableXcodebuildDenied`
  - `dangerousDeniedWhileOthersHoldIos`
  - `dangerousAllowedWhenNoOtherIosHolds`
  - `mixedCommandExportsBoth` (Review Focus 2)
  - `androidCommandWithOnlyIosHoldGrabsAndroid`
  - `offlineHeldSimulatorUdidIsStillOwn` (Review Focus 3)
  - `pairExportsPairUdid`
  - `noIosDevicesNoXcodeDeniesWithAcquireHint`
- [ ] **Step 2: Run** `--tests '*HookDecider*'`. Expected: new tests FAIL, old ones PASS.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(hooks): pin and guard iOS commands per platform`

---

### Task 8: Service, server, `cdm` script, CLAUDE.md

**Files:**
- Modify: `service/DeviceManager.kt`:
  - `acquire(holder, cwd, wants: Wants, timeout, onPosition)` and `release(holder, platform: Platform? = null)`;
  - `AcquireResult.Granted(serial, name, platform: Platform, pairSerial: String? = null)`.
- Modify: `service/DeviceManagerService.kt`. Ruling W applies per platform: release only the hold of `wants.platform`, or of the platform it doesn't fit.
- Modify: `server/CdmServer.kt`:
  - `/cdm/acquire` reads `platform=ios|android` and `virtual=true|false`;
  - the GRANTED line becomes `GRANTED <platform> <serial> <name>`;
  - `/cdm/release` reads `platform`;
  - `/cdm/status` prints one line per hold: `HOLDING <platform> <serial> <name>`.
- Modify: `src/jvmMain/resources/cdm`:
  - parse `--ios`, `--android`, `--real` and `--virtual`;
  - usage includes `tablet`;
  - `say_grant` prints `Got <name> (<serial>). Your adb and Gradle commands are now pinned to it.` for Android, or `Got <name> (<udid>). Your iOS commands get CDM_IOS_UDID=<udid>; pass it as -destination id=$CDM_IOS_UDID, simctl … $CDM_IOS_UDID or maestro --device $CDM_IOS_UDID.` for iOS;
  - `release --ios|--android`.
- Modify: `install/Installer.kt`. Replace the CLAUDE.md block text (line 226) to cover iOS tools, explicit `$CDM_IOS_UDID`, the new flags and `tablet`, plus the Task 6 property if that branch was taken.
- Test: `CdmServerTest`, `CdmScriptTest`, `DeviceManagerServiceTest`, `InstallerTest`, `FakeManager.kt`

- [ ] **Step 1: Write failing tests:**
  - `acquireIosFlagQueuesWithPlatform`
  - `acquireRealFlagSetsVirtualFalse`
  - `grantedLineCarriesPlatform`
  - `releaseIosKeepsAndroid`
  - `statusListsBothHolds`
  - `cdmScriptParsesNewFlags` (CdmScriptTest drives the script against a stub server, as the existing tests do)
  - `cdmScriptIosGrantMessage`
  - `claudeMdMentionsCdmIosUdid`
  - `rulingWPerPlatform`: holding an iPhone, `acquire --category watch --ios` releases the iPhone; `acquire --android` does not.
- [ ] **Step 2: Run** `--tests '*CdmServer*' --tests '*CdmScript*' --tests '*DeviceManagerService*' --tests '*Installer*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(cdm): platform and real/virtual flags, iOS grant output`

---

### Task 9: UI per mockup

**Files:**
- Modify: `ui/UiModels.kt`:
  - `DeviceRow` gains `platform`, `virtual`, `osVersion`, `pairedWith: String?`;
  - `QueueRow.wants: Wants`;
  - `WaitReason` text gains the new limits.
- Modify: `ui/components/Cards.kt`:
  - `DeviceCard` shows a `VirtualChip(platform)` after the category chip when `virtual`. It has a dashed border and a monitor icon, `emulator` for ANDROID and `simulator` for IOS, and fades in;
  - the second line appends ` · paired with <name>`;
  - `QueueCard` shows a platform chip, `real only` (Neutral) and a dashed `virtual only`.
- Modify: `ui/components/Chips.kt` (`VirtualChip`, `PlatformChip`)
- Modify: `ui/devices/DevicesScreen.kt`:
  - group rows under `SectionLabel("Android")` / `SectionLabel("iOS")` headers;
  - a header shows only when its group is non-empty;
  - headers are `animateItem()` items keyed `"hdr-android"` / `"hdr-ios"`.
- Modify: `ui/devices/DeviceDetailParts.kt` / `DeviceDetailScreen.kt`:
  - Platform, Kind (`Real` / `Emulator` / `Simulator`), Runtime and `UDID`/`Serial` rows;
  - category choices phone / tablet / watch.
- Modify: `ui/sessions/SessionsViewModel.kt` + screen (a holder row lists up to two devices from `snapshot.holdings`)
- Modify: `ui/queue/*` for the wait reasons
- Test: `UiModelsTest`, `DeviceDetailViewModelTest`, `SessionsViewModelTest`, `QueueViewModelTest`

- [ ] **Step 1: Write failing tests:**
  - `deviceRowsCarryPlatformAndVirtual`
  - `rowsGroupedAndroidThenIos`
  - `pairedWithNameResolved`
  - `queueRowShowsLimits` (wants → chip labels `["phone","iOS","real only"]`)
  - `waitReasonNoVirtualWatch` (`"no Wear OS emulator running"` when wants = watch+ANDROID+virtual and none exist)
  - `sessionRowListsTwoDevices`
  - `detailKindLabel` (Real / Emulator / Simulator)
- [ ] **Step 2: Run** `--tests '*ViewModel*' --tests '*UiModels*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Match the mockup's layout and colours (Global Constraints).
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Manual look.**
  - Boot one iPhone and one iPad simulator (`xcrun simctl boot <udid>`). Start an Android emulator if one is available.
  - Run `CDM_START_ROUTE=devices ./gradlew :composeApp:run`.
  - Compare against the mockup, and take a screenshot for the PR.
- [ ] **Step 6: Commit** `feat(ui): platform groups, emulator/simulator chips, iOS detail`

---

### Task 10: End-to-end check on this Mac

**Files:** none. If this finds a bug, fix it in the owning task's files with a test.

Steps 2–4 use a real Claude Code session started after the app is running. Before Step 1, also run `/verify`.

- [ ] **Step 1:** Run the full suite and `./gradlew :composeApp:packageDistributionForCurrentOS`. Expected: PASS, `.app` built.
- [ ] **Step 2:** With the packaged app running and two booted simulators, run `cdm acquire --category phone --ios`. Expected: a grant naming the simulator, and the card shows the holder.
- [ ] **Step 3:** In the same session, run `xcrun simctl install booted <some .app>`. In the CDM log, the command appears with the UDID substituted. A second session running `xcrun simctl boot <first UDID>` is denied.
- [ ] **Step 4:** In the same session, run `adb devices` (with an Android device or emulator attached). The session now holds two devices, and the Sessions screen shows both.
- [ ] **Step 5:** Record the results in the PR body. List anything untested: real iPhone, real watch pair, KMP init script if no project was available.
