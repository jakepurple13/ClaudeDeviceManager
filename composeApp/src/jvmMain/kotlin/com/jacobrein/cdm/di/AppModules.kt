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
import com.jacobrein.cdm.ui.nav.Navigator
import com.jacobrein.cdm.ui.nav.ShellViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import com.jacobrein.cdm.ui.UiActions
import org.koin.core.module.dsl.viewModelOf
import com.jacobrein.cdm.ui.SystemUiClock
import com.jacobrein.cdm.ui.UiClock
import com.jacobrein.cdm.ui.UiSources
import com.jacobrein.cdm.ui.devices.DeviceDetailViewModel
import com.jacobrein.cdm.ui.devices.DevicesViewModel
import com.jacobrein.cdm.ui.overview.OverviewViewModel
import com.jacobrein.cdm.ui.queue.QueueDetailViewModel
import com.jacobrein.cdm.ui.queue.QueueViewModel
import org.koin.dsl.bind
import org.koin.dsl.module

/** The app-lifetime scope: the service's loops and every UI action run here (single source; Main.kt uses it too). */
const val APP_SCOPE = "appScope"

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
        single { Navigator() }
        single<CoroutineScope>(named(APP_SCOPE)) {
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, e -> System.err.println("cdm: background action failed: $e") },
            )
        }
    },
    uiModule,
)

/** View models and their shared inputs. Split out so UI tests can pair it with fakes. */
val uiModule: Module = module {
    single<UiClock> { SystemUiClock }
    single { UiSources(get(), get(), get(), get(), get()) }
    single { UiActions(get(), get(named(APP_SCOPE))) }
    viewModelOf(::ShellViewModel)
    viewModelOf(::OverviewViewModel)
    viewModelOf(::DevicesViewModel)
    viewModelOf(::QueueViewModel)
    viewModelOf(::DeviceDetailViewModel)
    viewModelOf(::QueueDetailViewModel)
}
