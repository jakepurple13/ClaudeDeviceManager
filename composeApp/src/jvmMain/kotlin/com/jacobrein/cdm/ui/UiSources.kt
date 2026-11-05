package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.Timers
import com.jacobrein.cdm.data.ActivityRepository
import com.jacobrein.cdm.data.DeviceConfigRepository
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.service.DeviceManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The UI's clock. [ticks] emits about once a second so "held 12m" style labels keep moving. */
interface UiClock {
    fun now(): Instant
    val ticks: Flow<Instant>
}

object SystemUiClock : UiClock {
    override fun now(): Instant = Clock.System.now()

    override val ticks: Flow<Instant> = flow {
        while (true) {
            val now = now()
            emit(now)
            // Wake on the next whole second so every label on screen flips together.
            delay(1_000 - now.toEpochMilliseconds() % 1_000)
        }
    }
}

/** Combines the service's flows, the repositories, and the clock into one [World] stream for the view models. */
class UiSources(
    val manager: DeviceManager,
    configs: DeviceConfigRepository,
    activity: ActivityRepository,
    settings: SettingsRepository,
    clock: UiClock,
) {
    private val live = combine(manager.snapshot, manager.tracked, manager.holderInfo, ::Triple)
    private val timers = settings.settings
        .map { Timers(idle = it.idleMinutes.minutes, editing = it.editingMinutes.minutes) }
        .distinctUntilChanged()

    val world: Flow<World> = combine(live, configs.all, activity.recent(ACTIVITY_WINDOW), timers, clock.ticks) { l, c, a, t, now ->
        World(l.first, l.second, l.third, c, a, t, now)
    }

    private companion object {
        const val ACTIVITY_WINDOW = 200
    }
}
