package com.programmersbox.cdm.core.model

/**
 * A device category. A project default may be a comma-joined composite such as `phone,watch`
 * ([PHONE_WATCH]); split it with [members] before handing it to the allocator.
 */
@JvmInline
value class Category(val id: String) {
    /** The single categories this one stands for: `phone,watch` -> {phone, watch}; blank -> {any}. */
    fun members(): Set<Category> =
        id.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::Category).toSet().ifEmpty { setOf(ANY) }

    companion object {
        val PHONE = Category("phone")
        val WATCH = Category("watch")
        val ANY = Category("any")

        /** Project default for apps that run on either kind of device. */
        val PHONE_WATCH = Category("phone,watch")
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
