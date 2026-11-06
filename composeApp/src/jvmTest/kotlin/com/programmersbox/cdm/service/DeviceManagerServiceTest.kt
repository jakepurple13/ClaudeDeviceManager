package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.data.ActivityRepository
import com.programmersbox.cdm.data.AppDatabase
import com.programmersbox.cdm.data.BlockedRepository
import com.programmersbox.cdm.data.DeviceConfigDao
import com.programmersbox.cdm.data.DeviceConfigEntity
import com.programmersbox.cdm.data.DeviceConfigRepository
import com.programmersbox.cdm.data.SettingDao
import com.programmersbox.cdm.data.SettingEntity
import com.programmersbox.cdm.data.PinnedPermission
import com.programmersbox.cdm.data.ProjectRepository
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.data.inMemoryDatabase
import com.programmersbox.cdm.devices.DeviceSource
import com.programmersbox.cdm.devices.DeviceTracker
import com.programmersbox.cdm.devices.FakeAdb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The allocator clock is a manual [now], not virtual time: Room answers on real IO threads, and
 * runTest may skip virtual time ahead while it waits, which must not fire idle timeouts.
 */
class DeviceManagerServiceTest {
    private val db: AppDatabase = inMemoryDatabase()
    private val adb = FakeAdb(
        "List of devices attached\np-usb device\nw-usb device\n",
        hw = mapOf("p-usb" to "P", "w-usb" to "W"),
        watch = mapOf("w-usb" to "true"),
        models = mapOf("p-usb" to "Pixel 8", "w-usb" to "Pixel Watch"),
    )
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private var now = t0
    private val configs = DeviceConfigRepository(db.deviceConfigDao())
    private val projects = ProjectRepository(db.projectDao())
    private val settings = SettingsRepository(db.settingDao())
    private val activity = ActivityRepository(db.activityDao())
    private val blocked = BlockedRepository(db.blockedDao())
    private val home = java.nio.file.Files.createTempDirectory("cdm-home")
    private val mirror = PermissionMirror(home)
    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val watch = setOf(Category.WATCH)

    @AfterTest fun close() = db.close()

    /** The pre-iOS call shape: categories only, no platform or real/virtual limit. */
    private suspend fun DeviceManagerService.acquire(
        holder: HolderId,
        cwd: String,
        categories: Set<Category>,
        timeout: kotlin.time.Duration,
        onPosition: suspend (Int) -> Unit,
    ): AcquireResult = acquire(holder, cwd, Wants(categories), timeout, onPosition)

    private val iosSims = com.programmersbox.cdm.devices.IosTrackerTest::class.java.getResource("/ios/simctl-booted.json")!!.readText()
    private val iphone = DeviceKey("11111111-1111-1111-1111-111111111111")
    private val watchSim = DeviceKey("44444444-4444-4444-4444-444444444444")

    /** Two Android devices (P phone, W watch) plus the booted simulators from the fixture. */
    private suspend fun TestScope.startedWithIos(): DeviceManagerService {
        val ios = com.programmersbox.cdm.devices.IosTracker(com.programmersbox.cdm.devices.FakeIos(sims = iosSims))
        val s = DeviceManagerService(DeviceSource(DeviceTracker(adb), ios, backgroundScope), configs, projects, settings, activity, blocked, mirror, clock = { now })
        s.start(backgroundScope)
        s.snapshot.first { snap -> snap.devices.any { it.key == iphone } && snap.devices.any { it.key == DeviceKey("P") } }
        return s
    }

    private suspend fun TestScope.started(
        scope: CoroutineScope = backgroundScope,
        configs: DeviceConfigRepository = this@DeviceManagerServiceTest.configs,
        settings: SettingsRepository = this@DeviceManagerServiceTest.settings,
    ): DeviceManagerService {
        val s = DeviceManagerService(DeviceSource(DeviceTracker(adb), null, backgroundScope), configs, projects, settings, activity, blocked, mirror, clock = { now })
        s.start(scope)
        s.snapshot.first { it.devices.size == 2 }
        return s
    }

    private fun bash(cmd: String, session: String = "s1", agent: String? = null, agentType: String? = null) =
        HookInput("PreToolUse", session, agent, agentType, "/w", "Bash", cmd)

