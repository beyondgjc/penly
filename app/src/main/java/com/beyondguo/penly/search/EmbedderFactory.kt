package com.beyondguo.penly.search

import android.content.Context

/**
 * Embedder 工厂：从 APK assets 装载端内模型。
 *
 * 模型文件（int8 量化 bge-small-zh-v1.5，~24MB）与词表打进 APK，
 * **全程离线推理、不联网、向量不落库** —— 与密码箱的隐私模型一致。
 *
 * 任何加载失败（assets 缺失 / ORT 初始化异常 / 内存不足）都降级为
 * [NoopEmbedder]，检索退化为纯关键词，绝不因 AI 能力阻断解锁主链路。
 */
object EmbedderFactory {

    private const val MODEL_ASSET = "models/bge-small-zh-v1.5-q.onnx"
    private const val VOCAB_ASSET = "models/bge-zh-vocab.txt"

    fun create(context: Context): Embedder = runCatching {
        val appContext = context.applicationContext
        val model = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        val vocab = appContext.assets.open(VOCAB_ASSET).use { it.readBytes().decodeToString() }
        OnnxEmbedder(model, vocab)
    }.getOrElse {
        android.util.Log.w("PenlySearch", "Embedder 创建失败：${it.javaClass.simpleName}: ${it.message}")
        NoopEmbedder
    }
}
