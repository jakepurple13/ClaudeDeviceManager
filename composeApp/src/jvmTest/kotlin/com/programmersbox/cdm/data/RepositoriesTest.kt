package com.programmersbox.cdm.data

import com.programmersbox.cdm.core.allocator.AllocatorEvent
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class RepositoriesTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun projectCreatedWithAnyOnFirstSight() = runTest {
        val db = inMemoryDatabase()
        val repo = ProjectRepository(db.projectDao())
        assertEquals(Category.ANY, repo.defaultCategoryFor("/x/app"))
        assertEquals(listOf(Project("/x/app", Category.ANY)), repo.all.first())
        repo.setDefault("/x/app", Category.WATCH)
        assertEquals(Category.WATCH, repo.defaultCategoryFor("/x/app"))
        db.close()
    }

    @Test
    fun deviceOverridePersists() = runTest {
        val db = inMemoryDatabase()
        val repo = DeviceConfigRepository(db.deviceConfigDao())
        val k = DeviceKey("S1")
        repo.upsertSeen(k, now)
        repo.setCategory(k, Category.WATCH)
        repo.setDontUse(k, true)
        repo.rename(k, "Pixel")
        repo.upsertSeen(k, now.plus(kotlin.time.Duration.parse("1m")))
        val c = repo.all.first().single()
        assertEquals(Category.WATCH, c.categoryOverride)
        assertTrue(c.dontUse)
        assertEquals("Pixel", c.displayName)
        assertEquals(now.plus(kotlin.time.Duration.parse("1m")), c.lastSeen)
        repo.setCategory(k, null)
        assertEquals(null, repo.all.first().single().categoryOverride)
        db.close()
    }

    @Test
    fun settingsDefaults() = runTest {
        val db = inMemoryDatabase()
        val repo = SettingsRepository(db.settingDao())
        assertEquals(AppSettings(47823, 15, 5), repo.current())
        repo.update { it.copy(idleMinutes = 30) }
        assertEquals(AppSettings(47823, 30, 5), repo.settings.first())
        assertEquals(PinnedPermission.MIRROR, repo.current().pinnedPermission)
        repo.update { it.copy(pinnedPermission = PinnedPermission.ALWAYS_ASK) }
        assertEquals(AppSettings(47823, 30, 5, PinnedPermission.ALWAYS_ASK), repo.current())
        assertEquals(NotificationSettings(true, true, true, true), repo.current().notifications)
        repo.update { it.copy(notifications = NotificationSettings(grab = false, release = true, queued = false, queueStart = true)) }
        assertEquals(NotificationSettings(false, true, false, true), repo.current().notifications)
        assertEquals(PinnedPermission.ALWAYS_ASK, repo.current().pinnedPermission)
        db.close()
    }

    @Test
    fun activityAndBlocked() = runTest {
        val db = inMemoryDatabase()
        val a = ActivityRepository(db.activityDao())
        val h = HolderId("s1")
        a.record(AllocatorEvent.Granted(now, h, DeviceKey("D")))
        a.recordCommand(h, DeviceKey("D"), "adb devices", now)
        val rows = a.recent().first()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.kind == "command" && it.text == "adb devices" })
        val b = BlockedRepository(db.blockedDao())
        b.add(h)
        assertEquals(setOf(h), b.all())
        b.remove(h)
        assertEquals(emptySet(), b.all())
        db.close()
    }
}