    @Test fun scanMapsTrackedDevicesWithConfigOverrides() = runTest {
        configs.rename(DeviceKey("P"), "Jacob's phone")
        configs.setCategory(DeviceKey("P"), Category.WATCH)
        val s = started()
        val p = s.snapshot.value.devices.single { it.key == DeviceKey("P") }
        assertEquals("Jacob's phone", p.name)
        assertEquals(Category.WATCH, p.category)
        assertEquals("Pixel Watch", s.snapshot.value.devices.single { it.key == DeviceKey("W") }.name)
        assertEquals(2, s.tracked.value.size)
    }

    @Test fun acquireGrantsAtOnce() = runTest {
        val s = started()
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch", Platform.ANDROID), s.acquire(h1, "/w", watch, 1.minutes) {})
    }

    @Test fun acquireWithNoWantsUsesProjectDefault() = runTest {
        projects.setDefault("/w", Category.WATCH)
        val s = started()
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch", Platform.ANDROID), s.acquire(h1, "/w", emptySet(), 1.minutes) {})
    }

    @Test fun phoneAndWatchProjectDefaultWantsBoth() = runTest {
        projects.setDefault("/w", Category.PHONE_WATCH)
        val s = started()
        // The phone is taken, so a project that takes either kind gets the watch.
        s.acquire(h2, "/other", setOf(Category.PHONE), 1.minutes) {}
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch", Platform.ANDROID), s.acquire(h1, "/w", emptySet(), 1.minutes) {})
    }

    @Test fun phoneAndWatchProjectDefaultGrabsOnHook() = runTest {
        projects.setDefault("/w", Category.PHONE_WATCH)
        val s = started()
        s.acquire(h2, "/other", setOf(Category.WATCH), 1.minutes) {}
        s.preToolUse(bash("adb shell ls"))
        assertEquals(DeviceKey("P"), (s.status(h1) as HolderStatus.Holding).device)
    }

    @Test fun phoneAndWatchQueuesForBoth() = runTest {
        projects.setDefault("/w", Category.PHONE_WATCH)
        val s = started()
        s.acquire(h2, "/other", setOf(Category.ANY), 1.minutes) {}
        s.acquire(HolderId("s3"), "/other", setOf(Category.ANY), 1.minutes) {}
        assertEquals(AcquireResult.TimedOut(1), s.acquire(h1, "/w", emptySet(), 1.seconds) {})
        assertEquals(Wants(setOf(Category.PHONE, Category.WATCH)), (s.status(h1) as HolderStatus.Queued).wants)
    }

    @Test fun acquireWaitsReportsPositionThenGrants() = runTest {
        val s = started()
        s.acquire(h2, "/w", watch, 1.minutes) {}
        val positions = mutableListOf<Int>()
        val r = async { s.acquire(h1, "/w", watch, 5.minutes) { positions += it } }
        s.snapshot.first { h1 in it.queue }
        s.release(h2)
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch", Platform.ANDROID), r.await())
        assertEquals(listOf(1), positions)
    }

    @Test fun acquireTimesOutWithPositionAndKeepsSpot() = runTest {
        val s = started()
        s.acquire(h2, "/w", watch, 1.minutes) {}
        assertEquals(AcquireResult.TimedOut(1), s.acquire(h1, "/w", watch, 30.seconds) {})
        assertIs<HolderStatus.Queued>(s.status(h1))
    }

    /** Ruling W: acquiring a category the current hold doesn't fit gives the hold back and grabs/queues for the new one. */
    @Test fun acquireOtherCategoryReleasesCurrentHold() = runTest {
        val s = started()
        assertEquals(AcquireResult.Granted("p-usb", "Pixel 8", Platform.ANDROID), s.acquire(h1, "/w", setOf(Category.PHONE), 1.minutes) {})
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch", Platform.ANDROID), s.acquire(h1, "/w", watch, 1.minutes) {})
        assertEquals(null, s.snapshot.value.holds[DeviceKey("P")])
        // Same category again: keeps the hold.
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch", Platform.ANDROID), s.acquire(h1, "/w", watch, 1.minutes) {})
        // Wanted category taken: releases and queues.
        s.acquire(h2, "/w", setOf(Category.PHONE), 1.minutes) {}
        assertEquals(AcquireResult.TimedOut(1), s.acquire(h1, "/w", setOf(Category.PHONE), 1.seconds) {})
        assertEquals(null, s.snapshot.value.holds[DeviceKey("W")])
    }

