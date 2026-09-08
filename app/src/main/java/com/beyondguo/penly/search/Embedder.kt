package com.beyondguo.penly.search

/**
 * 文本向量化抽象（端内 AI 检索）。
 *
 * 一期索引**纯内存、不落盘**（见《印迹_端内AI检索_技术方案.md》§3.2），
 * 向量每次解锁都用当前实现重算，因此**换模型零迁移成本**——
 * 替换实现后下次解锁自动用新模型重建，不需要任何数据兼容处理。
 *
 * 两档实现：
 * - [NoopEmbedder]：降级档，`isReady = false`，检索退化为纯关键词，不阻断使用
 * - 真实实现（二期接入端侧模型）：`isReady = true`
 */
interface Embedder {

    /** 是否可用；false 时检索自动降级为纯关键词 */
    val isReady: Boolean

    /** 单条文本向量化；未就绪或计算失败返回 null */
    suspend fun embed(text: String): FloatArray?

    /**
     * 批量向量化。默认逐条调用 [embed]；
     * 真实实现应覆写以摊薄模型调用的固定开销（逐条调用会放大数十倍）。
     */
    suspend fun embedAll(texts: List<String>): List<FloatArray?> = texts.map { embed(it) }

    fun close()
}

/**
 * 降级实现：模型未下载 / 设备不支持 / 初始化失败时使用。
 *
 * 使整条检索链路退化为纯关键词匹配，行为与改造前一致，不阻断任何功能。
 */
object NoopEmbedder : Embedder {
    override val isReady: Boolean get() = false
    override suspend fun embed(text: String): FloatArray? = null
    override suspend fun embedAll(texts: List<String>): List<FloatArray?> = texts.map { null }
    override fun close() = Unit
}
