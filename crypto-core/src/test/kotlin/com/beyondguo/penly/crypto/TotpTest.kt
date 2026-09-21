package com.beyondguo.penly.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    /**
     * SHA256/SHA512 算法锚点（N1 修复回归）：期望值以 Python hmac/hashlib 标准库
     * 现场生成为权威源（counter=t//30；首次诊断曾把 t 误作 counter，弃其输出）。
     * 种子沿用 20 字节 ASCII 串。
     */
    @Test
    fun sha256_sha512_vectors() {
        val cases = listOf(
            59L to listOf("247374", "342147"),
            1_111_111_109L to listOf("756375", "049338"),
            1_111_111_111L to listOf("584430", "380122"),
        )
        for ((t, expected) in cases) {
            assertEquals("t=$t SHA256", expected[0], Totp.generate(rfcSecret, t, digits = 6, algo = "SHA256"))
            assertEquals("t=$t SHA512", expected[1], Totp.generate(rfcSecret, t, digits = 6, algo = "SHA512"))
        }
        // 8 位形态锚点（与首次真机观测一致）
        assertEquals("32247374", Totp.generate(rfcSecret, 59, digits = 8, algo = "SHA256"))
    }

    /** otpauth 链接的 algorithm 参数提取 + 未知算法必须抛错（拒绝优于静默算错，N1） */
    @Test
    fun otpauth_algorithm_param() {
        val p = Totp.parseInput(
            "otpauth://totp/Azure:user?secret=JBSWY3DPEHPK3PXP&issuer=Azure&algorithm=SHA256",
        )
        assertEquals("SHA256", p.algo)
        assertEquals("SHA-1 容错写法", "SHA1", Totp.parseInput("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=SHA-1").algo)
        assertThrows(IllegalArgumentException::class.java) {
            Totp.parseInput("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=MD5")
        }
        // generate 层同样拒绝
        assertThrows(IllegalArgumentException::class.java) {
            Totp.generate(rfcSecret, 59, algo = "MD5")
        }
    }

    /** 编辑回存：algo 与 digits/period 同语义——裸 base32 保留存量，链接优先 */
    @Test
    fun resolveEditParams_algo_preserved_and_uri_wins() {
        // 裸 base32 + 条目已存 SHA256 → 保留
        assertEquals("SHA256", Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 6, 30, "SHA256").algo)
        // 裸 base32 + 条目无 algo → 默认 SHA1
        assertEquals("SHA1", Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 6, 30, "").algo)
        // 链接带 algorithm=SHA256 → 链接优先
        assertEquals(
            "SHA256",
            Totp.resolveEditParams("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256", 6, 30, "SHA1").algo,
        )
    }

    /** 编辑回存语义（P1 回归锚）：裸 base32 输入保留条目已存参数，绝不能回落默认 6/30 */
    @Test
    fun resolveEditParams_bare_base32_preserves_stored_params() {
        val uri = "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=8&period=60"
        val stored = Totp.parseInput(uri)
        // 模拟编辑页：预填的是规范化 base32（不是链接），条目已存 8/60
        val resolved = Totp.resolveEditParams(stored.secret, stored.digits, stored.period)
        assertEquals(8, resolved.digits)
        assertEquals(60, resolved.period)
    }

    /** 编辑回存：条目无参数（0/0，老数据或手输 base32 新建）回落默认 6/30 */
    @Test
    fun resolveEditParams_no_stored_params_falls_back_to_defaults() {
        val resolved = Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 0, 0)
        assertEquals("JBSWY3DPEHPK3PXP", resolved.secret)
        assertEquals(6, resolved.digits)
        assertEquals(30, resolved.period)
    }

    /** 编辑回存：用户主动粘贴新 otpauth 链接时，链接参数优先于条目已存参数 */
    @Test
    fun resolveEditParams_uri_params_win() {
        val resolved = Totp.resolveEditParams(
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=8&period=60",
            6, 30,
        )
        assertEquals(8, resolved.digits)
        assertEquals(60, resolved.period)
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

    // ==================== 边界加固（v4.0） ====================

    /** otpauth://hotp/ 是计数器型（RFC 4226），无 period 语义——当 TOTP 导入会生成永远错配的码 */
    @Test
    fun otpauth_hotp_link_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Totp.parseInput("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=0")
        }
    }

    /** 未知 type 段一并拒绝 */
    @Test
    fun otpauth_unknown_type_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Totp.parseInput("otpauth://weird/x?secret=JBSWY3DPEHPK3PXP")
        }
    }

    /**
     * Passkey 跨设备码（FIDO:/ + base10 CBOR）必须被单独识别，
     * 提示文案要指向"扫错码"而非"格式错"。
     * 若不加这条分支，会落到 normalizeBase32 报「密钥含非 base32 字符」——
     * 用户完全无法据此判断该怎么做（2026-09-20 实测踩到，FIDO hybrid 载荷确实非 base32）。
     */
    @Test
    fun fido_hybrid_qr_code_rejected_withDedicatedMessage() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            Totp.parseInput("FIDO:/0782413389260407027892396947200830109947622896628611305147669918")
        }
        val msg = e.message ?: ""
        assertTrue("提示应说明是 Passkey 跨设备码：$msg", msg.contains("Passkey"))
        assertTrue("提示应给出替代做法：$msg", msg.contains("系统凭据管理器"))
    }

    /** 前缀大小写不敏感（规范写 FIDO:/，但扫码内容不应因大小写放行到 base32 分支） */
    @Test
    fun fido_hybrid_prefix_isCaseInsensitive() {
        assertThrows(IllegalArgumentException::class.java) { Totp.parseInput("fido:/0000") }
    }

    /** 反向保护：裸 base32 密钥不能被上面这条分支误伤 */
    @Test
    fun bare_base32_stillAccepted_afterFidoGuard() {
        val p = Totp.parseInput("JBSWY3DPEHPK3PXP")
        assertEquals("JBSWY3DPEHPK3PXP", p.secret)
    }

    /**
     * digits 参数「出现但越界」必须抛错而非静默回落默认 6——
     * 回落会生成与网站参数不一致的码，且用户无从发现（与 algorithm 的 N1 教训同口径）
     */
    @Test
    fun otpauth_digits_outOfRange_rejected_notSilentlyDefaulted() {
        for (bad in listOf("0", "1", "5", "9", "12", "abc")) {
            assertThrows("digits=$bad", IllegalArgumentException::class.java) {
                Totp.parseInput("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=$bad")
            }
        }
    }

    /** period 参数「出现但越界」同上：抛错不回落 */
    @Test
    fun otpauth_period_outOfRange_rejected_notSilentlyDefaulted() {
        for (bad in listOf("0", "-30", "3601", "abc")) {
            assertThrows("period=$bad", IllegalArgumentException::class.java) {
                Totp.parseInput("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=$bad")
            }
        }
    }

    /** digits 写入域收紧为 RFC 6238 定义的 6-8 位（1-5/9+ 位的服务端不存在，接受只会算错） */
    @Test
    fun digits_domain_narrowed_to_6_8() {
        assertThrows(IllegalArgumentException::class.java) { Totp.generate(rfcSecret, 59, digits = 5) }
        assertThrows(IllegalArgumentException::class.java) { Totp.generate(rfcSecret, 59, digits = 9) }
    }

    /** 负时间戳（时钟异常）拒绝——负 counter 的编码行为未定义 */
    @Test
    fun generate_rejects_negative_time() {
        assertThrows(IllegalArgumentException::class.java) { Totp.generate(rfcSecret, -1) }
    }

    /** 全 padding / 空串输入：解不出任何字节，必须拒绝而非返回空数组 */
    @Test
    fun base32Decode_emptyInput_rejected() {
        assertThrows(IllegalArgumentException::class.java) { Totp.base32Decode("===") }
        assertThrows(IllegalArgumentException::class.java) { Totp.base32Decode("") }
        assertThrows(IllegalArgumentException::class.java) { Totp.base32Decode(" - ") }
    }

    /** 存量参数越界（历史脏数据，如旧版接受过的 digits=9）在编辑回存时回落默认 */
    @Test
    fun resolveEditParams_storedParams_outOfRange_falls_back() {
        assertEquals(6, Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 9, 30).digits)
        assertEquals(6, Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 5, 30).digits)
        assertEquals(30, Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 6, 0).period)
        assertEquals(30, Totp.resolveEditParams("JBSWY3DPEHPK3PXP", 6, 7200).period)
    }

    /** secret 参数存在但为空（&secret=）与缺失（无参数）同罪 */
    @Test
    fun otpauth_emptySecret_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Totp.parseInput("otpauth://totp/x?secret=&issuer=GitHub")
        }
    }

    /** 7 位（RFC 允许的中间形态）：RFC 向量 S=94287082 → mod 10^7 = 4287082 */
    @Test
    fun digits_7_supported() {
        assertEquals("4287082", Totp.generate(rfcSecret, 59, digits = 7))
    }
}
