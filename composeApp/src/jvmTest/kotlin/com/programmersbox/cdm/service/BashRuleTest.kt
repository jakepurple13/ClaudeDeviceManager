package com.programmersbox.cdm.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BashRuleTest {
    private fun rule(s: String) = assertNotNull(BashRule.parse(s))

    @Test fun prefixRuleMatchesOnWordBoundary() {
        val r = rule("Bash(adb:*)")
        assertTrue(r.matches("adb shell ls"))
        assertTrue(r.matches("adb"))
        assertFalse(r.matches("adbx"))
        assertFalse(r.matches("xadb"))
    }

    @Test fun exactRule() {
        val r = rule("Bash(adb devices)")
        assertTrue(r.matches("adb devices"))
        assertTrue(r.matches("  adb devices "))
        assertFalse(r.matches("adb devices -l"))
    }

    @Test fun globRule() {
        val r = rule("Bash(./gradlew *)")
        assertTrue(r.matches("./gradlew installDebug"))
        assertFalse(r.matches("gradlew installDebug"))
        assertFalse(r.matches("./gradlewx installDebug"))
        assertTrue(rule("Bash(git * main)").matches("git push origin main"))
        assertFalse(rule("Bash(git * main)").matches("git push origin main2"))
    }

    @Test fun globMetacharsAreLiteral() {
        assertFalse(rule("Bash(a.b *)").matches("aXb c"))
        assertTrue(rule("Bash(a.b *)").matches("a.b c"))
    }

    @Test fun bareBashMatchesAnything() {
        assertTrue(rule("Bash").matches("rm -rf /"))
    }

    @Test fun nonBashRulesAreNull() {
        assertNull(BashRule.parse("Read(**)"))
        assertNull(BashRule.parse("BashOutput(x)"))
        assertNull(BashRule.parse("Bash(unclosed"))
        assertNull(BashRule.parse("Bash()"))
    }
}
