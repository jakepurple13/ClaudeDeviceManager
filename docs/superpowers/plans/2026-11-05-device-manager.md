# Claude Device Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Compose Multiplatform desktop app that hands out connected Android devices to Claude Code sessions and subagents ("holders"), through HTTP hooks and a `cdm` CLI, with a UI for Jacob to watch and control it.

**Architecture:** One JVM app. The pure-Kotlin core holds two pieces. `CommandClassifier` decides what a Bash command is. `Allocator` is a state machine for holds, the queue, timers, and blocks, with an injected clock. `DeviceManagerService` owns the allocator behind a mutex, feeds it from `DeviceTracker` (`adb`), logs its events to Room, and exposes `StateFlow`s. A Ktor server turns hook and `cdm` requests into service calls. The Compose UI uses a Navigation 3 rail, list-detail scenes, and Koin-injected ViewModels that read the service flows.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2 (JVM target only), Navigation 3 (`org.jetbrains.androidx.navigation3:navigation3-ui:1.2.0`, `org.jetbrains.compose.material3.adaptive:adaptive-navigation3:1.3.0`, `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0`), Room 3 (`androidx.room3:room3-runtime` / `room3-compiler` 3.0.3, `androidx.sqlite:sqlite-bundled:2.7.1`), KSP 2.3.13, Koin 4.2.2 (`koin-core`, `koin-compose-viewmodel`, `koin-compose-viewmodel-navigation`), Ktor server CIO 3.6.1, kotlinx-coroutines 1.11.0 (+ `-swing`), kotlinx-serialization-json 1.12.0, `sh.calvin.reorderable:reorderable:3.1.0`, Gradle 9.8.2.

**Spec:** `docs/superpowers/specs/2026-11-05-device-manager-design.md`. Read it first. Every rule below argues from it.

## Global Constraints

- Desktop/JVM target only. macOS is the target platform.
- Navigation 3 for all navigation. Devices and Queue use a list-detail scene.
- Every screen has a ViewModel, provided by Koin. Room 3 for persistence. Koin for all wiring; no hand-built object graphs outside `di/`.
- The HTTP server binds `127.0.0.1` only. The default port is `47823`, and the app writes the port to `~/.claude-device-manager/port`.
- Hooks are `type: "http"`. Never set `onFailure: "block"`. `PreToolUse` timeout is 2 s.
- Every rewrite carries a `permissionDecision` of `allow` or `ask`, computed by `PermissionMirror` (Task 6b) against the **original** command. Never `allow` when the mirror says otherwise. (Jacob's decision after Task 0; the spec's "Permission mirroring" section has the details.)
- Default timers: idle 15 min, editing 5 min. A queue spot is kept for 2 min after `cdm acquire` stops waiting. `cdm acquire` waits up to 570 s. `cdm` retries an unreachable app for 30 s.
- Holder key: `"<session_id>:<agent_id>"`, where `agent_id` defaults to `"main"`.
- The pin prefix comes from one function, `pinPrefix(serial)`, and its value is `export ANDROID_SERIAL=<serial>; `. Task 0 confirmed this form executes.
- Package root: `com.programmersbox.cdm`. Gradle module: `composeApp`. Source: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/`. Tests: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/`. (Below, `…/` means those roots.)
- Animation is required (spec, "Animation matters to Jacob"). Durations are 150–300 ms, `tween`/`spring` from Compose animation APIs. Every `LazyColumn`/`LazyRow` item uses `Modifier.animateItem()`. Conditional UI uses `AnimatedVisibility`. Swapped content uses `AnimatedContent`/`Crossfade`. Colors use `animateColorAsState`. Numbers and badges use `AnimatedContent` with a vertical slide. Animations never block input.
- If a pinned version fails to resolve or doesn't work with the others, use the nearest compatible version and note it in the commit message.

## Review Focus

1. **Text that merely contains "adb" or "gradle"** (`grep adb notes.txt`, `cat ~/adbkeys`, `echo gradlew`) must be `Other`, not a device command. Owned by Task 2.
2. **Compound and piped commands** (`cd app && ./gradlew installDebug 2>&1 | tail -20`) must classify as a device command, and the pin goes in front of the whole command. Owned by Task 2 and Task 6.
3. **A held device's `adb` serial changing** (Wi-Fi port changes on reconnect, or USB unplugged with Wi-Fi still up) must not break the hold. The next pin uses a currently connected serial. Owned by Task 5.
4. **Malformed or unexpected hook payloads** (empty body, missing `tool_input.command`, unknown `hook_event_name`) must get HTTP 200 with `{}`, never a 4xx/5xx and never a deny. Owned by Task 7.
5. **An existing `~/.claude/settings.json`** that has other hooks, or doesn't parse, must never be damaged. Merge into it, back it up first, and refuse to write if it doesn't parse. Owned by Task 8.

---

### Task 0: Spike — confirm hook behavior with real Claude Code

Throwaway. No product code. This task produces a findings note that Task 6 and Task 8 depend on.

**Files:**
- Create: `spikes/hook-echo.py` (logs each POST body to `spikes/hook-log.jsonl`; replies according to `SPIKE_MODE`)
- Create: `docs/superpowers/spikes/2026-11-05-hook-findings.md`

- [ ] **Step 1:** Write `spikes/hook-echo.py`: a `http.server` on `127.0.0.1:47899` that appends each request body to `hook-log.jsonl`. When `SPIKE_MODE=rewrite` and `tool_input.command` starts with `adb`, it replies `{"hookSpecificOutput":{"hookEventName":"PreToolUse","updatedInput":{...original tool_input, "command":"export ANDROID_SERIAL=FAKE; "+cmd}}}`. Otherwise it replies `{}`.
- [ ] **Step 2:** In a temp dir, write `.claude/settings.local.json` with `http` hooks for `PreToolUse` (matcher `Bash`), `SubagentStart`, `SubagentStop`, and `SessionEnd`, all pointing at the echo server, plus `"permissions":{"allow":["Bash(adb version)","Bash(adb devices:*)"]}`.
- [ ] **Step 3:** Run `claude -p "Run 'adb version'. Then use the Agent tool to spawn a general-purpose subagent that runs 'adb devices'." --settings .claude/settings.local.json` with `SPIKE_MODE=off`. Expected: the log shows `session_id` on every event, and `agent_id`/`agent_type` on the subagent's `PreToolUse`. Record whether the subagent's `session_id` equals the parent's.
- [ ] **Step 4:** Repeat with `SPIKE_MODE=rewrite` in default permission mode. Record whether the rewritten `adb version` ran without a prompt or denial, which means the allow rule still matched. If it was blocked, change the prefix to `ANDROID_SERIAL=FAKE ` (no `export`, no `;`) and retry. Record which prefix form, if any, keeps allow rules matching.
- [ ] **Step 5:** Repeat Step 3 with a fork (`Agent` with `subagent_type: "fork"`). Record whether its hooks carry an `agent_id`.
- [ ] **Step 6:** Write the findings note: subagent `session_id` sharing, `agent_id` on subagents and forks, the working prefix form, and the exact `SubagentStop`/`SessionEnd` payloads.
- [ ] **Step 7: Gate.** If no prefix form keeps allow rules matching, or subagents carry no `agent_id`, **stop and report to Jacob** with the findings. Do not continue to Task 6. Otherwise commit:

```bash
git add spikes docs/superpowers/spikes
git commit -m "spike: confirm Claude Code hook payloads and rewrite permission behavior"
```

