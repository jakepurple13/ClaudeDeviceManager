package com.programmersbox.cdm.ui

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.Timers
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.devices.TrackedDevice
import com.programmersbox.cdm.service.HolderInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UiModelsTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val p = DeviceKey("P1")
    private val w = DeviceKey("W1")
    private val g = DeviceKey("G1")

    private fun dev(k: DeviceKey, name: String, c: Category, dontUse: Boolean = false) =
        AllocDevice(k, name, c, dontUse, listOf("s-${k.hardwareSerial}"))

    private fun tracked(k: DeviceKey, c: Category) = TrackedDevice(k, listOf("s-${k.hardwareSerial}"), "m", "15", "usb", c)

    private fun world(snapshot: AllocatorSnapshot, tracked: List<TrackedDevice>, info: Map<HolderId, HolderInfo> = emptyMap()) =
        World(snapshot, tracked, info, emptyList(), emptyList(), Timers(), now)

    @Test
    fun timerLabelEditing() {
        val h = HolderId("a41f9c00", HolderId.MAIN)
        val snap = AllocatorSnapshot(
            devices = listOf(dev(p, "Pixel 8", Category.PHONE)),
            holds = mapOf(p to h),
            queue = emptyList(),
            statuses = mapOf(h to HolderStatus.Holding(p, now - 10.minutes, now - 1.minutes, editing = true)),
        )
        val row = deviceRows(world(snap, listOf(tracked(p, Category.PHONE)))).single()
        assertEquals("editing · releases in 4m", row.timerLabel)
        assertEquals(DeviceState.IN_USE, row.state)
        assertEquals(10.minutes, row.heldFor)
    }

    @Test
    fun dontUsePendingWhileHeld() {
        val h = HolderId("a41f9c00")
        val snap = AllocatorSnapshot(
            devices = listOf(dev(p, "Pixel 8", Category.PHONE, dontUse = true)),
            holds = mapOf(p to h),
            queue = emptyList(),
            statuses = mapOf(h to HolderStatus.Holding(p, now - 2.minutes, now, editing = false)),
        )
        val row = deviceRows(world(snap, listOf(tracked(p, Category.PHONE)))).single()
        assertEquals(DeviceState.IN_USE, row.state)
        assertEquals(true, row.dontUsePending)
        val free = deviceRows(world(snap.copy(holds = emptyMap(), statuses = emptyMap()), listOf(tracked(p, Category.PHONE)))).single()
        assertEquals(DeviceState.DONT_USE, free.state)
        assertEquals(false, free.dontUsePending)
    }

    @Test
    fun offlineHeld() {
        val h = HolderId("a41f9c00")
        val snap = AllocatorSnapshot(
            devices = listOf(dev(p, "Pixel 8", Category.PHONE).copy(connectedSerials = emptyList())),
            holds = mapOf(p to h),
            queue = emptyList(),
            statuses = mapOf(h to HolderStatus.Holding(p, now - 2.minutes, now - 1.minutes, editing = false)),
        )
        assertEquals(DeviceState.OFFLINE_HELD, deviceRows(world(snap, tracked = emptyList())).single().state)
    }

    /**
     * Final review #13: OFFLINE is reachable, so it stays. Releasing an "offline · held" device leaves it in the
     * allocator, unheld with no serials, until the next scan drops it (up to one scan interval).
     */
    @Test
    fun releasedOfflineDeviceShowsOfflineUntilNextScan() {
        val snap = AllocatorSnapshot(
            devices = listOf(dev(p, "Pixel 8", Category.PHONE).copy(connectedSerials = emptyList())),
            holds = emptyMap(),
            queue = emptyList(),
            statuses = emptyMap(),
        )
        assertEquals(DeviceState.OFFLINE, deviceRows(world(snap, tracked = emptyList())).single().state)
    }

    @Test
    fun waitReasons() {
        val h1 = HolderId("19be0000")
        val h2 = HolderId("77c20000")
        val snap = AllocatorSnapshot(
            devices = listOf(
                dev(p, "Pixel 6a", Category.PHONE),
                dev(w, "Gabb Watch 3", Category.WATCH),
                dev(g, "Galaxy Watch 6", Category.WATCH, dontUse = true),
            ),
            holds = mapOf(w to h2),
            queue = listOf(h1),
            statuses = mapOf(
                h2 to HolderStatus.Holding(w, now - 4.minutes, now, editing = true),
                h1 to HolderStatus.Queued(1, Wants(setOf(Category.WATCH))),
            ),
        )
        val info = mapOf(h2 to HolderInfo(h2, "/src/watch-launcher", null, null, now, ended = false))
        val trackedAll = listOf(tracked(p, Category.PHONE), tracked(w, Category.WATCH), tracked(g, Category.WATCH))
        assertEquals(
            listOf(
                WaitReason("Gabb Watch 3", "held by watch-launcher #77c2 (editing, releases in ≤5m)"),
                WaitReason("Galaxy Watch 6", "marked don't use"),
            ),
            waitReasons(h1, world(snap, trackedAll, info)),
        )
    }

    @Test
    fun holderLabelForSubagent() {
        assertEquals(
            "gabb-messenger #a41f · general-purpose",
            holderLabel(HolderId("a41f9c…", "agent-1"), "/src/gabb-messenger", "general-purpose"),
        )
    }

    private val iph = DeviceKey("UDID-IPHONE")
    private val ipad = DeviceKey("UDID-IPAD")

    private fun iosDev(k: DeviceKey, name: String, c: Category, virtual: Boolean = true, partner: DeviceKey? = null) =
        AllocDevice(k, name, c, false, listOf(k.hardwareSerial), Platform.IOS, virtual, partner)

    private fun iosTracked(k: DeviceKey, c: Category, virtual: Boolean = true) =
        TrackedDevice(k, listOf(k.hardwareSerial), "m", "18.2", "simulator", c, Platform.IOS, virtual)

    private fun emptySnap(devices: List<AllocDevice>) = AllocatorSnapshot(devices, emptyMap(), emptyList(), emptyMap())

    @Test
    fun deviceRowsCarryPlatformAndVirtual() {
        val snap = emptySnap(listOf(dev(p, "Pixel 8", Category.PHONE), iosDev(iph, "iPhone 16 · iOS 18.2", Category.PHONE)))
        val rows = deviceRows(world(snap, listOf(tracked(p, Category.PHONE), iosTracked(iph, Category.PHONE))))
        assertEquals(listOf(Platform.ANDROID, Platform.IOS), rows.map { it.platform })
        assertEquals(listOf(false, true), rows.map { it.virtual })
        assertEquals(listOf("15", "18.2"), rows.map { it.osVersion })
    }

    @Test
    fun rowsGroupedAndroidThenIos() {
        val snap = emptySnap(listOf(iosDev(iph, "iPhone", Category.PHONE), dev(p, "Pixel 8", Category.PHONE), iosDev(ipad, "iPad", Category.TABLET), dev(w, "Watch", Category.WATCH)))
        val rows = deviceRows(world(snap, listOf(tracked(p, Category.PHONE), tracked(w, Category.WATCH), iosTracked(iph, Category.PHONE), iosTracked(ipad, Category.TABLET))))
        assertEquals(listOf("Pixel 8", "Watch", "iPhone", "iPad"), rows.map { it.name })
    }

    @Test
    fun pairedWithNameResolved() {
        val snap = emptySnap(listOf(iosDev(iph, "iPhone 16", Category.PHONE, partner = w), dev(w, "Pixel Watch", Category.WATCH)))
        val rows = deviceRows(world(snap, listOf(iosTracked(iph, Category.PHONE), tracked(w, Category.WATCH))))
        assertEquals("Pixel Watch", rows.single { it.key == iph }.pairedWith)
        assertEquals(null, rows.single { it.key == w }.pairedWith)
    }

    @Test
    fun queueRowShowsLimits() {
        val h = HolderId("a41f")
        val snap = AllocatorSnapshot(
            emptyList(), emptyMap(), listOf(h),
            mapOf(h to HolderStatus.Queued(1, Wants(setOf(Category.PHONE), Platform.IOS, virtual = false))),
        )
        val row = queueRows(world(snap, emptyList())).single()
        assertEquals(listOf("phone", "iOS", "real only"), row.chipLabels)
    }

    @Test
    fun waitReasonNoVirtualWatch() {
        val h = HolderId("a41f")
        val snap = AllocatorSnapshot(
            listOf(dev(w, "Gabb Watch 3", Category.WATCH)), emptyMap(), listOf(h),
            mapOf(h to HolderStatus.Queued(1, Wants(setOf(Category.WATCH), Platform.ANDROID, virtual = true))),
        )
        val reasons = waitReasons(h, world(snap, listOf(tracked(w, Category.WATCH))))
        assertEquals(listOf("no Wear OS emulator running"), reasons.map { it.reason })
    }

    @Test
    fun kindLabels() {
        assertEquals("Real", kindLabel(Platform.ANDROID, virtual = false))
        assertEquals("Real", kindLabel(Platform.IOS, virtual = false))
        assertEquals("Emulator", kindLabel(Platform.ANDROID, virtual = true))
        assertEquals("Simulator", kindLabel(Platform.IOS, virtual = true))
    }

    @Test
    fun noMatchReasonWording() {
        fun r(c: Set<Category>, platform: Platform? = null, virtual: Boolean? = null) = noMatchReason(Wants(c, platform, virtual))
        assertEquals("no iPhone real device connected", r(setOf(Category.PHONE), Platform.IOS, false))
        assertEquals("no iPad simulator running", r(setOf(Category.TABLET), Platform.IOS, true))
        assertEquals("no Apple Watch simulator running", r(setOf(Category.WATCH), Platform.IOS, true))
        assertEquals("no Wear OS emulator running", r(setOf(Category.WATCH), Platform.ANDROID, true))
        assertEquals("no watch emulator or simulator running", r(setOf(Category.WATCH), virtual = true))
        assertEquals("no Android phone/tablet real device connected", r(setOf(Category.PHONE, Category.TABLET), Platform.ANDROID, false))
        assertEquals("no iOS device connected", r(setOf(Category.ANY), Platform.IOS))
    }
}
