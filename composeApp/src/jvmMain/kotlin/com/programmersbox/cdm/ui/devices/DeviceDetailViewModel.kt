package com.programmersbox.cdm.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.ui.ActivityRow
import com.programmersbox.cdm.ui.DeviceRow
import com.programmersbox.cdm.ui.UiActions
import com.programmersbox.cdm.ui.UiSources
import com.programmersbox.cdm.ui.World
import com.programmersbox.cdm.ui.activityRows
import com.programmersbox.cdm.ui.deviceName
import com.programmersbox.cdm.ui.deviceRows
import com.programmersbox.cdm.ui.holding
import com.programmersbox.cdm.ui.kindLabel
import com.programmersbox.cdm.ui.labelOf
import com.programmersbox.cdm.ui.minutesCeil
import com.programmersbox.cdm.ui.releasesIn
import com.programmersbox.cdm.ui.short
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The holder card on the device detail. */
data class HolderCard(
    val holder: HolderId,
    val label: String,
    val cwd: String,
    val agentType: String?,
    /** "held 12m · last device command 1m ago · releases if idle 14m more". */
    val timing: String,
    val lastCommand: String?,
)

data class DeviceDetailState(
    val key: DeviceKey,
    val loaded: Boolean = false,
    val name: String = key.hardwareSerial,
    val row: DeviceRow? = null,
    /** Label to value, in the order the detail pane shows them: Platform, Kind, Runtime, UDID or Serial, Connection. */
    val info: List<Pair<String, String>> = emptyList(),
    val detectedCategory: Category? = null,
    val categoryOverride: Category? = null,
    val holder: HolderCard? = null,
    val commands: List<ActivityRow> = emptyList(),
    val dontUse: Boolean = false,
    val confirmDontUse: Boolean = false,
)

class DeviceDetailViewModel(
    private val key: DeviceKey,
    private val actions: UiActions,
    sources: UiSources,
) : ViewModel() {
    private val confirm = MutableStateFlow(false)

    val uiState: StateFlow<DeviceDetailState> = combine(sources.world, confirm) { w, c -> build(w).copy(confirmDontUse = c) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailState(key))

    private fun build(w: World): DeviceDetailState {
        val tracked = w.tracked.firstOrNull { it.key == key }
        val config = w.configs.firstOrNull { it.key == key }
        val device = w.snapshot.devices.firstOrNull { it.key == key }
        val connection = when {
            tracked != null -> "${tracked.transport.uppercase().replace("WIFI", "Wi-Fi")} · connected"
            device != null -> "offline · held"
            else -> "offline"
        }
        val platform = tracked?.platform ?: device?.platform ?: Platform.ANDROID
        val virtual = tracked?.virtual ?: device?.virtual ?: false
        val platformName = if (platform == Platform.IOS) "iOS" else "Android"
        val info = listOfNotNull(
            "Platform" to platformName,
            "Kind" to kindLabel(platform, virtual),
            tracked?.osVersion?.ifBlank { null }?.let { "Runtime" to "$platformName $it" },
            (if (platform == Platform.IOS) "UDID" else "Serial") to key.hardwareSerial,
            "Connection" to connection,
        )
        val holder = w.snapshot.holds[key]
        val card = holder?.let { h ->
            val info = w.info[h]
            val st = w.holding(h)
            val timing = if (st == null) "" else listOf(
                "held ${short(w.now - st.since)}",
                "last device command ${short(w.now - st.lastActivity)} ago",
                if (st.editing) "editing · releases in ${minutesCeil(w.releasesIn(st))}" else "releases if idle ${minutesCeil(w.releasesIn(st))} more",
            ).joinToString(" · ")
            HolderCard(h, w.labelOf(h), info?.cwd.orEmpty(), info?.agentType, timing, info?.lastDeviceCommand)
        }
        val commands = w.activity.filter { it.kind == "command" && it.deviceKey == key.hardwareSerial }.take(RECENT_COMMANDS)
        return DeviceDetailState(
            key = key,
            loaded = true,
            name = w.deviceName(key),
            row = deviceRows(w).firstOrNull { it.key == key },
            info = info,
            detectedCategory = tracked?.detectedCategory,
            categoryOverride = config?.categoryOverride,
            holder = card,
            commands = activityRows(w, commands),
            dontUse = device?.dontUse ?: config?.dontUse ?: false,
        )
    }

    /** `null` goes back to the auto-detected category. */
    fun onCategorySelected(category: Category?) = actions.run { setCategory(key, category) }

    fun onRelease(block: Boolean) = actions.run { releaseDevice(key, block) }

    /** Turning don't-use on while someone holds the device asks first (release now, or after they release). */
    fun onDontUseToggled(on: Boolean) {
        if (on && actions.manager.snapshot.value.holds.containsKey(key)) {
            confirm.value = true
            return
        }
        actions.run { setDontUse(key, on, releaseNow = false) }
    }

    fun onConfirmDontUse(releaseNow: Boolean) {
        confirm.value = false
        actions.run { setDontUse(key, true, releaseNow) }
    }

    fun onDismissDontUse() {
        confirm.value = false
    }

    private companion object {
        const val RECENT_COMMANDS = 10
    }
}
