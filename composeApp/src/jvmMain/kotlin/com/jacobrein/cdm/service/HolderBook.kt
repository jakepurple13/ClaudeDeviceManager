package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.model.HolderId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** [HolderInfo] bookkeeping for display. Ended holders are kept for [keepEnded], then dropped by [prune]. */
internal class HolderBook(private val clock: () -> Instant, private val keepEnded: Duration = 1.hours) {
    private val state = MutableStateFlow<Map<HolderId, HolderInfo>>(emptyMap())
    val info: StateFlow<Map<HolderId, HolderInfo>> = state.asStateFlow()

    /** Null arguments keep the previous value. */
    fun touch(holder: HolderId, cwd: String?, agentType: String?, deviceCommand: String? = null) {
        val now = clock()
        state.update { m ->
            val old = m[holder]
            m + (
                holder to HolderInfo(
                    holder,
                    cwd ?: old?.cwd ?: "",
                    agentType ?: old?.agentType,
                    deviceCommand ?: old?.lastDeviceCommand,
                    now,
                    ended = false,
                )
                )
        }
    }

    fun end(holder: HolderId) {
        val now = clock()
        state.update { m ->
            val old = m[holder] ?: HolderInfo(holder, "", null, null, now, ended = true)
            m + (holder to old.copy(lastSeen = now, ended = true))
        }
    }

    fun endSession(sessionId: String) {
        val now = clock()
        state.update { m -> m.mapValues { (h, i) -> if (h.sessionId == sessionId) i.copy(lastSeen = now, ended = true) else i } }
    }

    fun prune() {
        val now = clock()
        state.update { m -> m.filterValues { !it.ended || now - it.lastSeen <= keepEnded } }
    }
}