---

### Task 1: Project scaffold with Koin and an empty window

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle wrapper (9.8.2)
- Create: `composeApp/build.gradle.kts` (plugins: kotlin multiplatform, compose multiplatform, compose compiler, kotlin serialization, KSP, Room 3; `kotlin { jvm() }`; `compose.desktop.application { mainClass = "com.jacobrein.cdm.MainKt" }`; KSP `add("kspJvm", room3-compiler)`; Room schema dir `composeApp/schemas`)
- Create: `…/Main.kt`, `…/di/AppModules.kt`
- Test: `…/di/KoinGraphTest.kt`

**Interfaces:**
- Produces: `fun appModules(): List<Module>` in `di/AppModules.kt`. Every later task adds its definitions there. `main()` calls `startKoin { modules(appModules()) }` and opens a `Window(title = "Claude Device Manager")`.

- [ ] **Step 1: Write the failing test** `KoinGraphTest.verifyGraph`: `appModules().forEach { it.verify() }` (Koin `verify()` API).
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest`. Expected: FAIL (does not compile).
- [ ] **Step 3:** Create the scaffold files and empty `appModules()`.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS. Run `./gradlew :composeApp:run`. Expected: an empty window opens. Close it.
- [ ] **Step 5: Commit** `chore: scaffold compose desktop app with koin`.

---

### Task 2: Domain models and `CommandClassifier`

**Files:**
- Create: `…/core/model/Models.kt`
- Create: `…/core/classify/CommandClassifier.kt`
- Test: `…/core/classify/CommandClassifierTest.kt`

**Interfaces — Produces:**

```kotlin
@JvmInline value class Category(val id: String) { companion object { val PHONE = Category("phone"); val WATCH = Category("watch"); val ANY = Category("any") } }
data class HolderId(val sessionId: String, val agentId: String = MAIN) {
    val key get() = "$sessionId:$agentId"
    companion object { const val MAIN = "main"; fun parse(key: String): HolderId }
}
@JvmInline value class DeviceKey(val hardwareSerial: String)   // ro.serialno

sealed interface CommandKind {
    data object Other : CommandKind
    data class Cdm(val subcommand: String) : CommandKind
    data class AdbGlobalSafe(val resetsTimer: Boolean) : CommandKind      // devices|version|help → false; connect|reconnect → true; pair → false
    data class AdbGlobalDangerous(val what: String) : CommandKind         // kill-server|start-server|disconnect (no address)
    data class AdbDisconnect(val address: String) : CommandKind           // service decides if it's the holder's own device
    data class Device(val explicitSerial: String?, val usbFlag: Boolean, val emulatorFlag: Boolean) : CommandKind
    data object GradleNonDevice : CommandKind
}
object CommandClassifier {
    fun classify(command: String): CommandKind
    fun addCdmIdentity(command: String, holder: HolderId, cwd: String): String
}
```

Approach: split the command into segments on `&&`, `||`, `;`, `|`, and newlines. Drop leading `VAR=value` assignments and `sudo`/`time`/`env`. A segment is adb only if its first word's basename is `adb`, Gradle only if it's `gradle`/`gradlew`, and cdm only if it's `cdm`. Gradle device tasks match `^(:?[\w-]+:)*(install|uninstall|connected)\w*$`. Across segments, the strongest kind wins: `AdbGlobalDangerous` > `AdbDisconnect` > `Device` > `GradleNonDevice` > `AdbGlobalSafe` > `Cdm` > `Other`. `addCdmIdentity` inserts ` --holder <key> --cwd '<cwd>'` after `cdm <subcommand>` in each cdm segment.

- [ ] **Step 1: Write the failing table test** `classifiesCommands` (expected kind per input):
  - `"ls -la"` → `Other`; `"grep adb notes.txt"` → `Other`; `"cat ~/adbkeys"` → `Other`; `"echo ./gradlew"` → `Other`
  - `"adb devices -l"` → `AdbGlobalSafe(false)`; `"adb connect 10.0.0.5:5555"` → `AdbGlobalSafe(true)`
  - `"adb kill-server"` → `AdbGlobalDangerous("kill-server")`; `"adb disconnect"` → `AdbGlobalDangerous("disconnect")`; `"adb disconnect 10.0.0.5:5555"` → `AdbDisconnect("10.0.0.5:5555")`
  - `"adb shell input tap 1 2"` → `Device(null,false,false)`; `"adb -s ABC123 install a.apk"` → `Device("ABC123",false,false)`; `"adb -d logcat"` → `Device(null,true,false)`
  - `"/opt/homebrew/bin/adb shell ls"` → `Device(null,false,false)`
  - `"./gradlew :app:installDebug"` → `Device(null,false,false)`; `"./gradlew connectedAndroidTest"` → `Device(...)`; `"./gradlew assembleDebug test"` → `GradleNonDevice`
  - `"cd app && ./gradlew installDebug 2>&1 | tail -20"` → `Device(null,false,false)`
  - `"ANDROID_HOME=/x adb shell ls"` → `Device(null,false,false)`
  - `"./gradlew assembleDebug && adb kill-server"` → `AdbGlobalDangerous("kill-server")`
  - `"cdm acquire --category watch"` → `Cdm("acquire")`
- [ ] **Step 2: Write the failing test** `addsCdmIdentity`: `addCdmIdentity("cdm acquire --category watch", HolderId("s1","a9"), "/x/y")` == `"cdm acquire --holder s1:a9 --cwd '/x/y' --category watch"`.
- [ ] **Step 3:** Run `./gradlew :composeApp:jvmTest --tests '*CommandClassifierTest*'`. Expected: FAIL.
- [ ] **Step 4:** Implement both files.
- [ ] **Step 5:** Run the same command. Expected: PASS.
- [ ] **Step 6: Commit** `feat: add domain models and bash command classifier`.

---

### Task 3: `Allocator` — holds, queue, blocks, Jacob's actions

**Files:**
- Create: `…/core/allocator/Allocator.kt`, `…/core/allocator/AllocatorTypes.kt`
- Test: `…/core/allocator/AllocatorQueueTest.kt`

**Interfaces — Produces** (not thread-safe; Task 7's service wraps it in a `Mutex`):

```kotlin
data class AllocDevice(val key: DeviceKey, val name: String, val category: Category, val dontUse: Boolean, val connectedSerials: List<String>)
enum class ReleaseReason { IDLE_TIMEOUT, EDIT_TIMEOUT, SELF_RELEASE, USER_RELEASE, USER_HANDOFF, DONT_USE, SUBAGENT_STOP, SESSION_END }
sealed interface HolderStatus {
    data object None : HolderStatus
    data class Holding(val device: DeviceKey, val since: Instant, val lastActivity: Instant, val editing: Boolean) : HolderStatus
    data class Queued(val position: Int, val wants: Set<Category>) : HolderStatus      // 1-based
    data class Released(val reason: ReleaseReason, val lastDevice: DeviceKey) : HolderStatus
    data object Blocked : HolderStatus
}
sealed interface GrabResult {
    data class Granted(val device: AllocDevice, val serial: String) : GrabResult
    data class HeldByOther(val device: AllocDevice, val holder: HolderId) : GrabResult
    data object NoneFree : GrabResult
    data object Blocked : GrabResult
    data object DontUse : GrabResult
}
sealed interface AllocatorEvent { val at: Instant
    data class Granted(...holder, device); data class Released(...holder, device, reason); data class Queued(...holder, wants); data class LeftQueue(...holder); data class Blocked(...holder); data class Unblocked(...holder) }
