package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.data.ActivityRepository
import com.jacobrein.cdm.data.AppDatabase
import com.jacobrein.cdm.data.DeviceConfigRepository
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.data.inMemoryDatabase
import com.jacobrein.cdm.server.FakeManager
import com.jacobrein.cdm.service.DeviceManager
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** A fake [DeviceManager] that records the UI actions. */
class RecordingManager(override val snapshot: MutableStateFlow<AllocatorSnapshot>) : FakeManager() {
    val moves = mutableListOf<Pair<HolderId, Int>>()
    val dontUseCalls = mutableListOf<Triple<DeviceKey, Boolean, Boolean>>()
    val releases = mutableListOf<Pair<DeviceKey, Boolean>>()
    val handOffs = mutableListOf<Pair<DeviceKey, HolderId>>()
    val removed = mutableListOf<HolderId>()
    val categories = mutableListOf<Pair<DeviceKey, Category?>>()

    override suspend fun moveInQueue(h: HolderId, index: Int) { moves += h to index }
    override suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean) { dontUseCalls += Triple(key, dontUse, releaseNow) }
    override suspend fun releaseDevice(key: DeviceKey, block: Boolean) { releases += key to block }
    override suspend fun handOff(key: DeviceKey, to: HolderId) { handOffs += key to to }
    override suspend fun removeFromQueue(h: HolderId) { removed += h }
    override suspend fun setCategory(key: DeviceKey, category: Category?) { categories += key to category }
}

/** A clock that only moves when the test says so. */
class FixedClock(start: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)) : UiClock {
    val time = MutableStateFlow(start)
    override fun now(): Instant = time.value
    override val ticks: Flow<Instant> get() = time
}

/**
 * An in-memory database that is already open. Several flows subscribing at once to a fresh one can race its
 * creation and fail with "no such table", so run one query first.
 */
fun openedInMemoryDatabase(): AppDatabase = inMemoryDatabase().also { db ->
    runBlocking { DeviceConfigRepository(db.deviceConfigDao()).all.first() }
}

fun testSources(manager: DeviceManager, clock: UiClock = FixedClock()): UiSources {
    val db = openedInMemoryDatabase()
    return UiSources(
        manager,
        DeviceConfigRepository(db.deviceConfigDao()),
        ActivityRepository(db.activityDao()),
        SettingsRepository(db.settingDao()),
        clock,
    )
}
