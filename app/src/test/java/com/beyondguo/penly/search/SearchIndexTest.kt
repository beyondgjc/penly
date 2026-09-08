package com.beyondguo.penly.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {

    @Test
    fun topK_identicalVector_scoresOne() {
        val index = SearchIndex()
        index.put(SearchEntry("a", "gmail", "标题：Gmail"), floatArrayOf(1f, 0f, 0f))
        val res = index.topK(floatArrayOf(1f, 0f, 0f), 5, 0.55f)
        assertEquals(1, res.size)
        assertEquals("a", res[0].first)
        assertEquals(1f, res[0].second, 1e-5f)
    }

    @Test
    fun topK_orthogonal_filteredByThreshold() {
        val index = SearchIndex()
        index.put(SearchEntry("a", "x", "y"), floatArrayOf(1f, 0f))
        assertTrue("正交向量相似度 0，应被阈值过滤", index.topK(floatArrayOf(0f, 1f), 5, 0.55f).isEmpty())
    }

    @Test
    fun topK_sortedDescendingAndLimited() {
        val index = SearchIndex()
        index.put(SearchEntry("a1", "a", "a"), floatArrayOf(1f, 0f, 0f))
        index.put(SearchEntry("b2", "b", "b"), floatArrayOf(1f, 1f, 0f))
        index.put(SearchEntry("c3", "c", "c"), floatArrayOf(1f, 1f, 1f))
        val res = index.topK(floatArrayOf(1f, 0.2f, 0f), 2, 0f)
        assertEquals(2, res.size)
        assertEquals("a1", res[0].first)
        assertTrue(res[0].second >= res[1].second)
    }

    @Test
    fun topK_zeroQuery_returnsEmpty() {
        val index = SearchIndex()
        index.put(SearchEntry("a", "x", "y"), floatArrayOf(1f, 0f))
        assertTrue(index.topK(floatArrayOf(0f, 0f), 5, 0f).isEmpty())
    }

    @Test
    fun keywordMatch_matchesLowercaseText() {
        val index = SearchIndex()
        index.put(SearchEntry("a", "gmail 邮箱", "t"), null)
        index.put(SearchEntry("b", "steam", "t"), null)
        assertEquals(listOf("a"), index.keywordMatch("gmail"))
        assertTrue(index.keywordMatch("steam").contains("b"))
        assertTrue(index.keywordMatch("不存在").isEmpty())
        assertTrue("空关键词不应匹配任何条目", index.keywordMatch("").isEmpty())
    }

    @Test
    fun clear_zeroesVectorsAndEmpties() {
        val index = SearchIndex()
        val v = floatArrayOf(1f, 2f, 3f)
        index.put(SearchEntry("a", "k", "t"), v)
        index.clear()
        assertEquals(0, index.size)
        assertFalse(index.hasVectors)
        assertArrayEquals("锁定销毁必须清零向量，杜绝内存残留", floatArrayOf(0f, 0f, 0f), v, 1e-6f)
    }

    @Test
    fun remove_dropsEntryAndZeroesVector() {
        val index = SearchIndex()
        val v = floatArrayOf(1f, 0f)
        index.put(SearchEntry("a", "k", "t"), v)
        index.remove("a")
        assertEquals(0, index.size)
        assertArrayEquals(floatArrayOf(0f, 0f), v, 1e-6f)
    }

    @Test
    fun isUpToDate_detectsContentChange() {
        val index = SearchIndex()
        index.put(SearchEntry("a", "k", "old"), null)
        assertTrue(index.isUpToDate(SearchEntry("a", "k", "old")))
        assertFalse(index.isUpToDate(SearchEntry("a", "k", "new")))
    }
}
