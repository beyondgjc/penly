package com.beyondguo.penly.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * DoubleEnvelope 逻辑测试（JVM）——用软件 KeyWrapper 替身跑信封语义。
 * AndroidKeyStoreWrapper 的 TEE 集成（真机）由 instrumented test 覆盖（#31）。
 */
class KeystoreEnvelopeTest {

    /** 测试替身：软件 GCM（Aead），语义与 TEE 版一致（nonce||ct||tag + AAD） */
    private class SoftwareWrapper : KeyWrapper {
        override val tag: String = "software"
        private val key = ByteArray(32) { 0x5a }

        override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray =
            Aead.gcmEncrypt(key, plaintext, aad)

        override fun unwrap(payload: ByteArray, aad: ByteArray): ByteArray =
            Aead.gcmDecrypt(key, payload, aad)
    }

    private val key32 = ByteArray(32) { 0x2a }
    private val password = "correct-horse-battery".toByteArray()

    @Test
    fun `seal unseal roundtrip preserves key32`() {
        val envelope = DoubleEnvelope.seal(SoftwareWrapper(), password, key32)
        assertEquals("software", envelope.wrapperTag)
        assertFalse(envelope.saltLocalB64.isEmpty())
        val recovered = DoubleEnvelope.unseal(SoftwareWrapper(), password, envelope)
        assertArrayEquals(key32, recovered)
    }

    @Test
    fun `wrong password rejected as wrongPasswordException`() {
        val envelope = DoubleEnvelope.seal(SoftwareWrapper(), password, key32)
        val wrong = "wrong-password".toByteArray()
        assertThrows(WrongPasswordException::class.java) {
            DoubleEnvelope.unseal(SoftwareWrapper(), wrong, envelope)
        }
    }

    @Test
    fun `envelope tamper rejected as wrongPasswordException`() {
        // payload 篡改 = outer 层（密码因素）GCM tag 失败，与密码错误同路出——不向 UI 泄露「被篡改」细节
        val envelope = DoubleEnvelope.seal(SoftwareWrapper(), password, key32)
        val payload = Aead.unb64(envelope.payloadB64).copyOf()
        payload[payload.size - 2] = (payload[payload.size - 2].toInt() xor 1).toByte()
        val tampered = envelope.copy(payloadB64 = Aead.b64(payload))
        assertThrows(WrongPasswordException::class.java) {
            DoubleEnvelope.unseal(SoftwareWrapper(), password, tampered)
        }
    }

    @Test
    fun `tee failure surfaces as key unavailable`() {
        // 设备因素层失效（换机/恢复出厂/密钥被删）：密码正确、outer 可解，inner unwrap 必败
        val brokenWrapper = object : KeyWrapper {
            override val tag: String = "broken"
            override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray =
                Aead.gcmEncrypt(ByteArray(32) { 0x5a }, plaintext, aad)
            override fun unwrap(payload: ByteArray, aad: ByteArray): ByteArray =
                throw IllegalStateException("KeyPermanentlyInvalidatedException (simulated)")
        }
        val envelope = DoubleEnvelope.seal(brokenWrapper, password, key32)
        assertThrows(KeyUnavailableException::class.java) {
            DoubleEnvelope.unseal(brokenWrapper, password, envelope)
        }
    }

    @Test
    fun `reseal generates new salt same key32`() {
        // 重新分片/重启用：新信封 salt 必然不同，但解出的 key32 恒等
        val e1 = DoubleEnvelope.seal(SoftwareWrapper(), password, key32)
        val e2 = DoubleEnvelope.seal(SoftwareWrapper(), password, key32)
        assertFalse(e1.saltLocalB64 == e2.saltLocalB64)
        assertArrayEquals(key32, DoubleEnvelope.unseal(SoftwareWrapper(), password, e1))
        assertArrayEquals(key32, DoubleEnvelope.unseal(SoftwareWrapper(), password, e2))
    }

    /**
     * KEK 强度闸门：本机层用 [Profile.SENSITIVE]。
     *
     * 这条在 2026-09-21（#18 落地）后有了**更重要的含义**：信封的参数从
     * 三个硬编码常量改成了走档位，所以这个测试同时守住了"档位定义 ==
     * 存量信封的加密参数"这一等价关系——数值漂移 = 所有用户解不开库。
     * （JVM 上 64MiB 档约 1-2 秒）
     */
    @Test
    fun `kek derivation uses argon2id 64mib tier`() {
        val salt = ByteArray(16) { 0x33 }
        val kek = Aead.argon2id(
            password, salt,
            memoryKiB = 64 * 1024, iterations = 3, parallelism = 1,
        )
        assertEquals(32, kek.size)

        // 档位派生的结果必须与上面硬编码参数的派生**逐字节相同**
        // —— 这就是"改走 profile 但行为零变化"的实证
        assertArrayEquals(kek, Profile.SENSITIVE.derive(password, salt))
    }
}