    /** Final review #11: being removed from the queue mid-wait is its own answer, not "timed out at 0". */
    @Test fun removedFromQueueWhileWaiting() = runTest {
        val s = started()
        s.acquire(h2, "/w", watch, 1.minutes) {}
        val r = async { s.acquire(h1, "/w", watch, 5.minutes) {} }
        s.snapshot.first { h1 in it.queue }
        s.removeFromQueue(h1)
        assertEquals(AcquireResult.Removed, r.await())
    }

    /** Ruling X: the hook cwd follows `cd`, so project defaults and the Projects row key on the project root. */
    @Test fun projectDefaultKeyedByProjectRoot() = runTest {
        val proj = home.resolve("proj").also { java.nio.file.Files.createDirectories(it.resolve(".git")) }
        val sub = java.nio.file.Files.createDirectories(proj.resolve("app/src"))
        projects.setDefault(proj.toString(), Category.WATCH)
        val s = started()
        s.preToolUse(HookInput("PreToolUse", "s1", null, null, sub.toString(), "Bash", "adb shell ls"))
        assertEquals(DeviceKey("W"), assertIs<HolderStatus.Holding>(s.status(h1)).device)
        s.acquire(h2, sub.toString(), emptySet(), 1.seconds) {}
        assertEquals(listOf(proj.toString()), projects.all.first().map { it.path })
    }

    @Test fun releaseAndBlockPersistsAndAcquireRefuses() = runTest {
        val s = started()
        s.acquire(h1, "/w", watch, 1.minutes) {}
        s.releaseDevice(DeviceKey("W"), block = true)
        assertEquals(AcquireResult.Blocked, s.acquire(h1, "/w", watch, 1.minutes) {})
        waitFor { h1 in blocked.all() }
        s.unblock(h1)
        waitFor { h1 !in blocked.all() }
    }

    @Test fun blockedHoldersLoadedAtStart() = runTest {
        blocked.add(h1)
        val s = started()
        assertEquals(HolderStatus.Blocked, s.status(h1))
    }

    @Test fun eventsPersisted() = runTest {
        val s = started()
        s.acquire(h1, "/w", watch, 1.minutes) {}
        waitFor { activity.recent().first().any { it.kind == "granted" && it.holderKey == "s1:main" } }
    }

    @Test fun preToolUseRoutesAndTracksHolderInfo() = runTest {
        projects.setDefault("/w", Category.WATCH)
        val s = started()
        val sub = HolderId("s1", "a1")
        val d = s.preToolUse(bash("adb shell ls", agent = "a1", agentType = "general-purpose"))
        assertEquals(pinPrefix("w-usb") + "adb shell ls", assertIs<HookReply.Rewrite>(d.reply).command)
        assertEquals(Decision.ASK, d.permission)
        val info = s.holderInfo.value.getValue(sub)
        assertEquals("general-purpose", info.agentType)
        assertEquals("adb shell ls", info.lastDeviceCommand)
        assertEquals("/w", info.cwd)
        waitFor { activity.recent().first().any { it.kind == "command" && it.text == "adb shell ls" } }

        val edit = HookInput("PreToolUse", "s1", "a1", "general-purpose", "/w", "Edit", null)
        assertEquals(HookDecision(HookReply.NoOpinion, null), s.preToolUse(edit))
        assertTrue(assertIs<HolderStatus.Holding>(s.status(sub)).editing)
        assertEquals(HookDecision(HookReply.NoOpinion, null), s.preToolUse(HookInput("PreToolUse", "s1", null, null, "/w", "Read", null)))
    }

    @Test fun mirrorPermissionAppliedOnlyToRewrites() = runTest {
        projects.setDefault("/w", Category.WATCH)
        val s = started()
        val bypass = s.preToolUse(bash("adb shell ls").copy(permissionMode = "bypassPermissions"))
        assertIs<HookReply.Rewrite>(bypass.reply)
        assertEquals(Decision.ALLOW, bypass.permission)
        val home = java.nio.file.Files.createTempDirectory("cdm-h")
        java.nio.file.Files.createDirectories(home.resolve(".claude"))
        home.resolve(".claude/settings.json").toFile().writeText("{\"permissions\":{\"allow\":[\"Bash\"]}}")
        val s2 = DeviceManagerService(DeviceSource(DeviceTracker(adb), null, backgroundScope), configs, projects, settings, activity, blocked, PermissionMirror(home), clock = { now })
        s2.start(backgroundScope)
        val plain = s2.preToolUse(HookInput("PreToolUse", "s9", null, null, "/w", "Bash", "echo hi"))
        assertEquals(HookDecision(HookReply.NoOpinion, null), plain)
        val edit = s2.preToolUse(HookInput("PreToolUse", "s9", null, null, "/w", "Edit", null))
        assertEquals(HookDecision(HookReply.NoOpinion, null), edit)
    }

