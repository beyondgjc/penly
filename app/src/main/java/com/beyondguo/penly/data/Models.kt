package com.beyondguo.penly.data

import com.beyondguo.penly.crypto.CryptoEngine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 存储槽位 —— **刻意无语义**：A / B 不代表主次。
 *
 * 真库落在哪个槽位由初始化时随机决定，另一槽位承载占位数据（未设置应急密码）
 * 或影子数据（已设置应急密码）。存储层面因此无法区分主次，也无法判断
 * 「用户是否设置了应急密码」——这是影子保险库不可证伪性的地基。
 *
 * 当前生效槽位只在内存（[com.beyondguo.penly.crypto.SessionManager]），
 * 绝不写入任何持久化介质。
 */
enum class Slot(val index: Int) {
    A(0),
    B(1),
    ;

    fun other(): Slot = if (this == A) B else A

    companion object {
        fun of(index: Int): Slot = if (index == 0) A else B

        /** 随机槽位：消除「槽位 A 总是真库」这类跨设备统计规律 */
        fun random(): Slot = if (CryptoEngine.randomBytes(1)[0].toInt() and 1 == 0) A else B
    }
}

/**
 * 数据模型 —— 字段名与小程序存储结构/备份格式逐字对齐（`_id`、`xEnc`/`xIv` 等），
 * 保证 `private-vault-backup` 备份文件两侧互认。
 */
@Serializable
data class VaultMeta(
    @SerialName("saltB64") val saltB64: String,
    @SerialName("verifyB64") val verifyB64: String,
    @SerialName("verifyIvB64") val verifyIvB64: String,
    /** "custom"（用户主密码） / "default"（内置默认主密码） */
    @SerialName("pwdMode") val pwdMode: String = MODE_CUSTOM,
    @SerialName("initialized") val initialized: Boolean = true,
    @SerialName("createdAt") val createdAt: Long = 0,
    @SerialName("updatedAt") val updatedAt: Long = 0,
    /** 仅出现在小程序 default 模式导出的备份里（用于派生 wxb-def-v1 密钥）；本地存储不写入 */
    @SerialName("openid") val openid: String? = null,
    /** 存储格式版本，用于后续迁移；老数据缺失时按 1 处理 */
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_V1,
    /**
     * 辅助槽位凭证（命名刻意中性，不暗示主从）：
     * - `auxSaltB64`：另一槽位的 salt（salt 本就可公开）
     * - `auxSecretEnc` / `auxSecretIv`：用**本槽位**密钥加密的一段秘密
     *
     * 两个槽位的字段结构完全一致，但语义不同：
     * - 真库槽位：秘密是「另一槽位的密码」——未设置应急密码时是随机密码（占位槽位），
     *   已设置时是应急密码。解锁真库后可据此自动重生成影子数据。
     * - 影子槽位：秘密是一段随机诱饵。应急密码解开影子槽位只会得到诱饵，
     *   无法触达真库——这是 duress 需要的**单向性**。
     */
    @SerialName("auxSaltB64") val auxSaltB64: String = "",
    @SerialName("auxSecretEnc") val auxSecretEnc: String = "",
    @SerialName("auxSecretIv") val auxSecretIv: String = "",
) {
    companion object {
        const val MODE_CUSTOM = "custom"
        const val MODE_DEFAULT = "default"

        const val SCHEMA_V1 = 1 // 单槽位（legacy vault_meta / vault_items）
        const val SCHEMA_V2 = 2 // 双槽位（vm_0/vi_0、vm_1/vi_1），条目 AES-256-CBC + MAC 四元组
        const val SCHEMA_V3 = 3 // 双槽位，条目 AES-256-GCM 逐字段（v5.0 契约 v2 本机格式，见 ItemCipher）
    }
}

