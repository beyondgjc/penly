package com.beyondguo.penly.crypto

import java.security.SecureRandom

/**
 * key32 的本机 TEE 信封（v5.0 地基工程，《印迹_跨端契约v2_地基工程.md》§4）。
 *
 * 解锁链：主密码 → Argon2id(64MiB) → KEK → [KeyWrapper]（TEE）unwrap → key32
 *
 * 严格双因素语义：
 * - 信封内容是 KEK（需主密码派生），TEE 离线/拆机解不出
 * - 拿到密码没设备 ✗　拿到设备没密码 ✗　两者都有 → TEE 在线放行
 * - 无单因素后门；key32 值不变（启用信封不动任何数据）
 * - 失效语义：换机/恢复出厂 → TEE 密钥销毁 → 信封永久不可解 → 唯一出路 = 备份恢复
 *   （产品配套：启用信封前强制「已完成一次导出」检查）
 *
 * 持久化：[Envelope] 三字段由调用方落 DataStore（后续任务接入 VaultStore）。
 */
object KeystoreEnvelope {

    private val AAD_INNER = "yinji-kek-v1".toByteArray()      // TEE 层（设备因素）
    private val AAD_OUTER = "yinji-envelope-v1".toByteArray() // KEK 层（密码因素）
    private val RANDOM = SecureRandom()

    /** 本机层 KEK 参数（仅 Android 本机解锁链，不进跨端契约） */
    private const val KEK_MEMORY_KIB = 64 * 1024
    private const val KEK_ITERATIONS = 3
    private const val KEK_PARALLELISM = 1

    data class Envelope(
        val saltLocalB64: String,   // Argon2id 本机盐（独立于契约层盐）
        val payloadB64: String,     // 外层：KEK 解出的内层信封（nonce||ct||tag）
        val wrapperTag: String,     // "keystore" / 测试实现自定义 —— 恢复流程需区分
    )

    /**
     * 双层信封封装（必须在解锁态调用，key32 由调用方自 SessionManager 传入）：
     *   inner = TEE_wrapKey.wrap(key32)            —— 设备因素（TEE 离线解不出）
     *   outer = Enc(KEK, inner)                    —— 密码因素（KEK = Argon2id(主密码)）
     * 两层缺一：只有密码 → outer 解开但 inner 需 TEE；只有设备 → inner 需 KEK。
     */
    fun seal(wrapper: KeyWrapper, masterPassword: ByteArray, key32: ByteArray): Envelope {
        val saltLocal = ByteArray(16).also { RANDOM.nextBytes(it) }
        val kek = CryptoV2.argon2id(
            password = masterPassword,
            salt = saltLocal,
            memoryKiB = KEK_MEMORY_KIB,
            iterations = KEK_ITERATIONS,
            parallelism = KEK_PARALLELISM,
        )
        val inner = wrapper.wrap(key32, AAD_INNER)
        val outer = CryptoV2.gcmEncrypt(kek, inner, AAD_OUTER)
        return Envelope(
            saltLocalB64 = CryptoV2.b64(saltLocal),
            payloadB64 = CryptoV2.b64(outer),
            wrapperTag = wrapper.tag,
        )
    }

    /**
     * 双层解封。失败语义：
     * - 主密码错误 → outer 的 GCM tag 失败 → [CryptoV2.IntegrityException]（「密码错误」）
     * - TEE 密钥失效（换机/恢复出厂）→ KeyPermanentlyInvalidatedException 等
     *   由 [KeyWrapper] 透传 —— 语义为「走备份恢复」，调用方必须与「密码错误」区分
     * - 信封被篡改 → IntegrityException
     */
    fun unseal(wrapper: KeyWrapper, masterPassword: ByteArray, envelope: Envelope): ByteArray {
        val kek = CryptoV2.argon2id(
            password = masterPassword,
            salt = CryptoV2.unb64(envelope.saltLocalB64),
            memoryKiB = KEK_MEMORY_KIB,
            iterations = KEK_ITERATIONS,
            parallelism = KEK_PARALLELISM,
        )
        val inner = CryptoV2.gcmDecrypt(
            kek,
            CryptoV2.unb64(envelope.payloadB64),
            AAD_OUTER,
        )
        return wrapper.unwrap(inner, AAD_INNER)
    }
}
