package com.beyondguo.penly.data

import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.Aead
import com.beyondguo.penly.crypto.MacVerificationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ItemCipher 单测（v5.0 #29 本机存储格式迁移地基）：
 * - V3（GCM 逐字段）：往返、结构（Iv/Mac 槽位空）、AAD 防搬移（跨条目/跨字段）、防篡改；
 * - V2（CBC+MAC 四元组）：往返；
 * - reEncryptItems：V2→V3 格式迁移、换钥语义、坏数据 fail-closed 中止；
 * - 批量 encryptEntries/decryptItems：全字段透传（totp 参数/appPackage/时间戳）。
 *
 * key 用 SecureRandom 直出 32B（不跑 PBKDF2/Argon2id，保持毫秒级）。
 */
class ItemCipherTest {

    private fun key(): ByteArray = CryptoEngine.randomBytes(32)

    /** GCM 密文中间字节翻转（一定变化：xor 0x01），构造篡改样本 */
    private fun tamperB64(dataB64: String): String {
        val raw = Aead.unb64(dataB64)
        val i = raw.size / 2
        raw[i] = (raw[i].toInt() xor 0x01).toByte()
        return Aead.b64(raw)
    }

    // ---------------- V3：字段级往返与结构 ----------------

    @Test
    fun `v3 field roundtrip preserves plaintext including chinese`() {
        val k = key()
        for (plain in listOf("P@ssw0rd!", "腾讯云账号", "多行备注\n第二行", "x")) {
            val f = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", plain, k)
            assertEquals(plain, ItemCipher.decField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", f.dataB64, f.ivB64, f.macB64, k))
        }
    }

    @Test
    fun `v3 empty field skips encryption and iv-mac slots stay empty`() {
        val k = key()
        val empty = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_NOTE, "item-1", "", k)
        assertEquals(ItemCipher.Field("", "", ""), empty) // 空字段不加密，Iv/Mac 槽位空
        assertEquals("", ItemCipher.decField(VaultMeta.SCHEMA_V3, ItemCipher.F_NOTE, "item-1", "", "", "", k))

        val full = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", "s3cret", k)
        assertEquals("", full.ivB64) // GCM 载荷自带 nonce → 无 Iv
        assertEquals("", full.macB64) // 完整性由 GCM tag 接管 → 无 Mac
        assertTrue(full.dataB64.isNotEmpty())
        // 同 key 同明文两次加密密文不同（GCM 随机 nonce，非确定性）
        val again = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", "s3cret", k)
        assertNotEquals(full.dataB64, again.dataB64)
    }

    // ---------------- V3：AAD 防搬移 / 防篡改 ----------------

    @Test
    fun `v3 ciphertext cannot be moved across items`() {
        val k = key()
        val f1 = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", "same-plain", k)
        val f2 = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-2", "same-plain", k)
        assertNotEquals(f1.dataB64, f2.dataB64)
        try {
            // 把 item-2 的密文拿到 item-1 的槽位解（同 key 同字段，仅 id 不同）
            ItemCipher.decField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", f2.dataB64, "", "", k)
            assertFalse("跨条目搬移密文必须被 AAD 拒收", true)
        } catch (_: MacVerificationException) {
        }
    }

