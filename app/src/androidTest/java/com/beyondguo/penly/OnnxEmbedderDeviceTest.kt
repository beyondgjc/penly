package com.beyondguo.penly

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beyondguo.penly.search.OnnxEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * 端上 Embedder 验证（真机 CPU 推理，模型/词表从 APK assets 装载）。
 *
 * 与 JVM 侧 [com.beyondguo.penly.search.ChineseRecallEvalTest]（完整召回评测）互补，
 * 这里验证端上链路本身：
 * - assets 模型能装载、ORT session 能创建
 * - 输出维度 = 384、L2 归一化正确（余弦计算的数学前提）
 * - 小规模语义冒烟：零字面重叠查询命中目标
 * - 打印首帧加载与单条推理耗时，作为低端机性能基线
 */
@RunWith(AndroidJUnit4::class)
class OnnxEmbedderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newEmbedder(): OnnxEmbedder {
        val t0 = System.currentTimeMillis()
        val model = context.assets.open("models/bge-small-zh-v1.5-q.onnx").use { it.readBytes() }
        val vocab = context.assets.open("models/bge-zh-vocab.txt").use { it.readBytes().decodeToString() }
        val e = OnnxEmbedder(model, vocab)
        println("模型装载耗时: ${System.currentTimeMillis() - t0} ms（含 24MB assets 读取 + ORT session 创建）")
        return e
    }

    @Test
    fun embed_dimension_l2norm_and_timing() = runBlocking {
        val embedder = newEmbedder()
        try {
            val t0 = System.currentTimeMillis()
            val v = embedder.embed("标题：微信支付\n分类：支付\n账号：test\n备注：扫码付款")
            val elapsed = System.currentTimeMillis() - t0
            println("单条推理耗时: $elapsed ms（首次包含 ORT 图优化/内存分配）")

            assertNotNull(v)
            v!!
            // 注意：bge-small-zh-v1.5 是 512 维（bge-small-en 才是 384 维）
            assertEquals("bge-small-zh 输出维度应为 512", 512, v.size)
            val norm = sqrt(v.map { it * it }.sum())
            assertTrue("L2 范数应≈1（实际 $norm），否则余弦相似度失真", norm in 0.98f..1.02f)

            // 二次推理摊薄首次开销后的耗时
            val t1 = System.currentTimeMillis()
            embedder.embed("标题：Steam\n分类：娱乐\n账号：a\n备注：游戏")
            println("稳态单条推理耗时: ${System.currentTimeMillis() - t1} ms")
        } finally {
            embedder.close()
        }
    }

    @Test
    fun ondevice_semantic_smoke() = runBlocking {
        val embedder = newEmbedder()
        try {
            val corpus = listOf(
                "标题：网易云音乐\n分类：娱乐\n账号：a\n备注：听歌黑胶会员",
                "标题：京东\n分类：购物\n账号：b\n备注：数码 Plus 会员",
                "标题：招商银行\n分类：金融\n账号：c\n备注：工资卡取款",
            )
            val vectors = embedder.embedAll(corpus)
            val qv = embedder.embedForQuery("听歌的软件会员")!!
            val scores = vectors.mapIndexed { i, v ->
                corpus[i].substringBefore('\n').removePrefix("标题：") to
                    (0 until qv.size).sumOf { (qv[it] * v!![it]).toDouble() }.toFloat()
            }.sortedByDescending { it.second }
            println("端上冒烟 Top3: " + scores.joinToString("  ") { "${it.first}=%.3f".format(it.second) })
            assertEquals("端上语义冒烟应命中网易云音乐", "网易云音乐", scores[0].first)
        } finally {
            embedder.close()
        }
    }
}
