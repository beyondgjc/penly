package com.beyondguo.penly.backup

import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.CryptoV2
import com.beyondguo.penly.data.PlainEntry
import com.beyondguo.penly.data.VaultItemV2
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `private-vault-backup` v2 备份文件编解码（《印迹_跨端契约v2_地基工程.md》§1-§3）。
 *
 * 与 v1 [BackupCodec] 的关系：完全独立、互不调用（仅复用 [BackupFormatException] 与格式常量）。
 * v1 继续服务存量备份文件的读取/导入；v2 是导出与迁移的目标格式。
 *
 * 关键语义（两端契约，改动必须先改契约文档并同步小程序端）：
 * - 顶层 `version` 是 v1/v2 分发键（2 = 本格式）；
 * - `meta.verify` 密码预检槽位：GCM(key32, "yinji-verify")，aad="yinji-verify-v1"
 *   —— 空备份也能预检密码（v1 空备份无密码可验的补丁）；
 * - 子密钥 = HKDF-SHA256(ikm=key32, salt=空, info="yinji-enc-v2")；
 * - 字段 AAD = `"<字段名>|<条目id>"`，字段名 ∈ {account, secret, note, totp}；
 * - 空串 `*Enc` = 无内容（不加密空串）；v2 文件不含 `*Iv` / `*Mac` 键。
 */
object BackupCodecV2 {

    const val FORMAT = BackupCodec.FORMAT
    const val VERSION = 2

    /** 契约层固定参数（§0/§1；小程序 WASM 可跑性实测前不动） */
    const val KDF_ALGO = "argon2id"
    const val KDF_MEMORY_KIB = 32 * 1024
    const val KDF_ITERATIONS = 4
    const val KDF_PARALLELISM = 1
    const val CIPHER_ALGO = "aes-256-gcm"

    private const val VERIFY_PLAINTEXT = "yinji-verify"
    private val VERIFY_AAD = "yinji-verify-v1".toByteArray(Charsets.UTF_8)

