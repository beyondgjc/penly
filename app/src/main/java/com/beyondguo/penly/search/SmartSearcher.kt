package com.beyondguo.penly.search

/**
 * 语义 + 关键词融合检索（端内完成，无任何网络调用）。
 *
 * - 语义：query embedding → 余弦 top-K → 阈值过滤
 * - 关键词：匹配索引内的小写文本（等价改造前 `ListScreen` 的匹配范围）
 * - 融合：**语义优先**，关键词作为补充，按 itemId 去重
 *
 * 降级：embedder 未就绪或索引尚未建成时，只返回关键词结果并置 [SearchOutcome.degraded]，
 * 行为与改造前完全一致，不阻断使用。
 *
 * ⚠️ [DEFAULT_THRESHOLD] 取自上游方案的经验值，**必须用中文语料重新标定**，
 * 不同模型的向量分布与归一化方式不同，照搬会导致误召回或全不命中。
 */
class SmartSearcher(
    private val embedder: Embedder,
    private val index: SearchIndex,
) {

    data class Hit(
        val itemId: String,
        /** 语义相似度；纯关键词命中为 0 */
        val similarity: Float,
        val viaSemantic: Boolean,
        val viaKeyword: Boolean,
    )

    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = DEFAULT_THRESHOLD,
    ): SearchOutcome {
        val kw = query.trim().lowercase()
        if (kw.isEmpty()) return SearchOutcome(emptyList(), semanticUsed = false, degraded = false)

        val semantic: List<Pair<String, Float>> =
            if (embedder.isReady && index.hasVectors) {
                val qv = embedder.embed(query)
                if (qv != null) index.topK(qv, topK, threshold) else emptyList()
            } else {
                emptyList()
            }

        val keywordIds = index.keywordMatch(kw).toSet()

        val hits = LinkedHashMap<String, Hit>()
        for ((id, sim) in semantic) {
            hits[id] = Hit(id, sim, viaSemantic = true, viaKeyword = id in keywordIds)
        }
        for (id in keywordIds) {
            if (hits.containsKey(id)) continue // 语义已覆盖，不重复
            hits[id] = Hit(id, 0f, viaSemantic = false, viaKeyword = true)
        }

        val degraded = !embedder.isReady || !index.hasVectors
        return SearchOutcome(
            hits = hits.values.toList(),
            semanticUsed = semantic.isNotEmpty(),
            degraded = degraded,
        )
    }

    companion object {
        const val DEFAULT_TOP_K = 5
        const val DEFAULT_THRESHOLD = 0.55f
    }
}

data class SearchOutcome(
    val hits: List<SmartSearcher.Hit>,
    /** 本次是否真的用上了语义检索 */
    val semanticUsed: Boolean,
    /** 是否处于降级（纯关键词）状态；UI 据此决定是否提示 */
    val degraded: Boolean,
)
