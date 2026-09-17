package com.beyondguo.penly.backup

import com.beyondguo.penly.crypto.CryptoEngine
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

    /**
     * 导入前预检：验证备份密码，确保「解得开才导入」——密码错误在覆盖本地数据之前就被拦截。
     *
     * 返回 null = 验证通过，可执行导入流程；非 null = 可直接展示的错误文案。
     * - custom 备份：[password] 必须是导出时设置的主密码；
     * - 默认保护备份：密码由 masterRef/openid 派生（与 [com.beyondguo.penly.data.VaultRepository] 导入口径一致），[password] 被忽略；
     * - meta 缺失的空备份：无密码可验，直接通过（清空语义由导入流程处理）。
     *
     * 纯函数、无 IO：重复一次 PBKDF2 派生（~200ms）换取导入数据必定可解锁的确定性。
     */
    fun verifyPassword(json: String, password: String): String? {
        val file = try {
            decode(json)
        } catch (e: BackupFormatException) {
            return e.message ?: "备份文件无效"
        }
        val m = file.data.meta ?: return null // 空备份：没有可验证的密码
        if (m.pwdMode != VaultMeta.MODE_DEFAULT) {
            if (password.isBlank()) return "请输入备份密码"
            val key = CryptoEngine.deriveKeyB64(password, m.saltB64)
            if (!CryptoEngine.verifyMaster(key, m.verifyB64, m.verifyIvB64)) {
                return "备份密码错误，无法导入"
            }
            return null
        }
        // 默认保护备份：与导入流程同口径按 masterRef/openid 派生，输入的 password 不参与
        val srcMaster = when (file.crypto.masterRef) {
            CryptoEngine.MASTER_REF_ANDROID -> CryptoEngine.ANDROID_DEFAULT_MASTER
            null, CryptoEngine.MASTER_REF_WXB -> m.openid?.let { CryptoEngine.WXB_DEFAULT_PREFIX + it }
            else -> return null // 未知来源：留给导入流程按同口径报错
        } ?: return null // WXB 缺 openid：留给导入流程报「缺少身份标识」
        val key = CryptoEngine.deriveKeyB64(srcMaster, m.saltB64)
        if (!CryptoEngine.verifyMaster(key, m.verifyB64, m.verifyIvB64)) {
            return "备份校验失败，文件可能已损坏"
        }
        return null
    }
}
