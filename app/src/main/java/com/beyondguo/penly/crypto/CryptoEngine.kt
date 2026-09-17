package com.beyondguo.penly.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 记录完整性校验失败：密文/IV 与 MAC 不符（被篡改或损坏）。消息保持中性，不泄露任何细节 */
class MacVerificationException(message: String = "数据完整性校验失败，记录可能被篡改") : Exception(message)

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

    /**
     * n 字节随机串的十六进制表示。
     * 用于「占位槽位密码」这类一次性秘密：生成后即弃，无人知晓（包括用户自己）。
     */
    fun randomHex(nBytes: Int): String = randomBytes(nBytes).joinToString("") { "%02x".format(it) }

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

    // ---------------- 记录完整性（encrypt-then-MAC，v1.1 跨端契约扩展） ----------------
    //
    // AES-CBC 无完整性：密文可被逐位翻转且解密不报错。为加密字段附加 HMAC-SHA256 校验值，
    // 与小程序 utils/crypto.js:226-232 逐字节互认（MAC_INFO/子密钥/输入拼接/空字段语义均一致）。

    /** MAC 子密钥派生信息串（与小程序 crypto.js:226 一致，发布后不可更改） */
    const val MAC_INFO = "yinji-record-mac-v1"

    /**
     * MAC 子密钥。
     * ⚠️ 逐字节对齐小程序 crypto.js:228 的**部署语义**：`cl.hmacSha256(utf8(MAC_INFO), key32)`
     * 的实参顺序是反直觉的——**信息串作 HMAC key，32 字节字段密钥作 message**：
     * `HMAC-SHA256(key=utf8("yinji-record-mac-v1"), msg=key32)`。
     * 这是线上已部署格式，Android 必须照抄，不得"顺手修正"为常规顺序。
     */
    fun macSubKey(key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(MAC_INFO.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(key)
    }

    /**
     * 单字段 MAC：Base64(HMAC-SHA256(子密钥, utf8("<ivB64>.<dataB64>")))。
     * 输入**逐字使用存储的 Base64 字符串**——两端通过 JSON 交换同一批字符串，无重编码漂移面。
     */
    fun recordMac(macSubKey: ByteArray, ivB64: String, dataB64: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macSubKey, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            mac.doFinal("$ivB64.$dataB64".toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * 验证单字段 MAC。语义与小程序完全同构：
     * - 密文为空 → true（空字段三空，无可验证物）
     * - MAC 为空 → true（旧数据/无 Mac 记录，宽容跳过——crypto.js:250 `if (mac && …)`）
     * - 其余 → 重算比对，不符 = 密文或 IV 被篡改/损坏
     */
    fun verifyRecordMac(macSubKey: ByteArray, ivB64: String, dataB64: String, macB64: String): Boolean {
        if (dataB64.isBlank() || macB64.isBlank()) return true
        return recordMac(macSubKey, ivB64, dataB64) == macB64
    }
}
