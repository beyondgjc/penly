package com.beyondguo.penly.search

import kotlin.math.sqrt

/**
 * 待索引条目：由仓库层在解锁后构造（含解密后的账号/备注），**只在内存中传递**。
 */
data class SearchEntry(
    val itemId: String,
    /** 小写拼接文本，供关键词匹配（对应改造前 `ListScreen` 的匹配范围） */
    val keywordText: String,
    /** 结构化短句，供向量化（见方案 §3.1 的拼接模板） */
    val embedText: String,
) {
    /** 内容指纹，用于增量更新时判断内容是否变化；**非安全用途**，仅变更检测 */
    val fingerprint: String get() = embedText.hashCode().toString()
}

/**
 * 内存向量索引（一期**不落盘**）。
 *
 * 生命周期与会话严格绑定：解锁后构建，锁定即整体丢弃；向量从不写入磁盘。
 * 由此得到三个好处（方案 §3.2）：
 * 1. 没有"加密是否到位 / 临时文件是否删净"的残留风险；
 * 2. 换模型无需迁移——下次解锁用新模型重算即可；
 * 3. 天然避开了双槽位下「存在两个索引文件即泄露设计」的问题。
 *
 * 除向量外还保留一份小写关键词文本，供降级时的纯关键词匹配使用；它同样只在内存。
 */
class SearchIndex {

    private data class Row(
        val keywordText: String,
        val fingerprint: String,
        val vector: FloatArray?,
    )

    private val rows = LinkedHashMap<String, Row>()

    val size: Int get() = rows.size

    /** 是否已具备语义检索能力（至少一条向量） */
    val hasVectors: Boolean get() = rows.values.any { it.vector != null }

    fun put(entry: SearchEntry, vector: FloatArray?) {
        rows[entry.itemId] = Row(entry.keywordText, entry.fingerprint, vector)
    }

    fun remove(itemId: String) {
        rows.remove(itemId)?.vector?.fill(0f)
    }

    fun contains(itemId: String): Boolean = rows.containsKey(itemId)

    /** 内容是否变化（用于增量更新时跳过无需重算的条目） */
    fun isUpToDate(entry: SearchEntry): Boolean =
        rows[entry.itemId]?.fingerprint == entry.fingerprint

    /** 锁定即销毁：清零向量，杜绝内存残留 */
    fun clear() {
        rows.values.forEach { it.vector?.fill(0f) }
        rows.clear()
    }

    /** 余弦相似度 top-K，按相似度降序；低于 [threshold] 的丢弃 */
    fun topK(query: FloatArray, k: Int, threshold: Float): List<Pair<String, Float>> {
        val qNorm = norm(query)
        if (qNorm == 0f) return emptyList()
        val scored = ArrayList<Pair<String, Float>>()
        for ((id, row) in rows) {
            val v = row.vector ?: continue
            val s = cosine(query, v, qNorm)
            if (s >= threshold) scored.add(id to s)
        }
        scored.sortByDescending { it.second }
        return scored.take(k)
    }

    /** 关键词匹配：命中 keywordText 的条目 id（降级路径与语义补充都用它） */
    fun keywordMatch(keyword: String): List<String> {
        if (keyword.isEmpty()) return emptyList()
        return rows.filter { it.value.keywordText.contains(keyword) }.map { it.key }
    }

    private fun cosine(a: FloatArray, b: FloatArray, aNorm: Float): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var bSum = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            bSum += b[i] * b[i]
        }
        if (bSum == 0f) return 0f
        return dot / (aNorm * sqrt(bSum))
    }

    private fun norm(a: FloatArray): Float {
        var sum = 0f
        for (v in a) sum += v * v
        return sqrt(sum)
    }
}
