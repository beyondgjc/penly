package com.beyondguo.penly.data

import com.beyondguo.penly.crypto.CryptoEngine
import java.security.SecureRandom
import kotlin.math.abs

/**
 * 影子数据生成器 —— **结构镜像 + 内容脱敏**。
 *
 * 设计取舍（详见《印迹Android_UI设计评审与改版方案.md》P0-③）：
 * 让用户手工维护一套"假记录"必然露馅（数量对不上、内容不更新、忘了维护）。
 * 因此改为从真库的**结构特征**自动生成：
 *
 * - 条目数量：与真库一致（数量级是最容易被一眼看穿的特征）
 * - 分类分布：复用真库各分类的出现频次
 * - 标题：取分类通用名（"邮箱 1"），而非具体服务名（"微信"）
 *   —— 刻意不伪装成真实账号：若假冒"微信"而密码是随机的，胁迫者一试即识破；
 *      通用名则明确表达"这是占位数据"，不构成欺骗，也就不存在"被识破"这一说
 * - 账号 / 密码：满足复杂度要求但**不可用**的随机串
 * - 时间戳：最近 90 天内随机分散（若全部挤在同一时刻，等于自报"这是刚生成的"）
 *
 * 输入只需要 [VaultItem]（其 title / category 本就以明文索引形式存储），
 * 不接触任何真库明文字段；输出为明文 [PlainEntry]，由调用方用影子槽位密钥加密。
 */
object ShadowVaultGenerator {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WINDOW_DAYS = 90

    private val DEFAULT_CATEGORIES = listOf("默认")
    private val TOKEN_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%^&*"

    private val random = SecureRandom()

    /**
     * 生成影子明文。
     *
     * @param source 真库条目（仅读 id 之外的结构性字段：category）
     * @param now    基准时间，默认当前；传固定值便于测试
     */
    fun generate(source: List<VaultItem>, now: Long = System.currentTimeMillis()): List<PlainEntry> {
        if (source.isEmpty()) return emptyList()

        // 按真库各分类的出现频次展开成抽样池，并原地打乱顺序。
        // 关键不变量：pool 是一个**严格守恒**各分类计数的多重集（不是随机抽取），
        // 因此影子库每个分类的条数与真库逐类相等 —— 这是"结构镜像"的核心，
        // 随机采样只能保证总量一致、逐类分布仅在期望上相符，会被统计比对看穿。
        // 打乱顺序仅为了避免"影子库呈排序排列"这一形态破绽；不改变各类计数。
        val pool = (source
            .groupBy { it.category.ifBlank { "默认" } }
            .flatMap { (cat, list) -> List(list.size) { cat } }
            .ifEmpty { DEFAULT_CATEGORIES })
            .toMutableList()
            .also { java.util.Collections.shuffle(it, random) }
        val catTotal = pool.groupingBy { it }.eachCount()

        val seen = mutableMapOf<String, Int>()
        return List(source.size) { i ->
            val category = pool[i]
            val seq = (seen[category] ?: 0) + 1
            seen[category] = seq
            // 同分类多条才加序号，避免"邮箱 1"这种只有一条却带序号的反常形态
            val title = if ((catTotal[category] ?: 1) > 1) "$category $seq" else category
            val (createdAt, updatedAt) = randomTimestamps(now)
            PlainEntry(
                id = CryptoEngine.genId(),
                title = title,
                category = category,
                account = "user_" + CryptoEngine.randomHex(4),
                secret = randomToken(16),
                note = "",
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }

    /**
     * 最近 90 天内随机分散的 (createdAt, updatedAt)，保证 createdAt <= updatedAt <= now。
     * 影子数据不是密码学秘密，随机性只需"看不出规律"，无需无偏。
     */
    private fun randomTimestamps(now: Long): Pair<Long, Long> {
        val createdOffset =
            random.nextInt(WINDOW_DAYS).toLong() * DAY_MS + random.nextInt(86_400).toLong() * 1000L
        val createdAt = now - createdOffset
        val spanDays = (WINDOW_DAYS / 3).coerceAtLeast(1)
        val updatedAt = (createdAt + random.nextInt(spanDays).toLong() * DAY_MS).coerceAtMost(now)
        return createdAt to updatedAt.coerceAtLeast(createdAt)
    }

    private fun randomToken(len: Int): String {
        val buf = CryptoEngine.randomBytes(len)
        return buildString(len) {
            for (b in buf) append(TOKEN_ALPHABET[(b.toInt() and 0x7f) % TOKEN_ALPHABET.length])
        }
    }

    /**
     * 真库条目数变化是否大到需要重生成影子数据。
     * 阈值取「20%」与「3 条」的较大者：库很小时允许按绝对条数触发，库大时按比例触发。
     */
    fun needsRegen(realCount: Int, shadowCount: Int): Boolean {
        if (realCount == shadowCount) return false
        val threshold = maxOf(3, (realCount * 0.2).toInt())
        return abs(realCount - shadowCount) >= threshold
    }
}