    @Test
    fun `v3 ciphertext cannot be moved across fields`() {
        val k = key()
        val fa = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_ACCOUNT, "item-1", "user@example.com", k)
        val fs = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", "hunter2", k)
        try {
            // account 槽位放 secret 的密文（同 key 同条目，仅字段名不同）
            ItemCipher.decField(VaultMeta.SCHEMA_V3, ItemCipher.F_ACCOUNT, "item-1", fs.dataB64, "", "", k)
            assertFalse("跨字段搬移密文必须被 AAD 拒收", true)
        } catch (_: MacVerificationException) {
        }
        try {
            ItemCipher.decField(VaultMeta.SCHEMA_V3, ItemCipher.F_SECRET, "item-1", fa.dataB64, "", "", k)
            assertFalse("反向搬移同样必须被拒收", true)
        } catch (_: MacVerificationException) {
        }
    }

    @Test
    fun `v3 tampered ciphertext is rejected`() {
        val k = key()
        val f = ItemCipher.encField(VaultMeta.SCHEMA_V3, ItemCipher.F_NOTE, "item-1", "note-body", k)
        try {
            ItemCipher.decField(VaultMeta.SCHEMA_V3, ItemCipher.F_NOTE, "item-1", tamperB64(f.dataB64), "", "", k)
            assertFalse("篡改密文必须拒收", true)
        } catch (_: MacVerificationException) {
        }
    }

    // ---------------- V2（CBC+MAC）：字段级往返 ----------------

    @Test
    fun `v2 field roundtrip with iv and mac populated`() {
        val k = key()
        val f = ItemCipher.encField(VaultMeta.SCHEMA_V2, ItemCipher.F_ACCOUNT, "item-1", "legacy-account", k)
        assertTrue(f.dataB64.isNotEmpty() && f.ivB64.isNotEmpty() && f.macB64.isNotEmpty()) // CBC 三元组齐全
        assertEquals("legacy-account", ItemCipher.decField(VaultMeta.SCHEMA_V2, ItemCipher.F_ACCOUNT, "item-1", f.dataB64, f.ivB64, f.macB64, k))
        // 空（无内容）字段在 V2 下同样短路
        assertEquals(ItemCipher.Field("", "", ""), ItemCipher.encField(VaultMeta.SCHEMA_V2, ItemCipher.F_NOTE, "item-1", "", k))
    }

    // ---------------- reEncryptItems：迁移 / 换钥 / fail-closed ----------------

    private fun sampleEntries(): List<PlainEntry> = listOf(
        PlainEntry(
            id = "e-1", title = "腾讯云", category = "开发",
            account = "guo@example.com", secret = "P@ss中文123!#$",
            note = "备注：含\n换行", totp = "JBSWY3DPEHPK3PXP",
            totpDigits = 8, totpPeriod = 60, totpAlgo = "SHA256",
            appPackage = "com.tencent.qq", createdAt = 1720000000000L, updatedAt = 1720000009999L,
        ),
        PlainEntry(
            id = "e-2", title = "只有标题", category = "生活",
            account = "", secret = "", note = "", totp = "",
            appPackage = "", createdAt = 1720000010000L, updatedAt = 1720000010000L,
        ),
    )

    @Test
    fun `reEncrypt migrates v2 to v3 under a new key`() {
        val oldKey = key()
        val newKey = key()
        val entries = sampleEntries()
        val v2Items = ItemCipher.encryptEntries(entries, oldKey, VaultMeta.SCHEMA_V2)
        val v3Items = ItemCipher.reEncryptItems(v2Items, oldKey, newKey, VaultMeta.SCHEMA_V2, VaultMeta.SCHEMA_V3)
        assertEquals(entries.size, v3Items.size)

        // 新格式结构：Iv/Mac 槽位全空；非敏感字段原样保留
        val first = v3Items[0]
        assertEquals("", first.accountIv)
        assertEquals("", first.accountMac)
        assertEquals("", first.totpMac)
        assertEquals("腾讯云", first.title)
        assertEquals(8, first.totpDigits)
        assertEquals("SHA256", first.totpAlgo)

        // 用新 key 按 V3 解密 → 明文逐字段等值（含空条目）
        val plain = ItemCipher.decryptItems(v3Items, newKey, VaultMeta.SCHEMA_V3)
        assertEquals(entries, plain)
        // 旧 key 已不再能解开 V3 密文（换钥语义）
        try {
            ItemCipher.decryptItems(v3Items, oldKey, VaultMeta.SCHEMA_V3)
            assertFalse("迁移后旧 key 解密应失败", true)
        } catch (_: MacVerificationException) {
        }
    }

    @Test
    fun `reEncrypt with same format equals key rotation`() {
        val oldKey = key()
        val newKey = key()
        val entries = sampleEntries()
        val items = ItemCipher.encryptEntries(entries, oldKey, VaultMeta.SCHEMA_V3)
        val rotated = ItemCipher.reEncryptItems(items, oldKey, newKey, VaultMeta.SCHEMA_V3, VaultMeta.SCHEMA_V3)
        assertEquals(entries, ItemCipher.decryptItems(rotated, newKey, VaultMeta.SCHEMA_V3))
    }

    @Test
    fun `reEncrypt aborts entirely on any tampered v2 record`() {
        val k = key()
        val entries = sampleEntries()
        val v2Items = ItemCipher.encryptEntries(entries, k, VaultMeta.SCHEMA_V2)
        // 篡改第一条（e-1）的 noteMac —— e-1 note 非空 → MAC 必然参与校验；
        // （e-2 全空字段，MAC 为空串，crypto.js 对空密文短路宽容，篡改空 MAC 不构成坏样本）
        val broken = v2Items.mapIndexed { i, it ->
            if (i == 0) it.copy(noteMac = it.noteMac.dropLast(1) + "A") else it
        }
        try {
            ItemCipher.reEncryptItems(broken, k, k, VaultMeta.SCHEMA_V2, VaultMeta.SCHEMA_V3)
            fail("任一记录完整性失败必须整体中止，绝不静默产出坏迁移")
        } catch (_: MacVerificationException) {
        }
    }

    // ---------------- 批量往返：全字段透传 ----------------

    @Test
    fun `encrypt-decrypt batch roundtrip keeps every field`() {
        val k = key()
        val entries = sampleEntries()
        val items = ItemCipher.encryptEntries(entries, k, VaultMeta.SCHEMA_V3)
        assertEquals(entries.map { it.id }, items.map { it.id })
        // appPackage 是明文字段，不参与加密但必须透传
        assertEquals("com.tencent.qq", items[0].appPackage)
        assertEquals(entries, ItemCipher.decryptItems(items, k, VaultMeta.SCHEMA_V3))
    }
}
