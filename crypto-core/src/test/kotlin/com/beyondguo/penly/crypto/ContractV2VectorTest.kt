package com.beyondguo.penly.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 契约 v2 固定向量与行为测试（《印迹_跨端契约v2_地基工程.md》§6）。
 * 小程序端对等实现以同一组向量互验（key32/子密钥逐字节一致 + 往返一致 + 篡改拒收）。
 */
class ContractV2VectorTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** RFC 5869 A.1 Test Case 1（SHA-256）——HKDF 实现的权威向量 */
    @Test
    fun `hkdf rfc5869 testcase1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Aead.hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            hex(okm),
        )
    }

    /** 域分离：不同标签派生不同子密钥；同标签确定性 */
    @Test
    fun `subkey domain separation`() {
        val key32 = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
        val a = Aead.subKey(key32, Aead.Domains.ENC)
        val a2 = Aead.subKey(key32, Aead.Domains.ENC)
        val b = Aead.subKey(key32, "yinji-enc-v3-future")
        assertEquals(32, a.size)
        assertArrayEquals(a, a2)
        assertFalse(a.contentEquals(b))
    }

    /** RFC 9106 §5.3 Argon2id 官方向量（t=3, m=32KiB, p=4, tagLen=32）——实现正确性的权威校验 */
    @Test
    fun `argon2id rfc9106 testvector`() {
        val pwd = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val ad = ByteArray(12) { 0x04 }
        val tag = Aead.argon2id(
            password = pwd, salt = salt,
            memoryKiB = 32, iterations = 3, parallelism = 4,
            outLen = 32, secret = secret, associatedData = ad,
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            hex(tag),
        )
    }

    /** 本机层参数（m=64MiB/t=3/p=1）确定性：同输入同输出，盐变输出变 */
    @Test
    fun `argon2id determinism and salt sensitivity`() {
        val pwd = "master-password".toByteArray()
        val salt1 = ByteArray(16) { 0x11 }
        val salt2 = ByteArray(16) { 0x22 }
        val k1 = Aead.argon2id(pwd, salt1, memoryKiB = 8192, iterations = 1, parallelism = 1)
        val k1b = Aead.argon2id(pwd, salt1, memoryKiB = 8192, iterations = 1, parallelism = 1)
        val k2 = Aead.argon2id(pwd, salt2, memoryKiB = 8192, iterations = 1, parallelism = 1)
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k1b)
        assertFalse(k1.contentEquals(k2))
    }

    /** GCM 往返 + 载荷结构（12B nonce + 密文 + 16B tag） */
    @Test
    fun `gcm roundtrip and payload layout`() {
        val key = Aead.subKey(ByteArray(32) { 7 }, Aead.Domains.ENC)
        val aad = "account|id-123".toByteArray()
        val plain = "用户名@bank".toByteArray(Charsets.UTF_8)
        val payload = Aead.gcmEncrypt(key, plain, aad)
        assertEquals(Aead.NONCE_BYTES, 12)
        assertEquals(plain.size + 12 + 16, payload.size)
        assertArrayEquals(plain, Aead.gcmDecrypt(key, payload, aad))
    }

    /** 篡改拒收：密文翻 1 字节 / AAD 不符 → IntegrityException */
    @Test
    fun `gcm tamper rejection`() {
        val key = Aead.subKey(ByteArray(32) { 9 }, Aead.Domains.ENC)
        val aad = "secret|id-1".toByteArray()
        val payload = Aead.gcmEncrypt(key, "secret".toByteArray(), aad)

        val flipped = payload.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertThrows(Aead.IntegrityException::class.java) { Aead.gcmDecrypt(key, flipped, aad) }

        val wrongAad = "secret|id-2".toByteArray()
        assertThrows(Aead.IntegrityException::class.java) { Aead.gcmDecrypt(key, payload, wrongAad) }

        // AAD 语义 = 防跨字段/跨条目搬移：同 key 不同 aad 的密文不可互换
        val p2 = Aead.gcmEncrypt(key, "other".toByteArray(), "account|id-2".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            Aead.gcmDecrypt(key, p2, "account|id-1".toByteArray())
        }
    }
}
