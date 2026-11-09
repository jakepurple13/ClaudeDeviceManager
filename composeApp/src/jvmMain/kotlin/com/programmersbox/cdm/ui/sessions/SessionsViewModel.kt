package com.programmersbox.cdm.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.ReleaseReason
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.ui.UiActions
import com.programmersbox.cdm.ui.UiSources
import com.programmersbox.cdm.ui.World
import com.programmersbox.cdm.ui.deviceName
import com.programmersbox.cdm.ui.holderLabel
import com.programmersbox.cdm.ui.holdingsOf
import com.programmersbox.cdm.ui.reservedCountOf
import com.programmersbox.cdm.ui.short
import com.programmersbox.cdm.ui.timerLabel
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class HolderState { HOLDING, WAITING, IDLE, ENDED, BLOCKED }

data class SessionHolderRow(
    val holder: HolderId,
    /** "main", or the subagent's type (falling back to its id). */
    val name: String,
    val state: HolderState,
    /** "Pixel 8 · idle 1m", "#2 in queue", "ended 12m ago", ... */
    val detail: String,
    val lastCommand: String?,
    /** The devices of a held set under their names (`android1`); empty unless the holder holds a set. */
    val members: List<SetMemberRow> = emptyList(),
    /** One timer for the whole set ("idle 1m"); null unless the holder holds a set. */
    val timerLabel: String? = null,
)

data class SetMemberRow(val name: String, val device: String)

data class SessionGroup(
    val sessionId: String,
    /** "gabb-messenger #a41f". */
    val title: String,
    val cwd: String?,
    /** The main agent first (when seen), then subagents by id. */
    val holders: List<SessionHolderRow>,
    /** Every holder in the session has ended. */
    val ended: Boolean,
    val lastSeen: Instant?,
) {
    val main: SessionHolderRow? get() = holders.firstOrNull { it.holder.agentId == HolderId.MAIN }
    val agents: List<SessionHolderRow> get() = holders.filter { it.holder.agentId != HolderId.MAIN }
}

data class SessionsState(
    /** Null until the first emission, so the screen doesn't flash its empty state. */
    val groups: List<SessionGroup>? = null,
    /** Sessions whose subagent rows are folded away. */
    val collapsed: Set<String> = emptySet(),
)

class SessionsViewModel(private val actions: UiActions, sources: UiSources) : ViewModel() {
    private val collapsed = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<SessionsState> = combine(sources.world, collapsed) { w, c -> SessionsState(sessionGroups(w), c) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsState())

    fun onToggle(sessionId: String) = collapsed.update { if (sessionId in it) it - sessionId else it + sessionId }

    fun onUnblock(h: HolderId) = actions.run { unblock(h) }
}

/**
 * Every holder the service knows (seen by a hook or `cdm`, or in the allocator), grouped by session. Live sessions
 * come first, most recently seen first; ended ones (kept for an hour by the service) follow.
 */
fun sessionGroups(w: World): List<SessionGroup> {
    val holders = (w.info.keys + w.snapshot.statuses.keys).distinct()
    return holders.groupBy { it.sessionId }.map { (sid, hs) ->
        val rows = hs.sortedWith(compareBy<HolderId> { it.agentId != HolderId.MAIN }.thenBy { it.agentId }).map { holderRow(it, w) }
        val cwd = (hs.firstOrNull { it.agentId == HolderId.MAIN } ?: hs.first()).let { w.info[it]?.cwd?.ifBlank { null } }
            ?: hs.firstNotNullOfOrNull { w.info[it]?.cwd?.ifBlank { null } }
        SessionGroup(
            sessionId = sid,
            title = holderLabel(HolderId(sid), cwd, null),
            cwd = cwd,
            holders = rows,
            ended = rows.all { it.state == HolderState.ENDED },
            lastSeen = hs.mapNotNull { w.info[it]?.lastSeen }.maxOrNull(),
        )
    }.sortedWith(
        compareBy<SessionGroup> { it.ended }
            .thenByDescending { it.lastSeen ?: Instant.DISTANT_PAST }
            .thenBy { it.sessionId },
    )
}

private fun holderRow(h: HolderId, w: World): SessionHolderRow {
    val info = w.info[h]
    val st = w.snapshot.statuses[h]
    // Up to two holds (one per platform), each with its own timer, joined by " + ".
    val held = w.holdingsOf(h).take(2).joinToString(" + ") { "${w.deviceName(it.device)} · ${w.timerLabel(it)}" }
    val set = (st as? HolderStatus.HoldingSet)?.set
    val (state, detail) = when {
        set != null -> HolderState.HOLDING to "${set.members.size} devices · ${w.timerLabel(set)}"
        st is HolderStatus.Blocked -> HolderState.BLOCKED to "blocked · cdm acquire refuses until unblocked"
        st is HolderStatus.Holding -> HolderState.HOLDING to held
        // Queued for one platform while holding the other: the held device and its timer still show.
        st is HolderStatus.Queued -> HolderState.WAITING to listOf("#${st.position} in queue", st.set?.let { "${w.reservedCountOf(h)} of ${it.members.size} reserved" }.orEmpty(), held).filter { it.isNotEmpty() }.joinToString(" · ")
        info?.ended == true -> HolderState.ENDED to "ended ${short(w.now - info.lastSeen)} ago"
        st is HolderStatus.Released -> HolderState.IDLE to "idle · ${released(st.reason)}"
        else -> HolderState.IDLE to (info?.let { "idle · seen ${short(w.now - it.lastSeen)} ago" } ?: "idle")
    }
    val name = if (h.agentId == HolderId.MAIN) "main" else info?.agentType ?: h.agentId
    val members = set?.members?.map { SetMemberRow(it.name, w.deviceName(it.device)) }.orEmpty()
    return SessionHolderRow(h, name, state, detail, info?.lastDeviceCommand, members, set?.let { w.timerLabel(it) })
}

private fun released(r: ReleaseReason) = when (r) {
    ReleaseReason.IDLE_TIMEOUT -> "device released after idle"
    ReleaseReason.EDIT_TIMEOUT -> "device released while editing"
    ReleaseReason.SELF_RELEASE -> "released its device"
    ReleaseReason.USER_RELEASE -> "device released by you"
    ReleaseReason.USER_HANDOFF -> "device handed off"
    ReleaseReason.DONT_USE -> "device marked don't use"
    ReleaseReason.SUBAGENT_STOP, ReleaseReason.SESSION_END -> "device released"
}
