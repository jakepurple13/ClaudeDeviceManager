package com.programmersbox.cdm.ui.nav

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId

/**
 * Dev-only: `CDM_START_ROUTE=devices:<hardware serial>` or `queue:<holder key>` (or a bare section name) opens that
 * screen at launch, for screenshots where synthetic clicks are blocked. Unset, unknown, or malformed values do nothing.
 */
fun applyDevStartRoute(navigator: Navigator, spec: String? = System.getenv("CDM_START_ROUTE")) {
    if (spec.isNullOrBlank()) return
    runCatching { open(navigator, spec) }.onFailure { System.err.println("cdm: ignoring CDM_START_ROUTE=$spec: $it") }
}

private fun open(navigator: Navigator, spec: String) {
    val section = spec.substringBefore(':').lowercase()
    val arg = spec.substringAfter(':', "").ifBlank { null }
    val top = TopLevel.entries.firstOrNull { it.name.lowercase() == section } ?: return
    navigator.selectTopLevel(top)
    when {
        arg == null -> Unit
        top == TopLevel.DEVICES -> navigator.openDetail(Route.DeviceDetail(DeviceKey(arg)))
        top == TopLevel.QUEUE -> navigator.openDetail(Route.QueueDetail(HolderId.parse(arg)))
    }
}