    private fun allowRule(rule: String) {
        java.nio.file.Files.createDirectories(home.resolve(".claude"))
        home.resolve(".claude/settings.json").toFile().writeText("{\"permissions\":{\"allow\":[\"$rule\"]}}")
    }

    private suspend fun TestScope.pinnedPermissionFor(mode: PinnedPermission, cmd: String = "adb shell ls"): HookDecision {
        projects.setDefault("/w", Category.WATCH)
        settings.update { it.copy(pinnedPermission = mode) }
        val d = started().preToolUse(bash(cmd))
        assertIs<HookReply.Rewrite>(d.reply)
        return d
    }

    @Test fun pinnedMirrorAsksWithoutAllowRule() = runTest {
        assertEquals(Decision.ASK, pinnedPermissionFor(PinnedPermission.MIRROR).permission)
    }

    @Test fun pinnedAlwaysAllowAllowsWithoutAllowRule() = runTest {
        assertEquals(Decision.ALLOW, pinnedPermissionFor(PinnedPermission.ALWAYS_ALLOW).permission)
    }

    @Test fun pinnedAlwaysAskAsksWithoutAllowRule() = runTest {
        assertEquals(Decision.ASK, pinnedPermissionFor(PinnedPermission.ALWAYS_ASK).permission)
    }

    @Test fun pinnedMirrorAllowsWithAllowRule() = runTest {
        allowRule("Bash(adb:*)")
        assertEquals(Decision.ALLOW, pinnedPermissionFor(PinnedPermission.MIRROR).permission)
    }

    @Test fun pinnedAlwaysAskAsksEvenWithAllowRule() = runTest {
        allowRule("Bash(adb:*)")
        assertEquals(Decision.ASK, pinnedPermissionFor(PinnedPermission.ALWAYS_ASK).permission)
    }

    @Test fun cdmOnlyRewriteStaysAllowedUnderAlwaysAsk() = runTest {
        val d = pinnedPermissionFor(PinnedPermission.ALWAYS_ASK, "cdm status")
        assertEquals(RewriteKind.CDM, assertIs<HookReply.Rewrite>(d.reply).kind)
        assertEquals(Decision.ALLOW, d.permission)
    }

    @Test fun nonRewriteHasNoPermissionInAnyMode() = runTest {
        settings.update { it.copy(pinnedPermission = PinnedPermission.ALWAYS_ALLOW) }
        val s = started()
        assertEquals(HookDecision(HookReply.NoOpinion, null), s.preToolUse(bash("echo hi")))
        settings.update { it.copy(pinnedPermission = PinnedPermission.ALWAYS_ASK) }
        assertEquals(HookDecision(HookReply.NoOpinion, null), s.preToolUse(bash("echo hi", session = "s2")))
    }

    @Test fun subagentStopReleasesAndEndedHoldersDropAfterAnHour() = runTest {
        val s = DeviceManagerService(
            DeviceSource(DeviceTracker(adb), null, backgroundScope), configs, projects, settings, activity, blocked, mirror, clock = { now },
            scanInterval = 2.seconds, tickInterval = 5.seconds,
        )
        s.start(backgroundScope)
        s.snapshot.first { it.devices.size == 2 }
        val sub = HolderId("s1", "a1")
        s.acquire(sub, "/w", watch, 1.minutes) {}
        s.subagentStop("s1", "a1")
        assertEquals(HolderStatus.None, s.status(sub))
        assertTrue(s.holderInfo.value.getValue(sub).ended)
        now = t0 + 2.hours
        s.holderInfo.first { sub !in it }
    }

