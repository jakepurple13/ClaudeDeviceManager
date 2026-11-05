package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.data.ActivityRepository
import com.jacobrein.cdm.data.AppDatabase
import com.jacobrein.cdm.data.BlockedRepository
import com.jacobrein.cdm.data.DeviceConfigDao
import com.jacobrein.cdm.data.DeviceConfigEntity
import com.jacobrein.cdm.data.DeviceConfigRepository
import com.jacobrein.cdm.data.SettingDao
import com.jacobrein.cdm.data.SettingEntity
import com.jacobrein.cdm.data.ProjectRepository
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.data.inMemoryDatabase
import com.jacobrein.cdm.devices.DeviceTracker
import com.jacobrein.cdm.devices.FakeAdb
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
    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val watch = setOf(Category.WATCH)

    @AfterTest fun close() = db.close()

    private suspend fun TestScope.started(
        scope: CoroutineScope = backgroundScope,
        configs: DeviceConfigRepository = this@DeviceManagerServiceTest.configs,
        settings: SettingsRepository = this@DeviceManagerServiceTest.settings,
    ): DeviceManagerService {
        val s = DeviceManagerService(DeviceTracker(adb), configs, projects, settings, activity, blocked, clock = { now })
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
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch"), s.acquire(h1, "/w", watch, 1.minutes) {})
    }

    @Test fun acquireWithNoWantsUsesProjectDefault() = runTest {
        projects.setDefault("/w", Category.WATCH)
        val s = started()
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch"), s.acquire(h1, "/w", emptySet(), 1.minutes) {})
    }

    @Test fun acquireWaitsReportsPositionThenGrants() = runTest {
        val s = started()
        s.acquire(h2, "/w", watch, 1.minutes) {}
        val positions = mutableListOf<Int>()
        val r = async { s.acquire(h1, "/w", watch, 5.minutes) { positions += it } }
        s.snapshot.first { h1 in it.queue }
        s.release(h2)
        assertEquals(AcquireResult.Granted("w-usb", "Pixel Watch"), r.await())
        assertEquals(listOf(1), positions)
    }

    @Test fun acquireTimesOutWithPositionAndKeepsSpot() = runTest {
        val s = started()
        s.acquire(h2, "/w", watch, 1.minutes) {}
        assertEquals(AcquireResult.TimedOut(1), s.acquire(h1, "/w", watch, 30.seconds) {})
        assertIs<HolderStatus.Queued>(s.status(h1))
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
        val r = s.preToolUse(bash("adb shell ls", agent = "a1", agentType = "general-purpose"))
        assertEquals(pinPrefix("w-usb") + "adb shell ls", assertIs<HookReply.Rewrite>(r).command)
        val info = s.holderInfo.value.getValue(sub)
        assertEquals("general-purpose", info.agentType)
        assertEquals("adb shell ls", info.lastDeviceCommand)
        assertEquals("/w", info.cwd)
        waitFor { activity.recent().first().any { it.kind == "command" && it.text == "adb shell ls" } }

        val edit = HookInput("PreToolUse", "s1", "a1", "general-purpose", "/w", "Edit", null)
        assertEquals(HookReply.NoOpinion, s.preToolUse(edit))
        assertTrue(assertIs<HolderStatus.Holding>(s.status(sub)).editing)
        assertEquals(HookReply.NoOpinion, s.preToolUse(HookInput("PreToolUse", "s1", null, null, "/w", "Read", null)))
    }

    @Test fun subagentStopReleasesAndEndedHoldersDropAfterAnHour() = runTest {
        val s = DeviceManagerService(
            DeviceTracker(adb), configs, projects, settings, activity, blocked, clock = { now },
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
        assertEquals(AcquireResult.Granted("p-usb", "Pixel 8"), s.acquire(h1, "/w", watch, 1.minutes) {})
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

    /** Polls a Room-backed condition; Room answers on real threads. */
    private suspend fun waitFor(cond: suspend () -> Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) {
                while (!cond()) kotlinx.coroutines.delay(10)
            }
        }
    }
}
