package com.programmersbox.cdm.ui

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HeldSet
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.SetMember
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.service.HolderInfo
import com.programmersbox.cdm.ui.sessions.HolderState
import com.programmersbox.cdm.ui.sessions.SessionsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() {
        vms.forEach { it.closeForTest() }
        Dispatchers.resetMain()
    }

    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val s1Main = HolderId("s1")
    private val s1Agent = HolderId("s1", "agent-1")
    private val s2Main = HolderId("s2")
    private val s3Main = HolderId("s3")
    private val phone = DeviceKey("P")

    private fun info(h: HolderId, at: Instant, agentType: String? = null, ended: Boolean = false) =
        HolderInfo(h, "/work/gabb-messenger", agentType, null, at, ended)

    @Test
    fun groupsAgentsUnderSession() = runBlocking {
        val snap = AllocatorSnapshot(
            devices = listOf(AllocDevice(phone, "Pixel 8", Category.PHONE, false, listOf("p-usb"))),
            holds = mapOf(phone to s1Agent),
            queue = listOf(s1Main),
            statuses = mapOf(
                s1Agent to HolderStatus.Holding(phone, t, t, editing = false),
                s1Main to HolderStatus.Queued(1, Wants(setOf(Category.PHONE))),
                s2Main to HolderStatus.Blocked,
            ),
        )
        // Subagent first in the map: the grouping must still put the main agent first.
        val infos = linkedMapOf(
            s1Agent to info(s1Agent, t + 1.seconds, agentType = "Explore"),
            s1Main to info(s1Main, t),
            s2Main to info(s2Main, t - 1.minutes),
        )
        val manager = RecordingManager(MutableStateFlow(snap), MutableStateFlow(infos))
        val vm = SessionsViewModel(testActions(manager), testSources(manager)).also { vms += it }

        val groups = withTimeout(5_000) { vm.uiState.mapNotNull { it.groups }.first() }

        assertEquals(2, groups.size)
        val s1 = groups.single { it.sessionId == "s1" }
        assertEquals(listOf("main", "agent-1"), s1.holders.map { it.holder.agentId })
        assertEquals(listOf(HolderState.WAITING, HolderState.HOLDING), s1.holders.map { it.state })
        assertEquals(HolderState.BLOCKED, groups.single { it.sessionId == "s2" }.holders.single().state)

        vm.onUnblock(s2Main)
        assertEquals(listOf(s2Main), manager.unblocked)
    }

    @Test
    fun endedSessionsSortAfterLiveOnes() = runBlocking {
        val snap = AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap())
        val infos = mapOf(
            s3Main to info(s3Main, t + 5.minutes, ended = true),
            s1Main to info(s1Main, t),
        )
        val manager = RecordingManager(MutableStateFlow(snap), MutableStateFlow(infos))
        val vm = SessionsViewModel(testActions(manager), testSources(manager)).also { vms += it }

        val groups = withTimeout(5_000) { vm.uiState.mapNotNull { it.groups }.first() }

        assertEquals(listOf("s1", "s3"), groups.map { it.sessionId })
        assertEquals(HolderState.ENDED, groups.last().holders.single().state)
        assertEquals(HolderState.IDLE, groups.first().holders.single().state)
    }

    @Test
    fun sessionRowListsTwoDevices() = runBlocking {
        val sim = DeviceKey("UDID-1")
        val android = HolderStatus.Holding(phone, t, t, editing = false)
        val ios = HolderStatus.Holding(sim, t, t, editing = false, platform = Platform.IOS)
        val snap = AllocatorSnapshot(
            devices = listOf(
                AllocDevice(phone, "Pixel 8", Category.PHONE, false, listOf("p-usb")),
                AllocDevice(sim, "iPhone 16 · iOS 18.2", Category.PHONE, false, listOf("UDID-1"), Platform.IOS, virtual = true),
            ),
            holds = mapOf(phone to s1Main, sim to s1Main),
            queue = emptyList(),
            statuses = mapOf(s1Main to android),
            holdings = mapOf(s1Main to listOf(android, ios)),
        )
        val manager = RecordingManager(MutableStateFlow(snap), MutableStateFlow(mapOf(s1Main to info(s1Main, t))))
        val vm = SessionsViewModel(testActions(manager), testSources(manager)).also { vms += it }

        val row = withTimeout(5_000) { vm.uiState.mapNotNull { it.groups }.first() }.single().holders.single()

        assertEquals("Pixel 8 · idle 0s + iPhone 16 · iOS 18.2 · idle 0s", row.detail)
    }

    private fun snapWith(statuses: Map<HolderId, HolderStatus>, holdings: List<HolderStatus.Holding>, queue: List<HolderId> = emptyList()) =
        AllocatorSnapshot(
            devices = listOf(
                AllocDevice(phone, "Pixel 8", Category.PHONE, false, listOf("p-usb")),
                AllocDevice(DeviceKey("UDID-1"), "iPhone 16", Category.PHONE, false, listOf("UDID-1"), Platform.IOS, virtual = true),
            ),
            holds = holdings.associate { it.device to s1Main },
            queue = queue,
            statuses = statuses,
            holdings = mapOf(s1Main to holdings),
        )

    private fun rowFor(snap: AllocatorSnapshot) = runBlocking {
        val manager = RecordingManager(MutableStateFlow(snap), MutableStateFlow(mapOf(s1Main to info(s1Main, t))))
        val vm = SessionsViewModel(testActions(manager), testSources(manager, FixedClock(t + 7.minutes))).also { vms += it }
        withTimeout(5_000) { vm.uiState.mapNotNull { it.groups }.first() }.single().holders.single()
    }

    /** Final whole-branch review: each hold shows its own timer. */
    @Test
    fun sessionRowShowsEachHoldsTimer() {
        val android = HolderStatus.Holding(phone, t, t + 6.minutes, editing = false)
        val ios = HolderStatus.Holding(DeviceKey("UDID-1"), t, t, editing = false, platform = Platform.IOS)
        val row = rowFor(snapWith(mapOf(s1Main to android), listOf(android, ios)))
        assertEquals("Pixel 8 · idle 1m + iPhone 16 · idle 7m", row.detail)
    }

    /** Final whole-branch review: queued for iOS while holding Android still shows the Android hold. */
    @Test
    fun queuedForOnePlatformStillShowsHeldDevice() {
        val android = HolderStatus.Holding(phone, t, t + 6.minutes, editing = false)
        val queued = HolderStatus.Queued(1, Wants(setOf(Category.PHONE), Platform.IOS))
        val row = rowFor(snapWith(mapOf(s1Main to queued), listOf(android), queue = listOf(s1Main)))
        assertEquals(HolderState.WAITING, row.state)
        assertEquals("#1 in queue · Pixel 8 · idle 1m", row.detail)
    }

    @Test
    fun sessionsGroupSetMembers() = runBlocking {
        val ios = DeviceKey("I")
        val held = HeldSet(
            listOf(SetMember("android1", Platform.ANDROID, phone), SetMember("ios1", Platform.IOS, ios)),
            t, t - 1.minutes, editing = false,
        )
        val snap = AllocatorSnapshot(
            devices = listOf(
                AllocDevice(phone, "Pixel 8", Category.PHONE, false, listOf("p-usb")),
                AllocDevice(ios, "iPhone 16", Category.PHONE, false, listOf("i"), platform = Platform.IOS),
            ),
            holds = mapOf(phone to s1Main, ios to s1Main),
            queue = emptyList(),
            statuses = mapOf(s1Main to HolderStatus.HoldingSet(held)),
            sets = mapOf(s1Main to held),
        )
        val manager = RecordingManager(MutableStateFlow(snap), MutableStateFlow(mapOf(s1Main to info(s1Main, t))))
        val vm = SessionsViewModel(testActions(manager), testSources(manager)).also { vms += it }

        val row = withTimeout(5_000) { vm.uiState.mapNotNull { it.groups }.first() }.single().holders.single()

        assertEquals(HolderState.HOLDING, row.state)
        assertEquals(listOf("android1" to "Pixel 8", "ios1" to "iPhone 16"), row.members.map { it.name to it.device })
        assertEquals("idle 1m", row.timerLabel)
    }
}
