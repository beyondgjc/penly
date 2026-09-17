package com.beyondguo.penly

import com.beyondguo.penly.backup.BackupCodec
import com.beyondguo.penly.backup.BackupCrypto
import com.beyondguo.penly.backup.BackupData
import com.beyondguo.penly.backup.BackupFile
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.VaultItem
import com.beyondguo.penly.data.VaultMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导入前预检（BackupCodec.verifyPassword）：
 * 「解得开才导入」——密码错误必须在覆盖本地数据之前被拦截。
 */
class BackupVerificationTest {

    private fun customBackupJson(master: String): String {
        val saltB64 = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(master, saltB64)
        val (vB64, vIvB64) = CryptoEngine.makeVerify(key)
        val meta = VaultMeta(
            saltB64 = saltB64, verifyB64 = vB64, verifyIvB64 = vIvB64,
            pwdMode = VaultMeta.MODE_CUSTOM, createdAt = 1L, updatedAt = 1L,
        )
        return encode(meta, masterRef = null, items = listOf(VaultItem(id = "a_t", title = "t")))
    }

    private fun androidDefaultBackupJson(): String {
        val saltB64 = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(CryptoEngine.ANDROID_DEFAULT_MASTER, saltB64)
        val (vB64, vIvB64) = CryptoEngine.makeVerify(key)
        val meta = VaultMeta(
            saltB64 = saltB64, verifyB64 = vB64, verifyIvB64 = vIvB64,
            pwdMode = VaultMeta.MODE_DEFAULT, createdAt = 1L, updatedAt = 1L,
        )
        return encode(meta, masterRef = CryptoEngine.MASTER_REF_ANDROID)
    }

    private fun wxbBackupJson(openid: String): String {
        val saltB64 = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(CryptoEngine.WXB_DEFAULT_PREFIX + openid, saltB64)
        val (vB64, vIvB64) = CryptoEngine.makeVerify(key)
        val meta = VaultMeta(
            saltB64 = saltB64, verifyB64 = vB64, verifyIvB64 = vIvB64,
            pwdMode = VaultMeta.MODE_DEFAULT, openid = openid, createdAt = 1L, updatedAt = 1L,
        )
        return encode(meta, masterRef = CryptoEngine.MASTER_REF_WXB)
    }

    private fun encode(
        meta: VaultMeta,
        masterRef: String?,
        items: List<VaultItem> = emptyList(),
    ): String = BackupCodec.encode(
        BackupFile(
            format = BackupCodec.FORMAT, version = BackupCodec.VERSION, exportedAt = 1L,
            crypto = BackupCrypto(
                kdf = "PBKDF2", hash = "SHA-256", iterations = 100000,
                keyLen = 32, saltLen = 16, ivLen = 16,
                cipher = "AES-256-CBC", encoding = "base64",
                masterRef = masterRef,
            ),
            data = BackupData(meta = meta, items = items),
        ),
    )

    @Test
    fun custom_correctPassword_passes() {
        assertNull(BackupCodec.verifyPassword(customBackupJson("test-master-1"), "test-master-1"))
    }

    @Test
    fun custom_wrongPassword_rejected() {
        assertEquals("备份密码错误，无法导入", BackupCodec.verifyPassword(customBackupJson("test-master-1"), "wrong-pwd"))
    }

    @Test
    fun custom_blankPassword_rejected() {
        assertEquals("请输入备份密码", BackupCodec.verifyPassword(customBackupJson("test-master-1"), ""))
    }

    @Test
    fun androidDefault_backup_passwordIgnored_alwaysPasses() {
        val json = androidDefaultBackupJson()
        assertNull(BackupCodec.verifyPassword(json, ""))
        assertNull(BackupCodec.verifyPassword(json, "whatever-typed"))
    }

    @Test
    fun wxbDefault_backup_passwordIgnored_passes() {
        assertNull(BackupCodec.verifyPassword(wxbBackupJson("o_test_openid"), "whatever"))
    }

    @Test
    fun emptyBackup_metaNull_passesThrough() {
        // 与 exportJson 空库导出等价：data 里不带 meta 字段 → 反序列化 meta=null
        val json = """
            {"format":"private-vault-backup","version":1,"exportedAt":1,
             "crypto":{"kdf":"PBKDF2","hash":"SHA-256","iterations":100000,"keyLen":32,
                       "saltLen":16,"ivLen":16,"cipher":"AES-256-CBC","encoding":"base64"},
             "data":{"items":[]}}
        """.trimIndent()
        assertNull(BackupCodec.verifyPassword(json, ""))
    }

    @Test
    fun corruptedJson_rejected_withParseError() {
        assertEquals(
            "文件解析失败，不是有效的备份文件",
            BackupCodec.verifyPassword("not-a-backup", "any"),
        )
    }
}
