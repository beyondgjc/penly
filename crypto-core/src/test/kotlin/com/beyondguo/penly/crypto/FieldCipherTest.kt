package com.beyondguo.penly.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段级加解密（#8）测试。
 *
 * 覆盖：往返、AAD 隔离（防跨字段/跨记录搬移）、算法标识 fail-closed、
 * 异常归一（GCM → MacVerificationException）、以及 #16 的批量重加密语义。
 */
class FieldCipherTest {

    private val key = ByteArray(32) { it.toByte() }

    // ---------------- 往返 ----------------

    @Test
    fun `seal open roundtrip restores plaintext`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val plain = "其实有点孤独".toByteArray(Charsets.UTF_8)
        val ct = FieldCipher.seal(key, aad, plain)
        assertArrayEquals(plain, FieldCipher.open(key, aad, ct))
    }

    @Test
    fun `empty plaintext roundtrips`() {
        val aad = FieldCipher.aad("feeling", "rec-empty")
        val ct = FieldCipher.seal(key, aad, ByteArray(0))
        assertArrayEquals(ByteArray(0), FieldCipher.open(key, aad, ct))
    }

    /** 同一明文两次加密 → 密文不同（nonce 随机），防"两条记录内容相同"泄露 */
    @Test
    fun `nonce is random per seal`() {
        val aad = FieldCipher.aad("feeling", "rec-2")
        val plain = "今天很好".toByteArray()
        val c1 = FieldCipher.seal(key, aad, plain)
        val c2 = FieldCipher.seal(key, aad, plain)
        assertFalse(c1 == c2)
        assertArrayEquals(plain, FieldCipher.open(key, aad, c1))
        assertArrayEquals(plain, FieldCipher.open(key, aad, c2))
    }

    // ---------------- AAD 隔离：密文钉死在原位 ----------------

    /** 换字段名 → 解不开（防"把 secret 的密文搬到 account 位置"） */
    @Test
    fun `ciphertext cannot be moved to another field`() {
        val ct = FieldCipher.seal(key, FieldCipher.aad("secret", "rec-1"), "s3cr3t".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.open(key, FieldCipher.aad("account", "rec-1"), ct)
        }
    }

    /** 换记录 id → 解不开（防"把记录 A 的密文搬到记录 B"） */
    @Test
    fun `ciphertext cannot be moved to another record`() {
        val ct = FieldCipher.seal(key, FieldCipher.aad("feeling", "rec-A"), "A 的感受".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.open(key, FieldCipher.aad("feeling", "rec-B"), ct)
        }
    }

    /** AAD 为空 vs 有值 → 互不相通（空 AAD 不是"通配"） */
    @Test
    fun `empty aad is distinct from named aad`() {
        val ct = FieldCipher.seal(key, ByteArray(0), "x".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.open(key, FieldCipher.aad("feeling", "rec-1"), ct)
        }
    }

    /** 错密钥 → 解不开（GCM 认证失败，不是静默乱码） */
    @Test
    fun `wrong key fails authentication`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val ct = FieldCipher.seal(key, aad, "秘密".toByteArray())
        val wrong = ByteArray(32) { (it + 1).toByte() }
        assertThrows(Aead.IntegrityException::class.java) { FieldCipher.open(wrong, aad, ct) }
    }

    /** 载荷被篡改 → 认证失败 */
    @Test
    fun `tampered payload is rejected`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val ct = FieldCipher.seal(key, aad, "原始内容".toByteArray())
        val raw = Aead.unb64(ct)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x01).toByte()
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.open(key, aad, Aead.b64(raw))
        }
    }

    // ---------------- aad 惯例 ----------------

    /** AAD 格式 = "<字段名>|<记录id>"，与契约 v2 备份文件同构（不可随意改） */
    @Test
    fun `aad format matches contract convention`() {
        assertEquals("secret|abc123", String(FieldCipher.aad("secret", "abc123"), Charsets.UTF_8))
    }

    // ---------------- 算法标识 fail-closed ----------------

    /** 未知 algId → 抛 UnsupportedFormatException，不猜不降级 */
    @Test
    fun `unknown algId is rejected`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val ct = FieldCipher.seal(key, aad, "x".toByteArray())
        assertThrows(UnsupportedFormatException::class.java) {
            FieldCipher.open(key, aad, ct, algId = "chacha20-poly1305")
        }
    }

    /** 显式传入本版本算法标识 → 正常解开（algId 不只是"摆设"） */
    @Test
    fun `explicit current algId works`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val ct = FieldCipher.seal(key, aad, "x".toByteArray())
        assertArrayEquals(
            "x".toByteArray(),
            FieldCipher.open(key, aad, ct, algId = FieldCipher.ALG_AES_256_GCM),
        )
    }

    // ---------------- 异常归一 ----------------

    /** openOrThrowMac：GCM 认证失败归一成 MacVerificationException（兼容既有调用方契约） */
    @Test
    fun `openOrThrowMac normalizes integrity failure`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val ct = FieldCipher.seal(key, aad, "x".toByteArray())
        assertThrows(MacVerificationException::class.java) {
            FieldCipher.openOrThrowMac(ByteArray(32), aad, ct)
        }
    }

    /** openOrThrowMac 正常路径不改变结果 */
    @Test
    fun `openOrThrowMac returns plaintext on success`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val plain = "正常内容".toByteArray()
        val ct = FieldCipher.seal(key, aad, plain)
        assertArrayEquals(plain, FieldCipher.openOrThrowMac(key, aad, ct))
    }

    /** openOrThrowMac 仍传播 UnsupportedFormatException（不能被归一吞掉） */
    @Test
    fun `openOrThrowMac does not swallow unsupported format`() {
        val aad = FieldCipher.aad("feeling", "rec-1")
        val ct = FieldCipher.seal(key, aad, "x".toByteArray())
        assertThrows(UnsupportedFormatException::class.java) {
            FieldCipher.openOrThrowMac(key, aad, ct, algId = "rsa-oaep")
        }
    }

    // ---------------- #16 批量重加密 ----------------

    private fun sealFn(k: ByteArray, aad: ByteArray, p: ByteArray) = FieldCipher.seal(k, aad, p)

    private fun openFn(k: ByteArray, aad: ByteArray, payload: String) = FieldCipher.open(k, aad, payload)

    /** 换钥重加密：新钥解得开、旧钥解不开 */
    @Test
    fun `reEncryptRecord with new key rotates secrets`() {
        val newKey = ByteArray(32) { (it + 7).toByte() }
        val fields = mapOf(
            "feeling" to FieldCipher.seal(key, FieldCipher.aad("feeling", "r1"), "第一条感受".toByteArray()),
        )
        val out = FieldCipher.reEncryptRecord(key, newKey, "r1", fields, ::openFn, ::sealFn)
        assertArrayEquals(
            "第一条感受".toByteArray(),
            FieldCipher.open(newKey, FieldCipher.aad("feeling", "r1"), out.getValue("feeling")),
        )
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.open(key, FieldCipher.aad("feeling", "r1"), out.getValue("feeling"))
        }
    }

    /** 同钥调用 = 纯换 nonce：内容不变，但密文变了 */
    @Test
    fun `reEncryptRecord with same key re-randomizes payload`() {
        val original = FieldCipher.seal(key, FieldCipher.aad("feeling", "r1"), "同样内容".toByteArray())
        val out = FieldCipher.reEncryptRecord(
            key, key, "r1", mapOf("feeling" to original), ::openFn, ::sealFn,
        )
        assertFalse(original == out.getValue("feeling"))
        assertArrayEquals(
            "同样内容".toByteArray(),
            FieldCipher.open(key, FieldCipher.aad("feeling", "r1"), out.getValue("feeling")),
        )
    }

    /** 空字段原样传回，不生成密文（与 seal 调用方约定一致） */
    @Test
    fun `reEncryptRecord passes empty fields through`() {
        val out = FieldCipher.reEncryptRecord(
            key, key, "r1", mapOf("feeling" to "", "witness" to ""), ::openFn, ::sealFn,
        )
        assertEquals("", out.getValue("feeling"))
        assertEquals("", out.getValue("witness"))
    }

    /** 多字段 + 键序保持 */
    @Test
    fun `reEncryptRecord handles multiple fields`() {
        val fields = linkedMapOf(
            "feeling" to FieldCipher.seal(key, FieldCipher.aad("feeling", "r9"), "感受".toByteArray()),
            "witness" to FieldCipher.seal(key, FieldCipher.aad("witness", "r9"), "所见".toByteArray()),
        )
        val out = FieldCipher.reEncryptRecord(key, key, "r9", fields, ::openFn, ::sealFn)
        assertEquals(listOf("feeling", "witness"), out.keys.toList())
        assertArrayEquals("感受".toByteArray(), FieldCipher.open(key, FieldCipher.aad("feeling", "r9"), out.getValue("feeling")))
        assertArrayEquals("所见".toByteArray(), FieldCipher.open(key, FieldCipher.aad("witness", "r9"), out.getValue("witness")))
    }

    /** 坏数据 fail-closed：任一字段解不开即抛，**不静默跳过** */
    @Test
    fun `reEncryptRecord is fail closed on corrupted field`() {
        val broken = Aead.b64(ByteArray(40)) // 全零载荷：认证必失败
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.reEncryptRecord(
                key, key, "r1", mapOf("feeling" to broken), ::openFn, ::sealFn,
            )
        }
    }

    /** 重加密后 AAD 仍被钉住：搬到别的记录依旧解不开 */
    @Test
    fun `reEncryptRecord keeps aad binding intact`() {
        val original = FieldCipher.seal(key, FieldCipher.aad("feeling", "r1"), "感受".toByteArray())
        val out = FieldCipher.reEncryptRecord(
            key, key, "r1", mapOf("feeling" to original), ::openFn, ::sealFn,
        )
        assertThrows(Aead.IntegrityException::class.java) {
            FieldCipher.open(key, FieldCipher.aad("feeling", "r2"), out.getValue("feeling"))
        }
    }

    /** 空映射 → 空结果（不崩） */
    @Test
    fun `reEncryptRecord tolerates empty map`() {
        assertTrue(FieldCipher.reEncryptRecord(key, key, "r1", emptyMap(), ::openFn, ::sealFn).isEmpty())
    }
}
