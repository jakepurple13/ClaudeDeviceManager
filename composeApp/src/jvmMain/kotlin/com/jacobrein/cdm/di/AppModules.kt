package com.jacobrein.cdm.di

import com.jacobrein.cdm.data.ActivityRepository
import com.jacobrein.cdm.data.AppDatabase
import com.jacobrein.cdm.data.BlockedRepository
import com.jacobrein.cdm.data.DeviceConfigRepository
import com.jacobrein.cdm.data.ProjectRepository
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.data.fileDatabase
import com.jacobrein.cdm.devices.AdbClient
import com.jacobrein.cdm.devices.DeviceTracker
import com.jacobrein.cdm.devices.ProcessAdbClient
import com.jacobrein.cdm.install.Installer
import com.jacobrein.cdm.server.ServerStatus
import com.jacobrein.cdm.service.DeviceManager
import com.jacobrein.cdm.service.DeviceManagerService
import com.jacobrein.cdm.service.PermissionMirror
import kotlin.io.path.Path
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Every later task adds its Koin definitions here. */
fun appModules(): List<Module> = listOf(
    module {
        single<AppDatabase> { fileDatabase() }
        single { get<AppDatabase>().deviceConfigDao() }
        single { get<AppDatabase>().projectDao() }
        single { get<AppDatabase>().settingDao() }
        single { get<AppDatabase>().activityDao() }
        single { get<AppDatabase>().blockedDao() }
        single { DeviceConfigRepository(get()) }
        single { ProjectRepository(get()) }
        single { SettingsRepository(get()) }
        single { ActivityRepository(get()) }
        single { BlockedRepository(get()) }
        single<AdbClient> { ProcessAdbClient() }
        single { DeviceTracker(get()) }
        single { PermissionMirror(Path(System.getProperty("user.home"))) }
        single { ServerStatus() }
        single { Installer(Path(System.getProperty("user.home"))) }
        single { DeviceManagerService(get(), get(), get(), get(), get(), get(), get()) } bind DeviceManager::class
    },
)
