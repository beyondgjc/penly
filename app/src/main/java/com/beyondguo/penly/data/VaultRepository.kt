package com.beyondguo.penly.data

import com.beyondguo.penly.backup.BackupCodec
import com.beyondguo.penly.backup.BackupCrypto
import com.beyondguo.penly.backup.BackupData
import com.beyondguo.penly.backup.BackupFile
import com.beyondguo.penly.backup.BackupFormatException
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.SessionManager

/** 备份导入失败（可向用户展示的中文信息） */
class ImportException(message: String) : Exception(message)

/** 导入结果类型 */
object ImportType {
    const val WIPED = "wiped" // 备份为空 → 清空本地
    const val RESTORED = "restored" // custom 或 Android default：meta+items 原样落地
    const val REENCRYPTED = "reencrypted" // 小程序 default 备份：解密后重加密为本地默认密钥
}

data class ImportResult(val type: String, val count: Int)

/**
 * 印迹业务仓库（对应小程序 services/vault.js + 部分 utils/crypto.js 业务封装）。
 * 所有读写只接触密文；明文与密钥仅在会话内存中。
 */
class VaultRepository(private val store: VaultStore) {

    val unlocked get() = SessionManager.unlocked

    // ---------------- meta / 初始化 / 解锁 ----------------

    suspend fun meta(): VaultMeta? = store.readMeta()

    suspend fun isInitialized(): Boolean = meta()?.initialized == true

    /**
     * 首次初始化：生成随机 salt → 派生密钥 → 生成校验串 → 建 meta。
     * mode 为 MODE_DEFAULT（内置默认主密码）或 MODE_CUSTOM（用户主密码）。
     */
    suspend fun initVault(master: String, mode: String) {
        require(master.length >= CryptoEngine.MASTER_MIN_LEN) { "主密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位" }
        val saltB64 = CryptoEngine.randomSaltB64()
        val key = CryptoEngine.deriveKeyB64(master, saltB64)
        val (verifyB64, verifyIvB64) = CryptoEngine.makeVerify(key)
        val now = System.currentTimeMillis()
        store.writeMeta(VaultMeta(saltB64, verifyB64, verifyIvB64, mode, true, now, now))
        SessionManager.establish(key)
    }

    /** 用主密码解锁（零知识：本地派生 → 解密校验串比对）。成功建立会话，失败返回 false。 */
    suspend fun unlock(master: String): Boolean {
        val m = meta() ?: return false
        val key = CryptoEngine.deriveKeyB64(master, m.saltB64)
        return if (CryptoEngine.verifyMaster(key, m.verifyB64, m.verifyIvB64)) {
            SessionManager.establish(key)
            true
        } else {
            false
        }
    }

    /** default 模式一键解锁（内置默认主密码） */
    suspend fun unlockDefault(): Boolean {
        val m = meta() ?: return false
        if (m.pwdMode != VaultMeta.MODE_DEFAULT) return false
        return unlock(CryptoEngine.ANDROID_DEFAULT_MASTER)
    }

    fun lock() = SessionManager.lock()

    // ---------------- 条目 CRUD ----------------

    suspend fun items(): List<VaultItem> = store.readItems().sortedByDescending { it.updatedAt }

    suspend fun item(id: String): VaultItem? = store.readItems().firstOrNull { it.id == id }

