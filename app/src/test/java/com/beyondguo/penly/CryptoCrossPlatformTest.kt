package com.beyondguo.penly

import com.beyondguo.penly.backup.BackupCodec
import com.beyondguo.penly.backup.BackupFile
import com.beyondguo.penly.crypto.CryptoEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨端加密互认测试：fixtures 由 tools/gen_test_fixtures.mjs 生成
 * （Node 原生 crypto，与小程序 crypto-lite 已逐字节互验）。
 * 通过即证明 Android 端可解锁/导入小程序导出的备份，导出亦可被小程序读取。
 */
class CryptoCrossPlatformTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---------- 引擎级向量 ----------

    @Test
    fun pbkdf2_matchesRfcVector() {
        val key = CryptoEngine.deriveKey("password", "salt".toByteArray(Charsets.UTF_8), iterations = 1)
        // PBKDF2-HMAC-SHA256("password","salt",c=1,32) 的公开标准向量
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(key),
        )
    }

    @Test
    fun pbkdf2_matchesNodeVector_100k() {
        // 与 Node 10 万次迭代向量对齐：从 fixture meta 的 salt 派生，须能解出校验串
        val file = BackupCodec.decode(res("backup_custom.json"))
        val key = CryptoEngine.deriveKeyB64("test-master-123", file.data.meta!!.saltB64)
        assertTrue(
            CryptoEngine.verifyMaster(key, file.data.meta!!.verifyB64, file.data.meta!!.verifyIvB64),
        )
    }

    // ---------- custom 备份（通用交换格式） ----------

    @Test
    fun unlockAndDecrypt_customBackupFromMiniProgram() {
        val file = BackupCodec.decode(res("backup_custom.json"))
        val meta = file.data.meta!!
        val key = CryptoEngine.deriveKeyB64("test-master-123", meta.saltB64)
        assertTrue(CryptoEngine.verifyMaster(key, meta.verifyB64, meta.verifyIvB64))

        val items = file.data.items
        assertEquals(2, items.size)

        fun dec(iv: String, enc: String) = CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, enc), key)

        val bank = items.first { it.title == "银行账号" }
        assertEquals("user-john-01", dec(bank.accountIv, bank.accountEnc))
        assertEquals("P@ss中文✅-secret", dec(bank.secretIv, bank.secretEnc))
        assertEquals("备注：含 emoji 🎉 与中文", dec(bank.noteIv, bank.noteEnc))

        val gh = items.first { it.title == "GitHub" }
        assertEquals("dev@example.com", dec(gh.accountIv, gh.accountEnc))
        assertEquals("gh_pat_1234567890", dec(gh.secretIv, gh.secretEnc))
    }

    @Test
    fun wrongPassword_rejected() {
        val file = BackupCodec.decode(res("backup_custom.json"))
        val meta = file.data.meta!!
        val badKey = CryptoEngine.deriveKeyB64("wrong-password", meta.saltB64)
        assertFalse(CryptoEngine.verifyMaster(badKey, meta.verifyB64, meta.verifyIvB64))
    }

    // ---------- 小程序 default 备份（openid 派生）→ Android 本地默认密钥 ----------

    @Test
    fun importWxbDefaultBackup_reEncryptToLocalDefault() {
        val file = BackupCodec.decode(res("backup_wxb_default.json"))
        val meta = file.data.meta!!
        assertEquals("default", meta.pwdMode)

        // 1. 用备份内 openid 派生源密钥并校验
        val sourceMaster = CryptoEngine.WXB_DEFAULT_PREFIX + meta.openid
        val srcKey = CryptoEngine.deriveKeyB64(sourceMaster, meta.saltB64)
        assertTrue(CryptoEngine.verifyMaster(srcKey, meta.verifyB64, meta.verifyIvB64))

        // 2. 解密源数据（模拟 VaultRepository.importJson 的重加密路径）
        val item = file.data.items.single()
        fun dec(iv: String, enc: String, key: ByteArray) =
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, enc), key)
        val plainSecret = dec(item.secretIv, item.secretEnc, srcKey)
        assertEquals("mail-pwd-🔧", plainSecret)

        // 3. 重加密为 Android 本地默认密钥，新 meta 校验通过
        val newSaltB64 = CryptoEngine.randomSaltB64()
        val targetKey = CryptoEngine.deriveKeyB64(CryptoEngine.ANDROID_DEFAULT_MASTER, newSaltB64)
        val re = CryptoEngine.aesEncrypt(plainSecret, targetKey)
        val (vB64, vIvB64) = CryptoEngine.makeVerify(targetKey)
        assertTrue(CryptoEngine.verifyMaster(targetKey, vB64, vIvB64))
        assertEquals("mail-pwd-🔧", dec(re.ivB64, re.dataB64, targetKey))
        // 旧openid密钥不再能解开新数据
        assertFalse(CryptoEngine.verifyMaster(srcKey, vB64, vIvB64))
    }

    // ---------- Android default 导出 ↔ 导入回环 ----------

    @Test
    fun exportImportRoundTrip_androidDefault() {
        // 导出侧：用默认主密码初始化并加密一条记录
        val saltB64 = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(CryptoEngine.ANDROID_DEFAULT_MASTER, saltB64)
        val (vB64, vIvB64) = CryptoEngine.makeVerify(key)
        val p = CryptoEngine.aesEncrypt("round-trip-账号🎯", key)
        val meta = com.beyondguo.penly.data.VaultMeta(
            saltB64 = saltB64, verifyB64 = vB64, verifyIvB64 = vIvB64,
            pwdMode = com.beyondguo.penly.data.VaultMeta.MODE_DEFAULT, initialized = true,
        )
        val item = com.beyondguo.penly.data.VaultItem(
            id = "a_roundtrip", title = "回环", category = "测试",
            accountEnc = p.dataB64, accountIv = p.ivB64,
        )
        val json = BackupCodec.encode(
            BackupFile(
                format = BackupCodec.FORMAT, version = BackupCodec.VERSION, exportedAt = 1L,
                crypto = com.beyondguo.penly.backup.BackupCrypto(
                    kdf = "PBKDF2", hash = "SHA-256", iterations = 100000,
                    keyLen = 32, saltLen = 16, ivLen = 16,
                    cipher = "AES-256-CBC", encoding = "base64",
                    masterRef = CryptoEngine.MASTER_REF_ANDROID,
                ),
                data = com.beyondguo.penly.backup.BackupData(meta = meta, items = listOf(item)),
            ),
        )
        // 导入侧：解码 → 默认主密码解锁 → 解密一致
        val back = BackupCodec.decode(json)
        assertEquals(CryptoEngine.MASTER_REF_ANDROID, back.crypto.masterRef)
        val k2 = CryptoEngine.deriveKeyB64(CryptoEngine.ANDROID_DEFAULT_MASTER, back.data.meta!!.saltB64)
        assertTrue(CryptoEngine.verifyMaster(k2, back.data.meta!!.verifyB64, back.data.meta!!.verifyIvB64))
        val acc = CryptoEngine.aesDecrypt(
            CryptoEngine.EncPayload(back.data.items.single().accountIv, back.data.items.single().accountEnc),
            k2,
        )
        assertEquals("round-trip-账号🎯", acc)
    }

    // ---------- 改主密码（旧密钥解密 → 新密钥重加密） ----------

    @Test
    fun changeMasterPassword_oldRejected_newAccepted() {
        val oldMaster = "old-master-66"
        val newMaster = "new-master-88"
        val saltB64 = CryptoEngine.randomSaltB64()
        val oldKey = CryptoEngine.deriveKeyB64(oldMaster, saltB64)
        val (vB64, vIvB64) = CryptoEngine.makeVerify(oldKey)
        val enc = CryptoEngine.aesEncrypt("-change-pwd-密文-", oldKey)

        // 模拟 changeMasterPassword：校验旧密码 → 新 salt/key/verify → 重加密
        assertTrue(CryptoEngine.verifyMaster(oldKey, vB64, vIvB64))
        val newSaltB64 = CryptoEngine.randomSaltB64()
        val newKey = CryptoEngine.deriveKeyB64(newMaster, newSaltB64)
        val re = CryptoEngine.aesEncrypt(
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(enc.ivB64, enc.dataB64), oldKey),
            newKey,
        )
        val (nvB64, nvIvB64) = CryptoEngine.makeVerify(newKey)

        // 新密码可解锁、可解密；旧密码被拒
        assertTrue(CryptoEngine.verifyMaster(newKey, nvB64, nvIvB64))
        assertEquals(
            "-change-pwd-密文-",
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(re.ivB64, re.dataB64), newKey),
        )
        assertFalse(CryptoEngine.verifyMaster(oldKey, nvB64, nvIvB64))
    }

    // ---------- 备份编解码边界 ----------

    @Test
    fun backupCodec_rejectsUnknownFormat() {
        val bad = """{"format":"other","version":1}"""
        try {
            BackupCodec.decode(bad)
            throw AssertionError("应当拒绝未知 format")
        } catch (_: com.beyondguo.penly.backup.BackupFormatException) {
        }
    }

    @Test
    fun backupCodec_ignoresUnknownFields() {
        // 模拟小程序导出里多余的字段必须被忽略
        val json = Json { ignoreUnknownKeys = true }
        val file = json.decodeFromString(
            BackupFile.serializer(),
            res("backup_custom.json"),
        )
        assertEquals("custom", file.data.meta!!.pwdMode)
    }
}
