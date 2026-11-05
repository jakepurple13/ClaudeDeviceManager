package com.jacobrein.cdm.server

import com.jacobrein.cdm.service.Decision
import com.jacobrein.cdm.service.HookDecision
import com.jacobrein.cdm.service.HookInput
import com.jacobrein.cdm.service.HookReply
import com.jacobrein.cdm.service.RewriteKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A hook body parsed leniently. [raw] is kept for fields [HookInput] does not carry (`tool_input`, `agent_id`). */
class ParsedHook(val input: HookInput, val toolInput: JsonObject?, val raw: JsonObject)

object HookJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when the body is not a JSON object or has no `session_id`. Never throws. */
    fun parse(body: String): ParsedHook? {
        val raw = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val session = raw.str("session_id")?.takeIf { it.isNotEmpty() } ?: return null
        val toolInput = (raw["tool_input"] as? JsonObject)
        val input = HookInput(
            hookEventName = raw.str("hook_event_name") ?: "",
            sessionId = session,
            agentId = raw.str("agent_id")?.takeIf { it.isNotEmpty() },
            agentType = raw.str("agent_type"),
            cwd = raw.str("cwd") ?: "",
            toolName = raw.str("tool_name"),
            command = toolInput?.str("command"),
            permissionMode = raw.str("permission_mode"),
        )
        return ParsedHook(input, toolInput, raw)
    }

    /** The JSON answer for a PreToolUse hook. */
    fun render(decision: HookDecision, toolInput: JsonObject?): JsonObject = when (val r = decision.reply) {
        HookReply.NoOpinion -> JsonObject(emptyMap())
        is HookReply.Deny -> hookOutput("deny", r.reason)
        is HookReply.Rewrite -> {
            val perm = when (decision.permission) {
                Decision.ALLOW -> "allow"
                else -> "ask" // null on a Rewrite should not happen; fail safe
            }
            val updated = buildJsonObject {
                toolInput?.forEach { (k, v) -> if (k != "command") put(k, v) }
                put("command", r.command)
            }
            hookOutput(perm, reasonFor(r), updated)
        }
    }

    private fun reasonFor(r: HookReply.Rewrite): String = when (r.kind) {
        RewriteKind.CDM -> "Claude Device Manager identity"
        RewriteKind.PIN -> {
            val serial = Regex("""ANDROID_SERIAL=(\S+?);""").find(r.command)?.groupValues?.get(1) ?: "device"
            "Pinned to $serial by Claude Device Manager"
        }
    }

    private fun hookOutput(permission: String, reason: String, updated: JsonObject? = null) = buildJsonObject {
        put("hookSpecificOutput", buildJsonObject {
            put("hookEventName", "PreToolUse")
            put("permissionDecision", permission)
            put("permissionDecisionReason", reason)
            if (updated != null) put("updatedInput", updated)
        })
    }

    private fun JsonObject.str(key: String): String? {
        val e: JsonElement = this[key] ?: return null
        if (e is JsonNull) return null
        return (e as? JsonPrimitive)?.contentOrNull
    }
}
