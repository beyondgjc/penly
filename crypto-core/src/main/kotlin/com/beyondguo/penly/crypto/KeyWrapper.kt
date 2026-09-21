package com.beyondguo.penly.crypto

/**
 * 密钥包装器：信封层（[DoubleEnvelope]）与具体保护介质之间的接缝。
 *
 * 这是加密内核与宿主平台之间**唯一的接缝**——core 只声明它，实现由宿主提供：
 * - 宿主 App 的 AndroidKeyStore 实现：TEE/StrongBox 硬件保护（生产）
 * - 测试内的软件实现：JVM 单测跑信封语义（不入生产）
 *
 * 载荷格式与 [Aead] 一致：nonce(12B) || ciphertext || tag(16B)。
 */
interface KeyWrapper {
    /** 持久化时标识包装来源（恢复流程需要区分 TEE 信封与降级信封） */
    val tag: String

    fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun unwrap(payload: ByteArray, aad: ByteArray): ByteArray
}
