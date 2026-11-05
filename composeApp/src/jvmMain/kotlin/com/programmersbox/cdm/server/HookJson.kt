package com.programmersbox.cdm.server

import com.programmersbox.cdm.service.Decision
import com.programmersbox.cdm.service.HookDecision
import com.programmersbox.cdm.service.HookInput
import com.programmersbox.cdm.service.HookReply
import com.programmersbox.cdm.service.RewriteKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A hook body parsed leniently; [toolInput] is kept whole so a rewrite can preserve its other fields. */
class ParsedHook(val input: HookInput, val toolInput: JsonObject?)

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
        return ParsedHook(input, toolInput)
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
            hookOutput(perm, reasonFor(r, perm == "ask"), updated)
        }
    }

    private fun reasonFor(r: HookReply.Rewrite, asks: Boolean): String = when (r.kind) {
        RewriteKind.CDM -> "Claude Device Manager identity"
        RewriteKind.PIN -> {
            val serial = Regex("""ANDROID_SERIAL=(\S+?);""").find(r.command)?.groupValues?.get(1) ?: "device"
            val pinned = "Pinned to $serial by Claude Device Manager"
            if (asks) "$pinned; needs approval because the pinned command no longer matches your allow rules" else pinned
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