    /** 解密条目；未解锁抛 [com.beyondguo.penly.crypto.VaultLockedException] */
    suspend fun decryptItem(item: VaultItem): PlainEntry {
        val key = SessionManager.requireKey()
        fun dec(enc: String, iv: String): String =
            if (enc.isBlank()) "" else CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, enc), key)
        return PlainEntry(
            id = item.id,
            title = item.title,
            category = item.category,
            account = dec(item.accountEnc, item.accountIv),
            secret = dec(item.secretEnc, item.secretIv),
            note = dec(item.noteEnc, item.noteIv),
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
        )
    }

    /** 仅解密账号字段（列表副标题用，比整条解密轻量） */
    suspend fun decryptAccount(item: VaultItem): String =
        if (item.accountEnc.isBlank()) ""
        else CryptoEngine.aesDecrypt(
            CryptoEngine.EncPayload(item.accountIv, item.accountEnc),
            SessionManager.requireKey(),
        )

    /** 新增或更新（title/category 明文索引，account/secret/note 加密） */
    suspend fun saveEntry(
        id: String?,
        title: String,
        category: String,
        account: String,
        secret: String,
        note: String,
    ): String {
        val key = SessionManager.requireKey()
        fun enc(plain: String): Pair<String, String> =
            if (plain.isEmpty()) "" to "" else {
                val p = CryptoEngine.aesEncrypt(plain, key)
                p.dataB64 to p.ivB64
            }
        val (aE, aI) = enc(account)
        val (sE, sI) = enc(secret)
        val (nE, nI) = enc(note)
        val now = System.currentTimeMillis()
        val old = id?.let { item(it) }
        val newItem = VaultItem(
            id = old?.id ?: CryptoEngine.genId(),
            title = title.trim(),
            category = category.trim().ifBlank { "默认" },
            accountEnc = aE, accountIv = aI,
            secretEnc = sE, secretIv = sI,
            noteEnc = nE, noteIv = nI,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        store.upsertItem(newItem)
        return newItem.id
    }

    suspend fun deleteItem(id: String) = store.deleteItem(id)

    // ---------------- 修改主密码 / 重置 ----------------

    /**
     * 设置/修改主密码：旧密钥解密全部记录 → 新密钥重加密 → 更新 meta（新 salt + 新校验串）。
     * [oldPlain] 为 null 表示 default → custom 升级（旧密码自动取内置默认主密码）。
     * 成功返回 null；失败返回中文错误信息。
     */
    suspend fun changeMasterPassword(oldPlain: String?, newPlain: String): String? {
        if (newPlain.length < CryptoEngine.MASTER_MIN_LEN) {
            return "主密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位"
        }
        val m = meta() ?: return "印迹尚未初始化"
        val oldMaster = oldPlain ?: CryptoEngine.ANDROID_DEFAULT_MASTER
        val oldKey = CryptoEngine.deriveKeyB64(oldMaster, m.saltB64)
        if (!CryptoEngine.verifyMaster(oldKey, m.verifyB64, m.verifyIvB64)) {
            return "旧主密码错误"
        }
        val newSaltB64 = CryptoEngine.randomSaltB64()
        val newKey = CryptoEngine.deriveKeyB64(newPlain, newSaltB64)
        val (newVerify, newVerifyIv) = CryptoEngine.makeVerify(newKey)
        val reEnc = reEncryptItems(store.readItems(), oldKey, newKey)
        store.writeItems(reEnc)
        val now = System.currentTimeMillis()
        store.writeMeta(VaultMeta(newSaltB64, newVerify, newVerifyIv, VaultMeta.MODE_CUSTOM, true, m.createdAt, now))
        SessionManager.establish(newKey)
        return null
    }

    /** 重置印迹：清空全部本地数据（忘记主密码场景） */
    suspend fun resetVault() {
        store.clearAll()
        SessionManager.lock()
    }

    // ---------------- 导出 / 导入 ----------------

    /**
     * 导出为 `private-vault-backup` v1 JSON。
     * default 模式导出仅本应用可再解锁（crypto.masterRef 标记），custom 模式导出通用。
     */
    suspend fun exportJson(): String {
        val m = meta() ?: throw IllegalStateException("印迹尚未初始化")
        val masterRef = if (m.pwdMode == VaultMeta.MODE_DEFAULT) CryptoEngine.MASTER_REF_ANDROID else null
        val file = BackupFile(
            format = BackupCodec.FORMAT,
            version = BackupCodec.VERSION,
            exportedAt = System.currentTimeMillis(),
            crypto = BackupCrypto(
                kdf = "PBKDF2",
                hash = "SHA-256",
                iterations = CryptoEngine.PBKDF2_ITERATIONS,
                keyLen = CryptoEngine.KEY_LEN_BYTES,
                saltLen = CryptoEngine.SALT_LEN_BYTES,
                ivLen = CryptoEngine.IV_LEN_BYTES,
                cipher = "AES-256-CBC",
                encoding = "base64",
                masterRef = masterRef,
            ),
            data = BackupData(meta = m.copy(openid = null), items = store.readItems()),
        )
        return BackupCodec.encode(file)
    }

    /**
     * 导入备份并覆盖本地：
     * - 备份无 meta → 清空本地
     * - custom 备份 → meta+items 原样落地（之后输主密码解锁）
     * - Android default 备份（masterRef=penly-def-v1）→ 原样落地
     * - 小程序 default 备份（wxb-def-v1::openid）→ 校验后解密、重加密为本地默认密钥
     * 导入完成即锁定。
     */
    suspend fun importJson(text: String): ImportResult {
        val file = try {
            BackupCodec.decode(text)
        } catch (e: BackupFormatException) {
            throw ImportException(e.message ?: "备份文件无效")
        }
        val m = file.data.meta
        if (m == null) {
            store.clearAll()
            SessionManager.lock()
            return ImportResult(ImportType.WIPED, 0)
        }
        val items = file.data.items
        if (m.pwdMode == VaultMeta.MODE_DEFAULT) {
            val sourceMaster = when (file.crypto.masterRef) {
                CryptoEngine.MASTER_REF_ANDROID -> CryptoEngine.ANDROID_DEFAULT_MASTER
                null, CryptoEngine.MASTER_REF_WXB ->
                    m.openid?.let { CryptoEngine.WXB_DEFAULT_PREFIX + it }
                        ?: throw ImportException("该备份为「默认保护」且缺少身份标识，无法解锁")
                else -> throw ImportException("未知的密钥来源（${file.crypto.masterRef}），无法解锁")
            }
            val srcKey = CryptoEngine.deriveKeyB64(sourceMaster, m.saltB64)
            if (!CryptoEngine.verifyMaster(srcKey, m.verifyB64, m.verifyIvB64)) {
                throw ImportException("备份校验失败，文件可能已损坏")
            }
            // 重加密为本地默认密钥，落一套全新 meta（新 salt + 新校验串）
            val newSaltB64 = CryptoEngine.randomSaltB64()
            val targetKey = CryptoEngine.deriveKeyB64(CryptoEngine.ANDROID_DEFAULT_MASTER, newSaltB64)
            val (newVerify, newVerifyIv) = CryptoEngine.makeVerify(targetKey)
            val now = System.currentTimeMillis()
            store.writeItems(reEncryptItems(items, srcKey, targetKey))
            store.writeMeta(VaultMeta(newSaltB64, newVerify, newVerifyIv, VaultMeta.MODE_DEFAULT, true, now, now))
            SessionManager.lock()
            return ImportResult(ImportType.REENCRYPTED, items.size)
        }
        // custom：原样落地，解锁交给锁屏
        store.writeMeta(m.copy(openid = null))
        store.writeItems(items)
        SessionManager.lock()
        return ImportResult(ImportType.RESTORED, items.size)
    }

    /** 用 oldKey 解密、newKey 重加密全部条目的三个密文字段 */
    private fun reEncryptItems(items: List<VaultItem>, oldKey: ByteArray, newKey: ByteArray): List<VaultItem> {
        return items.map { it ->
            fun re(enc: String, iv: String): Pair<String, String> =
                if (enc.isBlank()) "" to "" else {
                    val plain = CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, enc), oldKey)
                    val p = CryptoEngine.aesEncrypt(plain, newKey)
                    p.dataB64 to p.ivB64
                }
            val (aE, aI) = re(it.accountEnc, it.accountIv)
            val (sE, sI) = re(it.secretEnc, it.secretIv)
            val (nE, nI) = re(it.noteEnc, it.noteIv)
            it.copy(accountEnc = aE, accountIv = aI, secretEnc = sE, secretIv = sI, noteEnc = nE, noteIv = nI)
        }
    }
}
