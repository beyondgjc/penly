package com.beyondguo.penly

import com.beyondguo.penly.backup.BackupCodec
import com.beyondguo.penly.backup.BackupCrypto
import com.beyondguo.penly.backup.BackupData
import com.beyondguo.penly.backup.BackupFile
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.VaultItem
import com.beyondguo.penly.data.VaultMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 【临时取证测试 · QA 跨端互认实证】—— 运行后由 QA 删除，不作为常规回归用例。
 *
 * 证据链：
 *  - tools/crosscheck_mp_custom.json / crosscheck_mp_default.json：由【小程序真实代码】
 *    （utils/crypto.js + services/export.js，Node 直接 require）生成，含 v1.1 的 MAC 字段。
 *  - 本测试用【Android 真实代码】（BackupCodec + CryptoEngine + VaultMeta/VaultItem）消费它们，
 *    并按 VaultRepository.exportJson 的输出形状产出 Android 侧备份，供 Node 侧反向验证。
 */
class CrossCheckEvidenceTest {

    private val tools = File("C:/Users/beyondguo/WorkBuddy/2026-08-22-22-32-31/tools")
    private fun read(name: String) = File(tools, name).readText()
    private fun write(name: String, text: String) = File(tools, name).writeText(text)

    private val manifest by lazy {
        Json.parseToJsonElement(read("crosscheck_manifest.json")).jsonObject
    }
    private fun mstr(k: String) = manifest[k]!!.jsonPrimitive.content
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val b64 = java.util.Base64.getEncoder()
    private fun enc(master: String, saltB64: String, plain: String): CryptoEngine.EncPayload =
        CryptoEngine.aesEncrypt(plain, CryptoEngine.deriveKeyB64(master, saltB64))

    /**
     * 逐字段复刻 VaultRepository.exportJson（VaultRepository.kt:762-793）的输出形状：
     * meta 的 openid 置空、schemaVersion 回落到 SCHEMA_V1、aux* 清空；只导出当前槽位 items。
     */
    private fun encodeAsExportJson(meta: VaultMeta, items: List<VaultItem>, masterRef: String?): String =
        BackupCodec.encode(
            BackupFile(
                format = BackupCodec.FORMAT,
                version = BackupCodec.VERSION,
                exportedAt = System.currentTimeMillis(),
                crypto = BackupCrypto(
                    kdf = "PBKDF2", hash = "SHA-256",
                    iterations = CryptoEngine.PBKDF2_ITERATIONS,
                    keyLen = CryptoEngine.KEY_LEN_BYTES,
                    saltLen = CryptoEngine.SALT_LEN_BYTES,
                    ivLen = CryptoEngine.IV_LEN_BYTES,
                    cipher = "AES-256-CBC", encoding = "base64",
                    masterRef = masterRef,
                ),
                data = BackupData(
                    meta = meta.copy(
                        openid = null,
                        schemaVersion = VaultMeta.SCHEMA_V1,
                        auxSaltB64 = "", auxSecretEnc = "", auxSecretIv = "",
                    ),
                    items = items,
                ),
            ),
        )

    // ================= 任务 #1：小程序 → Android =================

