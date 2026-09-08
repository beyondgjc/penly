package com.beyondguo.penly.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearcherTest {

    /** 确定性假实现：按文本查表返回向量，便于断言相似度与阈值行为 */
    private class FakeEmbedder(private val table: Map<String, FloatArray>) : Embedder {
        override val isReady: Boolean get() = true
        override suspend fun embed(text: String): FloatArray? = table[text]
        override fun close() = Unit
    }

    @Test
    fun noopEmbedder_degradesToKeywordOnly() = runBlocking {
        val index = SearchIndex()
        index.put(SearchEntry("a", "gmail 邮箱", "标题：Gmail"), null)
        val out = SmartSearcher(NoopEmbedder, index).search("gmail")
        assertTrue("未接入模型时必须降级", out.degraded)
        assertFalse(out.semanticUsed)
        assertEquals(1, out.hits.size)
        assertEquals("a", out.hits[0].itemId)
        assertFalse(out.hits[0].viaSemantic)
        assertTrue(out.hits[0].viaKeyword)
    }

    @Test
    fun semanticHit_carriesSimilarityAndIsPrioritized() = runBlocking {
        val index = SearchIndex()
        index.put(SearchEntry("hit", "gmail", "标题：Gmail"), floatArrayOf(1f, 0f))
        index.put(SearchEntry("other", "steam", "标题：Steam"), floatArrayOf(0f, 1f))
        val out = SmartSearcher(FakeEmbedder(mapOf("邮箱" to floatArrayOf(1f, 0f))), index).search("邮箱")
        assertFalse(out.degraded)
        assertTrue(out.semanticUsed)
        assertEquals(1, out.hits.size)
        assertEquals("hit", out.hits[0].itemId)
        assertEquals(1f, out.hits[0].similarity, 1e-5f)
    }

    @Test
    fun fusion_dedupesItemHitByBoth() = runBlocking {
        val index = SearchIndex()
        index.put(SearchEntry("a", "gmail", "标题：Gmail"), floatArrayOf(1f, 0f))
        val out = SmartSearcher(FakeEmbedder(mapOf("gmail" to floatArrayOf(1f, 0f))), index).search("gmail")
        assertEquals("语义与关键词同时命中时只应出现一次", 1, out.hits.size)
        assertTrue(out.hits[0].viaSemantic)
        assertTrue(out.hits[0].viaKeyword)
    }

    @Test
    fun fusion_semanticFirstThenKeywordSupplement() = runBlocking {
        val index = SearchIndex()
        index.put(SearchEntry("bySemantic", "aaa", "t"), floatArrayOf(1f, 0f))
        index.put(SearchEntry("byKeyword", "gmail", "t"), floatArrayOf(0f, 1f))
        val out = SmartSearcher(FakeEmbedder(mapOf("q" to floatArrayOf(1f, 0f))), index).search("q")
        assertEquals(1, out.hits.size) // 关键词未命中 "q"，仅语义命中
        assertEquals("bySemantic", out.hits[0].itemId)
    }

    @Test
    fun emptyQuery_returnsEmpty() = runBlocking {
        val index = SearchIndex()
        index.put(SearchEntry("a", "gmail", "t"), floatArrayOf(1f, 0f))
        val out = SmartSearcher(FakeEmbedder(emptyMap()), index).search("   ")
        assertTrue(out.hits.isEmpty())
        assertFalse(out.semanticUsed)
    }

    @Test
    fun threshold_filtersWeakSemanticMatches() = runBlocking {
        val index = SearchIndex()
        index.put(SearchEntry("weak", "x", "t"), floatArrayOf(1f, 1f)) // 与 [1,0] 的余弦 ≈ 0.707
        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f, 0f)))
        val loose = SmartSearcher(embedder, index).search("q", threshold = 0.5f)
        val strict = SmartSearcher(embedder, index).search("q", threshold = 0.9f)
        assertEquals(1, loose.hits.size)
        assertTrue("提高阈值后应过滤掉弱匹配", strict.hits.isEmpty())
    }
}
