package com.beyondguo.penly.bio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生物识别解锁（仅 custom 主密码模式提供；default 模式也可用于进入验证）。
 *
 * 安全模型（加密绑定方案）：
 * - 主密码副本存本地前，先用 Android Keystore 里一把「每次使用需生物验证」的 AES-GCM 密钥加密；
 * - 该密钥不可导出、录入新指纹即失效（setInvalidatedByBiometricEnrollment）；
 * - 副本的加/解都必须先通过 BiometricPrompt(CryptoObject) 硬件验证。
 *
 * 已知限制：MIUI 上冷启动后的首次认证，系统对话框会先短暂显示感叹号（服务会话建立中），
 * 数秒后自动恢复为等待态，期间不回调应用层数据库错误。
 */
class BioManager {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "penly_bio_key"
        private const val PREFS = "penly_bio"
        private const val PREF_MASTER_ENC = "master_enc"
        private const val GCM_TAG_BITS = 128

        /** 设备支持已录入的生物识别（指纹/面容，强认证级别）；用于锁屏按钮（必须已录入） */
        fun canAuthenticate(context: Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS

        /** 设备具备生物识别硬件即返回 true（未录入也显示开关，引导去系统设置录入） */
        fun isBiometricAvailable(context: Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

        /** 是否已有本地主密码副本 */
        fun hasCachedMaster(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_MASTER_ENC, null) != null

        /** 清除副本与 Keystore 密钥（关闭开关 / 改主密码 / 重置时调用） */
        fun clear(context: Context) {
            try {
                val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                ks.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREF_MASTER_ENC).apply()
        }

        private fun ensureKey() {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) return
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1) // 每次使用都需验证（CryptoObject 模式）
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            generator.generateKey()
        }

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        /** Keystore 常见异常翻译为可操作的中文提示 */
        private fun friendlyKeyError(e: Exception): String {
            val msg = e.message ?: ""
            return when {
                msg.contains("biometric must be enrolled", ignoreCase = true) ->
                    "请先在系统设置录入指纹/面容，再开启指纹解锁"
                msg.contains("Secure hardware", ignoreCase = true) ||
                    msg.contains("StrongBox", ignoreCase = true) ->
                    "设备安全硬件不可用，无法开启指纹解锁"
                else -> "初始化安全密钥失败：$msg"
            }
        }

        /**
         * 开启生物识别：先通过生物验证拿到加密 cipher，再把主密码密文落盘。
         * [master] 的正确性由调用方先行校验（unlock 验证）。
         */
        fun saveMaster(
            activity: FragmentActivity,
            master: String,
            onDone: () -> Unit,
            onError: (String) -> Unit,
        ) {
            try {
                ensureKey()
            } catch (e: Exception) {
                onError(friendlyKeyError(e))
                return
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            prompt(
                activity,
                title = "验证身份以开启指纹解锁",
                subtitle = "开启后可用指纹快速解锁",
                cipher = cipher,
                onAuthed = { c ->
                    val ct = c.doFinal(master.toByteArray(Charsets.UTF_8))
                    val record = Base64.getEncoder().encodeToString(cipher.iv) + ":" +
                        Base64.getEncoder().encodeToString(ct)
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(PREF_MASTER_ENC, record).apply()
                    onDone()
                },
                onError = onError,
            )
        }

        /**
         * 指纹解锁：生物验证通过后解出主密码副本，交 [onMaster] 走正常解锁流程。
         * 失败（副本损坏/密钥失效等）回调 [onError]，调用方应清除副本要求手动输入。
         */
        fun unlockWithMaster(
            activity: FragmentActivity,
            onMaster: (String) -> Unit,
            onError: (String) -> Unit,
        ) {
            val record = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_MASTER_ENC, null)
            if (record == null) {
                onError("本地凭证缺失，请手动输入主密码")
                return
            }
            val parts = record.split(":")
            if (parts.size != 2) {
                clear(activity)
                onError("本地凭证已损坏，请手动输入主密码")
                return
            }
            try {
                val iv = Base64.getDecoder().decode(parts[0])
                val ct = Base64.getDecoder().decode(parts[1])
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                prompt(
                    activity,
                    title = "验证指纹以解锁密码箱",
                    subtitle = "解锁私密密码箱",
                    cipher = cipher,
                    onAuthed = { c ->
                        try {
                            onMaster(String(c.doFinal(ct), Charsets.UTF_8))
                        } catch (e: Exception) {
                            clear(activity)
                            onError("本地凭证已失效，请手动解锁")
                        }
                    },
                    onError = onError,
                )
            } catch (e: Exception) {
                clear(activity)
                onError("本地凭证已失效，请手动输入主密码")
            }
        }

        private fun prompt(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            cipher: Cipher,
            onAuthed: (Cipher) -> Unit,
            onError: (String) -> Unit,
        ) {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) onError("生物验证返回异常")
                    else onAuthed(c)
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        onError("已取消")
                    } else {
                        onError(errString.toString())
                    }
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("取消")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }
}
