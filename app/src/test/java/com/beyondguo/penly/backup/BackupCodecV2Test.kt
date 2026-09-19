package com.beyondguo.penly.backup

import com.beyondguo.penly.backup.BackupCodecV2.ParsedBackup
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.CryptoV2
import com.beyondguo.penly.data.PlainEntry
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 契约 v2 备份文件编解码测试（§1-§3 语义 + v1/v2 分发）。
 * Argon2id 用契约层真实参数（32MiB/t=4）——跑得慢是特性（预检即真实强度）。
 */
class BackupCodecV2Test {

    private val pwd = "test-主密码-123"

    private fun entry(
        id: String,
        withTotp: Boolean = true,
        withPasskey: Boolean = false,
    ) = PlainEntry(
        id = id,
        title = "标题-$id",
        category = if (withTotp) "login" else "note",
        account = "user@$id.com",
        secret = "p@ss-$id-密码",
        note = "备注内容\n第二行",
        totp = if (withTotp) "JBSWY3DPEHPK3PXP" else "",
        totpDigits = if (withTotp) 8 else 0,
        totpPeriod = if (withTotp) 60 else 0,
        totpAlgo = if (withTotp) "SHA256" else "",
        appPackage = if (withTotp) "com.example.$id" else "",
        rpId = if (withPasskey) "example.com" else "",
        credIdB64 = if (withPasskey) Base64.getEncoder().encodeToString("cred-$id".toByteArray()) else "",
        userHandleB64 = if (withPasskey) Base64.getEncoder().encodeToString("uh-$id".toByteArray()) else "",
        signCount = if (withPasskey) 5 else 0,
        passkeyPriv = if (withPasskey) "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQ-$id" else "",
        createdAt = 1000L,
        updatedAt = 2000L,
    )

    /** 全测试共享一次导出（Argon2id 只跑一次），各测试基于它验证不同语义 */
    private val fixtureText: String by lazy {
        BackupCodecV2.encode(
            entries = listOf(
                entry("id-1"),
                entry("id-2", withTotp = false),
                entry("id-3", withTotp = false, withPasskey = true),
            ),
            masterRef = null,
            password = pwd,
            vaultCreatedAt = 42L,
        )
    }

    private fun flipB64(payloadB64: String): String {
        val raw = Base64.getDecoder().decode(payloadB64)
        raw[raw.size - 2] = (raw[raw.size - 2].toInt() xor 1).toByte()
        return Base64.getEncoder().encodeToString(raw)
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val entries = listOf(
            entry("id-1"),
            entry("id-2", withTotp = false),
            entry("id-3", withTotp = false, withPasskey = true),
        )
        assertNull(BackupCodecV2.verifyPassword(fixtureText, pwd))
        val out = BackupCodecV2.decryptItems(BackupCodecV2.decode(fixtureText), pwd)
        assertEquals(entries, out)
    }