data class AllocatorSnapshot(val devices: List<AllocDevice>, val holds: Map<DeviceKey, HolderId>, val queue: List<HolderId>, val statuses: Map<HolderId, HolderStatus>)

class Allocator(private val clock: () -> Instant, blocked: Set<HolderId>, private val onEvent: (AllocatorEvent) -> Unit) {
    fun snapshot(): AllocatorSnapshot
    fun statusOf(holder: HolderId): HolderStatus
    fun setDevices(devices: List<AllocDevice>)          // devices missing from the list stay held (offline · held)
    fun tryGrab(holder: HolderId, wants: Set<Category>, specific: DeviceKey? = null): GrabResult
    fun enqueue(holder: HolderId, wants: Set<Category>): Int    // idempotent; returns 1-based position
    fun release(holder: HolderId, reason: ReleaseReason)
    fun releaseDevice(device: DeviceKey, block: Boolean)        // Jacob: USER_RELEASE (+ block holder)
    fun handOff(device: DeviceKey, to: HolderId)                // Jacob: USER_HANDOFF to old holder; to leaves queue, gets device
    fun setDontUse(device: DeviceKey, dontUse: Boolean, releaseNow: Boolean)
    fun moveInQueue(holder: HolderId, newIndex: Int)
    fun removeFromQueue(holder: HolderId)
    fun unblock(holder: HolderId)
    fun endHolder(holder: HolderId, reason: ReleaseReason)      // SUBAGENT_STOP; also removes from queue
    fun endSession(sessionId: String)                           // all holders in session, SESSION_END
    fun serialFor(holder: HolderId): String?                    // a currently connected serial of the held device
}
```

Rules from the spec. `ANY` in `wants` matches every category. When a device frees up, the queue is scanned in order and the first holder whose wants fit gets it; holders that don't fit are skipped. Holders must be `Queued`, not just waiting, to be granted. A grant to a queued holder removes it from the queue. `tryGrab` on a holder that already holds returns `Granted` with its current device. `dontUse` devices are never granted. With `releaseNow = false`, the device stays with its holder until that holder releases it.

- [ ] **Step 1: Write failing tests** in `AllocatorQueueTest`, using a fake clock and two devices `P` (phone) and `W` (watch):
  - `grantsFreeDeviceOfWantedCategory`: `tryGrab(h1, {WATCH})` is `Granted(W)`; `tryGrab(h2, {WATCH})` is `HeldByOther(W, h1)` when it names W, else `NoneFree`.
  - `anyMatchesAllCategories`: `tryGrab(h1, {ANY})` is `Granted`.
  - `grabIsIdempotentForHolder`: a second `tryGrab(h1, …)` returns the same device; there's one hold in the snapshot.
  - `releasePassesToFirstFittingQueued`: h1 holds W; queue = [h2 wants PHONE (P held by h3), h4 wants WATCH]; `release(h1, SELF_RELEASE)` grants W to h4; h2 stays queued at position 1.
  - `enqueueIsIdempotent`: `enqueue` twice returns the same position; queue size 1.
  - `blockedHolderCannotGrab`: `releaseDevice(W, block = true)` sets h1 to `Blocked`; `tryGrab(h1, …)` is `GrabResult.Blocked`; after `unblock(h1)`, `tryGrab` succeeds.
  - `dontUseNeverGranted`: `setDontUse(W, true, releaseNow = false)` while h1 holds → h1 keeps W; after h1 releases, W goes to nobody and the queued watch holder stays queued.
  - `dontUseReleaseNow`: h1's status is `Released(DONT_USE, W)`.
  - `handOffGivesDeviceToChosenHolder`: h1 holds W, h2 queued → `handOff(W, h2)` → h2 `Holding(W)`, h1 `Released(USER_HANDOFF, W)`, queue empty.
  - `moveInQueueReorders`: [h2, h3, h4] → `moveInQueue(h4, 0)` → [h4, h2, h3].
  - `endSessionReleasesAllAgents`: holders `s1:main` and `s1:a1` each hold a device → `endSession("s1")` frees both.
  - `eventsEmitted`: grant, release, queue, and block each emit the matching `AllocatorEvent`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*AllocatorQueueTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `Allocator` and its types.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add device allocator with queue, blocks, and user actions`.

---

### Task 4: `Allocator` — timers, editing, re-grab after timeout

**Files:**
- Modify: `…/core/allocator/Allocator.kt`
- Test: `…/core/allocator/AllocatorTimerTest.kt`

**Interfaces — Produces** (added to `Allocator`):

```kotlin
data class Timers(val idle: Duration = 15.minutes, val editing: Duration = 5.minutes, val queueGrace: Duration = 2.minutes)
// constructor gains: private val timers: () -> Timers
fun recordDeviceActivity(holder: HolderId)   // resets lastActivity, editing = false
fun recordEdit(holder: HolderId)             // editing = true (lastActivity unchanged)
fun attachWaiter(holder: HolderId); fun detachWaiter(holder: HolderId)   // cdm acquire long-poll open/closed
fun tick()                                   // apply idle/editing timeouts and queue grace
fun tryRegrab(holder: HolderId): GrabResult  // only valid when status is Released(IDLE_TIMEOUT|EDIT_TIMEOUT, d)
```

`tick()` behavior. A holder whose `now - lastActivity` exceeds `editing` (when `editing = true`) or `idle` (otherwise) is released with `EDIT_TIMEOUT` or `IDLE_TIMEOUT`, and its device passes to the queue. A queued holder that has been detached longer than `queueGrace` leaves the queue. `tryRegrab` returns `Granted` if the last device is free and not don't-use, else `HeldByOther`/`DontUse`.