@Serializable
data class VaultItem(
    @SerialName("_id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("accountEnc") val accountEnc: String = "",
    @SerialName("accountIv") val accountIv: String = "",
    @SerialName("secretEnc") val secretEnc: String = "",
    @SerialName("secretIv") val secretIv: String = "",
    @SerialName("noteEnc") val noteEnc: String = "",
    @SerialName("noteIv") val noteIv: String = "",
    /**
     * TOTP 两步验证共享密钥（v3.0 项目④）：base32 串，与密码同等待遇逐字段加密。
     * 缺省空串 = 无 2FA —— 旧数据/旧备份反序列化自动兼容，无需迁移。
     * ⚠️ 隐式契约：密文缺失即无 2FA，任何消费方不得改为显式判空键。
     */
    @SerialName("totpEnc") val totpEnc: String = "",
    @SerialName("totpIv") val totpIv: String = "",
    /**
     * TOTP 展示/刷新参数（非敏感，明文存储；0 = 用默认 6 位 / 30 秒）。
     * 由 otpauth:// 链接参数下发，网站校验用同一组值，验证器必须保持一致。
     */
    @SerialName("totpDigits") val totpDigits: Int = 0,
    @SerialName("totpPeriod") val totpPeriod: Int = 0,
    /** TOTP 哈希算法（SHA1/SHA256/SHA512；空串 = 缺省 SHA1），非敏感明文存储 */
    @SerialName("totpAlgo") val totpAlgo: String = "",
    /**
     * 条目来源 App 包名（v3.0 项目⑤ Autofill）：系统保存密码时记录"它属于哪个 App"，
     * 填充时优先匹配（GitHub 的登录页只建议 GitHub 的条目）。非敏感明文存储；
     * 空串 = 无来源（手动创建的条目，填充时全量展示）。
     */
    @SerialName("appPackage") val appPackage: String = "",
    /**
     * 记录完整性 MAC（v1.1 跨端契约扩展，encrypt-then-MAC）：
     * HMAC-SHA256(子密钥, ivB64 + "." + 密文B64)，子密钥派生见 CryptoEngine.MAC_INFO。
     * 空串 = 无校验（旧数据/手动构造），读取时宽容跳过；四组与四个加密字段一一对应。
     */
    @SerialName("accountMac") val accountMac: String = "",
    @SerialName("secretMac") val secretMac: String = "",
    @SerialName("noteMac") val noteMac: String = "",
    /** totp 为 Android 单侧扩展字段，Mac 同样只在 Android 侧计算与验证 */
    @SerialName("totpMac") val totpMac: String = "",
    /**
     * Passkey（v5.0-②）：rpId / credentialId / userHandle **明文**存储——锁定态下
     * Credential Provider 的 begin 查询要靠 rpId 过滤条目，且三者仅泄露
     * 「存在哪些站点」这一非敏感事实（与第三方密码管理器同水位）。
     * 空串 rpId = 非密码条目（普通账密），旧数据/旧备份反序列化自动兼容。
     */
    @SerialName("rpId") val rpId: String = "",
    /** WebAuthn credentialId（raw bytes 的 base64；展示时转 base64url） */
    @SerialName("credIdB64") val credIdB64: String = "",
    /** WebAuthn userHandle（raw bytes 的 base64，注册时由 RP 下发） */
    @SerialName("userHandleB64") val userHandleB64: String = "",
    /** WebAuthn 签名计数器（防克隆指标，明文，每次断言后递增回存） */
    @SerialName("signCount") val signCount: Int = 0,
    /**
     * Passkey 私钥（PKCS8 DER 的 base64，软件密钥对——2026-09-19 拍板：随金库加密
     * 存储以换取换机/备份恢复后 passkey 可用）。加密待遇与 account/secret/note/totp
     * 完全一致（GCM: AAD=passkey|条目id；CBC: aesEncrypt+recordMac）。
     */
    @SerialName("passkeyEnc") val passkeyEnc: String = "",
    @SerialName("passkeyIv") val passkeyIv: String = "",
    @SerialName("passkeyMac") val passkeyMac: String = "",
    @SerialName("createdAt") val createdAt: Long = 0,
    @SerialName("updatedAt") val updatedAt: Long = 0,
)

/**
 * v2 条目（契约 v2，《印迹_跨端契约v2_地基工程.md》§1-§3）：
 * - `*Enc` = base64( nonce(12B) || ciphertext || tag(16B) )，GCM 载荷自带 nonce → **无 `*Iv` 字段**
 * - 完整性由 GCM tag（防篡改）+ AAD=`字段名|条目id`（防搬移）接管 → **无 `*Mac` 字段**
 * - 空串 `*Enc` = 该字段无内容（约定：不加密空串）
 * - 明文字段与 v1 [VaultItem] 逐字对齐（title/category/totp 参数/appPackage/时间戳）
 */
@Serializable
data class VaultItemV2(
    @SerialName("_id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("accountEnc") val accountEnc: String = "",
    @SerialName("secretEnc") val secretEnc: String = "",
    @SerialName("noteEnc") val noteEnc: String = "",
    @SerialName("totpEnc") val totpEnc: String = "",
    @SerialName("totpDigits") val totpDigits: Int = 0,
    @SerialName("totpPeriod") val totpPeriod: Int = 0,
    @SerialName("totpAlgo") val totpAlgo: String = "",
    @SerialName("appPackage") val appPackage: String = "",
    /** Passkey 扩展（v5.0-②，与 [VaultItem] 同名同义；GCM 密文自带 nonce，无 Iv/Mac） */
    @SerialName("rpId") val rpId: String = "",
    @SerialName("credIdB64") val credIdB64: String = "",
    @SerialName("userHandleB64") val userHandleB64: String = "",
    @SerialName("signCount") val signCount: Int = 0,
    @SerialName("passkeyEnc") val passkeyEnc: String = "",
    @SerialName("createdAt") val createdAt: Long = 0,
    @SerialName("updatedAt") val updatedAt: Long = 0,
)

/** 解密后的条目（仅存在于内存/界面层，绝不持久化） */
data class PlainEntry(
    val id: String,
    val title: String,
    val category: String,
    val account: String,
    val secret: String,
    val note: String,
    /** TOTP 共享密钥（base32 串，未规范化原值可为空） */
    val totp: String = "",
    /** TOTP 参数（0 = 用默认 6 位 / 30 秒） */
    val totpDigits: Int = 0,
    val totpPeriod: Int = 0,
    /** TOTP 哈希算法（空串 = 缺省 SHA1） */
    val totpAlgo: String = "",
    /** 条目来源 App 包名（空串 = 手动创建，无来源匹配） */
    val appPackage: String = "",
    /** Passkey 扩展（非空 rpId = passkey 条目；priv = PKCS8 DER 的 base64） */
    val rpId: String = "",
    val credIdB64: String = "",
    val userHandleB64: String = "",
    val signCount: Int = 0,
    val passkeyPriv: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 单条记录的体检状态（[breached] 语义：0=未泄露，>0=泄露 N 次，-1=联网查询失败/未开启）。
 * 仅包含结构化判定结果，不含任何明文。
 */
@Serializable
data class ItemSecurityState(
    @SerialName("itemId") val itemId: String,
    @SerialName("breached") val breached: Int = 0,
    @SerialName("reused") val reused: Boolean = false,
    @SerialName("weak") val weak: Boolean = false,
)

/** 一次体检结果（[SecurityReportStore] 用会话密钥加密缓存，锁定即不可读） */
@Serializable
data class SecurityReport(
    @SerialName("scannedAt") val scannedAt: Long = 0,
    @SerialName("totalCount") val totalCount: Int = 0,
    @SerialName("breachedCount") val breachedCount: Int = 0,
    @SerialName("reusedCount") val reusedCount: Int = 0,
    @SerialName("weakCount") val weakCount: Int = 0,
    @SerialName("states") val states: List<ItemSecurityState> = emptyList(),
)
