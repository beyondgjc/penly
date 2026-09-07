package com.beyondguo.penly.security

import org.junit.Assert.assertEquals
import org.junit.Test

class BreachCheckerTest {

    @Test
    fun sha1Hex_knownVector() {
        // SHA-1("password") = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", BreachChecker.sha1Hex("password"))
    }

    @Test
    fun parseBreachCount_matchesSuffix() {
        // HIBP 区间响应每行是「后缀(前 5 位之外):次数」；password 的 SHA-1 前 5 位=5BAA6，后缀如下
        val body = """
            1E4C9B93F3CA358EA3A90A4CC34CF54C4A0:3861493
            ABCDEF0123456789ABCDEF0123456789ABCDEF01:1
            FEDCBA9876543210FEDCBA9876543210FEDCBA98:2
        """.trimIndent()
        val suffix = "1E4C9B93F3CA358EA3A90A4CC34CF54C4A0"
        assertEquals(3861493, BreachChecker.parseBreachCount(body, suffix))
    }

    @Test
    fun parseBreachCount_noMatch_returnsZero() {
        val body = "ABCDEF:1\nGHIJKL:2\n"
        assertEquals(0, BreachChecker.parseBreachCount(body, "ZZZZZZ"))
    }

    @Test
    fun parseBreachCount_ignoresCase() {
        val body = "abcdef:42\n"
        assertEquals(42, BreachChecker.parseBreachCount(body, "ABCDEF"))
    }
}