- [ ] **Step 1: Write failing tests:**
  - `idleTimeoutReleasesAfter15Min`: activity at t0, `tick()` at t0+14m59s → still holding; at t0+15m01s → `Released(IDLE_TIMEOUT, W)`.
  - `editShortensTimer`: activity t0, `recordEdit` at t0+1m, `tick()` at t0+5m01s → `Released(EDIT_TIMEOUT, W)`.
  - `deviceActivityClearsEditing`: `recordEdit`, then `recordDeviceActivity` at t0+4m → `tick()` at t0+8m still holding.
  - `offlineDeviceStaysHeld`: `setDevices([P])` (W gone) while h1 holds W → h1 still `Holding(W)`; `serialFor(h1)` is null.
  - `regrabWhenFree`: after `IDLE_TIMEOUT`, `tryRegrab(h1)` is `Granted(W)`.
  - `regrabFailsWhenTaken`: after timeout W went to queued h2 → `tryRegrab(h1)` is `HeldByOther(W, h2)`.
  - `queueGraceExpires`: h2 queued, `detachWaiter(h2)` at t0, `tick()` at t0+1m59s → still queued; at t0+2m01s → not in queue. `attachWaiter` before expiry keeps it.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*AllocatorTimerTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest --tests '*Allocator*'`. Expected: PASS, with Task 3's tests still green.
- [ ] **Step 5: Commit** `feat: add idle/editing timers and re-grab to allocator`.

---

### Task 5: Room 3 database, repositories, and device tracking

**Files:**
- Create: `…/data/AppDatabase.kt` (entities `DeviceConfigEntity`, `ProjectEntity`, `SettingEntity`, `ActivityEntity`, `BlockedHolderEntity` and their DAOs; built with `BundledSQLiteDriver` at `~/.claude-device-manager/cdm.db`)
- Create: `…/data/Repositories.kt`
- Create: `…/devices/AdbClient.kt`, `…/devices/DeviceTracker.kt`
- Test: `…/data/RepositoriesTest.kt`, `…/devices/DeviceTrackerTest.kt`

**Interfaces — Produces:**

```kotlin
data class DeviceConfig(val key: DeviceKey, val displayName: String?, val categoryOverride: Category?, val dontUse: Boolean, val lastSeen: Instant)
class DeviceConfigRepository(dao) { val all: Flow<List<DeviceConfig>>; suspend fun upsertSeen(key: DeviceKey, now: Instant); suspend fun setCategory(key, Category?); suspend fun setDontUse(key, Boolean); suspend fun rename(key, String?) }
class ProjectRepository(dao) { val all: Flow<List<Project>>; suspend fun defaultCategoryFor(cwd: String): Category  /* creates row with ANY on first sight */; suspend fun setDefault(path, Category) }
class SettingsRepository(dao) { val settings: Flow<AppSettings>; suspend fun current(): AppSettings; suspend fun update(transform: (AppSettings) -> AppSettings) }
data class AppSettings(val port: Int = 47823, val idleMinutes: Int = 15, val editingMinutes: Int = 5)
class ActivityRepository(dao) { fun recent(limit: Int = 200): Flow<List<ActivityEntry>>; suspend fun record(e: AllocatorEvent) ; suspend fun recordCommand(holder: HolderId, device: DeviceKey, command: String, at: Instant) }
class BlockedRepository(dao) { suspend fun all(): Set<HolderId>; suspend fun add(h); suspend fun remove(h) }

interface AdbClient { suspend fun devicesLong(): String; suspend fun shell(serial: String, vararg args: String): String }   // ProcessAdbClient runs `adb`
data class TrackedDevice(val key: DeviceKey, val serials: List<String>, val model: String, val androidVersion: String, val transport: String, val detectedCategory: Category)
class DeviceTracker(adb: AdbClient) { suspend fun scan(): List<TrackedDevice> }
```

`DeviceTracker.scan` parses `adb devices -l` rows in state `device` (it skips `unauthorized`/`offline`). For each serial it reads `getprop ro.serialno`, `ro.product.model`, and `ro.build.version.release`, plus `pm has-feature android.hardware.type.watch`. It groups by `ro.serialno` and lists USB serials before Wi-Fi ones (Wi-Fi: serial contains `:` or `_adb-tls-connect`). It caches per-serial props so unchanged devices don't re-run shell commands.

- [ ] **Step 1: Write failing tests** (`RepositoriesTest` uses an in-memory Room 3 DB built with `BundledSQLiteDriver`):
  - `projectCreatedWithAnyOnFirstSight`: `defaultCategoryFor("/x/app")` is `ANY`, and `all` now has the row.
  - `deviceOverridePersists`: `setCategory(k, WATCH)` → `all` shows the override.
  - `settingsDefaults`: `current()` == `AppSettings(47823, 15, 5)`.
  - `DeviceTrackerTest.groupsUsbAndWifiByHardwareSerial`: a fake `AdbClient` returns two serials (`38261FDJH00A1Z` and `192.168.1.20:41235`) that both report `ro.serialno=38261FDJH00A1Z` → one `TrackedDevice` with `serials == ["38261FDJH00A1Z", "192.168.1.20:41235"]`.
  - `detectsWatchFeature`: `has-feature` output `true` → `WATCH`, `false` → `PHONE`.
  - `skipsUnauthorized`: a row `XYZ unauthorized` is ignored.
  - **Review Focus 3** `wifiSerialChangeKeepsIdentity`: scan 1 sees `192.168.1.20:41235`, scan 2 sees `192.168.1.20:39001`, same `ro.serialno` → the same `DeviceKey` with updated `serials`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*RepositoriesTest*' --tests '*DeviceTrackerTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Register the DB, DAOs, repositories, `ProcessAdbClient`, and `DeviceTracker` as `single` in `appModules()`.
- [ ] **Step 4:** Run the same command, plus `KoinGraphTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add room persistence and adb device tracking`.

---

### Task 6: `DeviceManagerService` and hook decisions

**Files:**
- Create: `…/service/DeviceManagerService.kt`
- Create: `…/service/HookDecider.kt`
- Test: `…/service/HookDeciderTest.kt`

**Interfaces:**
- Consumes: `CommandClassifier`, `Allocator` (Tasks 3–4), the repositories and `DeviceTracker` (Task 5), and Task 0's prefix form.
- Produces:

```kotlin
fun pinPrefix(serial: String): String            // value per Task 0 findings
sealed interface HookReply { data object NoOpinion : HookReply; data class Rewrite(val command: String, val original: String, val kind: RewriteKind) : HookReply; data class Deny(val reason: String) : HookReply }
enum class RewriteKind { PIN, CDM }   // Task 6b's PermissionMirror turns (original, kind) into allow/ask; tests below compare only `command`
class HookDecider(private val allocator: Allocator, private val defaultCategory: suspend (cwd: String) -> Category) {
    suspend fun onBash(holder: HolderId, cwd: String, command: String): HookReply
    fun onEdit(holder: HolderId): HookReply      // recordEdit, always NoOpinion
}
class DeviceManagerService(...) {               // single in Koin; owns Allocator behind a Mutex
    val snapshot: StateFlow<AllocatorSnapshot>
    val tracked: StateFlow<List<TrackedDevice>>
    val holderInfo: StateFlow<Map<HolderId, HolderInfo>>   // updated on every hook/cdm call; ended holders kept 1 h
    suspend fun preToolUse(input: HookInput): HookReply
    suspend fun subagentStop(sessionId: String, agentId: String)
    suspend fun sessionEnd(sessionId: String)
    suspend fun acquire(holder: HolderId, cwd: String, wants: Set<Category>, timeout: Duration, onPosition: suspend (Int) -> Unit): AcquireResult
    suspend fun release(holder: HolderId)
    suspend fun status(holder: HolderId): HolderStatus
    // UI actions:
    suspend fun releaseDevice(key: DeviceKey, block: Boolean); suspend fun handOff(key: DeviceKey, to: HolderId); suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean)
    suspend fun setCategory(key: DeviceKey, category: Category?); suspend fun moveInQueue(h: HolderId, index: Int); suspend fun removeFromQueue(h: HolderId); suspend fun unblock(h: HolderId)
    fun start(scope: CoroutineScope)   // scan devices every 2 s → allocator.setDevices; tick every 5 s; persist events; load blocked set
}
data class HookInput(val hookEventName: String, val sessionId: String, val agentId: String?, val agentType: String?, val cwd: String, val toolName: String?, val command: String?)
data class HolderInfo(val holder: HolderId, val cwd: String, val agentType: String?, val lastDeviceCommand: String?, val lastSeen: Instant, val ended: Boolean)
sealed interface AcquireResult { data class Granted(val serial: String, val name: String); data class TimedOut(val position: Int); data object Blocked }
```

The `onBash` decision table follows the spec's "Bash command classification", rules 1 through 6, in order. `HookDecider` also tracks the holder's *agent type* only for display; that's passed through the service. `DeviceManagerService.preToolUse` routes `Bash` to `onBash` and edit tools (`Edit`, `Write`, `MultiEdit`, `NotebookEdit`) to `onEdit`, and records device commands to `ActivityRepository.recordCommand`.

- [ ] **Step 1: Write failing tests** in `HookDeciderTest` (real `Allocator`, fake clock, devices P and W, `defaultCategory = { WATCH }`):
  - `otherCommandFromNonHolderIsNoOpinion`: `"ls"` → `NoOpinion`.
  - `cdmGetsIdentity`: `"cdm release"` → `Rewrite("cdm release --holder s1:main --cwd '/w'")`.
  - `deviceCommandGrabsByProjectCategory`: `"adb shell ls"` → `Rewrite(pinPrefix(W.serial) + "adb shell ls")`, and the holder holds W.
  - `holderPinsEveryCommand`: after holding, `"./run_tests.sh"` → `Rewrite(pinPrefix(W.serial) + "./run_tests.sh")`.
  - **Review Focus 2** `compoundCommandPinnedWhole`: `"cd app && ./gradlew installDebug | tail"` → `Rewrite(pinPrefix(…) + "cd app && ./gradlew installDebug | tail")`.
  - `noneFreeDenies`: W held by h2 → `Deny` whose reason contains `` `cdm acquire --category watch` ``.
  - `explicitSerialHeldByOtherDenies`: `"adb -s <W serial> shell ls"` while h2 holds W → `Deny` naming W's display name.
  - `explicitOtherTransportSerialAccepted`: holder holds W; `-s` gives W's Wi-Fi serial → `Rewrite`.
  - `explicitWrongSerialWhileHoldingDenies`: holder holds W; `-s <P serial>` → `Deny` containing `"you hold"`.
  - `dangerousDenied`: `"adb kill-server"` → `Deny` containing `"Ask the user"`.
  - `regrabAfterIdleTimeout`: holder timed out, W free → `"adb shell ls"` → `Rewrite`, holding W.
  - `noRegrabWhenTaken`: timed out, W now with h2 → `Deny` containing `"released after 15 min idle"` and `cdm acquire`.
  - `userReleasedDenies`: after `releaseDevice(W, block = false)` → `Deny` containing `"released from Device Manager"`; with `block = true` the reason also contains `"blocked"`.
  - `gradleNonDeviceResetsTimer`: holder holds W; `"./gradlew assembleDebug"` at t0+14m → `tick()` at t0+20m still holding.
  - `editIsNoOpinionAndMarksEditing`: `onEdit(h)` → `NoOpinion`; status `editing == true`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*HookDeciderTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `HookDecider` and `DeviceManagerService`. In `acquire`: call `tryGrab`; on `NoneFree`/`HeldByOther`, `enqueue` + `attachWaiter`, then wait on `snapshot` until the holder is `Holding` (→ `Granted`), `Blocked`, or the timeout passes (→ `TimedOut(position)`), calling `onPosition` on each change. `detachWaiter` goes in a `finally` block.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add hook decision logic and device manager service`.

