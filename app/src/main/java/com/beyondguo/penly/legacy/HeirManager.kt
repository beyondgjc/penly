package com.beyondguo.penly.legacy

import com.beyondguo.penly.crypto.Shamir
import java.security.SecureRandom

/**
 * 遗产交接编排层（#43/#44）：遗产密钥 L 的生成、分片与恢复。
 *
 * 角色分工：
 * - 本对象只管「L ↔ 分片 ↔ hex(L)」的纯变换，不碰存储与 UI；
 * - 恢复包的生成走 [com.beyondguo.penly.data.VaultRepository.exportLegacyPackage]
 *   （= 标准 v2 备份，密码为 hex(L)，BackupCodecV2 契约零改动）；
 * - 导入走 [com.beyondguo.penly.data.VaultRepository.importJson](text, hex(L))，
 *   导入后本地解锁密码 = hex(L)，UI 层随即引导受托人改密接管。
 *
 * L 的生命周期约束：只在「生成恢复包」与「受托人恢复」两个瞬间的内存中存在，
 * 永不落盘、永不过日志；UI 层用毕即弃（无引用后 GC，Byte 数组无法主动清零是 JVM 限制，
 * 但分片形态本身已满足 2-of-3 安全模型，内存瞬时态不降低分界强度）。
 */
object HeirManager {

    /** 遗产密钥长度（B）——与金库主密钥同级强度的随机密钥 */
    private const val KEY_LEN_BYTES = 32

    /** 2-of-3：容忍任意 1 份丢失/泄露，2 份合谋即完整恢复能力 */
    const val THRESHOLD = 2
    const val TOTAL_SHARES = 3

    /** 一次新配置的产物：hex(L)（生成恢复包用，即刻消费不保存）+ 3 份分片文本 */
    data class Setup(val legacyKeyHex: String, val shareTexts: List<String>)

    /** 生成新的遗产密钥并拆分 —— 每次调用都是一次「轮换」，旧分片与旧恢复包随之作废 */
    fun generateSetup(random: SecureRandom = SecureRandom()): Setup {
        val key = ByteArray(KEY_LEN_BYTES).also { random.nextBytes(it) }
        val shares = Shamir.split(key, THRESHOLD, TOTAL_SHARES)
        try {
            return Setup(
                legacyKeyHex = key.joinToString("") { "%02x".format(it) },
                shareTexts = shares.map { Shamir.shareText(it) },
            )
        } finally {
            key.fill(0)
        }
    }

    /**
     * 受托人恢复：两份分片文本 → hex(L)。
     * 校验：格式合法、编号不重复（同一份分片抄两遍 = 1 份信息量，必须拒绝）。
     * 分片抄错时 combine 产出错误 L——由恢复包内层 GCM 的完整性校验 fail-closed。
     */
    fun recoverKeyHex(shareTextA: String, shareTextB: String): String {
        val a = Shamir.parseText(shareTextA)
        val b = Shamir.parseText(shareTextB)
        val key = Shamir.combine(listOf(a, b))
        try {
            return key.joinToString("") { "%02x".format(it) }
        } finally {
            key.fill(0)
        }
    }

    /** 分片文本编号（1..3），UI 展示「第 N 份」用；格式非法返回 -1 */
    fun shareIndex(text: String): Int =
        try {
            Shamir.parseText(text).x
        } catch (_: IllegalArgumentException) {
            -1
        }
}
