package com.beyondguo.penly.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

/**
 * 端内 ONNX 推理 Embedder（一期接入 bge-small-zh-v1.5 int8 量化版，~24MB）。
 *
 * 设计要点：
 * - **纯 Kotlin + ai.onnxruntime**，桌面 JVM 与 Android 共用同一实现：
 *   单测里跑的召回评测 = 真机上跑的推理，结论可直接迁移。
 * - **不落库、不联网**：模型打进 APK assets，推理全离线，向量只在内存（§3.2）。
 * - **换模型零迁移**（技术方案 §2 承诺）：不同模型的差异全部收进 [ModelConfig] ——
 *   查询前缀、池化方式、维度，换模型 = 换一个 Config + 一份模型文件。
 *
 * bge-small-zh-v1.5：CLS 池化 + L2 归一，检索场景查询侧要加指令前缀
 * "为这个句子生成表示以用于检索相关文章："（官方用法，提升 query↔passage 对齐）。
 * e5 系列若日后替换：queryPrefix="query: "、passagePrefix="passage: "、MEAN 池化。
 */
class OnnxEmbedder(
    modelBytes: ByteArray,
    vocabText: String,
    private val config: ModelConfig = ModelConfig.BGE_SMALL_ZH,
) : Embedder {

    data class ModelConfig(
        /** 查询侧前缀（模型官方用法；为空表示不加） */
        val queryPrefix: String,
        /** 文档（条目）侧前缀 */
        val passagePrefix: String,
        /** 池化策略 */
        val pooling: Pooling,
    ) {
        enum class Pooling { /** 取首 token（[CLS]）*/ CLS, /** 按 attention_mask 加权平均 */ MEAN }

        companion object {
            /**
             * 智源 bge-small-zh-v1.5（当前默认）。
             *
             * ⚠️ 查询前缀实测**必须为空**：官方指令前缀（"为这个句子生成表示以用于检索相关文章："）
             * 是为"短查询↔长文章"设计的；本场景是"短查询↔短条目"（embedText 只有一两行），
             * 加前缀会把查询向量拉偏 ~0.07，把裸条目（只有标题）的命中分从 ~0.44 拉到 ~0.37，
             * 直接掉出阈值。见 BareEntryProbeTest 实测数据。
             */
            val BGE_SMALL_ZH = ModelConfig(
                queryPrefix = "",
                passagePrefix = "",
                pooling = Pooling.CLS,
            )
            /** 预留：e5-small-multilingual（召回不达标时的备选） */
            val E5_SMALL = ModelConfig(
                queryPrefix = "query: ",
                passagePrefix = "passage: ",
                pooling = Pooling.MEAN,
            )
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)
    private val tokenizer = WordPieceTokenizer(vocabText)

    /** 模型需要的输入名集合（BERT 系为 input_ids/attention_mask/token_type_ids） */
    private val inputNames: Set<String> = session.inputNames.toSet()

    override val isReady: Boolean get() = true

    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching { forward(config.passagePrefix + text) }.getOrNull()
    }

    /** 查询向量化：带查询侧前缀。检索链路（[SmartSearcher]）必须用这个而不是 [embed] */
    override suspend fun embedForQuery(text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching { forward(config.queryPrefix + text) }.getOrNull()
    }

    override suspend fun embedAll(texts: List<String>): List<FloatArray?> = texts.map { embed(it) }

    // ---------- 推理 ----------

    private fun forward(text: String): FloatArray {
        val enc = tokenizer.encode(text)
        val seqLen = enc.inputIds.size
        val shape = longArrayOf(1, seqLen.toLong())

        val inputs = mutableMapOf<String, OnnxTensor>()
        if ("input_ids" in inputNames) {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), shape)
        }
        if ("attention_mask" in inputNames) {
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
        }
        if ("token_type_ids" in inputNames) {
            inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape)
        }

        session.run(inputs).use { results ->
            val tensor = results.get(0) as OnnxTensor
            val buf = tensor.floatBuffer
            // last_hidden_state: [1, seq, dim]；个别导出直接给 pooled [1, dim]
            val dim: Int
            val pooled: FloatArray
            if (seqLen > 0 && buf.remaining() % seqLen == 0 && buf.remaining() / seqLen > 1) {
                dim = buf.remaining() / seqLen
                pooled = when (config.pooling) {
                    ModelConfig.Pooling.CLS -> FloatArray(dim) { buf.get(it) } // 首 token
                    ModelConfig.Pooling.MEAN -> meanPool(buf, seqLen, dim, enc.attentionMask)
                }
            } else {
                dim = buf.remaining()
                pooled = FloatArray(dim) { buf.get(it) }
            }
            return l2Normalize(pooled)
        }
    }

    private fun meanPool(buf: java.nio.FloatBuffer, seqLen: Int, dim: Int, mask: LongArray): FloatArray {
        val acc = FloatArray(dim)
        var count = 0
        for (pos in 0 until seqLen) {
            if (mask[pos] == 0L) continue
            val base = pos * dim
            for (d in 0 until dim) acc[d] += buf.get(base + d)
            count++
        }
        if (count > 0) for (d in 0 until dim) acc[d] /= count
        return acc
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    override fun close() {
        session.close() // OrtEnvironment 是进程级单例，不 close
    }
}