---

### Task 6b: `PermissionMirror` — allow or ask for rewrites

**Files:**
- Create: `…/service/PermissionMirror.kt`, `…/service/BashRule.kt`
- Test: `…/service/PermissionMirrorTest.kt`, `…/service/BashRuleTest.kt`

**Interfaces:**
- Consumes: `HookReply.Rewrite`/`RewriteKind` (Task 6), and the segment splitting from `CommandClassifier` (Task 2; expose `CommandClassifier.segments(command): List<String>` if it isn't public yet).
- Produces:

```kotlin
enum class Decision { ALLOW, ASK }
data class PermissionRules(val allow: List<BashRule>, val ask: List<BashRule>, val deny: List<BashRule>)
sealed interface BashRule { fun matches(segment: String): Boolean
    companion object { fun parse(rule: String): BashRule? } }   // "Bash" → all; "Bash(x)" → exact; "Bash(x:*)" → prefix x; "Bash(x *)" / any "*" → glob; non-Bash rules → null
class PermissionMirror(private val home: Path, private val readOnlyBuiltins: Set<String> = DEFAULT_READ_ONLY) {
    fun rulesFor(cwd: String): PermissionRules           // merges ~/.claude/settings.json, <cwd>/.claude/settings.json, <cwd>/.claude/settings.local.json; unreadable/missing files contribute nothing; cached by file mtime
    fun decide(original: String, kind: RewriteKind, cwd: String, permissionMode: String?): Decision
}
```

The decision, in order:
1. Any segment matches deny → `ASK`.
2. `permissionMode == "bypassPermissions"` → `ALLOW`.
3. `kind == CDM` and every segment's first word is `cdm` → `ALLOW`.
4. Any segment matches an ask rule → `ASK`.
5. Every segment matches an allow rule or is a read-only built-in → `ALLOW`.
6. Otherwise → `ASK`.

A segment is read-only built-in when its first word is in `DEFAULT_READ_ONLY` = `cd, ls, cat, head, tail, pwd, echo, grep, rg, wc, which, file, stat, du, df, find, sort, uniq, tr, cut, date, env, printenv, true, false, test, [`. The exception is `find` with `-exec`, `-delete`, or `-ok`. Leading `VAR=value` assignments are stripped before matching, as Task 2 does.

`DeviceManagerService.preToolUse` now returns `HookReplyWithDecision`: for a `Rewrite`, it calls `mirror.decide(...)`. `HookInput` gains `permissionMode: String?`.

- [ ] **Step 1: Write failing tests:**
  - `BashRuleTest`: `parse("Bash(adb:*)")` matches `"adb shell ls"` and `"adb"`, but not `"adbx"`. `parse("Bash(adb devices)")` matches exactly that. `parse("Bash(./gradlew *)")` matches `"./gradlew installDebug"`. `parse("Bash")` matches anything. `parse("Read(**)")` is null.
  - `allowRuleMatchesOriginal`: user settings allow `Bash(adb:*)` → `decide("adb shell ls", PIN, cwd, "default") == ALLOW`.
  - `unmatchedAsks`: no rules → `decide("./run_tests.sh", PIN, …) == ASK`.
  - `compoundNeedsAllSegments`: allow `Bash(adb:*)` → `decide("rm -rf build && adb install a.apk", PIN, …) == ASK`; `decide("cd app && adb install a.apk", PIN, …) == ALLOW` (`cd` is read-only).
  - `readOnlyBuiltinsAllowed`: `decide("ls -la | grep foo", PIN, …) == ALLOW`; `decide("find . -delete", PIN, …) == ASK`.
  - `denyWins`: allow `Bash(adb:*)` + deny `Bash(adb uninstall:*)` → `decide("adb uninstall com.x", PIN, …) == ASK`.
  - `askRuleWins`: allow `Bash(adb:*)` + ask `Bash(adb shell rm:*)` → `ASK`.
  - `bypassMode`: no rules, mode `bypassPermissions` → `ALLOW`.
  - `cdmOnlyAllowed`: `decide("cdm acquire --category watch", CDM, …) == ALLOW`; `decide("cdm release && rm x", CDM, …) == ASK`.
  - `projectAndLocalSettingsMerged`: allow rule only in `<cwd>/.claude/settings.local.json` → `ALLOW`.
  - `unreadableSettingsIgnored`: a malformed `~/.claude/settings.json` → no exception; the decision falls back to the other files.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*PermissionMirrorTest*' --tests '*BashRuleTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Wire it into `DeviceManagerService` and register it as `single` in Koin with `home = Path(System.getProperty("user.home"))`.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: mirror user permission rules for rewritten commands`.

---

### Task 7: HTTP server and the `cdm` CLI

**Files:**
- Create: `…/server/CdmServer.kt` (Ktor CIO, `127.0.0.1:<settings.port>`; writes `~/.claude-device-manager/port` on start)
- Create: `…/server/HookJson.kt` (parse `HookInput` from raw JSON leniently; render `HookReply`)
- Create: `composeApp/src/jvmMain/resources/cdm` (bash + curl script)
- Test: `…/server/CdmServerTest.kt` (Ktor `testApplication`), `…/server/CdmScriptTest.kt`

**Interfaces — Produces** (routes):
- `POST /hooks/pre-tool-use`, `POST /hooks/subagent-stop`, `POST /hooks/session-end` take the raw hook JSON and always answer 200.
  - `NoOpinion` → `{}`
  - `Deny(r)` → `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":r}}`
  - `Rewrite(c)` with decision `d` → `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"|"ask","permissionDecisionReason":"Pinned to <device> by Claude Device Manager","updatedInput":<original tool_input with command = c>}}`
- `POST /cdm/acquire?holder=&cwd=&category=&timeoutSec=570` streams `text/plain` lines: `QUEUED <n>` on each position change, then one of `GRANTED <serial> <name>`, `TIMEOUT <n>`, or `BLOCKED`.
- `POST /cdm/release?holder=` → `RELEASED` or `NONE`. `GET /cdm/status?holder=` → one line: `HOLDING <serial> <name>`, `QUEUED <n>`, `BLOCKED`, or `NONE`.
- The `cdm` script: subcommands `acquire [--category c]`, `release`, `status`. It requires the `--holder`/`--cwd` that the hook injects, and prints a clear message if they're missing: "cdm must be run through Claude Code's Bash tool". It reads the port file and maps the server lines to human messages, copying the spec's text for each case. It retries connection failures for 30 s. If the app is still unreachable, it prints `Device Manager not running; proceeding without a device lock.` and exits 0.

- [ ] **Step 1: Write failing tests:**
  - **Review Focus 4** `malformedHookBodyIsEmptyObject`: bodies `""`, `"not json"`, `{"hook_event_name":"PreToolUse","tool_name":"Bash"}` (no command), and `{"hook_event_name":"Weird"}` → 200 with `{}`.
  - `denyShape`: a decider stubbed to `Deny("x")` → JSON with `permissionDecision == "deny"` and reason `"x"`.
  - `rewriteKeepsOtherToolInputFields`: input `tool_input = {"command":"adb shell ls","description":"d","timeout":5000}` and a `Rewrite` → `updatedInput` keeps `description` and `timeout`, and `permissionDecision` equals the mirror's decision. Test both `ALLOW` → `"allow"` and `ASK` → `"ask"` with a stubbed mirror.
  - `subagentIdentityFromAgentId`: `agent_id = "agent-1"` → the decider receives `HolderId(session, "agent-1")`; absent → `"main"`.
  - `acquireStreamsQueuedThenGranted`: h2 holds W; h1 acquires watch with `timeoutSec=5`; the test releases h2 → the response lines are `QUEUED 1`, then `GRANTED <W serial> <W name>`.
  - `acquireTimesOut`: `timeoutSec=1` → the last line is `TIMEOUT 1`.
  - `CdmScriptTest.appDownProceeds`: run the script with a port file pointing to a closed port and `CDM_RETRY_SECONDS=1` (an env override for tests) → exit 0, and stdout contains `proceeding without a device lock`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*CdmServerTest*' --tests '*CdmScriptTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. `main()` starts the service and server on app launch and stops them on window close.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add local http server for hooks and cdm cli`.

---

### Task 8: Installer — hooks, `cdm`, and the CLAUDE.md snippet

**Files:**
- Create: `…/install/Installer.kt`
- Test: `…/install/InstallerTest.kt`

**Interfaces — Produces:**

```kotlin
class Installer(private val home: Path) {
    fun hookStatus(port: Int): InstallStatus                         // NotInstalled | Installed | Outdated(port mismatch) | SettingsUnreadable
    fun installHooks(port: Int): Result<Path>                       // returns backup path
    fun installCdm(): Path                                           // ~/.local/bin/cdm, chmod 755
    val claudeMdSnippet: String
    fun appendSnippet(): Result<Unit>                                // idempotent: marker comments <!-- cdm:start --> / <!-- cdm:end -->
}
```

Hooks written to `~/.claude/settings.json`. Every entry carries the marker field `"statusMessage": "claude-device-manager"`, so it can be found and replaced.
- `PreToolUse` matcher `Bash` → `http://127.0.0.1:<port>/hooks/pre-tool-use`, timeout 2
- `PreToolUse` matcher `Edit|Write|MultiEdit|NotebookEdit` → same URL, timeout 2
- `SubagentStop` → `/hooks/subagent-stop`
- `SessionEnd` → `/hooks/session-end`

If Task 0 showed that the marker field is rejected, use a URL match instead (`127.0.0.1:*/hooks/`).

The snippet text covers what the spec's `cdm acquire` section says agents must be told: run `cdm acquire --category <phone|watch|phone,watch>` before any device work, including scripts; run `cdm release` when done; and `cdm acquire` may pause, which is expected.

- [ ] **Step 1: Write failing tests** (temp `home`):
  - `installsIntoMissingSettings`: no file → file created with the 4 hook entries.
  - **Review Focus 5** `preservesExistingHooksAndKeys`: existing settings with `"model"` and an unrelated `PreToolUse` command hook → after install both remain unchanged, the 4 entries are added, and the backup file equals the original bytes.
  - `reinstallReplacesNotDuplicates`: install on port 47823, then on 50000 → exactly 4 cdm entries, all on 50000.
  - `unparsableSettingsRefused`: settings `{"broken":` → `installHooks` fails, the file is unchanged, and `hookStatus` is `SettingsUnreadable`.
  - `appendSnippetIdempotent`: appending twice leaves one marked block, and existing CLAUDE.md content is preserved.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*InstallerTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement with `kotlinx.serialization.json` `JsonObject` edits. Write to a temp file and move it over the original atomically.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add hook and cdm installer`.

---

### Task 9: UI shell — rail, Navigation 3, list-detail

**Files:**
- Create: `…/ui/nav/Routes.kt`, `…/ui/nav/AppNavigation.kt`, `…/ui/theme/Theme.kt`, `…/ui/components/Chips.kt` (category chip, status dot)
- Create: `…/ui/window/AppWindow.kt`, `…/ui/window/WindowChrome.kt`, `…/ui/window/TitleBarColors.kt` (Jewel title bar)
- Modify: `…/Main.kt`, `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`, `settings.gradle.kts`
- Test: `…/ui/nav/NavigatorTest.kt`, `…/ui/window/WindowChromeTest.kt`

**Jewel title bar (spec: "Custom title bar").** Port the pattern from ScrcpyViewerV2. A read-only reference copy is at `/private/tmp/claude-501/-Users-jacobrein-Downloads-claude-device-manager/9a420eff-4985-4ba1-9f42-db478f6e5415/scratchpad/scrcpy-ref/composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/`. Treat it as reference only: don't copy scrcpy-specific settings, the mirror window, or Nucleus packaging.
- Dependency: `org.jetbrains.jewel:jewel-int-ui-decorated-window:0.41.0-262.10968.63`.
- Gradle: substitute `org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm` with the real `kotlinx-coroutines-core-jvm` at the catalog's coroutines version.
- Settings: apply the `org.gradle.toolchains.foojay-resolver-convention` plugin (1.0.0).
- Runtime: in `compose.desktop.application`, set `javaHome` to a JBR 25 toolchain (`JvmVendorSpec.JETBRAINS`, `languageVersion 25`), but only when a run or package task is requested, so tests don't download it. Add `jvmArgs += "--enable-native-access=ALL-UNNAMED"`.
- `WindowChrome { Custom, System }`, `windowChrome(supported: Boolean = customTitleBarSupported): WindowChrome`, and `customTitleBarSupported` (JBR available, Java feature ≥ 25, and Jewel's `DecoratedWindowKt` loads; any failure → false).
- `AppWindow(onCloseRequest, title, chrome, state, titleBar: @Composable AppTitleBarScope.() -> Unit, content)`. `Custom` uses `IntUiTheme` + `DecoratedWindow` + `TitleBar` with a `BoundedSlot`, with colours from `barColors(MaterialTheme.colorScheme)`. `System` is a plain `Window`.
- Title bar slot content: the text "Claude Device Manager" plus a live status ("N in use · M waiting", from `DeviceManagerService.snapshot`), with the numbers in `AnimatedContent`.
- Tests (`WindowChromeTest`): `unsupportedRuntimeFallsBackToSystem` (`windowChrome(supported = false) == System`); `supportedUsesCustom`; `barColorsFromScheme` (`barColors(darkColorScheme()).background == darkColorScheme().surfaceContainer`).
- Verify: `./gradlew :composeApp:run` shows the custom bar with native traffic lights, the window drags by the bar, and the status text updates.

**Interfaces — Produces:**

```kotlin
sealed interface Route : NavKey {
    data object Overview : Route; data object Devices : Route; data class DeviceDetail(val key: DeviceKey) : Route
    data object Queue : Route; data class QueueDetail(val holder: HolderId) : Route
    data object Sessions : Route; data object Projects : Route; data object Settings : Route
}
enum class TopLevel(val route: Route, val label: String) { OVERVIEW, DEVICES, QUEUE, SESSIONS, PROJECTS, SETTINGS }
class Navigator(start: Route = Route.Overview) {
    val backStack: SnapshotStateList<Route>
    fun selectTopLevel(t: TopLevel)                 // back stack = [t.route]
    fun openDetail(r: Route)                        // replaces any existing detail on top of the current list route
    fun back()
    val currentTopLevel: TopLevel
}
```

`AppNavigation` renders a `NavigationRail` (the Queue item gets a badge with the queue size from `DeviceManagerService.snapshot`) next to a `NavDisplay`. It uses `rememberListDetailSceneStrategy()`, list routes carry `ListDetailSceneStrategy.listPane()` metadata, detail routes carry `detailPane()`, and the entry decorators include `rememberViewModelStoreNavEntryDecorator()`. `NavDisplay` sets `transitionSpec`/`popTransitionSpec`: switching between top-level routes uses a fade plus a 24 dp vertical slide (220 ms), and opening or closing a detail uses a fade plus a horizontal slide from the end (250 ms). The rail's selected indicator animates (the Material 3 default), and the badge count uses `AnimatedContent`. The theme follows the system dark/light setting, and the dark palette matches the mockups (`#1b1d22` background, `#262a33` cards, `#6b8cff` accent).

- [ ] **Step 1: Write failing tests** in `NavigatorTest`:
  - `selectTopLevelResetsStack`: open `DeviceDetail(k)`, then `selectTopLevel(QUEUE)` → stack == `[Queue]`.
  - `openDetailReplacesDetail`: `[Devices, DeviceDetail(a)]` + `openDetail(DeviceDetail(b))` → `[Devices, DeviceDetail(b)]`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*NavigatorTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Each route renders a placeholder `Text` for now.
- [ ] **Step 4:** Run the tests. Expected: PASS. Run `./gradlew :composeApp:run`. Expected: the rail switches between the six placeholders.
- [ ] **Step 5: Commit** `feat: add navigation rail and nav3 list-detail shell`.

---

### Task 10: Overview, Devices, and Queue screens

**Files:**
- Create: `…/ui/overview/OverviewViewModel.kt`, `OverviewScreen.kt`
- Create: `…/ui/devices/DevicesViewModel.kt`, `DevicesScreen.kt`, `DeviceDetailViewModel.kt`, `DeviceDetailScreen.kt`
- Create: `…/ui/queue/QueueViewModel.kt`, `QueueScreen.kt`, `QueueDetailViewModel.kt`, `QueueDetailScreen.kt`
- Create: `…/ui/UiModels.kt` (a mapper from `AllocatorSnapshot` + `TrackedDevice` + `DeviceConfig` + activity to the UI rows)
- Test: `…/ui/UiModelsTest.kt`, `…/ui/QueueViewModelTest.kt`, `…/ui/DeviceDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceManagerService` (Task 6), the repositories (Task 5), and `Navigator` (Task 9).
- Produces:

```kotlin
data class DeviceRow(val key: DeviceKey, val name: String, val category: Category, val categoryOverridden: Boolean, val state: DeviceState, val holderLabel: String?, val heldFor: Duration?, val timerLabel: String?)   // e.g. "idle 1m", "editing · releases in 5m"
enum class DeviceState { FREE, IN_USE, OFFLINE_HELD, DONT_USE, OFFLINE }
data class QueueRow(val holder: HolderId, val label: String, val position: Int, val wants: Set<Category>, val waited: Duration)
data class WaitReason(val device: String, val reason: String)   // "held by watch-launcher #77c2 (editing, releases in ≤5m)" | "marked don't use"
fun holderLabel(h: HolderId, cwd: String?, agentType: String?): String   // "gabb-messenger #a41f" or "… · general-purpose"
```

The screens follow `prototype.html` and the spec's UI section. Overview has count tiles, the device list, Up next (top 3), and Activity (latest 20). Device detail has the category dropdown (Auto / Phone / Watch), the holder card with Release and "Release + block", recent commands, and the Don't-use toggle; switching it on while the device is in use opens a dialog asking "Release now" or "After current holder releases". The Queue list is drag-to-reorder with `sh.calvin.reorderable`, and the Queue detail shows the WaitReason list with Move to top, "Hand it <device> now" (one per eligible device), and Remove from queue. Animations, required:
- Device, queue, activity, and up-next rows use `Modifier.animateItem()`, with stable keys (`DeviceKey.hardwareSerial`, `HolderId.key`, activity id).
- Count tiles and queue positions use `AnimatedContent` slide-and-fade.
- Status dots and chips use `animateColorAsState`.
- The holder card switches between held and free with `AnimatedContent`.
- Don't-use dimming uses `animateFloatAsState` on alpha.
- The don't-use dialog and "why it's waiting" rows use `AnimatedVisibility`.
- Dragged queue rows elevate while dragging, using reorderable's `isDragging` with `animateDpAsState`.

Register each ViewModel with `viewModelOf(::…)` (`DeviceDetailViewModel` and `QueueDetailViewModel` take their key as a parameter via `koinViewModel { parametersOf(key) }`).

- [ ] **Step 1: Write failing tests:**
  - `UiModelsTest.timerLabelEditing`: holding with `editing = true`, last activity 1 min ago → `timerLabel == "editing · releases in 4m"`.
  - `UiModelsTest.offlineHeld`: held device absent from `tracked` → `DeviceState.OFFLINE_HELD`.
  - `UiModelsTest.waitReasons`: queued watch holder; W held by h2 (editing), G marked don't use → two `WaitReason`s in device order with the texts above.
  - `UiModelsTest.holderLabelForSubagent`: `holderLabel(HolderId("a41f9c…","agent-1"), "/src/gabb-messenger", "general-purpose") == "gabb-messenger #a41f · general-purpose"`.
  - `QueueViewModelTest.dragCallsService`: `onMove(from = 2, to = 0)` → the service's `moveInQueue(queue[2], 0)` was called (fake service).
  - `DeviceDetailViewModelTest.dontUseWhileHeldAsks`: `onDontUseToggled(true)` while in use → `uiState.confirmDontUse == true`, and no service call until `onConfirmDontUse(releaseNow = true)`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*UiModelsTest*' --tests '*ViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the ViewModels, the mapper, and the screens. Extract an interface `DeviceManager` from `DeviceManagerService`'s public API if a fake is needed for the tests, and bind it in Koin.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS. Run `./gradlew :composeApp:run` with a device connected. Expected: the device appears on Overview and Devices with the correct category.
- [ ] **Step 5: Commit** `feat: add overview, devices, and queue screens`.

---

### Task 11: Sessions, Projects, and Settings screens

**Files:**
- Create: `…/ui/sessions/SessionsViewModel.kt`, `SessionsScreen.kt`
- Create: `…/ui/projects/ProjectsViewModel.kt`, `ProjectsScreen.kt`
- Create: `…/ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`
- Test: `…/ui/SettingsViewModelTest.kt`, `…/ui/SessionsViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceManager`, `ProjectRepository`, `SettingsRepository`, `Installer` (Task 8).
- Produces: none for later tasks.

The screens:
- **Sessions** groups holders by `sessionId`, with the main agent first and then subagents. Each row shows the state (holding / waiting / idle / ended / blocked), with Unblock on blocked rows. Ended holders stay listed for 1 hour.
- **Projects** lists each path with a category dropdown (Any / Phone / Watch / Phone+Watch).
- **Settings** has port, idle minutes, and editing minutes (validated: port 1024–65535, minutes 1–240), hook status with Install/Repair, Install `cdm` (which shows a hint if `~/.local/bin` isn't on `PATH`), and the snippet with Copy and "Append to ~/.claude/CLAUDE.md" (that one asks for confirmation first). Changing the port restarts the server and marks the hooks `Outdated` until they're repaired. Animations follow the same rules as Task 10: session and project rows use `animateItem()`, subagent rows expand and collapse with `AnimatedVisibility`, and status changes in Settings crossfade.

- [ ] **Step 1: Write failing tests:**
  - `SettingsViewModelTest.rejectsBadPort`: `onPortChanged("80")` → `uiState.portError != null`, and the repository is not updated.
  - `SettingsViewModelTest.appendNeedsConfirm`: `onAppendSnippet()` → `confirmAppend == true`; `Installer.appendSnippet` is called only after `onConfirmAppend()`.
  - `SessionsViewModelTest.groupsAgentsUnderSession`: holders `s1:main`, `s1:agent-1`, `s2:main` → 2 groups; `s1` has main first.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*SettingsViewModelTest*' --tests '*SessionsViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: add sessions, projects, and settings screens`.

---

### Task 11b: Install on startup, uninstall on exit

**Files:**
- Modify: `…/install/Installer.kt`; the startup and shutdown wiring (`Main.kt` / `ServerController` or a new `…/install/InstallLifecycle.kt`); the Settings screen and ViewModel
- Test: `…/install/InstallerTest.kt`, `…/install/InstallLifecycleTest.kt`

**Interfaces — Produces:**

```kotlin
// Installer additions
fun uninstallHooks(): Result<Unit>      // removes only our marked hook entries (and events/groups left empty); no backup restore
fun removeSnippet(): Result<Unit>       // removes the <!-- cdm:start -->…<!-- cdm:end --> block (and one surrounding blank line); no-op if absent
fun uninstallCdm()                      // deletes ~/.local/bin/cdm if it is our script (content matches the resource)
fun pruneBackups(keep: Int = 5)
class InstallLifecycle(installer: Installer, …) { suspend fun onStarted(port: Int); fun onExit() }   // onExit idempotent, safe from a shutdown hook
```

The spec's "Automatic install and uninstall" section is the authority. On startup, after a successful bind, call `installHooks(port)`, `installCdm()`, `appendSnippet()` and `pruneBackups()`. After a port change and restart, re-install the hooks for the new port. On window close, Cmd+Q, or the JVM shutdown hook, call `onExit()` once: uninstall the hooks, remove the snippet, and delete `cdm`. Log failures and never block exit. Settings replaces the Install buttons with read-only status rows (hooks, `cdm`, snippet) and a "Repair" action. Copy stays.

- [ ] **Step 1: Write failing tests** (temp home only):
  - `uninstallRemovesOnlyOurs`: user hooks plus ours → after uninstall, the user hooks are byte-equal as JSON values, and no marked entries remain.
  - `uninstallWhenNotInstalledIsNoop`
  - `removeSnippetRestoresOriginalClaudeMd`: original content, then append, then remove → equals the original.
  - `uninstallCdmOnlyDeletesOurScript`: a foreign `cdm` file is kept.
  - `pruneKeepsNewestFive`
  - `lifecycleInstallsThenUninstallsOnce`: `onExit` called twice → the second call is a no-op.
  - `failedStartDoesNotInstall`
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*Installer*' --tests '*InstallLifecycle*'`. Expected: FAIL.
- [ ] **Step 3:** Implement and wire it.
- [ ] **Step 4:** Run the full `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: install integration on startup, remove on exit`.

---

### Task 12: End-to-end check with real sessions and a device

No new product code, unless this check finds a bug. Fix any bug through the owning task's tests.

- [ ] **Step 1:** Run `./gradlew :composeApp:run`. In Settings, click Install hooks, Install `cdm`, and Append snippet. Expected: `~/.claude/settings.json` has 4 cdm entries, and a backup file exists.
- [ ] **Step 2:** With one phone connected, start Claude session A in one project: "run `cdm acquire --category phone`, then `adb shell getprop ro.product.model`". Expected: the app shows A holding the phone, and the command ran pinned.
- [ ] **Step 3:** Start session B: the same request. Expected: B's `cdm acquire` pauses, and Queue shows B at position 1 with "why it's waiting" naming A.
- [ ] **Step 4:** In the app, Release A's device. Expected: B's acquire returns `GRANTED`, and A's next `adb` command is denied with the "released from Device Manager" message.
- [ ] **Step 5:** In session B, have a subagent run `cdm acquire --category phone`. Expected: the subagent appears as its own holder, queued behind or blocked by B's main agent, not sharing the device.
- [ ] **Step 5b:** In session A, while it holds the device, run `./gradlew --version`, which is not in your allow rules. Expected: a permission prompt that shows the `export ANDROID_SERIAL=…;` prefix. Then run `adb shell ls`, which is allowed by `Bash(adb:*)` if you have that rule. Expected: no prompt.
- [ ] **Step 6:** Quit the app, then run `adb devices` in a session. Expected: the command runs normally with no hook error that blocks it.
- [ ] **Step 7:** Record the results in `docs/superpowers/spikes/2026-11-05-hook-findings.md` under "E2E", then commit `docs: record end-to-end verification`.
