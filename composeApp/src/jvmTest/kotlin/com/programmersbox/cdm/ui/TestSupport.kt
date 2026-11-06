package com.programmersbox.cdm.ui

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.data.ActivityRepository
import com.programmersbox.cdm.data.AppDatabase
import com.programmersbox.cdm.data.DeviceConfigRepository
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.data.inMemoryDatabase
import com.programmersbox.cdm.server.FakeManager
import com.programmersbox.cdm.service.DeviceManager
import com.programmersbox.cdm.service.HolderInfo
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout

/** A fake [DeviceManager] that records the UI actions. */
class RecordingManager(
    override val snapshot: MutableStateFlow<AllocatorSnapshot>,
    override val holderInfo: MutableStateFlow<Map<HolderId, HolderInfo>> = MutableStateFlow(emptyMap()),
    override val tracked: MutableStateFlow<List<com.programmersbox.cdm.devices.TrackedDevice>> = MutableStateFlow(emptyList()),
) : FakeManager() {
    val moves = mutableListOf<Pair<HolderId, Int>>()
    val dontUseCalls = mutableListOf<Triple<DeviceKey, Boolean, Boolean>>()
    val releases = mutableListOf<Pair<DeviceKey, Boolean>>()
    val handOffs = mutableListOf<Pair<DeviceKey, HolderId>>()
    val removed = mutableListOf<HolderId>()
    val categories = mutableListOf<Pair<DeviceKey, Category?>>()
    val unblocked = mutableListOf<HolderId>()

    override suspend fun moveInQueue(h: HolderId, index: Int) { moves += h to index }
    override suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean) { dontUseCalls += Triple(key, dontUse, releaseNow) }
    override suspend fun releaseDevice(key: DeviceKey, block: Boolean) { releases += key to block }
    override suspend fun handOff(key: DeviceKey, to: HolderId) { handOffs += key to to }
    override suspend fun removeFromQueue(h: HolderId) { removed += h }
    override suspend fun setCategory(key: DeviceKey, category: Category?) { categories += key to category }
    override suspend fun unblock(h: HolderId) { unblocked += h }
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

/** [UiActions] on a scope that runs launched actions at once unless the test passes its own. */
@OptIn(ExperimentalCoroutinesApi::class)
fun testActions(manager: DeviceManager, scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher())) = UiActions(manager, scope)

/**
 * Cancels a view model's scope and waits for it, so none of its Room queries resume on a later test's Main
 * dispatcher (which fails that test with "uncaught exceptions before the test started"). Call before resetMain.
 */
fun ViewModel.closeForTest() = runBlocking {
    withTimeout(5_000) { viewModelScope.coroutineContext.job.cancelAndJoin() }
}
