package com.beyondguo.penly.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyWrapper] 的 AndroidKeyStore 实现：wrapKey 生成于 TEE（可选 StrongBox），永不导出。
 *
 * 留在 app 模块（不进 crypto-core）——它依赖 `android.security.keystore`，
 * 而 core 必须保持纯 JVM。这是 core 与平台之间那条接缝的另一半。
 *
 * 失效语义（严格双因素）：
 * - 换机 / 恢复出厂 → TEE 密钥销毁 → 信封永久不可解 → 唯一出路 = 备份恢复
 * - 刻意**不设** setUserAuthenticationRequired：锁屏凭据变更不会销毁密钥，
 *   失效面收窄到「设备生命周期事件」，避免数据灾难面扩大；
 *   生物识别 gate 由上层解锁流程承担，不与信封绑定。
 */
class AndroidKeyStoreWrapper(
    private val context: Context,
    private val alias: String = "yinji-kek-wrap-v1",
    private val preferStrongBox: Boolean = false,
) : KeyWrapper {

    override val tag: String = "keystore"

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                builder.setIsStrongBoxBacked(true)
            } catch (_: IllegalArgumentException) {
                // 机型声明支持但安全元件拒绝 —— 落回 TEE
            }
        }
        val kpg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kpg.init(builder.build())
        return kpg.generateKey()
    }

    override fun wrap(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return cipher.iv + ct
    }

    override fun unwrap(payload: ByteArray, aad: ByteArray): ByteArray {
        if (payload.size <= 12) throw Aead.IntegrityException()
        val nonce = payload.copyOfRange(0, 12)
        val ct = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ct)
        } catch (e: StrongBoxUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw Aead.IntegrityException()
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** StrongBox 硬件支持探测（API 28+） */
        fun strongBoxSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
    }
}