    /** 小程序真实导出（带 accountMac/secretMac/noteMac）必须能被 Android 解析 + 解密。 */
    @Test
    fun task1_miniprogramRealExportWithMac_decodesAndDecrypts() {
        val text = read("crosscheck_mp_custom.json")
        assertTrue("小程序导出的确含 MAC 字段", text.contains("accountMac") && text.contains("secretMac") && text.contains("noteMac"))

        val file = BackupCodec.decode(text)            // 真实 Android 解码（ignoreUnknownKeys）
        assertNull("custom 模式 masterRef 必须缺省", file.crypto.masterRef)
        assertEquals("custom", file.data.meta!!.pwdMode)

        val key = CryptoEngine.deriveKeyB64(mstr("customMaster"), file.data.meta!!.saltB64)
        assertTrue(
            "Android verifyMaster 通过小程序校验串",
            CryptoEngine.verifyMaster(key, file.data.meta!!.verifyB64, file.data.meta!!.verifyIvB64),
        )

        val items = file.data.items
        assertEquals(2, items.size)

        val it0 = items.first { it.title == "银行账号-实证" }
        fun dec(iv: String, e: String) = CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, e), key)
        assertEquals("user-john-01", dec(it0.accountIv, it0.accountEnc))
        assertEquals("P@ss中文✅-secret", dec(it0.secretIv, it0.secretEnc))
        assertEquals("备注：含 emoji 🎉 与中文", dec(it0.noteIv, it0.noteEnc))

        val it1 = items.first { it.title == "GitHub-实证" }
        assertEquals("dev@example.com", dec(it1.accountIv, it1.accountEnc))
        assertEquals("gh_pat_1234567890", dec(it1.secretIv, it1.secretEnc))
    }

    /** 小程序 default（wxb-def-v1）导出 → Android 按 importJson 逻辑解锁并转本机默认密钥。 */
    @Test
    fun task1_miniprogramDefaultExport_androidUnlocksAndReEncrypts() {
        val file = BackupCodec.decode(read("crosscheck_mp_default.json"))
        assertEquals("wxb-def-v1", file.crypto.masterRef)
        val meta = file.data.meta!!

        val sourceMaster = CryptoEngine.WXB_DEFAULT_PREFIX + meta.openid      // VaultRepository.kt:824
        val srcKey = CryptoEngine.deriveKeyB64(sourceMaster, meta.saltB64)
        assertTrue(CryptoEngine.verifyMaster(srcKey, meta.verifyB64, meta.verifyIvB64))

        val item = file.data.items.single()
        assertEquals(
            "user@163.com",
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(item.accountIv, item.accountEnc), srcKey),
        )

        // 复刻 VaultRepository.importJson 840-849：重加密为 Android 本地默认密钥
        val newSalt = CryptoEngine.randomSaltB64()
        val targetKey = CryptoEngine.deriveKeyB64(CryptoEngine.ANDROID_DEFAULT_MASTER, newSalt)
        val (nv, nvi) = CryptoEngine.makeVerify(targetKey)
        val re = CryptoEngine.aesEncrypt(
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(item.secretIv, item.secretEnc), srcKey),
            targetKey,
        )
        assertTrue(CryptoEngine.verifyMaster(targetKey, nv, nvi))
        assertEquals(
            "mail-pwd-🔧",
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(re.ivB64, re.dataB64), targetKey),
        )
        assertFalse("旧 openid 密钥不再可用", CryptoEngine.verifyMaster(srcKey, nv, nvi))
    }

    // ================= 任务 #2：Android → 小程序 =================

    /** 产出 Android custom 模式导出（exportJson 形状：masterRef 缺省、aux* 空、schemaVersion=1）。 */
    @Test
    fun task2_emitAndroidCustomExport() {
        val master = mstr("customMaster")
        val salt = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(master, salt)
        val (v, vi) = CryptoEngine.makeVerify(key)
        val now = System.currentTimeMillis()
        val a = enc(master, salt, "android-custom-账号🎯")
        val s = enc(master, salt, "android-custom-密码🔐")
        val n = enc(master, salt, "android-custom-备注")
        val meta = VaultMeta(
            saltB64 = salt, verifyB64 = v, verifyIvB64 = vi,
            pwdMode = VaultMeta.MODE_CUSTOM, initialized = true, createdAt = now, updatedAt = now,
        )
        val item = VaultItem(
            id = "a_cc_custom", title = "Android自定义-实证", category = "测试",
            accountEnc = a.dataB64, accountIv = a.ivB64,
            secretEnc = s.dataB64, secretIv = s.ivB64,
            noteEnc = n.dataB64, noteIv = n.ivB64,
            createdAt = now, updatedAt = now,
        )
        val json = encodeAsExportJson(meta, listOf(item), masterRef = null)
        assertFalse("custom 模式不得写出 masterRef（explicitNulls=false）", json.contains("masterRef"))
        assertFalse("Android 导出不含任何 MAC 字段", json.contains("Mac"))
        write("crosscheck_android_custom.json", json)
    }

    /** 产出 Android default 模式导出（masterRef=penly-def-v1）。 */
    @Test
    fun task2_emitAndroidDefaultExport() {
        val master = CryptoEngine.ANDROID_DEFAULT_MASTER
        val salt = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(master, salt)
        val (v, vi) = CryptoEngine.makeVerify(key)
        val now = System.currentTimeMillis()
        val a = enc(master, salt, "android-default-账号")
        val s = enc(master, salt, "android-default-密码🛡️")
        val n = enc(master, salt, "android-default-备注中文")
        val meta = VaultMeta(
            saltB64 = salt, verifyB64 = v, verifyIvB64 = vi,
            pwdMode = VaultMeta.MODE_DEFAULT, initialized = true, createdAt = now, updatedAt = now,
        )
        val item = VaultItem(
            id = "a_cc_default", title = "Android默认-实证", category = "测试",
            accountEnc = a.dataB64, accountIv = a.ivB64,
            secretEnc = s.dataB64, secretIv = s.ivB64,
            noteEnc = n.dataB64, noteIv = n.ivB64,
            createdAt = now, updatedAt = now,
        )
        val json = encodeAsExportJson(meta, listOf(item), masterRef = CryptoEngine.MASTER_REF_ANDROID)
        assertTrue(json.contains("\"masterRef\":\"penly-def-v1\""))
        write("crosscheck_android_default.json", json)
    }

    // ================= 任务 #3-1：MAC 往返（v1.1 对齐后：保留且可验） =================

    /** 小程序(带MAC) → Android 导入 → Android 再导出：MAC 保留，且 Android 重算验签逐条通过（P1 已修复）。 */
    @Test
    fun task3_1_macPreservedOnAndroidRoundTrip() {
        val inbound = read("crosscheck_mp_custom.json")
        val file = BackupCodec.decode(inbound)                       // 导入
        assertTrue(inbound.contains("accountMac"))
        // 复刻 exportJson（VaultRepository.kt）：custom 模式原样落地 → 再导出
        val outbound = encodeAsExportJson(
            file.data.meta!!,
            file.data.items,
            masterRef = null,
        )
        assertTrue("往返后 MAC 必须保留（P1 已修复，旧断言为 assertFalse）", outbound.contains("accountMac"))
        // 跨端字节级闸门：Android 用同一子密钥重算，与小程序写入的 Mac 逐条比对一致
        val key = CryptoEngine.deriveKeyB64(mstr("customMaster"), file.data.meta!!.saltB64)
        val mk = CryptoEngine.macSubKey(key)
        file.data.items.forEach { item ->
            assertTrue("accountMac 验签: ${item.id}", CryptoEngine.verifyRecordMac(mk, item.accountIv, item.accountEnc, item.accountMac))
            assertTrue("secretMac 验签: ${item.id}", CryptoEngine.verifyRecordMac(mk, item.secretIv, item.secretEnc, item.secretMac))
            assertTrue("noteMac 验签: ${item.id}", CryptoEngine.verifyRecordMac(mk, item.noteIv, item.noteEnc, item.noteMac))
        }
        // 密文原样保留，明文仍可解
        val it0 = file.data.items.first { it.title == "银行账号-实证" }
        assertEquals(
            "P@ss中文✅-secret",
            CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(it0.secretIv, it0.secretEnc), key),
        )
        write("crosscheck_android_roundtrip.json", outbound)
    }

    // ================= 换机闭环 =================
    // Android default → 小程序(importFromString→reencrypted) → 小程序再导出 → Android 再导入

    /** Android 备份经小程序「导入再导出」后带上了 MAC 字段，Android 仍须能完整消费。 */
    @Test
    fun closure_androidToMiniProgramRoundTripBackToAndroid() {
        val text = read("crosscheck_mp_reexport_after_android.json")
        assertTrue("小程序再导出含 masterRef=wxb-def-v1", text.contains("\"masterRef\"" ) && text.contains("wxb-def-v1"))
        assertTrue("含 MAC 字段", text.contains("secretMac"))

        val file = BackupCodec.decode(text)                       // Android 真实解码
        val meta = file.data.meta!!
        assertEquals("default", meta.pwdMode)
        val sourceMaster = CryptoEngine.WXB_DEFAULT_PREFIX + meta.openid   // VaultRepository.kt:824
        val srcKey = CryptoEngine.deriveKeyB64(sourceMaster, meta.saltB64)
        assertTrue(CryptoEngine.verifyMaster(srcKey, meta.verifyB64, meta.verifyIvB64))

        val item = file.data.items.single()
        fun dec(iv: String, e: String) = CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, e), srcKey)
        assertEquals("android-default-账号", dec(item.accountIv, item.accountEnc))
        assertEquals("android-default-密码🛡️", dec(item.secretIv, item.secretEnc))
        assertEquals("android-default-备注中文", dec(item.noteIv, item.noteEnc))
        println("[EVIDENCE] 换机闭环 3 跳后明文完整还原：${dec(item.secretIv, item.secretEnc)}")
    }

    // ================= 任务 #3-2：非 ASCII 主密码 PBKDF2 =================

    /** Java PBEKeySpec(char[]) 与 JS utf8Encode 对中文/emoji 主密码必须逐字节一致。 */
    @Test
    fun task3_2_pbkdf2NonAsciiMasterMatchesMiniProgram() {
        val mine = CryptoEngine.deriveKey(
            mstr("unicodeMaster"),
            mstr("unicodeSaltUtf8").toByteArray(Charsets.UTF_8),
        )
        val mpHex = mstr("unicodePbkdf2HexFromMiniProgram")
        println("[EVIDENCE] master=${mstr("unicodeMaster")}")
        println("[EVIDENCE] android hex = ${hex(mine)}")
        println("[EVIDENCE] miniprogram hex = $mpHex")
        assertEquals("PBKDF2 非 ASCII 主密码跨端不一致", mpHex, hex(mine))
    }
}
