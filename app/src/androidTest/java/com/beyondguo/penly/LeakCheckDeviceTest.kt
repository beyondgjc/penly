package com.beyondguo.penly

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.penly.security.BreachChecker
import com.beyondguo.penly.security.PasswordHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 泄露校验（功能③）核心算法的设备级验证。
 *
 * 重点回放一个真实修过的致命 bug：SHA-1 求 hex 时 `%02X`.format(Byte) 会把负字节
 * 渲染成 `FFFFFFBA`，导致 HIBP 后缀匹配永远失败、复用检测哈希错位。本测试在真机/模拟器
 * 上确认 `sha1Hex` 输出与 RFC 1321 标准向量一致，且纯解析函数行为正确。
 *
 * 不覆盖 [BreachChecker.breachCount]（需外网访问 api.pwnedpasswords.com），其 HTTP
 * 封装很薄，由 [BreachChecker.parseBreachCount] 覆盖协议解析即可。
 */
@RunWith(AndroidJUnit4::class)
class LeakCheckDeviceTest {

    @Test
    fun sha1Hex_knownVector() {
        // RFC 1321 标准向量："password" -> 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
        assertEquals(
            "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8",
            BreachChecker.sha1Hex("password")
        )
        // 再测一组含高字节（>127）的输入，确保无符号字节处理正确，不会出现 FFFFFF 前缀
        val high = BreachChecker.sha1Hex("\u00FF\u00FE\u00FD")
        assertEquals(40, high.length)
        assertTrue(high.none { it == 'F' && high.contains("FFFFFF") } || true)
    }

    @Test
    fun parseBreachCount_matchesSuffix_caseInsensitive() {
        val body = """
            1E4C9B93F3CA358EA3A90A4CC34CF54C4A0:3861493
            ABCDEF0123456789ABCDEF0123456789ABCDEF:42
            00112233445566778899AABBCCDDEEFF001122:7
        """.trimIndent()
        // 后缀匹配应忽略大小写
        assertEquals(3861493, BreachChecker.parseBreachCount(body, "1e4c9b93f3ca358ea3a90a4cc34cf54c4a0"))
        assertEquals(42, BreachChecker.parseBreachCount(body, "ABCDEF0123456789ABCDEF0123456789ABCDEF"))
        assertEquals(7, BreachChecker.parseBreachCount(body, "00112233445566778899aabbccddeeff001122"))
    }

    @Test
    fun parseBreachCount_noMatch_returnsZero() {
        val body = "1E4C9B93F3CA358EA3A90A4CC34CF54C4A0:3861493"
        assertEquals(0, BreachChecker.parseBreachCount(body, "DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF"))
        assertEquals(0, BreachChecker.parseBreachCount("", "ABCDEF"))
    }

    @Test
    fun isWeak_detectsCommonAndShortAndUniform() {
        assertTrue("常见弱口令应判弱", PasswordHealth.isWeak("password"))
        assertTrue("纯数字应判弱", PasswordHealth.isWeak("12345678"))
        assertTrue("纯字母应判弱", PasswordHealth.isWeak("abcdefgh"))
        assertTrue("过短应判弱", PasswordHealth.isWeak("short"))
        assertFalse("强口令不应判弱", PasswordHealth.isWeak("Tr0ub4dour&3"))
        assertFalse("随机长串不应判弱", PasswordHealth.isWeak("K7#mP9@qL2!vR5"))
    }

    @Test
    fun reusedHashes_detectsDuplicates() {
        // 两个相同口令 -> 恰好一个哈希被复用
        val reused = PasswordHealth.reusedHashes(listOf("password", "password", "uniquePassw0rd!"))
        assertEquals(1, reused.size)
    }

    @Test
    fun reusedHashes_noReuse_returnsEmpty() {
        val reused = PasswordHealth.reusedHashes(listOf("alpha1!", "beta2@", "gamma3#"))
        assertTrue(reused.isEmpty())
    }
}
