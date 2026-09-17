package com.beyondguo.penly.crypto

import com.beyondguo.penly.data.VaultItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录完整性 MAC（encrypt-then-MAC v1.1 跨端契约扩展）测试。
 *
 * 固定向量由**小程序真实代码**（miniprogram-mvp/utils/vendor/crypto-lite.js，
 * 经工作区 tools_mac_vector.cjs 生成）输出，Android 必须逐字节复现——
 * 这是「小程序带 Mac 备份 → Android 导入验签」的跨端闸门。
 */
class RecordMacTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // node tools_mac_vector.cjs 生成（小程序 crypto-lite 真实输出，含 crypto.js:228 反序实参语义）
    private val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val subKeyHex = "6669e72ea30280c543a2fe085f7a79e38cdb700383d5636098f9275e26c8560e"

    @Test
    fun `macSubKey matches miniprogram crypto-lite`() {
        val sub = CryptoEngine.macSubKey(hex(keyHex))
        assertEquals(subKeyHex, sub.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `recordMac matches miniprogram vectors`() {
        val mk = CryptoEngine.macSubKey(hex(keyHex))
        // 向量 1
        assertEquals(
            "ai7KKuKbDEiwE8ckOt/dbQSw95jmGUTv975UzD68bic=",
            CryptoEngine.recordMac(mk, "AAAAAAAAAAAAAAAAAAAAAA==", "qZ9xKm2vV3wX7yZ0aB4cD6eF8gH0iJ2kL4mN6oP8qR0="),
        )
        // 向量 2
        assertEquals(
            "yjw5CzbpBFQzFUGZ/FBe2NTqui9XMnkF6Al3RqiMx+I=",
            CryptoEngine.recordMac(mk, "Zm9vYmFyMTIzNDU2Nzg5MA==", "WdB2sK3wW4xY8zA1bC5dE7fG9hI1jK3lM5nO7pQ9sR1="),
        )
    }

    @Test
    fun `verifyRecordMac detects tampering`() {
        val mk = CryptoEngine.macSubKey(hex(keyHex))
        val iv = "AAAAAAAAAAAAAAAAAAAAAA=="
        val ct = "qZ9xKm2vV3wX7yZ0aB4cD6eF8gH0iJ2kL4mN6oP8qR0="
        val mac = CryptoEngine.recordMac(mk, iv, ct)
        assertTrue(CryptoEngine.verifyRecordMac(mk, iv, ct, mac))
        // 密文首字符翻转（AES-CBC 可塑性的最小实证：无 Mac 时这不报任何错）
        assertFalse(CryptoEngine.verifyRecordMac(mk, iv, "p" + ct.substring(1), mac))
        // IV 翻转一位
        assertFalse(CryptoEngine.verifyRecordMac(mk, "BAAAAAAAAAAAAAAAAAAAAA==", ct, mac))
        // Mac 串不符
        assertFalse(CryptoEngine.verifyRecordMac(mk, iv, ct, mac.dropLast(1) + "A"))
    }

    @Test
    fun `verifyRecordMac tolerates legacy and empty records`() {
        val mk = CryptoEngine.macSubKey(hex(keyHex))
        // 旧数据：密文在、Mac 缺 → 宽容跳过（对齐 crypto.js:250 `if (mac && …)`）
        assertTrue(CryptoEngine.verifyRecordMac(mk, "iv==", "ct==", ""))
        // 空字段：三空（crypto.js:315 `if (!ct)` 短路）
        assertTrue(CryptoEngine.verifyRecordMac(mk, "", "", ""))
    }

    /**
     * VaultItem 序列化契约：带 Mac 的条目 JSON 往返保真；
     * encodeDefaults 关闭 → 空 Mac 不出现在 JSON（旧格式字节不变，存量行不迁移）。
     */
    @Test
    fun `vaultItem json macs roundtrip and defaults omitted`() {
        val json = Json { ignoreUnknownKeys = true } // 与 VaultStore/BackupCodec 同配置
        val item = VaultItem(
            id = "a_1", title = "腾讯",
            accountEnc = "aE=", accountIv = "aI=", accountMac = "aM=",
            secretEnc = "sE=", secretIv = "sI=", secretMac = "sM=",
            totpEnc = "tE=", totpIv = "tI=", totpMac = "tM=",
        )
        val encoded = json.encodeToString(VaultItem.serializer(), item)
        assertTrue(encoded.contains("\"accountMac\":\"aM=\""))
        assertTrue(encoded.contains("\"totpMac\":\"tM=\""))
        val decoded = json.decodeFromString(VaultItem.serializer(), encoded)
        assertEquals("aM=", decoded.accountMac)
        assertEquals("sM=", decoded.secretMac)
        assertEquals("", decoded.noteMac) // 未设置的字段回落默认空串
        assertEquals("tM=", decoded.totpMac)

        // 旧格式：无 Mac 字段 → 序列化输出不含任何 Mac 键（存量数据不迁移）
        val legacy = json.encodeToString(VaultItem.serializer(), VaultItem(id = "a_2"))
        assertFalse(legacy.contains("Mac"))
        // 小程序 v1.1 备份（含 Mac）能被同配置解码
        val fromMp = json.decodeFromString(
            VaultItem.serializer(),
            """{"_id":"mp_1","accountEnc":"aE=","accountIv":"aI=","accountMac":"zz="}""",
        )
        assertEquals("zz=", fromMp.accountMac)
    }
}