    @Test fun sessionEndReleasesEveryHolderInSession() = runTest {
        val s = started()
        s.acquire(h1, "/w", watch, 1.minutes) {}
        s.acquire(HolderId("s1", "a1"), "/w", setOf(Category.PHONE), 1.minutes) {}
        s.sessionEnd("s1")
        assertTrue(s.snapshot.value.holds.isEmpty())
        assertTrue(s.holderInfo.value.values.all { it.ended })
    }

    @Test fun tickUsesTimersFromSettings() = runTest {
        settings.update { it.copy(idleMinutes = 1) }
        val s = started()
        s.acquire(h1, "/w", watch, 1.minutes) {}
        now = t0 + 2.minutes
        s.snapshot.first { it.holds.isEmpty() }
        assertIs<HolderStatus.Released>(s.status(h1))
    }

    @Test fun userActionsReachAllocatorAndPersist() = runTest {
        val s = started()
        s.setDontUse(DeviceKey("W"), dontUse = true, releaseNow = true)
        assertTrue(s.snapshot.value.devices.single { it.key == DeviceKey("W") }.dontUse)
        assertTrue(configs.all.first().single { it.key == DeviceKey("W") }.dontUse)
        s.setCategory(DeviceKey("P"), Category.WATCH)
        assertEquals(Category.WATCH, s.snapshot.value.devices.single { it.key == DeviceKey("P") }.category)
        assertEquals(AcquireResult.Granted("p-usb", "Pixel 8", Platform.ANDROID), s.acquire(h1, "/w", watch, 1.minutes) {})
        s.handOff(DeviceKey("P"), h2)
        assertEquals(DeviceKey("P"), assertIs<HolderStatus.Holding>(s.status(h2)).device)
    }

    @Test fun settingsErrorDoesNotStopLaterTimerUpdates() = runTest {
        val dao = object : SettingDao {
            var subscriptions = 0
            override fun observeAll() = flow {
                subscriptions++
                if (subscriptions == 1) {
                    emit(emptyList())
                    throw java.io.IOException("db hiccup")
                }
                emit(listOf(SettingEntity("idleMinutes", "1")))
            }
            override suspend fun getAll() = emptyList<SettingEntity>()
            override suspend fun put(entity: SettingEntity) {}
        }
        val s = started(settings = SettingsRepository(dao))
        s.acquire(h1, "/w", watch, 1.minutes) {}
        now = t0 + 2.minutes
        s.snapshot.first { it.holds.isEmpty() } // only the post-error emission sets idle = 1 min
        assertTrue(dao.subscriptions >= 2)
    }

    @Test fun failedConfigWriteLeavesAllocatorUnchanged() = runTest {
        val dao = object : DeviceConfigDao {
            override fun observeAll() = flowOf(emptyList<DeviceConfigEntity>())
            override suspend fun get(serial: String): DeviceConfigEntity? = null
            override suspend fun put(entity: DeviceConfigEntity) = throw java.io.IOException("disk full")
        }
        val s = started(configs = DeviceConfigRepository(dao))
        s.setDontUse(DeviceKey("W"), dontUse = true, releaseNow = true)
        s.setCategory(DeviceKey("P"), Category.WATCH)
        val devices = s.snapshot.value.devices
        assertTrue(!devices.single { it.key == DeviceKey("W") }.dontUse)
        assertEquals(Category.PHONE, devices.single { it.key == DeviceKey("P") }.category)
    }

    @Test fun cancelledAcquireDetachesWaiterButKeepsQueueSpot() = runTest {
        val s = started()
        s.acquire(h2, "/w", watch, 1.minutes) {}
        val waiting = async { s.acquire(h1, "/w", watch, 5.minutes) {} }
        s.snapshot.first { h1 in it.queue }
        waiting.cancelAndJoin()
        assertIs<HolderStatus.Queued>(s.status(h1))
        // Detached: the 2 min queue grace runs out on the next tick. An attached waiter would stay queued.
        now = t0 + 3.minutes
        s.snapshot.first { h1 !in it.queue }
        assertEquals(HolderStatus.None, s.status(h1))
    }

