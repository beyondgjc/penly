package com.beyondguo.penly.passkey

import com.beyondguo.penly.crypto.Shamir
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Shamir 2-of-3（遗产交接 #43）数学性质测试：
 * 任意 2 份还原、1 份信息论安全（点值无法推秘密）、文本往返、容错与拒绝路径。
 */
class ShamirTest {

    private val secret = ByteArray(32) { (it * 13 + 7).toByte() }

    @Test
    fun `any two of three shares reconstruct the secret`() {
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        assertEquals(3, shares.size)
        // 全部三种两两组合都能还原
        val combos = listOf(
            listOf(shares[0], shares[1]),
            listOf(shares[0], shares[2]),
            listOf(shares[1], shares[2]),
        )
        for (combo in combos) {
            assertArrayEquals(secret, Shamir.combine(combo))
        }
    }

    @Test
    fun `all shares combined also reconstructs`() {
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        assertArrayEquals(secret, Shamir.combine(shares))
    }

    @Test
    fun `single share fails closed - combine refuses to run`() {
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        // 单份直接拒绝合并（硬性 fail-closed，强于「拼出错值」）：份额数不足即抛异常，
        // 调用方拿不到任何可用于解密 L 的候选值。
        assertThrows(IllegalArgumentException::class.java) {
            Shamir.combine(listOf(shares[0]))
        }
    }

    @Test
    fun `share text roundtrip`() {
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        for (s in shares) {
            val parsed = Shamir.parseText(Shamir.shareText(s))
            assertEquals(s.x, parsed.x)
            assertArrayEquals(s.y, parsed.y)
        }
    }

    @Test
    fun `share text tolerates whitespace and fullwidth dash`() {
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        val text = Shamir.shareText(shares[1])
        val parsed = Shamir.parseText("  ${text.replace('-', '－')} \n")
        assertEquals(shares[1].x, parsed.x)
        assertArrayEquals(shares[1].y, parsed.y)
    }

    @Test
    fun `parse rejects malformed input`() {
        assertThrows(IllegalArgumentException::class.java) { Shamir.parseText("YJH1") }
        assertThrows(IllegalArgumentException::class.java) { Shamir.parseText("YJH1AB-xxx") }
        assertThrows(IllegalArgumentException::class.java) { Shamir.parseText("YJH9-@@@") }
        assertThrows(IllegalArgumentException::class.java) { Shamir.parseText("YJH0-AAAA") }
    }

    @Test
    fun `wrong shares cannot produce the secret`() {
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        // 篡改一份 y 的最后一个字节 → 两份组合结果必然错误（外层 GCM 会兜底拒绝）
        val tampered = Shamir.Share(shares[2].x, shares[2].y.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() })
        assertNotEquals(
            secret.toList(),
            Shamir.combine(listOf(shares[1], tampered)).toList(),
        )
    }

    @Test
    fun `split rejects invalid config and duplicate combine x`() {
        assertThrows(IllegalArgumentException::class.java) { Shamir.split(secret, 1, 3) }
        assertThrows(IllegalArgumentException::class.java) { Shamir.split(secret, 3, 2) }
        val shares = Shamir.split(secret, threshold = 2, shares = 3)
        assertThrows(IllegalArgumentException::class.java) {
            Shamir.combine(listOf(shares[0], shares[0]))
        }
    }

    @Test
    fun `3-of-5 configuration works as well`() {
        val shares = Shamir.split(secret, threshold = 3, shares = 5)
        assertArrayEquals(secret, Shamir.combine(listOf(shares[0], shares[2], shares[4])))
        // 2 份不够（3-of-5 下两份组合结果随机）
        assertNotEquals(secret.toList(), Shamir.combine(listOf(shares[0], shares[1])).toList())
    }
}
