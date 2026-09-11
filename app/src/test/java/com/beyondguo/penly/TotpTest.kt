package com.beyondguo.penly

import com.beyondguo.penly.crypto.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * TOTP 算法正确性锚点。
 *
 * RFC 6238 Appendix B 的官方向量基于 8 位码 + ASCII 种子
 * "12345678901234567890"（即其字节值，非 base32）；
 * Google Authenticator 等主流验证器默认 6 位 = 取 8 位的后 6 位。
 */
class TotpTest {

    private val rfcSecret = "12345678901234567890".encodeToByteArray()

    /** RFC 6238 Appendix B 全部 6 组时间锚点（SHA1），按主流验证器的 6 位形态断言 */
    @Test
    fun rfc6238_appendixB_vectors_6digit() {
        val cases = listOf(
            59L to "287082",                    // 94287082
            1_111_111_109L to "081804",         // 07081804
            1_111_111_111L to "050471",         // 14050471
            1_234_567_890L to "005924",         // 89005924
            2_000_000_000L to "279037",         // 69279037
            20_000_000_000L to "353130",        // 65353130
        )
        for ((t, expected) in cases) {
            assertEquals("t=$t", expected, Totp.generate(rfcSecret, t, digits = 6))
        }
    }

    /** 官方向量的原生 8 位形态 */
    @Test
    fun rfc6238_appendixB_vector_8digit() {
        assertEquals("94287082", Totp.generate(rfcSecret, 59, digits = 8))
    }

    /** 相邻时间窗必须产生不同验证码（窗口滚动语义） */
    @Test
    fun adjacent_windows_differ() {
        val a = Totp.generate(rfcSecret, 59, digits = 6)
        val b = Totp.generate(rfcSecret, 59 + 30, digits = 6)
        org.junit.Assert.assertNotEquals(a, b)
    }

    /** base32 解码：向量以 Python 标准库 base64.b32encode 为权威源交叉验证 */
    @Test
    fun base32_rfc4648_vectors() {
        assertEquals("foo".encodeToByteArray().toList(), Totp.base32Decode("MZXW6===").toList())
        assertEquals("foob".encodeToByteArray().toList(), Totp.base32Decode("MZXW6YQ=").toList())
        assertEquals("fooba".encodeToByteArray().toList(), Totp.base32Decode("MZXW6YTB").toList())
        assertEquals("foobar".encodeToByteArray().toList(), Totp.base32Decode("MZXW6YTBOI======").toList())
        assertEquals("abcd".encodeToByteArray().toList(), Totp.base32Decode("MFRGGZA=").toList())
    }

    /** base32 容错：小写 / 空格 / 连字符 / padding 混杂输入 */
    @Test
    fun base32_tolerant_normalization() {
        assertEquals("foo".encodeToByteArray().toList(), Totp.base32Decode("mzxw6===").toList())
        assertEquals("foo".encodeToByteArray().toList(), Totp.base32Decode(" MZXW 6=== ").toList())
    }

    /** otpauth:// 链接提取 secret 与 digits/period 参数 */
    @Test
    fun otpauth_uri_extraction() {
        val p = Totp.parseInput("otpauth://totp/GitHub:user?secret=JBSWY3DPEHPK3PXP&issuer=GitHub")
        assertEquals("JBSWY3DPEHPK3PXP", p.secret)
        assertEquals(6, p.digits)
        assertEquals(30, p.period)
    }

    /** 链接自带 digits/period 时必须采用（网站校验用同一组参数） */
    @Test
    fun otpauth_uri_custom_params() {
        val p = Totp.parseInput(
            "otpauth://totp/Bank:x?secret=JBSWY3DPEHPK3PXP&digits=8&period=60",
        )
        assertEquals("JBSWY3DPEHPK3PXP", p.secret)
        assertEquals(8, p.digits)
        assertEquals(60, p.period)
    }

    /** 裸 base32 输入：规范化 + 默认参数 */
    @Test
    fun bare_base32_defaults() {
        val p = Totp.parseInput(" jbsw y3dp-ehpk 3pxp ")
        assertEquals("JBSWY3DPEHPK3PXP", p.secret)
        assertEquals(6, p.digits)
        assertEquals(30, p.period)
    }

    /** period 语义：同一 period 下窗口滚动一致，不同 period 计数器错开 */
    @Test
    fun period_changes_window_alignment() {
        val s = rfcSecret
        // period=60 时 t=89 与 t=119 落在同一窗口（counter 均为 1）
        assertEquals(Totp.generate(s, 89, 6, 60), Totp.generate(s, 119, 6, 60))
        // period=30 时这两个时刻分属不同窗口（counter 2 与 3）
        org.junit.Assert.assertNotEquals(Totp.generate(s, 89, 6, 30), Totp.generate(s, 119, 6, 30))
    }

    /** 非法输入必须抛错（由 UI 层转错误提示，不允许静默存入坏密钥） */
    @Test
    fun invalid_inputs_rejected() {
        assertThrows(IllegalArgumentException::class.java) { Totp.base32Decode("abc012") } // 含 0/1
        assertThrows(IllegalArgumentException::class.java) { Totp.parseInput("") }
        assertThrows(IllegalArgumentException::class.java) {
            Totp.parseInput("otpauth://totp/x?issuer=GitHub") // 缺 secret
        }
        assertThrows(IllegalArgumentException::class.java) { Totp.generate(ByteArray(0), 59) }
    }
}
