package com.beyondguo.penly.crypto

import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * 自描述密文容器（#17）——「数据能活十年」的保险。
 *
 * ## 要解决的问题
 * 密文一旦落盘，解密方必须知道**用什么参数、什么算法**加密的。
 * 如果这些信息只存在于代码常量里，那么每次调整默认参数，老数据就解不开了。
 * 容器把参数**写进数据本身**，于是：
 * - 老数据带着自己的参数走，改默认值不影响它
 * - 未知的版本 / 算法能被**明确识别并拒绝**，而不是被错误地当成本版本解开
 *
 * ## 分层：库头 + 单字段密文
 * ```
 * VaultHeader     每库一份（明文存，不含任何秘密）：headerV + kdf + dataAlg
 *   └─ FieldCiphertext   每条记录每个字段一份：algId(可省) + payloadB64
 * ```
 * **为什么库头与记录分开**：Argon2id（64MiB 档）一次派生约 500ms，
 * 不可能每条记录派生一次。库级 salt 派生一次得 key32（由调用方缓存在
 * [KeySession] 里），记录级只用 HKDF 子密钥（域标签 [Aead.Domains.ENC]）
 * + 每字段独立随机 nonce。
 *
 * ## 设计依据：本项目已有先例
 * 跨端备份的 `BackupCodecV2` **已经在这么做**——它从备份文件的 `kdf` 字段里
 * 读回 `saltB64 / memoryKiB / iterations / parallelism`。本容器就是把那个做法
 * 抽成通用组件，**字段结构与之对齐**（[KdfSpec] 同名同义），不另创一套。
 *
 * ## fail-closed 纪律
 * 未知 `headerV` / `kdf.alg` / `dataAlg` → 抛 [UnsupportedFormatException]，
 * **不猜、不降级**。
 */
object Container {

    /** 当前容器格式版本 */
    const val HEADER_V = 1

    const val KDF_ARGON2ID = "argon2id"
    const val DATA_ALG_AES_256_GCM = "aes-256-gcm"

    /** 每库 salt 长度（与契约层、本机层一致） */
    const val SALT_LEN_BYTES = 16

    private val RANDOM = SecureRandom()

    /**
     * 建库：按档位生成参数 + 随机盐，产出库头。
     *
     * **档位只在这里出现一次**——它立刻被展开成显式数值（[KdfSpec]）。
     * 之后所有解密都只读库头里的数值，不再回头看 [Profile]，
     * 这是"调整档位定义不会让老数据失效"的实现方式。
     */
    fun createHeader(profile: Profile): VaultHeader {
        val salt = ByteArray(SALT_LEN_BYTES).also { RANDOM.nextBytes(it) }
        return VaultHeader(
            headerV = HEADER_V,
            kdf = profile.toSpec(Aead.b64(salt)),
            dataAlg = DATA_ALG_AES_256_GCM,
        )
    }

    /**
     * 按**库头里存的参数**派生密钥（不是按当前默认参数——这是关键区别）。
     *
     * 调用方应在解锁时调用一次，把结果交给 [KeySession.establish]，
     * 不要每条记录都调（64MiB 档约 500ms）。
     */
    fun deriveKey(header: VaultHeader, password: ByteArray): ByteArray {
        val kdf = header.kdf
        if (kdf.alg != KDF_ARGON2ID) {
            throw UnsupportedFormatException("未知的 KDF 算法：${kdf.alg}（本版本仅支持 $KDF_ARGON2ID）")
        }
        return Aead.argon2id(
            password = password,
            salt = Aead.unb64(kdf.saltB64),
            memoryKiB = kdf.memoryKiB,
            iterations = kdf.iterations,
            parallelism = kdf.parallelism,
        )
    }

    /**
     * 加密一个字段。
     *
     * @param key 库级 key32（由 [deriveKey] 得到）
     * @param aad 附加上下文，**强烈建议填**：把密文钉在"哪条记录哪个字段"上，
     *   防止攻击者把一个字段的密文搬到另一个字段/另一条记录仍能解开。
     *   惯例格式 `"<字段名>|<记录id>"`（见 SDK 接口清单 §五）。
     */
    fun seal(
        header: VaultHeader,
        key: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): FieldCiphertext {
        requireDataAlgSupported(header)
        val subKey = Aead.subKey(key, Aead.Domains.ENC)
        val payload = Aead.gcmEncrypt(subKey, plaintext, aad)
        return FieldCiphertext(
            algId = null, // 缺省继承库头
            payloadB64 = Aead.b64(payload),
        )
    }

    /**
     * 解密一个字段。
     *
     * 认证失败（密文被改 / AAD 不符 / key 不对）→ [Aead.IntegrityException]。
     */
    fun open(
        header: VaultHeader,
        key: ByteArray,
        aad: ByteArray,
        ct: FieldCiphertext,
    ): ByteArray {
        val alg = ct.algId ?: header.dataAlg
        if (alg != DATA_ALG_AES_256_GCM) {
            throw UnsupportedFormatException("未知的数据算法：$alg（本版本仅支持 $DATA_ALG_AES_256_GCM）")
        }
        return Aead.gcmDecrypt(
            Aead.subKey(key, Aead.Domains.ENC),
            Aead.unb64(ct.payloadB64),
            aad,
        )
    }

    /** 校验库头版本与算法，任何不认识的一律拒绝（不猜、不降级） */
    private fun requireDataAlgSupported(header: VaultHeader) {
        if (header.headerV != HEADER_V) {
            throw UnsupportedFormatException(
                "未知的容器版本：${header.headerV}（本版本支持到 $HEADER_V）",
            )
        }
        if (header.dataAlg != DATA_ALG_AES_256_GCM) {
            throw UnsupportedFormatException(
                "未知的数据算法：${header.dataAlg}（本版本仅支持 $DATA_ALG_AES_256_GCM）",
            )
        }
    }
}

/**
 * KDF 参数（显式数值，**不是档位名**）。
 *
 * 字段与跨端备份的 `BackupCodecV2.KdfParamsV2` 对齐（`alg/saltB64/memoryKiB/
 * iterations/parallelism`）——两处存的是同一类信息，故意保持同构。
 *
 * @param saltB64 每库独立的盐（Base64）
 * @param memoryKiB Argon2id 内存代价（KiB）
 */
@Serializable
data class KdfSpec(
    val alg: String = Container.KDF_ARGON2ID,
    val saltB64: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
)

/**
 * 库头：每库一份，**明文存**（不含任何秘密，只有参数）。
 *
 * @param headerV 容器格式版本——决定怎么解读其余字段
 * @param dataAlg 字段级密文算法（GCM 载荷布局 = nonce(12) ‖ ct ‖ tag(16)）
 */
@Serializable
data class VaultHeader(
    val headerV: Int = Container.HEADER_V,
    val kdf: KdfSpec,
    val dataAlg: String = Container.DATA_ALG_AES_256_GCM,
)

/**
 * 单字段密文。载荷是 [Aead.gcmEncrypt] 的输出原样 Base64：
 * `nonce(12B) ‖ ciphertext ‖ tag(16B)`。
 *
 * @param algId 缺省（null）= 继承库头的 `dataAlg`；显式填 = 该字段用了不同算法
 *   （为将来算法迁移预留；当前实现只认 GCM）
 */
@Serializable
data class FieldCiphertext(
    val algId: String? = null,
    val payloadB64: String,
)
