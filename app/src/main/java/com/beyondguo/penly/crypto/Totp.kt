package com.beyondguo.penly.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP 两步验证（RFC 6238）—— 纯 Kotlin，零第三方依赖。
 *
 * 原理：网站与验证器共享同一把密钥 K（开通 2FA 时网站以二维码/base32 下发），
 * 双方各自用「K + 当前时间计数 T = floor(Unix秒/period)」执行同一份公开算法
 * （HMAC-SHA1 → 动态截断 → 取模），得到同一个 6 位数字。密钥永不联网传输，
 * 网站登录时只比对数字，因此任何持有 K 的验证器（Google Authenticator / 印迹）
 * 算出的码都有效。
 *
 * 时间对齐不靠两端校时：T 以 Unix 纪元（1970-01-01）为锚点，全球设备同一时刻
 * 算出的 T 相同；秒级时钟误差由服务端"前后窗口都算一遍"的容差兜住。
 *
 * 位数（digits）与刷新间隔（period）由**网站决定**，随 otpauth:// 链接参数下发
 * （缺省 6 位 / 30 秒，覆盖绝大多数站点）；验证器必须用同一组参数才能对上。
 *
 * 正确性由 RFC 6238 Appendix B 官方测试向量锚定（见 TotpTest）。
 */
object Totp {

    /** RFC 4648 base32 字母表（不含 0/1/8/9） */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD = 30

    /**
     * 解析结果：规范化 base32 密钥 + 网站指定的展示/刷新参数。
     * @param secret 规范化 base32 密钥串
     * @param digits 验证码位数（6 = 缺省）
     * @param period 刷新间隔秒（30 = 缺省）
     */
    data class Params(val secret: String, val digits: Int, val period: Int)

    /**
     * Base32 解码（RFC 4648）。
     * 容错：忽略空格/连字符/padding（=），小写自动转大写。
     */
    fun base32Decode(input: String): ByteArray {
        val clean = input.filter { it != ' ' && it != '-' && it != '=' }.uppercase()
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "非法 base32 字符：$c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out[pos++] = ((buffer shr (bits - 8)) and 0xFF).toByte()
                bits -= 8
            }
        }
        return out.copyOf(pos)
    }

    /**
     * 生成指定时刻的验证码。
     * @param secret 共享密钥字节（base32 解码后的原始字节）
     * @param timeSeconds Unix 时间秒
     * @param digits 验证码位数
     * @param period 时间窗秒数
     */
    fun generate(
        secret: ByteArray,
        timeSeconds: Long,
        digits: Int = DEFAULT_DIGITS,
        period: Int = DEFAULT_PERIOD,
    ): String {
        require(secret.isNotEmpty()) { "密钥为空" }
        require(digits in 1..9) { "位数不支持：$digits" }
        require(period in 1..3600) { "刷新间隔不支持：$period" }
        val counter = timeSeconds / period
        // 计数器转 8 字节大端（RFC 6238 §4.1）
        val msg = ByteArray(8).apply {
            for (i in 7 downTo 0) this[i] = (counter shr (8 * (7 - i))).toByte()
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        // 动态截断（RFC 4226 §5.3）：末字节低 4 位为偏移，取 4 字节并屏蔽符号位
        val offset = hash.last().toInt() and 0x0F
        val bin = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return ((bin % modulus).toString().padStart(digits, '0'))
    }

    /** 便捷入口：base32 密钥串 + 当前系统时间 */
    fun generate(
        secretBase32: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        digits: Int = DEFAULT_DIGITS,
        period: Int = DEFAULT_PERIOD,
    ): String = generate(base32Decode(secretBase32), timeSeconds, digits, period)

    /**
     * 规范化用户粘贴的 2FA 密钥，支持两种形态：
     * 1) 光秃秃的 base32 串：去空格/连字符、统一大写，参数用默认 6/30
     * 2) `otpauth://totp/...?secret=XXX&digits=8&period=60` 完整链接：
     *    提取 secret 并读取 digits/period（缺省回落 6/30）
     * 非法输入抛 IllegalArgumentException（由 UI 层转为错误提示）。
     */
    fun parseInput(raw: String): Params {
        val s = raw.trim()
        require(s.isNotEmpty()) { "密钥为空" }
        if (!s.startsWith("otpauth://", ignoreCase = true)) {
            return Params(normalizeBase32(s), DEFAULT_DIGITS, DEFAULT_PERIOD)
        }
        val query = s.substringAfter('?', "")
        val params = query.split('&')
            .mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).lowercase() to it.substring(i + 1)
            }
            .toMap()
        val secret = params["secret"] ?: ""
        require(secret.isNotEmpty()) { "链接中未找到 secret 参数" }
        val digits = params["digits"]?.toIntOrNull()?.takeIf { it in 1..9 } ?: DEFAULT_DIGITS
        val period = params["period"]?.toIntOrNull()?.takeIf { it in 1..3600 } ?: DEFAULT_PERIOD
        return Params(normalizeBase32(secret), digits, period)
    }

    private fun normalizeBase32(s: String): String {
        val clean = s.replace(" ", "").replace("-", "").uppercase()
        require(clean.isNotEmpty()) { "密钥为空" }
        require(clean.all { it == '=' || ALPHABET.contains(it) }) { "密钥含非 base32 字符" }
        return clean
    }
}
