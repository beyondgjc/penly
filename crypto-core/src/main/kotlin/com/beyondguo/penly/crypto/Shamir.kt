package com.beyondguo.penly.crypto

import java.security.SecureRandom
import java.util.Base64

/**
 * Shamir 秘密共享（GF(2^8)，多项式 0x11D 域）——遗产交接 #43 的密码学地基。
 *
 * 语义（遗产场景 2-of-3）：
 * - [split]：把 32B 遗产密钥 L 拆成 3 份分片，任意 2 份可重建 L；
 * - [combine]：≥threshold 份分片拉格朗日插值还原；少于 threshold 份在信息论上
 *   不泄露任何关于 L 的信息（一次性多项式系数均匀随机）；
 * - 单份分片 = L 的线性函数（f(x_i) 一个点），泄露/丢失 1 份无需轮换。
 *
 * 安全边界（诚实声明）：
 * - 分片本身无认证（Shamir 固有）：错误/篡改分片组合出的 L 必然是错的——
 *   但恢复包内层是 AES-256-GCM（BackupCodecV2），错误 L 解密必然 IntegrityException，
 *   fail-closed 由外层 GCM 兜底，不会出现「错误密钥解出乱码数据」；
 * - 任何 2 份合谋 = 完整恢复能力，3 份分片必须放在互不共谋的位置。
 *
 * 纯 JVM 实现，host 单测直接验证；不落盘、不打日志（调用方保证 L 只在内存存活）。
 */
object Shamir {

    /** 分片文本前缀（含版本位 v1）：抄录/粘贴/未来扫码的统一载体 */
    const val SHARE_PREFIX = "YJH1"

    /** GF(2^8) 指数/对数表（生成多项式 0x11D，Reed-Solomon 惯用域） */
    private val EXP = IntArray(510)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 510) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun inv(a: Int): Int {
        require(a != 0) { "GF(256) 除零" }
        return EXP[255 - LOG[a]]
    }

    /** 一份分片：x = 非零域元素坐标（本实现固定 1..shares），y = 与 secret 等长的点值 */
    class Share(val x: Int, val y: ByteArray)

    /**
     * 拆分 secret 为 [shares] 份分片，任意 [threshold] 份可还原。
     * threshold 必须 ≥ 2（=1 时任一单份即秘密，违背遗产场景意图）。
     */
    fun split(
        secret: ByteArray,
        threshold: Int,
        shares: Int,
        random: SecureRandom = SecureRandom(),
    ): List<Share> {
        require(secret.isNotEmpty()) { "秘密不能为空" }
        require(shares in 2..255) { "分片数须在 2..255" }
        require(threshold in 2..shares) { "阈值须在 2..分片数" }
        val out = List(shares) { Share(it + 1, ByteArray(secret.size)) }
        // 逐字节独立建多项式：f(x) = s + a1·x + a2·x² + … + a(t-1)·x^(t-1)（mod GF）
        val poly = IntArray(threshold)
        for (i in secret.indices) {
            poly[0] = secret[i].toInt() and 0xFF
            for (j in 1 until threshold) poly[j] = random.nextInt(256)
            for (s in out) {
                var v = poly[0]
                var xp = 1
                for (j in 1 until threshold) {
                    xp = mul(xp, s.x)
                    v = v xor mul(poly[j], xp)
                }
                s.y[i] = v.toByte()
            }
        }
        return out
    }

    /**
     * 合并 ≥threshold 份分片还原秘密（拉格朗日插值在 x=0 处取值）。
     * 分片数不足时不抛错也拼不出正确结果——调用方必须保证份额数，或依赖
     * 外层 GCM 的完整性校验兜底（错误 L 解密必然失败）。
     */
    fun combine(shares: List<Share>): ByteArray {
        require(shares.size >= 2) { "至少需要 2 份分片" }
        val xs = shares.map { it.x }
        require(xs.toSet().size == xs.size) { "分片重复（x 坐标相同）" }
        require(xs.all { it in 1..255 }) { "分片坐标非法" }
        val len = shares[0].y.size
        require(shares.all { it.y.size == len }) { "分片长度不一致" }
        val out = ByteArray(len)
        for (i in 0 until len) {
            var acc = 0
            for (j in shares.indices) {
                // 基函数 L_j(0) = Π_{m≠j} x_m / (x_m − x_j)；GF 上减法 = 异或
                var num = 1
                var den = 1
                for (m in shares.indices) {
                    if (m == j) continue
                    num = mul(num, shares[m].x)
                    den = mul(den, shares[m].x xor shares[j].x)
                }
                acc = acc xor mul(shares[j].y[i].toInt() and 0xFF, mul(num, inv(den)))
            }
            out[i] = acc.toByte()
        }
        return out
    }

    /** 分片 → 可抄录文本：`YJH1<坐标>-<b64url(y)>`（无 padding，无空格） */
    fun shareText(s: Share): String =
        "$SHARE_PREFIX${s.x}-${Base64.getUrlEncoder().withoutPadding().encodeToString(s.y)}"

    /** 分片文本 → [Share]；容忍首尾空白与全角连字符（人工抄录容错） */
    fun parseText(text: String): Share {
        val t = text.trim().replace('－', '-').replace(" ", "")
        val body = t.removePrefix(SHARE_PREFIX)
        val sep = body.indexOf('-')
        if (t.length <= SHARE_PREFIX.length || sep != 1) {
            throw IllegalArgumentException("分片格式不正确（应为 $SHARE_PREFIX<编号>-<内容>）")
        }
        val x = body.substring(0, sep).toIntOrNull() ?: throw IllegalArgumentException("分片编号不是数字")
        if (x !in 1..255) throw IllegalArgumentException("分片编号越界（$x）")
        val y = try {
            Base64.getUrlDecoder().decode(body.substring(sep + 1))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("分片内容不是有效的 base64url")
        }
        if (y.isEmpty()) throw IllegalArgumentException("分片内容为空")
        return Share(x, y)
    }
}
