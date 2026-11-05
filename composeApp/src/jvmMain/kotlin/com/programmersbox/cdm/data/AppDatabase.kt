package com.programmersbox.cdm.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.flow.Flow

// Category/HolderId/Instant are stored as primitives (id string, key string, epoch millis);
// repositories convert at the boundary, so no TypeConverters are needed on value classes.

@Entity(tableName = "device_config")
data class DeviceConfigEntity(
    @PrimaryKey val hardwareSerial: String,
    val displayName: String?,
    val categoryOverride: String?,
    val dontUse: Boolean,
    val lastSeen: Long,
)

@Entity(tableName = "project")
data class ProjectEntity(@PrimaryKey val path: String, val defaultCategory: String)

@Entity(tableName = "setting")
data class SettingEntity(@PrimaryKey val name: String, val value: String)

@Entity(tableName = "activity")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val holderKey: String?,
    val deviceKey: String?,
    val text: String,
)

@Entity(tableName = "blocked_holder")
data class BlockedHolderEntity(@PrimaryKey val holderKey: String)

@Dao
interface DeviceConfigDao {
    @Query("SELECT * FROM device_config ORDER BY hardwareSerial")
    fun observeAll(): Flow<List<DeviceConfigEntity>>

    @Query("SELECT * FROM device_config WHERE hardwareSerial = :serial")
    suspend fun get(serial: String): DeviceConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: DeviceConfigEntity)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM project ORDER BY path")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project WHERE path = :path")
    suspend fun get(path: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ProjectEntity)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM setting")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM setting")
    suspend fun getAll(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SettingEntity)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity ORDER BY at DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ActivityEntity>>

    @Insert
    suspend fun insert(entity: ActivityEntity)
}

@Dao
interface BlockedHolderDao {
    @Query("SELECT * FROM blocked_holder")
    suspend fun all(): List<BlockedHolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BlockedHolderEntity)

    @Query("DELETE FROM blocked_holder WHERE holderKey = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [
        DeviceConfigEntity::class, ProjectEntity::class, SettingEntity::class,
        ActivityEntity::class, BlockedHolderEntity::class,
    ],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceConfigDao(): DeviceConfigDao
    abstract fun projectDao(): ProjectDao
    abstract fun settingDao(): SettingDao
    abstract fun activityDao(): ActivityDao
    abstract fun blockedDao(): BlockedHolderDao
}

fun defaultDbFile(): File {
    val dir = File(System.getProperty("user.home"), ".claude-device-manager")
    dir.mkdirs()
    return File(dir, "cdm.db")
}

fun fileDatabase(file: File = defaultDbFile()): AppDatabase =
    Room.databaseBuilder<AppDatabase>(file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .build()

/** Test-friendly factory. */
fun inMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
