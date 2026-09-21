package com.beyondguo.penly.crypto

import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * 信封外层失败：主密码错误（或 payload 被篡改——同为密码因素层失败）。
 * 解锁 UI 据此走「密码错误」普通重试路径。
 */
class WrongPasswordException(message: String = "主密码错误") : Exception(message)

/**
 * 设备因素失败：TEE 密钥不可用（换机/恢复出厂/系统删除密钥）。
 * outer 已解开（密码因素成立），失败必然在设备层——此时信封永久不可解，
 * 唯一出路 = 从备份恢复。解锁 UI 据此走专用降级流程，绝不与「密码错误」混淆。
 */
class KeyUnavailableException(message: String = "设备安全密钥不可用，需从备份恢复") : Exception(message)

/**
 * 双层信封：把 key32 用「设备因素 × 密码因素」两层共同保护（v5.0 地基工程，
 * 《印迹_跨端契约v2_地基工程.md》§4）。
 *
 * 解锁链：主密码 → Argon2id(64MiB) → KEK → [KeyWrapper].unwrap → key32
 *
 * **命名**：本类原名 `KeystoreEnvelope`。改名理由——它的语义是"双层信封"，
 * 与 Keystore 并无耦合（设备层完全由 [KeyWrapper] 接缝抽象，测试里就换成软件实现）。
 * 原名字会让人误以为它绑死了 AndroidKeyStore。
 *
 * 严格双因素语义：
 * - 信封内容是 KEK（需主密码派生），TEE 离线/拆机解不出
 * - 拿到密码没设备 ✗　拿到设备没密码 ✗　两者都有 → TEE 在线放行
 * - 无单因素后门；key32 值不变（启用信封不动任何数据）
 * - 失效语义：换机/恢复出厂 → TEE 密钥销毁 → 信封永久不可解 → 唯一出路 = 备份恢复
 *   （产品配套：启用信封前强制「已完成一次导出」检查）
 *
 * 持久化：[Envelope] 三字段由调用方落 DataStore（见 VaultStore 的 `ve_` 键）。
 */
object DoubleEnvelope {

    private val AAD_INNER = "yinji-kek-v1".toByteArray()      // TEE 层（设备因素）
    private val AAD_OUTER = "yinji-envelope-v1".toByteArray() // KEK 层（密码因素）
    private val RANDOM = SecureRandom()

    /**
     * 本机层 KEK 档位（仅 Android 本机解锁链，不进跨端契约）。
     *
     * 2026-09-21：原先这里是三个硬编码常量（64 MiB / t=3 / p=1），
     * 现改走 [Profile.SENSITIVE] —— 让"参数档位"这套抽象有第一个真实使用者，
     * 而不是写完没人用的空壳。
     *
     * ⚠️ **数值一字未变**（profile 的 SENSITIVE 就是照这三个数定的），
     * 否则所有存量信封都解不开。这层等价关系由 `ProfileTest.sensitive matches
     * penly kek exactly` 钉住。
     */
    private val KEK_PROFILE = Profile.SENSITIVE

    @Serializable
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
        val kek = KEK_PROFILE.derive(masterPassword, saltLocal)
        val inner = wrapper.wrap(key32, AAD_INNER)
        val outer = Aead.gcmEncrypt(kek, inner, AAD_OUTER)
        return Envelope(
            saltLocalB64 = Aead.b64(saltLocal),
            payloadB64 = Aead.b64(outer),
            wrapperTag = wrapper.tag,
        )
    }

    /**
     * 双层解封。失败语义（两段分层抛出，调用方必须区分）：
     * - 主密码错误（或 payload 被篡改）→ outer GCM tag 失败 → [WrongPasswordException]
     * - TEE 密钥失效（换机/恢复出厂/密钥被删）→ [KeyWrapper] 抛出的任何异常
     *   统一转 [KeyUnavailableException] —— 语义为「走备份恢复」
     */
    fun unseal(wrapper: KeyWrapper, masterPassword: ByteArray, envelope: Envelope): ByteArray {
        val kek = KEK_PROFILE.derive(masterPassword, Aead.unb64(envelope.saltLocalB64))
        val inner = try {
            Aead.gcmDecrypt(
                kek,
                Aead.unb64(envelope.payloadB64),
                AAD_OUTER,
            )
        } catch (e: Aead.IntegrityException) {
            throw WrongPasswordException()
        }
        return try {
            wrapper.unwrap(inner, AAD_INNER)
        } catch (e: KeyUnavailableException) {
            throw e
        } catch (e: Exception) {
            // 走到这里说明 outer 已解开（密码因素成立），失败必然在设备因素层：
            // KeyPermanentlyInvalidatedException / KeyStore 无此密钥 / GCM tag 不符等
            throw KeyUnavailableException()
        }
    }
}
