package com.programmersbox.cdm.ui.lifecycle

/** One thing the app sets up on launch and takes down on quit. */
enum class LifecycleStep { SERVER, HOOKS, CDM, SNIPPET, DEVICES }

/** Start-up order, then quit order (the order [com.programmersbox.cdm.install.InstallLifecycle] removes in). */
val STARTUP_STEPS = listOf(LifecycleStep.SERVER, LifecycleStep.HOOKS, LifecycleStep.CDM, LifecycleStep.SNIPPET, LifecycleStep.DEVICES)
val SHUTDOWN_STEPS = listOf(LifecycleStep.HOOKS, LifecycleStep.SNIPPET, LifecycleStep.CDM, LifecycleStep.SERVER)

enum class LifecyclePhase {
    /** Nothing to show. */
    Idle,
    Starting,
    /** Start-up finished cleanly; the card and pill linger briefly, then go back to [Idle]. */
    Ready,
    StartFailed,
    Stopping,
    /** Clean-up finished cleanly; the window closes next. */
    Stopped,
    StopFailed,
    ;

    val isShutdown: Boolean get() = this == Stopping || this == Stopped || this == StopFailed
}

sealed interface StepStatus {
    data object Pending : StepStatus
    data object Running : StepStatus
    data object Done : StepStatus
    data object Removed : StepStatus
    /** Not attempted: the server never started, so nothing was installed. */
    data object Skipped : StepStatus
    data class Failed(val message: String) : StepStatus

    val finished: Boolean get() = this != Pending && this != Running
}

data class StepRow(val step: LifecycleStep, val status: StepStatus, val detail: String)

data class LifecycleUi(
    val phase: LifecyclePhase = LifecyclePhase.Idle,
    val rows: List<StepRow> = emptyList(),
    /** The card is showing. The user can hide it; the title-bar pill keeps showing progress. */
    val cardVisible: Boolean = false,
    val deviceCount: Int = 0,
) {
    val running: Boolean get() = phase == LifecyclePhase.Starting || phase == LifecyclePhase.Stopping
    val failed: Boolean get() = phase == LifecyclePhase.StartFailed || phase == LifecyclePhase.StopFailed
    val current: StepRow? get() = rows.firstOrNull { it.status == StepStatus.Running }
    /** Rows dealt with. On quit the rows start out [StepStatus.Done] (connected), so only removal outcomes count. */
    val finishedCount: Int
        get() = rows.count {
            if (phase.isShutdown) it.status != StepStatus.Done && it.status.finished else it.status.finished
        }

    /** The title-bar line: fills during start-up, drains during quit. */
    val progress: Float
        get() {
            if (rows.isEmpty()) return 0f
            val done = finishedCount.toFloat() / rows.size
            return if (phase.isShutdown) 1f - done else done
        }

    /** A failed integration step can be retried; a server failure needs a new port in Settings instead. */
    val canRepair: Boolean
        get() = phase == LifecyclePhase.StartFailed &&
            rows.none { it.step == LifecycleStep.SERVER && it.status is StepStatus.Failed } &&
            rows.any { it.status is StepStatus.Failed }
    val serverFailed: Boolean
        get() = rows.any { it.step == LifecycleStep.SERVER && it.status is StepStatus.Failed }
}

/** The checklist row label. */
fun LifecycleStep.label(shutdown: Boolean): String = when (this) {
    LifecycleStep.SERVER -> if (shutdown) "Stop server" else "Start server"
    LifecycleStep.HOOKS -> if (shutdown) "Remove hooks" else "Add hooks"
    LifecycleStep.CDM -> if (shutdown) "Remove cdm" else "Install cdm"
    LifecycleStep.SNIPPET -> if (shutdown) "Remove CLAUDE.md note" else "Add CLAUDE.md note"
    LifecycleStep.DEVICES -> "Scan devices"
}

/** The title-bar pill text while the step runs. */
fun LifecycleStep.verb(shutdown: Boolean): String = when (this) {
    LifecycleStep.SERVER -> if (shutdown) "Stopping server" else "Starting server"
    LifecycleStep.HOOKS -> if (shutdown) "Removing hooks" else "Adding hooks"
    LifecycleStep.CDM -> if (shutdown) "Removing cdm" else "Installing cdm"
    LifecycleStep.SNIPPET -> if (shutdown) "Removing CLAUDE.md note" else "Adding CLAUDE.md note"
    LifecycleStep.DEVICES -> "Scanning devices"
}

/**
 * How one part of the hub looks for a step status, each value 0 (off) to 1 (on). The hub animates between them.
 * [seen]: the node is there. [drawn]: its spoke reaches the hub. [lit]: accent colour. [glow]: working on it.
 * [drift]: detached and floating away. [error]: failed.
 */
data class PartVisual(
    val seen: Float = 0f,
    val drawn: Float = 0f,
    val lit: Float = 0f,
    val glow: Float = 0f,
    val drift: Float = 0f,
    val error: Float = 0f,
)

fun partVisual(status: StepStatus?): PartVisual = when (status) {
    null, StepStatus.Pending -> PartVisual()
    StepStatus.Running -> PartVisual(seen = 1f, drawn = 1f, glow = 1f)
    StepStatus.Done -> PartVisual(seen = 1f, drawn = 1f, lit = 1f)
    StepStatus.Removed -> PartVisual(seen = 1f, drift = 1f)
    StepStatus.Skipped -> PartVisual(seen = 1f)
    is StepStatus.Failed -> PartVisual(seen = 1f, drawn = 1f, error = 1f)
}
