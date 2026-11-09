# Multi-Device Claim Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one session acquire a set of devices (several per platform, mixed platforms, mixed kinds), granted all at once. Each command then runs on a named member through `cdm run <name> -- <command>`, or on every member through `cdm run all`.

**Architecture:** The allocator adds a **set** beside today's per-platform holds. A set is one queue entry, granted all or nothing through bipartite matching, with reservations so it can't starve. It shares one timer and has named members. Today's single holds are untouched. A new `SetHookRules` class handles `cdm run` and set holders' commands for `HookDecider`. Service, server, the `cdm` script and the UI grow set-aware paths alongside the existing single-device ones.

**Tech Stack:** Kotlin/JVM, Compose Desktop (Jewel), Ktor server, bash (`cdm`), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-09-multi-device-claim-design.md`

## Global Constraints

- Today's single-device behavior, output and wire format stay unchanged. All existing tests in `./gradlew :composeApp:jvmTest` keep passing, except tests that assert a set request is impossible.
- Member names are `android<N>` / `ios<N>`, numbered from 1 in member-spec order within each platform. Names are stable until the set is released. A partial `cdm release --<platform>` does not renumber them.
- `cdm run all` exports `CDM_ANDROID<N>_SERIAL`, `CDM_IOS<N>_UDID`, `CDM_IOS<N>_PAIR_UDID`, and `ANDROID_SERIAL` as a comma list. It never sets `CDM_IOS_UDID`.
- The sentinel value is exactly `use-cdm-run`.
- `cdm run` segments are rewritten to `( export …; <rest> )`.
- The allocator is not thread-safe. It stays behind the service Mutex. `HookDecider` keeps `deferEvents`/`flushEvents` around every decision.
- Holds are not persisted, so no Room migration is needed.
- UI changes animate (rows in and out, state changes), per the project's UI rule.
- Run tests with `./gradlew :composeApp:jvmTest` from the worktree root.

## Review Focus

1. **One device fits several member specs** (`--android phone --android phone,watch` with one phone and one watch free). The set must still be granted, phone to the first spec and watch to the second. Test in Task 1.
2. **A paired iPhone and Apple Watch inside a set.** The pair is one member and is never split across two members. Test in Task 1.
3. **A set waiting behind single requests.** Singles must not take the devices it has reserved. A second set behind it must not reserve devices. Test in Task 3.
4. **Chained `cdm run` segments** (`cdm run android1 -- adb install a.apk && cdm run android2 -- adb install a.apk`). Each segment is pinned to its own member, and no pin leaks between them. Test in Task 6.
5. **An allow rule `Bash(cdm:*)` with `cdm run android1 -- rm -rf build`.** It must not be auto-allowed. Test in Task 7.

---

### Task 1: Set types and matching

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/core/allocator/AllocatorTypes.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/core/allocator/SetMatcher.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/core/allocator/SetMatcherTest.kt`

**Interfaces:**
- Produces:
  - `data class SetRequest(val members: List<Wants>)`. Every member has a non-null `platform`. `init` requires `members.size >= 2` and that every platform is non-null.
  - `data class SetMember(val name: String, val platform: Platform, val device: DeviceKey, val partner: DeviceKey? = null)`
  - `data class HeldSet(val members: List<SetMember>, val since: Instant, val lastActivity: Instant, val editing: Boolean)`
  - `data class ReleasedSet(val reason: ReleaseReason, val members: List<SetMember>)`
  - `sealed interface GrabSetResult { data class Granted(val set: HeldSet); data object NoneFree; data object Blocked; data object Impossible }`
  - `HolderStatus.Queued` gains `val set: SetRequest? = null`.
  - New `data class HolderStatus.HoldingSet(val set: HeldSet) : HolderStatus`.
  - `AllocatorSnapshot` gains `val sets: Map<HolderId, HeldSet> = emptyMap()` and `val reserved: Map<DeviceKey, HolderId> = emptyMap()`.
  - `object SetMatcher`:
    - `fun assign(specs: List<Wants>, candidates: List<AllocDevice>): List<AllocDevice>?` returns one device per spec, in spec order, or null.
    - `fun maxPartial(specs: List<Wants>, candidates: List<AllocDevice>): Map<Int, AllocDevice>` maps spec index to device, for a maximum matching.
    - `fun names(specs: List<Wants>): List<String>` gives `android1`… / `ios1`… in spec order.
  - A candidate with `partner` set stands for its whole pair. It fits a spec when either side fits, and the side that fits becomes the returned device. A pair's two keys never appear in the result twice. The caller passes each pair once, as its primary.