    /** v2 字段名枚举（AAD 第一段；即 SerialName 去掉 Enc 后缀） */
    const val FIELD_ACCOUNT = "account"
    const val FIELD_SECRET = "secret"
    const val FIELD_NOTE = "note"
    const val FIELD_TOTP = "totp"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // 契约参数（kdf/cipher/contractVersion/count…）必须显式落盘——
        // v1 教训：默认值不落盘是隐式契约（MEMORY.md「BackupCodec 陷阱」条）
        encodeDefaults = true
    }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

    // ---------------- 文件模型（§1） ----------------

    @Serializable
    data class BackupMetaV2(
        @SerialName("contractVersion") val contractVersion: Int = 2,
        @SerialName("createdAt") val createdAt: Long = 0,
        @SerialName("count") val count: Int = 0,
        /** 密码预检槽位：base64(GCM(key32, "yinji-verify"))，aad="yinji-verify-v1" */
        @SerialName("verify") val verify: String,
        /** 仅 wxb-def-v1 来源需要（派生小程序默认主密码） */
        @SerialName("openid") val openid: String? = null,
    )

    @Serializable
    data class KdfParamsV2(
        @SerialName("algo") val algo: String,
        @SerialName("memoryKiB") val memoryKiB: Int,
        @SerialName("iterations") val iterations: Int,
        @SerialName("parallelism") val parallelism: Int,
        @SerialName("saltB64") val saltB64: String,
    )

    @Serializable
    data class CipherParamsV2(
        @SerialName("algo") val algo: String,
        @SerialName("nonceBytes") val nonceBytes: Int,
    )

    @Serializable
    data class BackupCryptoV2(
        /** null = custom 主密码；penly-def-v1 / wxb-def-v1 = 默认保护（语义同 v1） */
        @SerialName("masterRef") val masterRef: String? = null,
        @SerialName("kdf") val kdf: KdfParamsV2,
        @SerialName("cipher") val cipher: CipherParamsV2,
    )

    @Serializable
    data class BackupFileV2(
        @SerialName("format") val format: String,
        @SerialName("version") val version: Int,
        @SerialName("exportedAt") val exportedAt: Long,
        @SerialName("meta") val meta: BackupMetaV2,
        @SerialName("crypto") val crypto: BackupCryptoV2,
        @SerialName("items") val items: List<VaultItemV2> = emptyList(),
    )

    // ---------------- 派生（§3：子密钥 = HKDF(key32, salt=空, info=域标签)） ----------------

    /** 字段 AAD：`"<字段名>|<条目id>"` —— 防密文跨字段/跨条目搬移 */
    fun aad(field: String, itemId: String): ByteArray = "$field|$itemId".utf8()

    private fun deriveKey32(masterPassword: ByteArray, kdf: KdfParamsV2): ByteArray {
        if (kdf.algo != KDF_ALGO) throw BackupFormatException("KDF 不支持（${kdf.algo}）")
        return CryptoV2.argon2id(
            password = masterPassword,
            salt = CryptoV2.unb64(kdf.saltB64),
            memoryKiB = kdf.memoryKiB,
            iterations = kdf.iterations,
            parallelism = kdf.parallelism,
        )
    }

    private fun resolveMasterBytes(masterRef: String?, password: String, openid: String?): ByteArray =
        when (masterRef) {
            null -> password.utf8()
            CryptoEngine.MASTER_REF_ANDROID -> CryptoEngine.ANDROID_DEFAULT_MASTER.utf8()
            CryptoEngine.MASTER_REF_WXB -> (CryptoEngine.WXB_DEFAULT_PREFIX + (openid ?: "")).utf8()
            else -> throw BackupFormatException("未知 masterRef（$masterRef）")
        }

    private fun key32Of(file: BackupFileV2, password: String): ByteArray =
        deriveKey32(resolveMasterBytes(file.crypto.masterRef, password, file.meta.openid), file.crypto.kdf)

    // ---------------- 编码（导出） ----------------

    /** 单字段加密：空串原样返回（无内容），否则 GCM(subKey) → base64(nonce||ct||tag) */
    private fun enc(plain: String, field: String, itemId: String, subKey: ByteArray): String =
        if (plain.isEmpty()) ""
        else CryptoV2.b64(CryptoV2.gcmEncrypt(subKey, plain.utf8(), aad(field, itemId)))

    fun encryptEntry(e: PlainEntry, subKey: ByteArray): VaultItemV2 = VaultItemV2(
        id = e.id,
        title = e.title,
        category = e.category,
        accountEnc = enc(e.account, FIELD_ACCOUNT, e.id, subKey),
        secretEnc = enc(e.secret, FIELD_SECRET, e.id, subKey),
        noteEnc = enc(e.note, FIELD_NOTE, e.id, subKey),
        totpEnc = enc(e.totp, FIELD_TOTP, e.id, subKey),
        totpDigits = e.totpDigits,
        totpPeriod = e.totpPeriod,
        totpAlgo = e.totpAlgo,
        appPackage = e.appPackage,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    /**
     * 编码 v2 备份文件（导出主路径）。
     * @param masterRef null=custom（[password] 即用户主密码）；非 null 时 [password]
     *   必须传对应来源的默认主密码（仓库层负责取值，如 ANDROID_DEFAULT_MASTER）
     */
    fun encode(
        entries: List<PlainEntry>,
        masterRef: String?,
        password: String,
        vaultCreatedAt: Long = 0,
        openid: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String {
        require(password.isNotEmpty()) { "备份密码不能为空" }
        val salt = CryptoEngine.randomBytes(16)
        val key32 = CryptoV2.argon2id(
            password = password.utf8(), salt = salt,
            memoryKiB = KDF_MEMORY_KIB, iterations = KDF_ITERATIONS, parallelism = KDF_PARALLELISM,
        )
        val subKey = CryptoV2.subKey(key32, CryptoV2.Domains.ENC_V2)
        val file = BackupFileV2(
            format = FORMAT,
            version = VERSION,
            exportedAt = now,
            meta = BackupMetaV2(
                contractVersion = 2,
                createdAt = vaultCreatedAt,
                count = entries.size,
                verify = CryptoV2.b64(CryptoV2.gcmEncrypt(key32, VERIFY_PLAINTEXT.utf8(), VERIFY_AAD)),
                openid = openid,
            ),
            crypto = BackupCryptoV2(
                masterRef = masterRef,
                kdf = KdfParamsV2(KDF_ALGO, KDF_MEMORY_KIB, KDF_ITERATIONS, KDF_PARALLELISM, CryptoV2.b64(salt)),
                cipher = CipherParamsV2(CIPHER_ALGO, CryptoV2.NONCE_BYTES),
            ),
            items = entries.map { encryptEntry(it, subKey) },
        )
        return encodeFile(file)
    }

    /** 文件模型 → JSON（迁移/测试需要重序列化改写后的文件） */
    fun encodeFile(file: BackupFileV2): String = json.encodeToString(BackupFileV2.serializer(), file)

    // ---------------- 解码（导入） ----------------

    fun decode(text: String): BackupFileV2 {
        val file = try {
            json.decodeFromString(BackupFileV2.serializer(), text)
        } catch (_: Exception) {
            throw BackupFormatException("文件解析失败，不是有效的备份文件")
        }
        if (file.format != FORMAT) throw BackupFormatException("文件格式不支持（${file.format}）")
        if (file.version != VERSION) throw BackupFormatException("备份版本不支持（v${file.version}）")
        if (file.crypto.cipher.algo != CIPHER_ALGO) {
            throw BackupFormatException("加密算法不支持（${file.crypto.cipher.algo}）")
        }
        return file
    }

    /** 解密全部条目（导入主路径）。任一字段解不开 → [CryptoV2.IntegrityException] */
    fun decryptItems(file: BackupFileV2, password: String): List<PlainEntry> {
        val subKey = CryptoV2.subKey(key32Of(file, password), CryptoV2.Domains.ENC_V2)
        return file.items.map { it.toPlain(subKey) }
    }

    private fun VaultItemV2.toPlain(subKey: ByteArray): PlainEntry = PlainEntry(
        id = id,
        title = title,
        category = category,
        account = dec(accountEnc, FIELD_ACCOUNT, id, subKey),
        secret = dec(secretEnc, FIELD_SECRET, id, subKey),
        note = dec(noteEnc, FIELD_NOTE, id, subKey),
        totp = dec(totpEnc, FIELD_TOTP, id, subKey),
        totpDigits = totpDigits,
        totpPeriod = totpPeriod,
        totpAlgo = totpAlgo,
        appPackage = appPackage,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun dec(payloadB64: String, field: String, itemId: String, subKey: ByteArray): String =
        if (payloadB64.isEmpty()) ""
        else String(CryptoV2.gcmDecrypt(subKey, CryptoV2.unb64(payloadB64), aad(field, itemId)), Charsets.UTF_8)

    /**
     * 导入前预检（口径同 v1 [BackupCodec.verifyPassword]）：解得开才导入。
     * null = 通过；非 null = 可直接展示的错误文案。Argon2id 32MiB 档单次派生 ≈ 秒级。
     */
    fun verifyPassword(text: String, password: String): String? {
        val file = try {
            decode(text)
        } catch (e: BackupFormatException) {
            return e.message ?: "备份文件无效"
        }
        val key32 = try {
            key32Of(file, password)
        } catch (e: BackupFormatException) {
            return e.message
        }
        return try {
            CryptoV2.gcmDecrypt(key32, CryptoV2.unb64(file.meta.verify), VERIFY_AAD)
            null
        } catch (_: CryptoV2.IntegrityException) {
            "备份密码错误，无法导入"
        } catch (_: Exception) {
            "备份校验失败，文件可能已损坏"
        }
    }

    // ---------------- v1/v2 统一分发 ----------------

    /** 解析结果：v1 文件走 [BackupCodec] 旧链路，v2 走本对象新链路 */
    sealed interface ParsedBackup {
        data class V1(val file: BackupFile) : ParsedBackup
        data class V2(val file: BackupFileV2) : ParsedBackup
    }

    /** 按顶层 `version` 分发（导入接线的统一入口；version 缺失/非法按 v1 解析报错） */
    fun decodeAny(text: String): ParsedBackup {
        val version = try {
            json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
        return when (version) {
            VERSION -> ParsedBackup.V2(decode(text))
            else -> ParsedBackup.V1(BackupCodec.decode(text))
        }
    }
}
