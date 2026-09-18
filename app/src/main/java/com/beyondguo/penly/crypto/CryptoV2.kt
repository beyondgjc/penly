package com.beyondguo.penly.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 契约 v2 密码学原语（2026-09-18）——格式与参数定义见工作区
 * 《印迹_跨端契约v2_地基工程.md》，两端实现以该文档 + 固定向量为闸门。
 *
 * 与 [CryptoEngine]（v1）的关系：完全独立的命名空间。v1 原语（CBC/verifyRecordMac）
 * 仅服务 v1 备份文件与存量数据的读取/迁移，不做任何扩展。
 */
object CryptoV2 {

    /** GCM 载荷 = nonce(12B) || ciphertext || tag(16B) */
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128

    class IntegrityException(message: String = "数据完整性校验失败") : Exception(message)

    // ---------------- HKDF-SHA256（RFC 5869） ----------------

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen in 1..32 * 255) { "HKDF 输出长度越界" }
        val mac = Mac.getInstance("HmacSHA256")
        // Extract
        val prk = if (salt.isEmpty()) {
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            mac.doFinal(ikm)
        } else {
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            mac.doFinal(ikm)
        }
        // Expand
        val okm = ByteArray(outLen)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < outLen) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, outLen - pos)
            System.arraycopy(t, 0, okm, pos, n)
            pos += n
            counter++
        }
        return okm
    }

    /** 契约域标签表（《契约v2》§3）——新用途必须先注册，禁止复用既有标签 */
    object Domains {
        const val ENC_V2 = "yinji-enc-v2"
    }

    /** 用途子密钥派生：key32 语义隔离（无 salt 变体，info 即域标签） */
    fun subKey(key32: ByteArray, domain: String): ByteArray =
        hkdfSha256(ikm = key32, salt = ByteArray(0), info = domain.toByteArray(Charsets.UTF_8), outLen = 32)

    // ---------------- AES-256-GCM（载荷 = nonce || ct || tag） ----------------

    private fun cipher(key: ByteArray, nonce: ByteArray, aad: ByteArray, mode: Int): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c
    }

    fun gcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val ct = cipher(key, nonce, aad, Cipher.ENCRYPT_MODE).doFinal(plaintext)
        return nonce + ct
    }

    fun gcmDecrypt(key: ByteArray, payload: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (payload.size < NONCE_BYTES + 16) throw IntegrityException()
        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val ct = payload.copyOfRange(NONCE_BYTES, payload.size)
        return try {
            cipher(key, nonce, aad, Cipher.DECRYPT_MODE).doFinal(ct)
        } catch (_: Exception) {
            throw IntegrityException()
        }
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    // ---------------- Argon2id（RFC 9106，本机层 KEK / 契约层 v2 KDF） ----------------

    /**
     * Argon2id 派生。secret/associatedData 一般留空（印迹不使用），保留参数是为了
     * 与 RFC 9106 §5.3 官方向量逐字节互验（实现正确性的权威校验）。
     */
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        outLen: Int = 32,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .withSecret(secret)
            .withAdditional(associatedData)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(outLen)
        gen.generateBytes(password, out, 0, outLen)
        return out
    }
}
