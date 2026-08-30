package com.beyondguo.penly.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
) {
    companion object {
        const val MODE_CUSTOM = "custom"
        const val MODE_DEFAULT = "default"
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
    val createdAt: Long,
    val updatedAt: Long,
)
