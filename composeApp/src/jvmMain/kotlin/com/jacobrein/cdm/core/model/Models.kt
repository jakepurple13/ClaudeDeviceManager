package com.jacobrein.cdm.core.model

@JvmInline
value class Category(val id: String) {
    companion object {
        val PHONE = Category("phone")
        val WATCH = Category("watch")
        val ANY = Category("any")
    }
}

data class HolderId(val sessionId: String, val agentId: String = MAIN) {
    val key get() = "$sessionId:$agentId"

    companion object {
        const val MAIN = "main"

        fun parse(key: String): HolderId {
            val i = key.indexOf(':')
            return if (i < 0) HolderId(key) else HolderId(key.substring(0, i), key.substring(i + 1).ifEmpty { MAIN })
        }
    }
}

/** Hardware serial (`ro.serialno`). */
@JvmInline
value class DeviceKey(val hardwareSerial: String)
