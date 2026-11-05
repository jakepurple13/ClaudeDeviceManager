package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.AllocDevice
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.allocator.Timers
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.devices.TrackedDevice
import com.jacobrein.cdm.service.HolderInfo
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
                h1 to HolderStatus.Queued(1, setOf(Category.WATCH)),
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
}
