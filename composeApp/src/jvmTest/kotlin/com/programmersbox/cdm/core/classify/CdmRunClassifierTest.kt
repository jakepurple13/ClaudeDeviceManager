package com.programmersbox.cdm.core.classify

import kotlin.test.Test
import kotlin.test.assertEquals

class CdmRunClassifierTest {
    @Test
    fun parsesSingle() {
        val c = "cdm run android2 -- adb shell input tap 1 2"
        assertEquals(listOf(CdmRunSegment(0, c.length, "android2", "adb shell input tap 1 2")), CommandClassifier.cdmRuns(c))
    }

    @Test
    fun parsesChained() {
        val c = "cdm run a1 -- adb devices && cdm run all -- adb logcat -d"
        val first = "cdm run a1 -- adb devices ".length
        val r = CommandClassifier.cdmRuns(c)
        assertEquals(2, r.size)
        assertEquals(CdmRunSegment(0, first, "a1", "adb devices"), r[0])
        assertEquals(CdmRunSegment(first + 2, c.length, "all", "adb logcat -d"), r[1])
        assertEquals(" cdm run all -- adb logcat -d", c.substring(r[1].start))
    }

    @Test
    fun parsesPipeline() {
        val r = CommandClassifier.cdmRuns("cdm run android1 -- adb logcat | grep x")
        assertEquals(1, r.size)
        assertEquals("android1", r[0].target)
        assertEquals("adb logcat", r[0].rest)
    }

    @Test
    fun keepsQuotes() {
        val r = CommandClassifier.cdmRuns("cdm run a -- adb shell 'echo a && b' \"\$X\"")
        assertEquals(1, r.size)
        assertEquals("adb shell 'echo a && b' \"\$X\"", r[0].rest)
    }

    @Test
    fun missingDashDash() {
        assertEquals("a", CommandClassifier.cdmRuns("cdm run a adb devices").single().target)
        assertEquals("", CommandClassifier.cdmRuns("cdm run a adb devices").single().rest)
        val none = CommandClassifier.cdmRuns("cdm run").single()
        assertEquals("", none.target)
        assertEquals("", none.rest)
        val noName = CommandClassifier.cdmRuns("cdm run -- adb devices").single()
        assertEquals("", noName.target)
        assertEquals("adb devices", noName.rest)
        assertEquals(emptyList(), CommandClassifier.cdmRuns("cdm status"))
    }

    @Test
    fun withoutCdmRunStripsPrefixes() {
        assertEquals(
            "adb devices && adb logcat | grep x ; echo hi",
            CommandClassifier.withoutCdmRun("cdm run a1 -- adb devices && cdm run all -- adb logcat | grep x ; echo hi"),
        )
        assertEquals("cdm run a1 adb", CommandClassifier.withoutCdmRun("cdm run a1 adb"))
        assertEquals("ls", CommandClassifier.withoutCdmRun("ls"))
    }

    @Test
    fun classifyStaysCdm() {
        assertEquals(listOf<CommandKind>(CommandKind.Cdm("run")), CommandClassifier.classifyAll("cdm run a -- adb devices"))
    }
}
