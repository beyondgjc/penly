package com.beyondguo.penly.security

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.SessionManager
import com.beyondguo.penly.data.SecurityReport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 体检报告缓存 + 联网开关偏好。
 *
 * - 报告用**会话密钥**加密后存 DataStore（key: security_report）：锁定后即无法解密读取
 *   （《方案》§7 要求），重查即可；缓存有效期 7 天。
 * - 联网开关 [breach_net_on] 仅记录用户是否授权联网校验，不含敏感信息，锁定亦可持久化。
 */
class SecurityReportStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val reportKey = stringPreferencesKey("security_report")
    private val netKey = booleanPreferencesKey("breach_net_on")

    private val Context.ds by preferencesDataStore(name = "penly_security")

    // ---- 联网开关（用户授权，默认关闭）----
    suspend fun isNetworkEnabled(): Boolean = context.ds.data.first()[netKey] ?: false
    suspend fun setNetworkEnabled(on: Boolean) {
        context.ds.edit { it[netKey] = on }
    }

    // ---- 报告缓存（会话密钥加密）----
    suspend fun save(report: SecurityReport) {
        val k = SessionManager.requireKey()
        val enc = CryptoEngine.aesEncrypt(json.encodeToString(SecurityReport.serializer(), report), k)
        context.ds.edit { it[reportKey] = "${enc.ivB64}|${enc.dataB64}" }
    }

    /** 读取缓存；失效（>7 天）或解密失败（如改密后密钥变更）返回 null */
    suspend fun load(): SecurityReport? {
        val raw = context.ds.data.first()[reportKey] ?: return null
        val k = SessionManager.requireKey() // 锁定抛 VaultLockedException
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2) return null
        return try {
            val jsonText = CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(parts[0], parts[1]), k)
            val report = json.decodeFromString(SecurityReport.serializer(), jsonText)
            if (System.currentTimeMillis() - report.scannedAt > CACHE_MS) null else report
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clear() {
        context.ds.edit { it.remove(reportKey) }
    }

    companion object {
        const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