    /** Ruling W per platform: a flagged acquire gives back only that platform's hold when it doesn't fit. */
    @Test fun rulingWPerPlatform() = runTest {
        val s = startedWithIos()
        val phone = Wants(setOf(Category.PHONE), Platform.IOS)
        assertEquals(AcquireResult.Granted(iphone.hardwareSerial, "iPhone 16 Pro · iOS 18.2", Platform.IOS), s.acquire(h1, "/w", phone, 1.minutes) {})
        // --ios --category watch: the iPhone doesn't fit, so it goes back and the watch simulator is granted.
        val w = s.acquire(h1, "/w", Wants(watch, Platform.IOS), 1.minutes) {}
        assertEquals(AcquireResult.Granted(watchSim.hardwareSerial, "Apple Watch Series 10 (46mm) · watchOS 11.2", Platform.IOS), w)
        assertEquals(null, s.snapshot.value.holds[iphone])
        // --android: adds an Android hold; the iOS watch stays.
        val a = s.acquire(h1, "/w", Wants(setOf(Category.ANY), Platform.ANDROID), 1.minutes) {}
        assertEquals(Platform.ANDROID, assertIs<AcquireResult.Granted>(a).platform)
        assertEquals(h1, s.snapshot.value.holds[watchSim])
        assertEquals(setOf(Platform.ANDROID, Platform.IOS), s.holdings(h1).map { it.platform }.toSet())
    }

    /** A holder with an Android hold that queued for iOS gets the iOS UDID back, not its Android serial. */
    @Test fun queuedIosGrantReturnsIosUdid() = runTest {
        val s = startedWithIos()
        val phone = Wants(setOf(Category.PHONE), Platform.IOS)
        s.acquire(h2, "/w", phone, 1.minutes) {}
        assertEquals(AcquireResult.Granted("p-usb", "Pixel 8", Platform.ANDROID), s.acquire(h1, "/w", setOf(Category.PHONE), 1.minutes) {})
        val r = async { s.acquire(h1, "/w", phone, 5.minutes) {} }
        s.snapshot.first { h1 in it.queue }
        s.release(h2)
        assertEquals(AcquireResult.Granted(iphone.hardwareSerial, "iPhone 16 Pro · iOS 18.2", Platform.IOS), r.await())
        assertEquals(h1, s.snapshot.value.holds[DeviceKey("P")])
    }

    @Test fun releaseIosKeepsAndroid() = runTest {
        val s = startedWithIos()
        s.acquire(h1, "/w", Wants(setOf(Category.PHONE), Platform.ANDROID), 1.minutes) {}
        s.acquire(h1, "/w", Wants(setOf(Category.PHONE), Platform.IOS), 1.minutes) {}
        assertEquals(2, s.holdings(h1).size)
        s.release(h1, Platform.IOS)
        assertEquals(listOf(Platform.ANDROID), s.holdings(h1).map { it.platform })
        s.release(h1)
        assertTrue(s.holdings(h1).isEmpty())
    }

    /** --android releases only a queue entry that could be answered on Android; an iOS-pinned one stays. */
    @Test fun releaseAndroidKeepsIosQueueEntry() = runTest {
        val s = startedWithIos()
        val phone = Wants(setOf(Category.PHONE), Platform.IOS)
        s.acquire(h2, "/w", phone, 1.minutes) {}
        s.acquire(h1, "/w", setOf(Category.PHONE), 1.minutes) {}
        assertEquals(AcquireResult.TimedOut(1), s.acquire(h1, "/w", phone, 1.seconds) {})
        s.release(h1, Platform.ANDROID)
        assertTrue(h1 in s.snapshot.value.queue)
        assertTrue(s.holdings(h1).isEmpty())
        s.release(h1, Platform.IOS)
        assertTrue(h1 !in s.snapshot.value.queue)
    }

    @Test fun acquireRealFlagSetsVirtualFalse() = runTest {
        val s = startedWithIos()
        // Only simulators on iOS: --real waits instead of taking one.
        assertEquals(AcquireResult.TimedOut(1), s.acquire(h1, "/w", Wants(setOf(Category.PHONE), Platform.IOS, virtual = false), 1.seconds) {})
        assertTrue(s.holdings(h1).isEmpty())
        assertEquals(Wants(setOf(Category.PHONE), Platform.IOS, false), assertIs<HolderStatus.Queued>(s.status(h1)).wants)
    }

    /** Polls a Room-backed condition; Room answers on real threads. */
    private suspend fun waitFor(cond: suspend () -> Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) {
                while (!cond()) kotlinx.coroutines.delay(10)
            }
        }
    }
}