- [ ] **Step 1: Write failing tests** in `SetMatcherTest`:
  - `assignsOverlappingSpecs`: specs `[phone-android, {phone,watch}-android]`, candidates `[watch, phone]`. Expect `[phone, watch]`.
  - `returnsNullWhenShort`: two android-phone specs, one phone. Expect `null`.
  - `pairCountsOnceAndFitsEitherSide`: an iPhone candidate with partner watch, specs `[ios watch]`. Expect the watch side returned (key = watch). Also: specs `[ios phone, ios watch]` with only that pair gives `null`.
  - `maxPartialFindsLargest`: three android-phone specs, two phones. Map size 2.
  - `namesNumberPerPlatformInOrder`: `[android, ios, android]` gives `["android1", "ios1", "android2"]`.
  - `setRequestRejectsOneMemberOrNullPlatform`: `assertFailsWith<IllegalArgumentException>`.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*SetMatcherTest*'`. Expected: compile FAIL (unresolved `SetMatcher`).
- [ ] **Step 3: Add the types and `SetMatcher`.** `assign`/`maxPartial` use Kuhn's augmenting-path matching: specs on one side, candidates on the other. Fix the compile errors from the new `HolderStatus` subtype in exhaustive `when`s by adding a branch that behaves like `Holding` for now. Later tasks refine them.
- [ ] **Step 4: Run** the full suite `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(allocator): add set types and matcher`.

### Task 2: Allocator grants and releases sets

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/core/allocator/Allocator.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/core/allocator/AllocatorSetTest.kt`

**Interfaces:**
- Consumes: Task 1 types and `SetMatcher`.
- Produces, on `Allocator`:
  - `fun tryGrabSet(holder: HolderId, req: SetRequest): GrabSetResult`
  - `fun enqueueSet(holder: HolderId, req: SetRequest): Int` returns the 1-based position, or 0 if blocked.
  - `fun feasible(req: SetRequest): Boolean` checks assignment over listed, non-don't-use devices, ignoring holds and connection.
  - `fun setOf(holder: HolderId): HeldSet?`
  - `release(holder, reason, platform)` also removes that platform's members, or the whole set when `platform == null`.
  - `statusOf(holder)` returns `HoldingSet` for a set holder (Blocked and Queued still come first).
  - `snapshot().holds` maps every set member's device and partner to the holder. `snapshot().sets` is filled.
  - `releaseDevice(key, block)` on any member releases the whole set.
  - `setDontUse(key, true, releaseNow = true)` on a member releases the whole set.
  - `handOff` on a member does nothing.
  - `endHolder`/`endSession` clear sets.
- Each member's release emits `AllocatorEvent.Released`. Each member's grant emits `AllocatorEvent.Granted`. No new event types.

- [ ] **Step 1: Write failing tests** in `AllocatorSetTest`:
  - `grantsWholeSetAtOnce`: 2 Android phones and 1 iPhone free; request `[android phone, android phone, ios phone]`. Expect `Granted`, with names `android1, android2, ios1`, `holds` covering all three, and `statusOf` returning `HoldingSet`.
  - `neverGrantsPartially`: one of two phones held by `h2`. `tryGrabSet` returns `NoneFree`, and `h1` holds nothing.
  - `queuedSetGrantedWhenLastDeviceFrees`: enqueue, then `h2` releases. `h1` gets the whole set, and the queue is empty.
  - `feasibleFalseWhenTooFewListed`.
  - `releasePlatformKeepsOtherMembersAndNames`: after `release(h1, SELF_RELEASE, ANDROID)` the set has only `ios1`, still named `ios1`.
  - `userReleaseOfOneMemberReleasesSet` and `dontUseReleasesSet`.
  - `handOffIgnoresSetMember`.
  - `pairedIphoneInSetHoldsWatchToo`.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*AllocatorSetTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Store sets in `private val sets = LinkedHashMap<HolderId, HeldSet>()` and queued requests in `private val queueSets = HashMap<HolderId, SetRequest>()`. Candidates for `assign` are free, usable devices whose partner is free (`partnerFree`), with each pair passed once. In `passToQueue`, iterate the queue in order: a set entry is granted when `assign` over free devices succeeds. Single entries behave as today. Do not add reservations yet.
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(allocator): grant and release device sets`.

