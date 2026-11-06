package com.programmersbox.cdm.service

/** The one place the pin form lives (Task 0 confirmed it executes). */
fun pinPrefix(serial: String): String = pinPrefix(serial, null, null)

/**
 * The pin for every platform the holder holds: `export` (not a bare assignment) so the value reaches child processes
 * such as Gradle and xcodebuild. Empty when every argument is null.
 */
fun pinPrefix(androidSerial: String?, iosUdid: String?, iosPairUdid: String?): String = buildString {
    androidSerial?.let { append("export ANDROID_SERIAL=").append(it).append("; ") }
    iosUdid?.let { append("export CDM_IOS_UDID=").append(it).append("; ") }
    iosPairUdid?.let { append("export CDM_IOS_PAIR_UDID=").append(it).append("; ") }
}

/** What a rewrite added: a device pin (possibly with cdm identity too), or only cdm identity. */
enum class RewriteKind { PIN, CDM }

sealed interface HookReply {
    data object NoOpinion : HookReply

    /** [original] is the command as the agent wrote it; Task 6b's PermissionMirror judges that, not [command]. */
    data class Rewrite(val command: String, val original: String, val kind: RewriteKind) : HookReply
    data class Deny(val reason: String) : HookReply
}
