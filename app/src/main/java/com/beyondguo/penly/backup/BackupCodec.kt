package com.beyondguo.penly.backup

import com.beyondguo.penly.data.VaultItem
import com.beyondguo.penly.data.VaultMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `private-vault-backup` v1 备份文件编解码。
 * 与小程序 services/export.js 产出格式逐字段对齐；唯一扩展是 crypto.masterRef（小程序会忽略未知字段）。
 */
@Serializable
data class BackupCrypto(
    @SerialName("kdf") val kdf: String,
    @SerialName("hash") val hash: String,
    @SerialName("iterations") val iterations: Int,
    @SerialName("keyLen") val keyLen: Int,
    @SerialName("saltLen") val saltLen: Int,
    @SerialName("ivLen") val ivLen: Int,
    @SerialName("cipher") val cipher: String,
    @SerialName("encoding") val encoding: String,
    /** 密钥来源标记：penly-def-v1 / wxb-def-v1 / 缺省(=custom 主密码) */
    @SerialName("masterRef") val masterRef: String? = null,
)

@Serializable
data class BackupData(
    @SerialName("meta") val meta: VaultMeta? = null,
    @SerialName("items") val items: List<VaultItem> = emptyList(),
)

@Serializable
data class BackupFile(
    @SerialName("format") val format: String,
    @SerialName("version") val version: Int,
    @SerialName("exportedAt") val exportedAt: Long,
    @SerialName("crypto") val crypto: BackupCrypto,
    @SerialName("data") val data: BackupData,
)

/** 备份文件不合法 */
class BackupFormatException(message: String) : Exception(message)

object BackupCodec {

    const val FORMAT = "private-vault-backup"
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw BackupFormatException("文件解析失败，不是有效的备份文件")
        }
        if (file.format != FORMAT) throw BackupFormatException("文件格式不支持（${file.format}）")
        if (file.version > VERSION) throw BackupFormatException("备份版本过新（v${file.version}），请先升级应用")
        return file
    }
}