### Task 3: Reservations (no starvation)

**Files:**
- Modify: `Allocator.kt`
- Test: `AllocatorSetTest.kt`

**Interfaces:**
- Produces: `snapshot().reserved: Map<DeviceKey, HolderId>`, and `fun reservedFor(holder: HolderId): List<DeviceKey>`.
- Rule: only the **first set entry in queue order** reserves. Its reservation is the device set of `SetMatcher.maxPartial(spec, freeUnreserved + itsReserved)`, recomputed after every change that can affect it:
  - `setDevices`
  - release, timeout, don't-use, `removeFromQueue`, `moveInQueue`
  - queue-grace expiry
  - grant
- Reserved devices are skipped by `tryGrab`, by first-use grabs (through `tryGrab`), by `passToQueue` for everyone else, and by later sets.
- When the set leaves the queue, its reservation is dropped and the freed devices go through `passToQueue`.
- A reserved device that goes offline or becomes don't-use just leaves the reservation. The set keeps its place.

- [ ] **Step 1: Write failing tests:**
  - `firstSetReservesFreedDevices`: set of 2 queued, one phone frees. That phone is `reserved[h1]`, and `tryGrab(h3, androidPhone)` returns `NoneFree`.
  - `singleBehindSetCanTakeDeviceSetDoesNotNeed`: the set wants Android phones, and a watch frees. A queued single watch request gets it.
  - `secondSetDoesNotReserve`.
  - `reservationDroppedWhenSetRemoved`: `removeFromQueue(h1)`. The reserved phone goes to the next queued single.
  - `reservedDeviceOfflineLeavesReservationKeepsPlace`.
  - `setGrantedFromReservedPlusFreed`.
- [ ] **Step 2: Run** `--tests '*AllocatorSetTest*'`. Expected: new tests FAIL.
- [ ] **Step 3: Implement** a private `settleReservation()` called at the end of each mutating method listed above.
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(allocator): reserve devices for the first waiting set`.

### Task 4: Shared timer, regrab, activity

**Files:**
- Modify: `Allocator.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/core/allocator/AllocatorSetTimerTest.kt`

**Interfaces:**
- Produces:
  - `fun recordSetActivity(holder: HolderId)` resets `lastActivity` and clears `editing`.
  - `recordEdit(holder)` also flags the set.
  - `tick()` times the set out as a unit: it releases every member and records `ReleasedSet(reason, members)`.
  - `fun releasedSetOf(holder: HolderId): ReleasedSet?`
  - `fun tryRegrabSet(holder: HolderId): GrabSetResult` is valid only after `IDLE_TIMEOUT`/`EDIT_TIMEOUT`. It regrabs exactly the former member devices with the same names, all or nothing. Anything else returns `NoneFree`.
  - `undoGrab` is not needed for sets, because sets are only granted by `cdm acquire`, never inside a hook decision.

- [ ] **Step 1: Write failing tests:**
  - `activityOnAnyMemberKeepsWholeSet`.
  - `idleTimeoutReleasesWholeSet`, with the reason recorded.
  - `regrabRestoresSameDevicesAndNames`.
  - `regrabFailsIfOneMemberTaken`: one device then belongs to `h2`, and nothing is held by `h1`.
  - `editingTimerAppliesToSet`.
- [ ] **Step 2: Run** `--tests '*AllocatorSetTimerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(allocator): shared timer and regrab for sets`.

