package com.beyondguo.penly.search

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * 探针：裸条目（只有标题）时，短查询的余弦分到底多少？
 * 用于诊断真机上"买/购物"搜不出"淘宝"。
 */
class BareEntryProbeTest {

    private fun asset(name: String): ByteArray {
        val f = listOf(File("src/main/assets/models/$name"), File("app/src/main/assets/models/$name"))
            .firstOrNull { it.exists() } ?: error("找不到 $name")
        return f.readBytes()
    }

    @Test
    fun probe() = runBlocking {
        val e = OnnxEmbedder(asset("bge-small-zh-v1.5-q.onnx"), asset("bge-zh-vocab.txt").decodeToString())
        try {
            val bare = "标题：淘宝\n分类：\n账号：\n备注："
            val rich = "标题：淘宝\n分类：购物\n账号：taobao_user\n备注：网购主力账号"
            val docVs = e.embedAll(listOf(bare, rich))
            for (q in listOf("买", "购物", "买东西", "网上购物", "买东西的网站", "网购平台")) {
                val qv = e.embedForQuery(q)!!
                val noPrefix = e.embed(q)!!
                println(
                    "Q[$q] 语义: bare=%.3f rich=%.3f | 无前缀: bare=%.3f".format(
                        dot(qv, docVs[0]!!), dot(qv, docVs[1]!!), dot(noPrefix, docVs[0]!!),
                    ),
                )
            }
        } finally {
            e.close()
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }
}
