package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.service.AcquireResult
import com.programmersbox.cdm.service.Decision
import com.programmersbox.cdm.service.HookDecision
import com.programmersbox.cdm.service.HookReply
import com.programmersbox.cdm.service.RewriteKind
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CdmServerTest {
    private fun app(m: FakeManager, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { cdmModule(m) }
            block()
        }

    private fun hso(body: String) = Json.parseToJsonElement(body).jsonObject["hookSpecificOutput"]!!.jsonObject

    @Test
    fun malformedHookBodyIsEmptyObject() = app(FakeManager()) {
        for (body in listOf("", "not json", """{"hook_event_name":"PreToolUse","tool_name":"Bash"}""", """{"hook_event_name":"Weird"}""")) {
            val r = client.post("/hooks/pre-tool-use") { setBody(body) }
            assertEquals(HttpStatusCode.OK, r.status, body)
            assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(r.bodyAsText()), body)
        }
    }

    @Test
    fun managerExceptionStill200Empty() = app(object : FakeManager() {
        override suspend fun preToolUse(input: com.programmersbox.cdm.service.HookInput): HookDecision = error("boom")
    }) {
        val r = client.post("/hooks/pre-tool-use") { setBody("""{"session_id":"s","hook_event_name":"PreToolUse","tool_input":{"command":"ls"}}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("{}", r.bodyAsText())
    }

    @Test
    fun denyShape() = app(FakeManager().apply { decision = HookDecision(HookReply.Deny("x"), null) }) {
        val r = client.post("/hooks/pre-tool-use") { setBody("""{"session_id":"s","hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"adb devices"}}""") }
        val o = hso(r.bodyAsText())
        assertEquals("PreToolUse", o["hookEventName"]!!.jsonPrimitive.content)
        assertEquals("deny", o["permissionDecision"]!!.jsonPrimitive.content)
        assertEquals("x", o["permissionDecisionReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun rewriteKeepsOtherToolInputFields() {
        val pinned = "Pinned to ABC by Claude Device Manager"
        val asks = "$pinned; needs approval because the pinned command no longer matches your allow rules"
        for ((d, expected, reason) in listOf(Triple(Decision.ALLOW, "allow", pinned), Triple(Decision.ASK, "ask", asks))) {
            val m = FakeManager().apply {
                decision = HookDecision(HookReply.Rewrite("export ANDROID_SERIAL=ABC; adb shell ls", "adb shell ls", RewriteKind.PIN), d)
            }
            app(m) {
                val r = client.post("/hooks/pre-tool-use") {
                    setBody("""{"session_id":"s","hook_event_name":"PreToolUse","permission_mode":"auto","tool_name":"Bash","tool_input":{"command":"adb shell ls","description":"d","timeout":5000}}""")
                }
                val o = hso(r.bodyAsText())
                assertEquals(expected, o["permissionDecision"]!!.jsonPrimitive.content)
                assertEquals(reason, o["permissionDecisionReason"]!!.jsonPrimitive.content)
                val u = o["updatedInput"]!!.jsonObject
                assertEquals("export ANDROID_SERIAL=ABC; adb shell ls", u["command"]!!.jsonPrimitive.content)
                assertEquals("d", u["description"]!!.jsonPrimitive.content)
                assertEquals(5000, u["timeout"]!!.jsonPrimitive.int)
                assertEquals("auto", m.inputs.single().permissionMode)
            }
        }
    }

    @Test
    fun rewriteWithNullPermissionFailsSafeToAsk() = app(FakeManager().apply {
        decision = HookDecision(HookReply.Rewrite("cdm acquire --holder s:main --cwd '/x'", "cdm acquire", RewriteKind.CDM), null)
    }) {
        val r = client.post("/hooks/pre-tool-use") { setBody("""{"session_id":"s","hook_event_name":"PreToolUse","tool_input":{"command":"cdm acquire"}}""") }
        val o = hso(r.bodyAsText())
        assertEquals("ask", o["permissionDecision"]!!.jsonPrimitive.content)
        assertEquals("Claude Device Manager identity", o["permissionDecisionReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun subagentIdentityFromAgentId() {
        val m = FakeManager()
        app(m) {
            client.post("/hooks/pre-tool-use") { setBody("""{"session_id":"s","hook_event_name":"PreToolUse","agent_id":"agent-1","tool_input":{"command":"ls"}}""") }
            client.post("/hooks/pre-tool-use") { setBody("""{"session_id":"s","hook_event_name":"PreToolUse","tool_input":{"command":"ls"}}""") }
        }
        assertEquals(listOf(HolderId("s", "agent-1"), HolderId("s", "main")), m.inputs.map { it.holder })
    }

    @Test
    fun subagentStopAndSessionEnd() {
        val m = FakeManager()
        app(m) {
            assertEquals("{}", client.post("/hooks/subagent-stop") { setBody("""{"session_id":"s","agent_id":"a1","hook_event_name":"SubagentStop"}""") }.bodyAsText())
            assertEquals("{}", client.post("/hooks/session-end") { setBody("""{"session_id":"s","hook_event_name":"SessionEnd"}""") }.bodyAsText())
            assertEquals("{}", client.post("/hooks/subagent-stop") { setBody("garbage") }.bodyAsText())
        }
        assertEquals(listOf("s" to "a1"), m.stops)
        assertEquals(listOf("s"), m.ended)
    }

    @Test
    fun acquireStreamsQueuedThenGranted() {
        val gate = CompletableDeferred<Unit>()
        val m = FakeManager().apply {
            acquireImpl = { _, _, _, _, onPos ->
                onPos(1)
                gate.await()
                AcquireResult.Granted("W-SERIAL", "Watch 4")
            }
        }
        app(m) {
            coroutineScope {
                val body = async { client.post("/cdm/acquire?holder=s:main&cwd=/x&category=watch&timeoutSec=5").bodyAsText() }
                gate.complete(Unit)
                assertEquals("QUEUED 1\nGRANTED W-SERIAL Watch 4\n", body.await())
            }
        }
        assertEquals(setOf(Category.WATCH), m.lastWants)
    }

    @Test
    fun acquireParsesCategoriesAndTimesOut() {
        val m = FakeManager().apply {
            acquireImpl = { _, _, _, timeout, onPos ->
                onPos(1)
                withTimeoutOrNull(timeout) { CompletableDeferred<Unit>().await() }
                AcquireResult.TimedOut(1)
            }
        }
        app(m) {
            val body = client.post("/cdm/acquire?holder=s:main&cwd=/x&category=phone,watch&timeoutSec=1").bodyAsText()
            assertEquals("QUEUED 1\nTIMEOUT 1", body.trim())
        }
        assertEquals(setOf(Category.PHONE, Category.WATCH), m.lastWants)
    }

    @Test
    fun acquireBlockedAndEmptyCategory() {
        val m = FakeManager()
        app(m) {
            assertEquals("BLOCKED", client.post("/cdm/acquire?holder=s:main&cwd=/x").bodyAsText().trim())
        }
        assertEquals(emptySet(), m.lastWants)
    }

    @Test
    fun releaseAndStatusLines() {
        val m = FakeManager()
        app(m) {
            assertEquals("NONE", client.post("/cdm/release?holder=s:main").bodyAsText().trim())
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.Queued(2, setOf(Category.ANY)) }
            assertEquals("RELEASED", client.post("/cdm/release?holder=s:main").bodyAsText().trim())
            assertEquals("QUEUED 2", client.get("/cdm/status?holder=s:main").bodyAsText().trim())
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.Blocked }
            assertEquals("BLOCKED", client.get("/cdm/status?holder=s:main").bodyAsText().trim())
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.None }
            assertEquals("NONE", client.get("/cdm/status?holder=s:main").bodyAsText().trim())
            assertTrue(client.get("/cdm/status").status == HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun unknownEventWithSessionAndBashCommandIsNoOp() {
        val m = FakeManager()
        app(m) {
            val r = client.post("/hooks/pre-tool-use") {
                setBody("""{"session_id":"s","hook_event_name":"Weird","tool_name":"Bash","tool_input":{"command":"adb devices"}}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("{}", r.bodyAsText())
        }
        assertTrue(m.inputs.isEmpty(), "service must not be called for unknown events")
    }

    @Test
    fun pingAnswersCdmOk() = app(FakeManager()) {
        assertEquals("cdm-ok", client.get("/cdm/ping").bodyAsText().trim())
    }
}