### Task 5: Classifier support for `cdm run`

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/core/classify/CommandClassifier.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/core/classify/CdmRunClassifierTest.kt`

**Interfaces:**
- Produces:
  - `data class CdmRunSegment(val start: Int, val end: Int, val target: String, val rest: String)`. `start`/`end` index the raw command (the same ranges `segments` uses). `rest` is the raw text after `--`, trimmed. `target` is the name, or `all`.
  - `fun CommandClassifier.cdmRuns(command: String): List<CdmRunSegment>` returns one entry per segment whose first word is `cdm` and second word is `run`. If `--` is missing, or a name is missing, it still returns an entry, with `target = ""` or `rest = ""`, so the hook can deny it with a helpful message.
  - `fun CommandClassifier.withoutCdmRun(command: String): String` replaces each `cdm run <name> -- ` prefix with nothing.
  - `classifyAll` on a `cdm run` segment keeps returning `CommandKind.Cdm("run")`. The hook classifies `rest` itself.

- [ ] **Step 1: Write failing tests:**
  - `parsesSingle`: `cdm run android2 -- adb shell input tap 1 2` gives target `android2` and rest `adb shell input tap 1 2`.
  - `parsesChained` (two segments, `&&`, correct ranges).
  - `parsesPipeline`: `cdm run android1 -- adb logcat | grep x` gives rest `adb logcat` for the first segment only.
  - `keepsQuotes`: rest `adb shell 'echo a && b'` is kept verbatim.
  - `missingDashDash`.
  - `withoutCdmRunStripsPrefixes`.
- [ ] **Step 2: Run** `--tests '*CdmRunClassifierTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement**, reusing the private `ranges`, `prefixLength` and `tokenEnd`.
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(classify): parse cdm run segments`.

### Task 6: Hook rules for `cdm run` and set holders

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/service/SetHookRules.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/cdm/service/HookDecider.kt` (call into it at the top of `decide`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/service/SetHookRulesTest.kt`

**Interfaces:**
- Consumes: Task 2–4 allocator API, and Task 5 `cdmRuns`.
- Produces: `class SetHookRules(allocator: Allocator, iosPinScript: () -> Path?)` with `fun decide(holder: HolderId, cwd: String, command: String): HookReply?`. It returns null when the command is neither a `cdm run` command nor from a set holder, and `HookDecider` then carries on as today. `HookDecider` calls it first, inside the existing `deferEvents` window.
- Members of a holder: from `setOf(holder)`, or, for a single holder, `android1`/`ios1` mapped to its per-platform holds.
- Rules, in order:
  1. A command containing any `cdm run` segment: every other segment must classify as `Other` or `Cdm` (not run). Otherwise deny with `"Put each device command in its own \`cdm run <name> -- …\`."`
  2. For each `cdm run` segment:
     - An empty target or rest is denied with the usage line. An unknown name is denied, listing the holder's names. A holder with nothing held, and no regrabbable timed-out set, is denied with `"You hold no devices. Run \`cdm acquire\` first."`.
     - A timed-out set calls `tryRegrabSet`. If that fails, deny with `"Your devices timed out and one was taken; run \`cdm acquire\` again."`.
     - Decide `rest` with the target fixed. The same checks as today apply against that member only: dangerous global, transport id, disconnect of a non-member, explicit serial or UDID or `ANDROID_SERIAL`/`CDM_IOS_UDID` naming anything but the target, `-d`/`-e` resolving elsewhere, an iOS kind on an Android member (or the reverse), and an unpinnable iOS kind (except KMP Gradle, which gets the init script).
     - For `all`, the allowed set is every member. Android gets a comma `ANDROID_SERIAL`. iOS kinds must name a member UDID explicitly. KMP Gradle iOS is denied with `"Inside \`cdm run all\`, KMP iOS tests can't pick a device. Use \`cdm run ios<N> -- …\`."`.
     - Rewrite the segment in place to `( export …; <rest> )`, with `booted` rewritten to the member UDID. Set holders also get every `CDM_<PLATFORM><N>_…` variable exported.
     - Record set activity (or `recordDeviceActivity` on that platform for a single holder).
  3. A set holder's command with no `cdm run`:
     - A platform with more than one member: a device kind is denied (listing names and the `cdm run` form), unless every explicit serial or UDID names one member. Then it is pinned to that member, like a `cdm run`.
     - A platform with one member is pinned as today.
     - `Other` commands get `export ANDROID_SERIAL=use-cdm-run;` (and `CDM_IOS_UDID=use-cdm-run` when there is more than one iOS member) plus the per-member variables.
     - `cdm` (non-run) commands keep today's Rule 1 identity rewrite.
- Every reply from these rules is a `HookReply.Rewrite(…, RewriteKind.PIN)` or a `Deny`.

- [ ] **Step 1: Write failing tests** in `SetHookRulesTest` (style as in `HookDeciderTest`):
  - `runPinsNamedAndroidMember`: holder with a set `[p1, p2]`. `cdm run android2 -- adb shell ls` gives `"( export ANDROID_SERIAL=p2-usb; export CDM_ANDROID1_SERIAL=p1-usb; export CDM_ANDROID2_SERIAL=p2-usb; adb shell ls )"`.
  - `chainedRunsPinEachSegment` (Review Focus 4).
  - `runDeniesOtherSerial`: `cdm run android1 -- adb -s p2-usb shell ls` is denied.
  - `runAllExportsCommaList`: `ANDROID_SERIAL=p1-usb,p2-usb`.
  - `runAllDeniesKmpIos`.
  - `runIosExportsUdidBeforeArgs`: `cdm run ios1 -- xcrun simctl launch $CDM_IOS_UDID x` contains `export CDM_IOS_UDID=<udid>;` before `xcrun`.
  - `runOnSingleHolderUsesAndroid1`.
  - `runUnknownNameListsNames`.
  - `runFromNobodyDenied`.
  - `runRegrabsAfterTimeout` and `runDeniesWhenRegrabFails`.
  - `bareDeviceCommandFromSetHolderDenied`.
  - `explicitMemberSerialAllowed`.
  - `otherCommandGetsSentinel`: `./run-test.sh` gives a prefix containing `export ANDROID_SERIAL=use-cdm-run;`.
  - `mixedRunAndDeviceSegmentDenied`.
  - `singleHolderBehaviourUnchanged`: the existing `HookDeciderTest` still passes, no change needed.
- [ ] **Step 2: Run** `--tests '*SetHookRulesTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `SetHookRules`. Move any helper shared with `HookDecider` (`label`, serial checks, `isUsbSerial`) to `internal` top-level functions rather than duplicating it.
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(hook): cdm run and set-holder pinning`.

### Task 7: Service and server for sets

**Files:**
- Modify: `service/DeviceManager.kt`, `service/DeviceManagerService.kt`, `service/PermissionMirror.kt`, `server/CdmServer.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/server/FakeManager.kt`
- Test: `service/DeviceManagerServiceTest.kt`, `server/CdmServerTest.kt`, `service/PermissionMirrorTest.kt`

**Interfaces:**
- Produces:
  - `data class GrantedMember(val name: String, val platform: Platform, val serial: String, val deviceName: String, val pairSerial: String?)`
  - `AcquireResult.GrantedSet(val members: List<GrantedMember>)` and `AcquireResult.Refused(val message: String)`
  - `DeviceManager.acquireSet(holder: HolderId, cwd: String, req: SetRequest, timeout: Duration, onPosition: suspend (Int) -> Unit): AcquireResult`. A member with empty categories gets the project default. It is refused when:
    - the holder holds anything: `"You already hold devices. Run \`cdm release\` first."`
    - `!feasible`: `"No combination of the listed devices fits this request."`
  - Single `acquire` from a set holder returns `Refused("You hold a set of devices. Use \`cdm run <name> -- …\`, or run \`cdm release\` first.")`.
  - `release(holder, platform)`, `renew` and `status` cover sets. `renew` calls `recordSetActivity`.
  - `preToolUse` records set activity instead of per-platform activity for set holders, and logs the command against each touched member.
  - **Wire:**
    - `POST /cdm/acquire` accepts repeated `member=<android|ios>:<value>`, where value is `""`, `N`, `cat` or `N:cat` and cat may be a comma list. It also accepts repeated `device=`.
    - The server expands these into `SetRequest.members`. If the total is 1, it takes today's path.
    - A grant prints one `MEMBER <name> <platform> <serial> <device name>` line per member, then `DONE`. A `Refused` result answers HTTP 400 with the message.
    - `GET /cdm/status` for a set holder prints `MEMBER …` lines.
  - `PermissionMirror.decide` judges `CommandClassifier.withoutCdmRun(original)` when the original has a `cdm run` segment, and never applies its cdm-only ALLOW to it.

- [ ] **Step 1: Write failing tests:**
  - Service:
    - `acquireSetGrantsAndReturnsMembers`
    - `acquireSetRefusedWhenHolding`
    - `acquireSetRefusedWhenInfeasible`
    - `acquireSetWaitsThenGrants` (position callback fires, then `GrantedSet`)
    - `singleAcquireRefusedForSetHolder`
    - `releaseAndroidKeepsIosMembers`
  - Server:
    - `acquireMembersParsesCountsAndCategories`: `member=android:2:phone&member=ios:1` gives 3 members, with the right `Wants`.
    - `acquireSetStreamsMemberLinesThenDone`
    - `statusListsMembers`
    - `oneMemberUsesLegacyPath`
  - Mirror: `cdmRunInnerCommandJudgedNotCdmRule` (Review Focus 5). With an allow rule `Bash(cdm:*)`, `cdm run android1 -- rm -rf build` gives `ASK`.
- [ ] **Step 2: Run** `--tests '*DeviceManagerServiceTest*' --tests '*CdmServerTest*' --tests '*PermissionMirrorTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Mirror the waiting loop of `acquire` for `acquireSet`, so the heartbeat and detach behavior stay the same.
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(server): acquire, status and release for device sets`.

### Task 8: `cdm` script

**Files:**
- Modify: `composeApp/src/jvmMain/resources/cdm`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/cdm/server/CdmScriptTest.kt`

**Interfaces:**
- Consumes: Task 7 wire format.
- Behavior:
  - `--android`/`--ios` take an optional value when the next argument doesn't start with `-`. Each occurrence adds `member=<platform>:<value>`. `--device` can be repeated.
  - Legacy mode applies when exactly one member spec (or one `--device`) is given and its count is 1. It sends today's params and prints today's output, so existing tests pass unchanged. Otherwise the script sends `member=`/`device=` params.
  - The "Pass only one of --ios and --android." check applies only in legacy mode.
  - A set grant prints:
    - `Got your devices:`
    - one line per member: two-space indent, then name, model and serial in parentheses
    - the two usage lines, verbatim from the spec section "`cdm acquire` output for a set"
  - `status` prints `Holding <name>: <device> (<serial>[, iOS]).` per `MEMBER` line.
  - `cdm run` is handled **before** the holder check. It prints `Device Manager not running; running without a device lock.` to stderr, drops `<name>` and `--`, and `exec`s the rest. A missing `--` prints the usage line and exits 2.
  - The usage string gains `cdm acquire … [--android [N][:cat]] [--ios [N][:cat]] …` and `cdm run <name|all> -- <command>`.

- [ ] **Step 1: Write failing tests:**
  - `setAcquirePrintsMembers` (FakeManager returns `GrantedSet`)
  - `setAcquireSendsMemberParams` (FakeManager records `SetRequest`)
  - `legacyAcquireUnchanged` (existing tests pass)
  - `runWithoutHookExecsCommand`: `cdm run android1 -- echo hi` gives exit 0, stdout `hi`, and the not-running line on stderr.
  - `runMissingDashDashExits2`
  - `statusPrintsMembers`
- [ ] **Step 2: Run** `--tests '*CdmScriptTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(cdm): set acquire and cdm run`.

### Task 9: UI for sets and reservations

**Files:**
- Modify: `ui/UiModels.kt`, `ui/sessions/SessionsViewModel.kt` (and its screen), `ui/devices/DevicesScreen.kt`, `ui/queue/QueueScreen.kt`, `ui/queue/QueueDetailViewModel.kt`, `ui/queue/QueueDetailScreen.kt`, `ui/devices/DeviceDetailParts.kt` (hand-off control), `ui/components/Chips.kt`
- Test: `ui/UiModelsTest.kt`, `ui/QueueViewModelTest.kt`, `ui/SessionsViewModelTest.kt`

**Interfaces:**
- Consumes: `snapshot.sets`, `snapshot.reserved`, `HolderStatus.Queued.set`.
- Produces:
  - `DeviceState.RESERVED`. `DeviceRow.reservedFor: String?` holds the holder label. `DeviceRow.memberName: String?` is, for example, `android2`.
  - `QueueRow.setSummary: String?`, for example `"2 × Android phone, 1 × iOS phone"`. Group equal specs and keep the order of first appearance.
  - `QueueRow.reservedCount: Int` and `QueueRow.memberCount: Int`, shown as `"1 of 3 reserved"`.
  - `fun setSummary(req: SetRequest): String`
  - In the device detail, hand-off is disabled for set members, with the tooltip `"Part of a device set; release the set instead."`.
  - Sessions screen: a set holder's members are grouped under one row with their names, plus one shared timer label.
- New rows, chip changes and the reserved state animate, using the existing `AnimatedRows`/`AnimatedCount` components.

- [ ] **Step 1: Write failing tests:**
  - `deviceRowShowsReservedState`
  - `deviceRowShowsMemberName`
  - `queueRowSummarisesSet` (exact string above)
  - `queueRowCountsReserved`
  - `sessionsGroupSetMembers`
  - `handOffDisabledForSetMember` (on the view model's exposed flag)
- [ ] **Step 2: Run** `--tests '*UiModelsTest*' --tests '*QueueViewModelTest*' --tests '*SessionsViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the models, then the screens.
- [ ] **Step 4: Run** the full suite. Then launch the app (`run` skill) with a set held, using the `:sample` app or two `cdm acquire` calls against real or emulator devices. Screenshot the Devices, Queue and Sessions screens.
- [ ] **Step 5: Commit** `feat(ui): show device sets and reservations`.

### Task 10: Installed instructions, README, Gradle comma check

**Files:**
- Modify: `install/Installer.kt` (the CLAUDE.md block), `README.md`
- Test: `install/InstallerTest.kt`

- [ ] **Step 1: Write a failing test** `claudeMdMentionsSets`. The installed block contains the exact sentence: `To hold several devices at once, run for example \`cdm acquire --android 2\` (or \`--android phone --android watch\`), then run each command with \`cdm run <name> -- <command>\`, or \`cdm run all -- <command>\` for one command that uses them all.`
- [ ] **Step 2: Run** `--tests '*InstallerTest*'`. Expected: FAIL.
- [ ] **Step 3: Add the sentence** after the `--device` sentence. Leave the rest of the block unchanged.
- [ ] **Step 4: Verify Gradle comma `ANDROID_SERIAL`.** Acquire two Android devices (`cdm acquire --android 2`) in a scratch Android project. Run `cdm run all -- ./gradlew connectedDebugAndroidTest`, then `cdm release`. Confirm that only those two devices run the tests. If AGP ignores the comma list, change `cdm run all` to the supported mechanism, and record it in the spec and README.
- [ ] **Step 5: Update the README.** Change the "One Android and one iOS device per session" bullet. Add a "Device sets" section covering request syntax, all-or-nothing waiting, reservations, `cdm run` / `cdm run all`, the sentinel, and release behavior.
- [ ] **Step 6: Run** the full suite. Expected: PASS.
- [ ] **Step 7: Commit** `docs: document device sets and cdm run`.
