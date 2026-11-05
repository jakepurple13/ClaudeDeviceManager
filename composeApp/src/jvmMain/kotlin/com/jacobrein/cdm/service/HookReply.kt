package com.jacobrein.cdm.service

/** The one place the pin form lives (Task 0 confirmed it executes). */
fun pinPrefix(serial: String): String = "export ANDROID_SERIAL=$serial; "

/** What a rewrite added: a device pin (possibly with cdm identity too), or only cdm identity. */
enum class RewriteKind { PIN, CDM }

sealed interface HookReply {
    data object NoOpinion : HookReply

    /** [original] is the command as the agent wrote it; Task 6b's PermissionMirror judges that, not [command]. */
    data class Rewrite(val command: String, val original: String, val kind: RewriteKind) : HookReply
    data class Deny(val reason: String) : HookReply
}
