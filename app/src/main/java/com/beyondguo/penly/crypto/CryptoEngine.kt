package com.beyondguo.penly.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密引擎 —— 与小程序 utils/crypto.js 严格字节级互认（详见《Android端实现方案.md》§1）。
 *
 * 契约（任何一侧不得单方变更）：
 * - 密钥派生：PBKDF2-HMAC-SHA256，100,000 次迭代，16 字节随机 salt，输出 32 字节密钥
 * - 对称加密：AES-256-CBC（PKCS7 padding），每字段独立 16 字节随机 IV
 * - 编码：Base64 标准字母表（含 padding）
 * - 校验串：用派生密钥加密 KNOWN_PLAINTEXT，解锁时本地解密比对（零知识）
 *
 * 纯 JVM 实现，无 Android 依赖，便于单元测试做跨端向量验证。
 */
object CryptoEngine {

    /** 校验串固定明文：与小程序 utils/crypto.js 一致，发布后不可更改 */
    const val KNOWN_PLAINTEXT = "PRIVATE_VAULT_VERIFY_TOKEN_v1"

    const val PBKDF2_ITERATIONS = 100_000
    const val KEY_LEN_BYTES = 32
    const val SALT_LEN_BYTES = 16
    const val IV_LEN_BYTES = 16

    const val MASTER_MIN_LEN = 6

    /** Android 端内置默认主密码（产品决策 2026-08-29：固定常量、等同公开，用户知情接受） */
    const val ANDROID_DEFAULT_MASTER = "penly-def-v1::PenlyFixedDefaultMaster"

    /** 小程序 default 模式的主密码前缀 */
    const val WXB_DEFAULT_PREFIX = "wxb-def-v1::"

    const val MASTER_REF_ANDROID = "penly-def-v1"
    const val MASTER_REF_WXB = "wxb-def-v1"

    private val random = SecureRandom()

    /** 一段密文载荷（IV + 密文，均为 Base64） */
    data class EncPayload(val ivB64: String, val dataB64: String)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun randomSaltB64(): String = Base64.getEncoder().encodeToString(randomBytes(SALT_LEN_BYTES))

    fun genId(): String {
        val hex = randomBytes(8).joinToString("") { "%02x".format(it) }
        return "a_$hex${java.lang.Long.toString(System.currentTimeMillis(), 36)}"
    }

    /** PBKDF2-HMAC-SHA256 派生密钥；PBEKeySpec 以 UTF-8 处理密码字符，与 JS 端 utf8Encode 一致 */
    fun deriveKey(master: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(master.toCharArray(), salt, iterations, KEY_LEN_BYTES * 8)
        return factory.generateSecret(spec).encoded
    }

    fun deriveKeyB64(master: String, saltB64: String): ByteArray =
        deriveKey(master, Base64.getDecoder().decode(saltB64))

    fun aesEncrypt(plain: String, key: ByteArray): EncPayload {
        val iv = randomBytes(IV_LEN_BYTES)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return EncPayload(
            ivB64 = Base64.getEncoder().encodeToString(iv),
            dataB64 = Base64.getEncoder().encodeToString(ct),
        )
    }

    fun aesDecrypt(payload: EncPayload, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(Base64.getDecoder().decode(payload.ivB64)),
        )
        val pt = cipher.doFinal(Base64.getDecoder().decode(payload.dataB64))
        return String(pt, Charsets.UTF_8)
    }

    /** 生成校验串：verifyB64 = 加密 KNOWN_PLAINTEXT 的密文，verifyIvB64 = 对应 IV */
    fun makeVerify(key: ByteArray): Pair<String, String> {
        val p = aesEncrypt(KNOWN_PLAINTEXT, key)
        return p.dataB64 to p.ivB64
    }

    /** 零知识校验：解密校验串比对常量；任何异常（含 padding 错误）一律视为密码错误 */
    fun verifyMaster(key: ByteArray, verifyB64: String, verifyIvB64: String): Boolean {
        return try {
            val decrypted = aesDecrypt(EncPayload(verifyIvB64, verifyB64), key)
            decrypted == KNOWN_PLAINTEXT
        } catch (_: Exception) {
            false
        }
    }
}