    @Test
    fun `passkey fields encrypted and aad-bound`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(fixtureText).jsonObject
        val passkeyItem = root["items"]!!.jsonArray[2].jsonObject
        // 明文不落盘：私钥只在 passkeyEnc 里
        assertFalse(passkeyItem.toString().contains("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQ-id-3"))
        assertTrue(passkeyItem.containsKey("passkeyEnc"))
        assertTrue(passkeyItem.containsKey("rpId"))
        // v2 全格式无 Iv/Mac（含 passkey）
        listOf("passkeyIv", "passkeyMac").forEach { key ->
            assertFalse("v2 文件不应包含 $key 键", passkeyItem.containsKey(key))
        }
        // passkeyEnc 与其他字段同构：同条目跨字段搬移（secretEnc↔passkeyEnc）必须被 AAD 拦下
        val file = BackupCodecV2.decode(fixtureText)
        val a = file.items[2]
        val swapped = file.copy(items = listOf(
            a.copy(secretEnc = a.passkeyEnc, passkeyEnc = a.secretEnc),
        ))
        assertThrows(CryptoV2.IntegrityException::class.java) {
            BackupCodecV2.decryptItems(swapped, pwd)
        }
    }

    @Test
    fun `v2 structure has no iv and no mac keys`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(fixtureText).jsonObject
        assertEquals("2", root["version"]?.jsonPrimitive?.content)
        assertEquals("argon2id", root["crypto"]!!.jsonObject["kdf"]!!.jsonObject["algo"]?.jsonPrimitive?.content)
        val item = root["items"]!!.jsonArray[0].jsonObject
        listOf(
            "accountIv", "secretIv", "noteIv", "totpIv",
            "accountMac", "secretMac", "noteMac", "totpMac",
        ).forEach { key ->
            assertFalse("v2 文件不应包含 $key 键", item.containsKey(key))
        }
        assertTrue(item.containsKey("accountEnc"))
        // 契约参数必须显式落盘（encodeDefaults=true）
        assertTrue(root["crypto"]!!.jsonObject["kdf"]!!.jsonObject.containsKey("memoryKiB"))
        assertTrue(root["meta"]!!.jsonObject.containsKey("count"))
    }

    @Test
    fun `wrong password rejected`() {
        assertNotNull(BackupCodecV2.verifyPassword(fixtureText, "wrong-pwd"))
        assertThrows(CryptoV2.IntegrityException::class.java) {
            BackupCodecV2.decryptItems(BackupCodecV2.decode(fixtureText), "wrong-pwd")
        }
    }

    @Test
    fun `tamper rejected`() {
        val file = BackupCodecV2.decode(fixtureText)
        // 篡改条目密文：verify 槽位未动 → 预检可能通过，但字段解密必须失败
        val badItems = file.copy(
            items = file.items.mapIndexed { i, item ->
                if (i == 0) item.copy(accountEnc = flipB64(item.accountEnc)) else item
            },
        )
        assertThrows(CryptoV2.IntegrityException::class.java) {
            BackupCodecV2.decryptItems(badItems, pwd)
        }
        // 篡改 verify 槽位：预检必须失败
        val badVerify = file.copy(meta = file.meta.copy(verify = flipB64(file.meta.verify)))
        assertNotNull(BackupCodecV2.verifyPassword(BackupCodecV2.encodeFile(badVerify), pwd))
    }

    @Test
    fun `aad binds field and item id`() {
        val file = BackupCodecV2.decode(fixtureText)
        val a = file.items[0]   // 全字段非空
        val b = file.items[1]   // account/secret/note 非空，totp 空
        // 跨条目搬移（AAD 含条目 id）
        val crossItem = file.copy(items = listOf(
            b.copy(accountEnc = a.accountEnc),
            a.copy(accountEnc = b.accountEnc),
        ))
        assertThrows(CryptoV2.IntegrityException::class.java) {
            BackupCodecV2.decryptItems(crossItem, pwd)
        }
        // 同条目跨字段搬移（AAD 含字段名）
        val crossField = file.copy(items = listOf(
            a.copy(accountEnc = a.secretEnc, secretEnc = a.accountEnc),
            b,
        ))
        assertThrows(CryptoV2.IntegrityException::class.java) {
            BackupCodecV2.decryptItems(crossField, pwd)
        }
    }

    @Test
    fun `default masterRef ignores input password`() {
        val text = BackupCodecV2.encode(
            entries = listOf(entry("id-9")),
            masterRef = CryptoEngine.MASTER_REF_ANDROID,
            password = CryptoEngine.ANDROID_DEFAULT_MASTER,
        )
        assertNull(BackupCodecV2.verifyPassword(text, "whatever-user-typed"))
        val out = BackupCodecV2.decryptItems(BackupCodecV2.decode(text), "also-ignored")
        assertEquals("id-9", out[0].id)
    }

    @Test
    fun `empty backup still verifies password`() {
        val text = BackupCodecV2.encode(emptyList(), masterRef = null, password = pwd)
        assertNull(BackupCodecV2.verifyPassword(text, pwd))
        assertNotNull(BackupCodecV2.verifyPassword(text, "nope"))
        assertTrue(BackupCodecV2.decryptItems(BackupCodecV2.decode(text), pwd).isEmpty())
    }

    @Test
    fun `decodeAny dispatches v1 and v2`() {
        assertTrue(BackupCodecV2.decodeAny(fixtureText) is ParsedBackup.V2)

        val v1File = BackupFile(
            format = BackupCodec.FORMAT,
            version = 1,
            exportedAt = 123L,
            crypto = BackupCrypto(
                kdf = "pbkdf2", hash = "sha256", iterations = 100000,
                keyLen = 32, saltLen = 16, ivLen = 16,
                cipher = "aes-256-cbc", encoding = "utf8", masterRef = null,
            ),
            data = BackupData(meta = null, items = emptyList()),
        )
        assertTrue(BackupCodecV2.decodeAny(BackupCodec.encode(v1File)) is ParsedBackup.V1)
    }
}
