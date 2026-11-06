package com.programmersbox.cdm.di

import com.programmersbox.cdm.data.ActivityRepository
import com.programmersbox.cdm.data.AppDatabase
import com.programmersbox.cdm.data.BlockedRepository
import com.programmersbox.cdm.data.DeviceConfigRepository
import com.programmersbox.cdm.data.ProjectRepository
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.data.fileDatabase
import com.programmersbox.cdm.devices.AdbClient
import com.programmersbox.cdm.devices.AdbSelection
import com.programmersbox.cdm.devices.DeviceTracker
import com.programmersbox.cdm.devices.ProcessAdbClient
import com.programmersbox.cdm.install.InstallLifecycle
import com.programmersbox.cdm.install.Installer
import com.programmersbox.cdm.server.ServerControl
import com.programmersbox.cdm.server.ServerController
import com.programmersbox.cdm.server.ServerListener
import com.programmersbox.cdm.server.ServerStatus
import com.programmersbox.cdm.service.DeviceManager
import com.programmersbox.cdm.service.DeviceManagerService
import com.programmersbox.cdm.service.PermissionMirror
import kotlin.io.path.Path
import com.programmersbox.cdm.ui.nav.Navigator
import com.programmersbox.cdm.ui.lifecycle.LifecycleController
import com.programmersbox.cdm.ui.nav.ShellViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import com.programmersbox.cdm.ui.UiActions
import org.koin.core.module.dsl.viewModelOf
import com.programmersbox.cdm.ui.SystemUiClock
import com.programmersbox.cdm.ui.UiClock
import com.programmersbox.cdm.ui.UiSources
import com.programmersbox.cdm.ui.devices.DeviceDetailViewModel
import com.programmersbox.cdm.ui.devices.DevicesViewModel
import com.programmersbox.cdm.ui.overview.OverviewViewModel
import com.programmersbox.cdm.ui.queue.QueueDetailViewModel
import com.programmersbox.cdm.ui.queue.QueueViewModel
import com.programmersbox.cdm.ui.projects.ProjectsViewModel
import com.programmersbox.cdm.ui.sessions.SessionsViewModel
import com.programmersbox.cdm.ui.settings.SettingsViewModel
import com.programmersbox.cdm.ui.update.UpdateViewModel
import com.programmersbox.cdm.update.AppUpdater
import com.programmersbox.cdm.update.NucleusAppUpdater
import com.programmersbox.cdm.update.UpdateController
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
        single {
            // Follow the stored adb choice for the app's lifetime; ProcessAdbClient waits for the first value.
            AdbSelection().also { sel ->
                val settings = get<SettingsRepository>()
                get<CoroutineScope>(named(APP_SCOPE)).launch { settings.settings.collect { sel.apply(it.adbMode, it.adbPath) } }
            }
        }
        single<AdbClient> { val sel = get<AdbSelection>(); ProcessAdbClient(adbPath = sel::path) }
        single { DeviceTracker(get()) }
        single { PermissionMirror(Path(System.getProperty("user.home"))) }
        single { ServerStatus() }
        single { Installer(Path(System.getProperty("user.home"))) }
        single { InstallLifecycle(get()) } bind ServerListener::class
        single { ServerController(get(), get(), listener = get()) } bind ServerControl::class
        single { DeviceManagerService(get(), get(), get(), get(), get(), get(), get()) } bind DeviceManager::class
        single { Navigator() }
        single {
            val service = get<DeviceManagerService>()
            val integration = get<InstallLifecycle>()
            LifecycleController(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                serverError = get<ServerStatus>().serverError,
                integration = integration.state,
                scanned = service.scanned,
                deviceCount = { service.tracked.value.size },
                repairIntegration = { integration.repair() },
            )
        }
        single<AppUpdater> { NucleusAppUpdater() }
        single { UpdateController(get(), get(named(APP_SCOPE))) }
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
    viewModelOf(::SessionsViewModel)
    viewModelOf(::ProjectsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::UpdateViewModel)
}
