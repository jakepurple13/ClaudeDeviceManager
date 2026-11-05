package com.jacobrein.cdm.ui.nav

import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId

/**
 * Dev-only: `CDM_START_ROUTE=devices:<hardware serial>` or `queue:<holder key>` (or a bare section name) opens that
 * screen at launch, for screenshots where synthetic clicks are blocked. Unset or unknown values do nothing.
 */
fun applyDevStartRoute(navigator: Navigator, spec: String? = System.getenv("CDM_START_ROUTE")) {
    if (spec.isNullOrBlank()) return
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
