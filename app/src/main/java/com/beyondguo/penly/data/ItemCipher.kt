package com.beyondguo.penly.data

import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.CryptoV2
import com.beyondguo.penly.crypto.MacVerificationException

/**
 * 条目字段级加解密（本机存储格式分派核心，全部纯函数、不碰会话）。
 *
 * 两种本机条目格式：
 * - [VaultMeta.SCHEMA_V2]（2026-06~09）：AES-256-CBC + encrypt-then-MAC 四元组（[CryptoEngine]）
 * - [VaultMeta.SCHEMA_V3]（v5.0 起）：AES-256-GCM 逐字段，子密钥 = HKDF(key, "yinji-enc-v2")，
 *   AAD = "字段名|条目id"——**与契约 v2 备份文件完全同构**（防密文跨字段/跨条目搬移，
 *   v1 MAC 四元组的拼装攻击面在 GCM 格式下由 AAD 收口）。
 *
 * 通用语义：
 * - 字段空串 = 无内容，两格式一致，不参与加解密；
 * - GCM 格式下 Iv/Mac 槽位恒为空串——载体复用 [VaultItem]，存储层结构与读写链路零变更；
 * - 密钥全程不变（PBKDF2 派生），格式迁移只换字段密文，verify 链/解锁/aux 零扰动。
 */
object ItemCipher {

    /** 契约 v2 字段名枚举（AAD 第一段，逐字对齐备份文件 §2） */
    const val F_ACCOUNT = "account"
    const val F_SECRET = "secret"
    const val F_NOTE = "note"
    const val F_TOTP = "totp"

    /** 单字段加密产物：CBC 为 (ct, iv, mac) 三元组；GCM 只用 dataB64（iv/mac 空串） */
    data class Field(val dataB64: String, val ivB64: String, val macB64: String)

    // ---------------- 字段级 ----------------

    fun encField(format: Int, field: String, itemId: String, plain: String, key: ByteArray): Field =
        if (plain.isEmpty()) {
            Field("", "", "")
        } else when (format) {
            VaultMeta.SCHEMA_V3 ->
                Field(CryptoV2.b64(gcm(field, itemId, plain.toByteArray(Charsets.UTF_8), key, encrypt = true)), "", "")
            else -> {
                val p = CryptoEngine.aesEncrypt(plain, key)
                Field(p.dataB64, p.ivB64, CryptoEngine.recordMac(CryptoEngine.macSubKey(key), p.ivB64, p.dataB64))
            }
        }

    /**
     * 解密单字段。任何完整性失败（MAC 不符 / GCM tag 不符）统一抛
     * [MacVerificationException]——调用方一律 fail-closed，绝不静默降级。
     */
    fun decField(format: Int, field: String, itemId: String, enc: String, iv: String, mac: String, key: ByteArray): String {
        if (enc.isBlank()) return ""
        return when (format) {
            VaultMeta.SCHEMA_V3 -> try {
                String(gcm(field, itemId, CryptoV2.unb64(enc), key, encrypt = false), Charsets.UTF_8)
            } catch (_: CryptoV2.IntegrityException) {
                throw MacVerificationException()
            }
            else -> {
                if (!CryptoEngine.verifyRecordMac(CryptoEngine.macSubKey(key), iv, enc, mac)) {
                    throw MacVerificationException()
                }
                CryptoEngine.aesDecrypt(CryptoEngine.EncPayload(iv, enc), key)
            }
        }
    }

    /** GCM 收口：子密钥域分离 + AAD 绑定字段与条目 */
    private fun gcm(field: String, itemId: String, data: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        val sub = CryptoV2.subKey(key, CryptoV2.Domains.ENC_V2)
        val aad = "$field|$itemId".toByteArray(Charsets.UTF_8)
        return if (encrypt) CryptoV2.gcmEncrypt(sub, data, aad) else CryptoV2.gcmDecrypt(sub, data, aad)
    }

    // ---------------- 条目级 ----------------

    /** 明文条目 → 密文条目（影子数据生成 / 备份导入重建共用） */
    fun encryptEntries(entries: List<PlainEntry>, key: ByteArray, format: Int): List<VaultItem> =
        entries.map { e ->
            val a = encField(format, F_ACCOUNT, e.id, e.account, key)
            val s = encField(format, F_SECRET, e.id, e.secret, key)
            val n = encField(format, F_NOTE, e.id, e.note, key)
            val t = encField(format, F_TOTP, e.id, e.totp, key)
            VaultItem(
                id = e.id,
                title = e.title,
                category = e.category,
                accountEnc = a.dataB64, accountIv = a.ivB64, accountMac = a.macB64,
                secretEnc = s.dataB64, secretIv = s.ivB64, secretMac = s.macB64,
                noteEnc = n.dataB64, noteIv = n.ivB64, noteMac = n.macB64,
                totpEnc = t.dataB64, totpIv = t.ivB64, totpMac = t.macB64,
                totpDigits = e.totpDigits,
                totpPeriod = e.totpPeriod,
                totpAlgo = e.totpAlgo,
                appPackage = e.appPackage,
                createdAt = e.createdAt,
                updatedAt = e.updatedAt,
            )
        }

    /** 密文条目 → 明文条目（[decryptItem] 的 key 参数化版本，供未建立会话的导入/迁移用） */
    fun decryptItem(item: VaultItem, key: ByteArray, format: Int): PlainEntry = PlainEntry(
        id = item.id,
        title = item.title,
        category = item.category,
        account = decField(format, F_ACCOUNT, item.id, item.accountEnc, item.accountIv, item.accountMac, key),
        secret = decField(format, F_SECRET, item.id, item.secretEnc, item.secretIv, item.secretMac, key),
        note = decField(format, F_NOTE, item.id, item.noteEnc, item.noteIv, item.noteMac, key),
        totp = decField(format, F_TOTP, item.id, item.totpEnc, item.totpIv, item.totpMac, key),
        totpDigits = item.totpDigits,
        totpPeriod = item.totpPeriod,
        totpAlgo = item.totpAlgo,
        appPackage = item.appPackage,
        createdAt = item.createdAt,
        updatedAt = item.updatedAt,
    )

    fun decryptItems(items: List<VaultItem>, key: ByteArray, format: Int): List<PlainEntry> =
        items.map { decryptItem(it, key, format) }

    /**
     * 全量重加密（改密 / 存储格式迁移共用）：逐条按旧格式解密（完整性失败即抛
     * [MacVerificationException] 中止整体）→ 按新格式重加密。同格式调用 = 换密钥，
     * 异格式调用（V2→V3）= 纯格式迁移。
     */
    fun reEncryptItems(
        items: List<VaultItem>,
        oldKey: ByteArray,
        newKey: ByteArray,
        oldFormat: Int,
        newFormat: Int,
    ): List<VaultItem> =
        items.map { src ->
            val plain = decryptItem(src, oldKey, oldFormat)
            val a = encField(newFormat, F_ACCOUNT, src.id, plain.account, newKey)
            val s = encField(newFormat, F_SECRET, src.id, plain.secret, newKey)
            val n = encField(newFormat, F_NOTE, src.id, plain.note, newKey)
            val t = encField(newFormat, F_TOTP, src.id, plain.totp, newKey)
            src.copy(
                accountEnc = a.dataB64, accountIv = a.ivB64, accountMac = a.macB64,
                secretEnc = s.dataB64, secretIv = s.ivB64, secretMac = s.macB64,
                noteEnc = n.dataB64, noteIv = n.ivB64, noteMac = n.macB64,
                totpEnc = t.dataB64, totpIv = t.ivB64, totpMac = t.macB64,
            )
        }
}
