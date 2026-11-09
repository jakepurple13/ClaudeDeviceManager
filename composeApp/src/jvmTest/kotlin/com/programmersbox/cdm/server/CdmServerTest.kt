package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.Platform
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
                AcquireResult.Granted("W-SERIAL", "Watch 4", Platform.ANDROID)
            }
        }
        app(m) {
            coroutineScope {
                val body = async { client.post("/cdm/acquire?holder=s:main&cwd=/x&category=watch&timeoutSec=5").bodyAsText() }
                gate.complete(Unit)
                assertEquals("QUEUED 1\nGRANTED android W-SERIAL Watch 4\n", body.await())
            }
        }
        assertEquals(Wants(setOf(Category.WATCH)), m.lastWants)
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
        assertEquals(Wants(setOf(Category.PHONE, Category.WATCH)), m.lastWants)
    }

    @Test
    fun acquireBlockedAndEmptyCategory() {
        val m = FakeManager()
        app(m) {
            assertEquals("BLOCKED", client.post("/cdm/acquire?holder=s:main&cwd=/x").bodyAsText().trim())
        }
        assertEquals(Wants(emptySet()), m.lastWants)
    }

    @Test
    fun releaseAndStatusLines() {
        val m = FakeManager()
        app(m) {
            assertEquals("NONE", client.post("/cdm/release?holder=s:main").bodyAsText().trim())
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.Queued(2, com.programmersbox.cdm.core.allocator.Wants.ANY) }
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
    fun acquireIosFlagQueuesWithPlatform() {
        val m = FakeManager()
        app(m) {
            client.post("/cdm/acquire?holder=s:main&cwd=/x&category=phone&platform=ios").bodyAsText()
            assertEquals(Wants(setOf(Category.PHONE), Platform.IOS), m.lastWants)
            client.post("/cdm/acquire?holder=s:main&cwd=/x&platform=android&virtual=true").bodyAsText()
            assertEquals(Wants(emptySet(), Platform.ANDROID, virtual = true), m.lastWants)
            val bad = client.post("/cdm/acquire?holder=s:main&cwd=/x&platform=windows")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        }
    }

    @Test
    fun acquireRealFlagSetsVirtualFalse() {
        val m = FakeManager()
        app(m) {
            client.post("/cdm/acquire?holder=s:main&cwd=/x&category=tablet&virtual=false").bodyAsText()
            assertEquals(Wants(setOf(Category.TABLET), null, virtual = false), m.lastWants)
            assertEquals(HttpStatusCode.BadRequest, client.post("/cdm/acquire?holder=s:main&cwd=/x&virtual=maybe").status)
        }
    }

    @Test
    fun grantedLineCarriesPlatform() {
        val m = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.Granted("UDID-1", "iPhone 16 Pro", Platform.IOS, pairSerial = "W-UDID") } }
        app(m) {
            assertEquals("GRANTED ios UDID-1 iPhone 16 Pro\n", client.post("/cdm/acquire?holder=s:main&cwd=/x&platform=ios").bodyAsText())
            m.acquireImpl = { _, _, _, _, _ -> AcquireResult.Granted("SER", "Pixel 8", Platform.ANDROID) }
            assertEquals("GRANTED android SER Pixel 8\n", client.post("/cdm/acquire?holder=s:main&cwd=/x").bodyAsText())
        }
    }

    @Test
    fun releaseIosKeepsAndroid() {
        val m = FakeManager()
        val h = HolderId("s", "main")
        app(m) {
            m.holdingsImpl = { listOf(holding("A", Platform.ANDROID), holding("I", Platform.IOS)) }
            assertEquals("RELEASED", client.post("/cdm/release?holder=s:main&platform=ios").bodyAsText().trim())
            assertEquals("RELEASED", client.post("/cdm/release?holder=s:main").bodyAsText().trim())
            // Only an Android hold: --ios has nothing to release.
            m.holdingsImpl = { listOf(holding("A", Platform.ANDROID)) }
            assertEquals("NONE", client.post("/cdm/release?holder=s:main&platform=ios").bodyAsText().trim())
        }
        assertEquals(listOf(h to Platform.IOS, h to null, h to Platform.IOS), m.released)
    }

    @Test
    fun statusListsBothHolds() {
        val m = object : FakeManager() {
            override val snapshot = kotlinx.coroutines.flow.MutableStateFlow(
                com.programmersbox.cdm.core.allocator.AllocatorSnapshot(
                    devices = listOf(
                        com.programmersbox.cdm.core.allocator.AllocDevice(com.programmersbox.cdm.core.model.DeviceKey("A"), "Pixel 8", Category.PHONE, false, listOf("SER-A")),
                        com.programmersbox.cdm.core.allocator.AllocDevice(com.programmersbox.cdm.core.model.DeviceKey("I"), "iPhone 16 Pro", Category.PHONE, false, listOf("I"), Platform.IOS, virtual = true),
                    ),
                    holds = emptyMap(), queue = emptyList(), statuses = emptyMap(),
                ),
            )
        }
        m.holdingsImpl = { listOf(holding("A", Platform.ANDROID), holding("I", Platform.IOS)) }
        m.statusImpl = { holding("A", Platform.ANDROID) }
        app(m) {
            assertEquals("HOLDING android SER-A Pixel 8\nHOLDING ios I iPhone 16 Pro\n", client.get("/cdm/status?holder=s:main").bodyAsText())
            // Holding Android while queued for iOS: both show.
            m.holdingsImpl = { listOf(holding("A", Platform.ANDROID)) }
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.Queued(1, Wants(setOf(Category.PHONE), Platform.IOS)) }
            assertEquals("HOLDING android SER-A Pixel 8\nQUEUED 1\n", client.get("/cdm/status?holder=s:main").bodyAsText())
        }
    }

    private fun holding(key: String, p: Platform) = com.programmersbox.cdm.core.allocator.HolderStatus.Holding(
        com.programmersbox.cdm.core.model.DeviceKey(key), kotlin.time.Instant.fromEpochMilliseconds(0), kotlin.time.Instant.fromEpochMilliseconds(0), false, p,
    )

    private val pixel = com.programmersbox.cdm.core.allocator.AllocDevice(
        com.programmersbox.cdm.core.model.DeviceKey("A"), "Pixel 8", Category.PHONE, false, listOf("SER-A"),
    )
    private val iphone = com.programmersbox.cdm.core.allocator.AllocDevice(
        com.programmersbox.cdm.core.model.DeviceKey("I"), "iPhone 16 Pro", Category.PHONE, false, listOf("I"), Platform.IOS, virtual = true,
    )

    @Test
    fun acquireMembersParsesCountsAndCategories() {
        val m = FakeManager()
        app(m) {
            client.post("/cdm/acquire?holder=s:main&cwd=/x&member=android:2:phone&member=ios:1").bodyAsText()
            assertEquals(
                com.programmersbox.cdm.core.allocator.SetRequest(
                    listOf(
                        Wants(setOf(Category.PHONE), Platform.ANDROID),
                        Wants(setOf(Category.PHONE), Platform.ANDROID),
                        Wants(emptySet(), Platform.IOS),
                    ),
                ),
                m.lastSet,
            )
            // --category fills members without their own; --real applies to every member; a comma list keeps either kind.
            client.post("/cdm/acquire?holder=s:main&cwd=/x&category=tablet&virtual=false&member=android:&member=ios:phone,watch").bodyAsText()
            assertEquals(
                listOf(
                    Wants(setOf(Category.TABLET), Platform.ANDROID, virtual = false),
                    Wants(setOf(Category.PHONE, Category.WATCH), Platform.IOS, virtual = false),
                ),
                m.lastSet!!.members,
            )
            for (bad in listOf("android:0", "android:-1", "android:x:y:z", "windows:1", "android:2:", "android::phone", "android:0:phone", "android:65")) {
                val r = client.post("/cdm/acquire?holder=s:main&cwd=/x&member=$bad&member=ios:1")
                assertEquals(HttpStatusCode.BadRequest, r.status, bad)
            }
        }
    }

    @Test
    fun memberSpecParsing() {
        val none = emptySet<Category>()
        assertEquals(listOf(Wants(none, Platform.ANDROID)), memberWants("android:", none, null).getOrThrow())
        assertEquals(listOf(Wants(none, Platform.IOS)), memberWants("ios", none, null).getOrThrow())
        assertEquals(List(3) { Wants(none, Platform.IOS) }, memberWants("ios:3", none, null).getOrThrow())
        assertEquals(listOf(Wants(setOf(Category.WATCH), Platform.ANDROID)), memberWants("android:watch", none, null).getOrThrow())
        assertEquals(
            List(2) { Wants(setOf(Category.PHONE, Category.WATCH), Platform.ANDROID, virtual = true) },
            memberWants("android:2:phone,watch", setOf(Category.TABLET), true).getOrThrow(),
        )
        assertEquals(List(2) { Wants(setOf(Category.TABLET), Platform.ANDROID) }, memberWants("android:2", setOf(Category.TABLET), null).getOrThrow())
        for (bad in listOf("android:0", "android:-1", "android:x:y:z", "windows:1", ":1", "android:2:", "android::phone", "android:phone,", "android:99999999999", "android:65", "ios:65:phone")) {
            assertTrue(memberWants(bad, none, null).isFailure, bad)
        }
        assertEquals(64, memberWants("android:64", none, null).getOrThrow().size)
        assertEquals("bad member \"android:65\": count must be 1..64", memberWants("android:65", none, null).exceptionOrNull()?.message)
    }

    @Test
    fun acquireSetStreamsMemberLinesThenDone() {
        val m = FakeManager().apply {
            acquireSetImpl = { _, _, _, _, onPos ->
                onPos(1)
                AcquireResult.GrantedSet(
                    listOf(
                        com.programmersbox.cdm.service.GrantedMember("android1", Platform.ANDROID, "SER-A", "Pixel 8", null),
                        com.programmersbox.cdm.service.GrantedMember("ios1", Platform.IOS, "UDID-1", "iPhone 16 Pro", "W-UDID"),
                    ),
                )
            }
        }
        app(m) {
            assertEquals(
                "QUEUED 1\nMEMBER android1 android SER-A Pixel 8\nMEMBER ios1 ios UDID-1 iPhone 16 Pro\nDONE\n",
                client.post("/cdm/acquire?holder=s:main&cwd=/x&member=android:1&member=ios:1").bodyAsText(),
            )
            m.acquireSetImpl = { _, _, _, _, _ -> AcquireResult.Refused("You already hold devices. Run `cdm release` first.") }
            val r = client.post("/cdm/acquire?holder=s:main&cwd=/x&member=android:2")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("You already hold devices. Run `cdm release` first.\n", r.bodyAsText())
            // A refused single acquire (the holder holds a set) answers 400 too.
            m.acquireImpl = { _, _, _, _, _ -> AcquireResult.Refused("nope") }
            val single = client.post("/cdm/acquire?holder=s:main&cwd=/x")
            assertEquals(HttpStatusCode.BadRequest, single.status)
            assertEquals("nope\n", single.bodyAsText())
        }
    }

    /** An immediate refusal answers 400 even when the acquire finishes on another thread. */
    @Test
    fun immediateRefusalFromAnotherThreadIs400() {
        val m = FakeManager().apply {
            acquireSetImpl = { _, _, _, _, _ ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AcquireResult.Refused("no") }
            }
            acquireImpl = { _, _, _, _, _ ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { AcquireResult.Refused("no") }
            }
        }
        // The real CIO engine, whose dispatcher is multi-threaded.
        val pf = java.nio.file.Files.createTempDirectory("cdm-race").resolve("port")
        val server = CdmServer(m, port = 0, portFile = pf)
        val port = kotlinx.coroutines.runBlocking { server.start() }
        try {
            val http = java.net.http.HttpClient.newHttpClient()
            repeat(25) { i ->
                val q = if (i % 2 == 0) "&member=android:2" else ""
                val req = java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/cdm/acquire?holder=s:main&cwd=/x$q"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()
                val r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                assertEquals(400, r.statusCode(), "attempt $i")
                assertEquals("no\n", r.body(), "attempt $i")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun acquireDevicesFormASet() {
        val m = FakeManager().apply { snapshotFlow.value = snapshotFlow.value.copy(devices = listOf(pixel, iphone)) }
        app(m) {
            client.post("/cdm/acquire?holder=s:main&cwd=/x&device=SER-A&device=iPhone%2016%20Pro").bodyAsText()
            assertEquals(
                listOf(
                    Wants(setOf(Category.ANY), Platform.ANDROID, device = pixel.key),
                    Wants(setOf(Category.ANY), Platform.IOS, device = iphone.key),
                ),
                m.lastSet!!.members,
            )
            client.post("/cdm/acquire?holder=s:main&cwd=/x&device=SER-A&member=ios:1").bodyAsText()
            assertEquals(listOf(Platform.IOS, Platform.ANDROID), m.lastSet!!.members.map { it.platform })
        }
    }

    @Test
    fun statusListsMembers() {
        val m = FakeManager().apply { snapshotFlow.value = snapshotFlow.value.copy(devices = listOf(pixel, iphone)) }
        val t = kotlin.time.Instant.fromEpochMilliseconds(0)
        val set = com.programmersbox.cdm.core.allocator.HeldSet(
            listOf(
                com.programmersbox.cdm.core.allocator.SetMember("android1", Platform.ANDROID, pixel.key),
                com.programmersbox.cdm.core.allocator.SetMember("ios1", Platform.IOS, iphone.key),
            ),
            t, t, false,
        )
        m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.HoldingSet(set) }
        app(m) {
            assertEquals("MEMBER android1 android SER-A Pixel 8\nMEMBER ios1 ios I iPhone 16 Pro\n", client.get("/cdm/status?holder=s:main").bodyAsText())
            // Release answers RELEASED for a held set, and for the platform of one of its members only.
            assertEquals("RELEASED", client.post("/cdm/release?holder=s:main").bodyAsText().trim())
            assertEquals("RELEASED", client.post("/cdm/release?holder=s:main&platform=ios").bodyAsText().trim())
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.HoldingSet(set.copy(members = set.members.take(1))) }
            assertEquals("NONE", client.post("/cdm/release?holder=s:main&platform=ios").bodyAsText().trim())
            // A queued set is released by any release.
            val req = com.programmersbox.cdm.core.allocator.SetRequest(List(2) { Wants(emptySet(), Platform.ANDROID) })
            m.statusImpl = { com.programmersbox.cdm.core.allocator.HolderStatus.Queued(1, Wants(emptySet()), req) }
            assertEquals("RELEASED", client.post("/cdm/release?holder=s:main&platform=ios").bodyAsText().trim())
        }
    }

    @Test
    fun oneMemberUsesLegacyPath() {
        val m = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.Granted("UDID-1", "iPhone 16 Pro", Platform.IOS) } }
        app(m) {
            assertEquals("GRANTED ios UDID-1 iPhone 16 Pro\n", client.post("/cdm/acquire?holder=s:main&cwd=/x&member=ios:1").bodyAsText())
            assertEquals(Wants(emptySet(), Platform.IOS), m.lastWants)
            client.post("/cdm/acquire?holder=s:main&cwd=/x&member=android:watch&virtual=true").bodyAsText()
            assertEquals(Wants(setOf(Category.WATCH), Platform.ANDROID, virtual = true), m.lastWants)
        }
        assertEquals(null, m.lastSet)
    }

    @Test
    fun pingAnswersCdmOk() = app(FakeManager()) {
        assertEquals("cdm-ok", client.get("/cdm/ping").bodyAsText().trim())
    }
}
