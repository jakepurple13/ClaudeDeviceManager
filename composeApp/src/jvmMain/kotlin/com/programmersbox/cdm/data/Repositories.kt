package com.programmersbox.cdm.data

import com.programmersbox.cdm.core.allocator.AllocatorEvent
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DeviceConfig(
    val key: DeviceKey,
    val displayName: String?,
    val categoryOverride: Category?,
    val dontUse: Boolean,
    val lastSeen: Instant,
)

data class Project(val path: String, val defaultCategory: Category)

/**
 * The permission answer for a command the hook pinned to a device (see PermissionMirror). [MIRROR] applies the
 * user's own Claude Code rules to the original command; [ALWAYS_ALLOW] and [ALWAYS_ASK] override them. Claude Code
 * still enforces its own deny rules even when the hook says allow.
 */
enum class PinnedPermission { MIRROR, ALWAYS_ALLOW, ALWAYS_ASK }

/**
 * Which `adb` the app runs. [AUTO] uses the user's own adb when one is found and the bundled copy otherwise;
 * [BUNDLED] always uses the copy shipped with the app; [CUSTOM] uses [AppSettings.adbPath].
 */
enum class AdbMode { AUTO, BUNDLED, CUSTOM }

data class AppSettings(
    val port: Int = 47823,
    val idleMinutes: Int = 15,
    val editingMinutes: Int = 5,
    val pinnedPermission: PinnedPermission = PinnedPermission.MIRROR,
    val adbMode: AdbMode = AdbMode.AUTO,
    /** The adb file for [AdbMode.CUSTOM]; kept when another mode is picked so switching back restores it. */
    val adbPath: String = "",
)

data class ActivityEntry(
    val id: Long,
    val at: Instant,
    val kind: String,
    val holderKey: String?,
    val deviceKey: String?,
    val text: String,
)

class DeviceConfigRepository(private val dao: DeviceConfigDao) {
    val all: Flow<List<DeviceConfig>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun upsertSeen(key: DeviceKey, now: Instant) {
        val e = dao.get(key.hardwareSerial)
        dao.put(
            (e ?: DeviceConfigEntity(key.hardwareSerial, null, null, false, 0))
                .copy(lastSeen = now.toEpochMilliseconds()),
        )
    }

    suspend fun setCategory(key: DeviceKey, category: Category?) = modify(key) { it.copy(categoryOverride = category?.id) }
    suspend fun setDontUse(key: DeviceKey, dontUse: Boolean) = modify(key) { it.copy(dontUse = dontUse) }
    suspend fun rename(key: DeviceKey, name: String?) = modify(key) { it.copy(displayName = name) }

    private suspend fun modify(key: DeviceKey, f: (DeviceConfigEntity) -> DeviceConfigEntity) {
        val e = dao.get(key.hardwareSerial) ?: DeviceConfigEntity(key.hardwareSerial, null, null, false, 0)
        dao.put(f(e))
    }

    private fun DeviceConfigEntity.toModel() = DeviceConfig(
        DeviceKey(hardwareSerial), displayName, categoryOverride?.let(::Category), dontUse,
        Instant.fromEpochMilliseconds(lastSeen),
    )
}

class ProjectRepository(private val dao: ProjectDao) {
    val all: Flow<List<Project>> = dao.observeAll().map { l -> l.map { Project(it.path, Category(it.defaultCategory)) } }

    /** Creates the row with ANY on first sight. */
    suspend fun defaultCategoryFor(cwd: String): Category {
        dao.get(cwd)?.let { return Category(it.defaultCategory) }
        dao.put(ProjectEntity(cwd, Category.ANY.id))
        return Category.ANY
    }

    suspend fun setDefault(path: String, category: Category) = dao.put(ProjectEntity(path, category.id))
}

class SettingsRepository(private val dao: SettingDao) {
    val settings: Flow<AppSettings> = dao.observeAll().map { it.toSettings() }

    suspend fun current(): AppSettings = dao.getAll().toSettings()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(current())
        dao.putAll(
            listOf(
                SettingEntity(PORT, n.port.toString()),
                SettingEntity(IDLE, n.idleMinutes.toString()),
                SettingEntity(EDITING, n.editingMinutes.toString()),
                SettingEntity(PINNED, n.pinnedPermission.name),
                SettingEntity(ADB_MODE, n.adbMode.name),
                SettingEntity(ADB_PATH, n.adbPath),
            ),
        )
    }

    private fun List<SettingEntity>.toSettings(): AppSettings {
        val m = associate { it.name to it.value }
        val d = AppSettings()
        return AppSettings(
            m[PORT]?.toIntOrNull() ?: d.port,
            m[IDLE]?.toIntOrNull() ?: d.idleMinutes,
            m[EDITING]?.toIntOrNull() ?: d.editingMinutes,
            m[PINNED]?.let { v -> PinnedPermission.entries.firstOrNull { it.name == v } } ?: d.pinnedPermission,
            m[ADB_MODE]?.let { v -> AdbMode.entries.firstOrNull { it.name == v } } ?: d.adbMode,
            m[ADB_PATH] ?: d.adbPath,
        )
    }

    private companion object {
        const val PORT = "port"
        const val IDLE = "idleMinutes"
        const val EDITING = "editingMinutes"
        const val PINNED = "pinnedPermission"
        const val ADB_MODE = "adbMode"
        const val ADB_PATH = "adbPath"
    }
}

class ActivityRepository(private val dao: ActivityDao) {
    fun recent(limit: Int = 200): Flow<List<ActivityEntry>> =
        dao.recent(limit).map { l ->
            l.map { ActivityEntry(it.id, Instant.fromEpochMilliseconds(it.at), it.kind, it.holderKey, it.deviceKey, it.text) }
        }

    suspend fun record(e: AllocatorEvent) {
        val (kind, holder, device, text) = when (e) {
            is AllocatorEvent.Granted -> Row("granted", e.holder.key, e.device.hardwareSerial, "granted ${e.holder.key} → ${e.device.hardwareSerial}")
            is AllocatorEvent.Released -> Row("released", e.holder.key, e.device.hardwareSerial, "released ${e.holder.key} ← ${e.device.hardwareSerial} (${e.reason})")
            is AllocatorEvent.Queued -> Row("queued", e.holder.key, null, "queued ${e.holder.key} wants ${e.wants.joinToString { it.id }}")
            is AllocatorEvent.LeftQueue -> Row("left_queue", e.holder.key, null, "left queue ${e.holder.key}")
            is AllocatorEvent.Blocked -> Row("blocked", e.holder.key, null, "blocked ${e.holder.key}")
            is AllocatorEvent.Unblocked -> Row("unblocked", e.holder.key, null, "unblocked ${e.holder.key}")
        }
        dao.insert(ActivityEntity(at = e.at.toEpochMilliseconds(), kind = kind, holderKey = holder, deviceKey = device, text = text))
    }

    suspend fun recordCommand(holder: HolderId, device: DeviceKey, command: String, at: Instant) {
        dao.insert(ActivityEntity(at = at.toEpochMilliseconds(), kind = "command", holderKey = holder.key, deviceKey = device.hardwareSerial, text = command))
    }

    private data class Row(val kind: String, val holder: String?, val device: String?, val text: String)
}

class BlockedRepository(private val dao: BlockedHolderDao) {
    suspend fun all(): Set<HolderId> = dao.all().map { HolderId.parse(it.holderKey) }.toSet()
    suspend fun add(h: HolderId) = dao.put(BlockedHolderEntity(h.key))
    suspend fun remove(h: HolderId) = dao.delete(h.key)
}
