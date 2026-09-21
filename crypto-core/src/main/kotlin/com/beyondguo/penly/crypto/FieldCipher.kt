package com.beyondguo.penly.crypto

/**
 * 字段级加解密（#8）——不含任何数据模型知识的「按 AAD 隔离的密文」组件。
 *
 * ## 它和 [Container] 什么关系
 * [Container] 管的是**库级**的事：参数怎么定、key 怎么派生、库头长什么样。
 * [FieldCipher] 管的是**字段级**的事：拿到一个已派生的 key32 之后，
 * 怎么把一个字段加密成一段可安全落盘的字符串，以及怎么解回来。
 *
 * 两者是「一次派生 + 多次字段读写」的关系：
 * ```
 * val header = Container.createHeader(Profile.SENSITIVE)
 * val key    = Container.deriveKey(header, pwd)      // 一次，~500ms
 * KeySession.establish(key)
 *
 * val ct = FieldCipher.seal(key, aad("feeling", id), text.toByteArray())
 * val pt = FieldCipher.open(key, aad("feeling", id), ct)
 * ```
 *
 * ## 为什么需要它（而不是各处自己拼 [Aead] 原语）
 * 「子密钥派生 + AAD 拼装 + 完整性异常归一」这三件事**必须每次都做对**，
 * 任何一处漏掉都是静默的强度下降：
 * - 漏了域分离子密钥 → 同一 key 在不同协议里复用，跨协议攻击面打开
 * - 漏了 AAD → 密文可以在字段之间 / 记录之间被搬运而不被发现
 * - 漏了异常归一 → 调用方漏 catch 原始异常，fail-closed 纪律破功
 *
 * 本组件把这三步固化成一个入口，调用方只需提供 **key + AAD + 明文**。
 *
 * ## 与 penly 的 `ItemCipher` 的关系
 * `ItemCipher` 是**本组件的业务适配层**：它把「account/secret/note/totp/passkey」
 * 五个具名字段 × `VaultItem` 结构 × V2/V3 双格式分派套在本组件之上。
 * 本组件不认识 `VaultItem`，因此可以被「印迹记录」等其它宿主直接复用。
 */
object FieldCipher {

    /**
     * 拼装惯例 AAD：`"<字段名>|<记录id>"`。
     *
     * 契约 v2 备份文件用的就是这个格式（见《印迹跨端备份互认.md》），
     * 所以本机存储与备份文件在 AAD 语义上天然同构——同一份数据换载体不会解不开。
     *
     * AAD 的作用是**把密文钉死在原位**：攻击者把 A 记录的秘密字段密文搬到
     * B 记录的账户字段，即便 key 相同也会因 AAD 不符而认证失败。
     */
    fun aad(field: String, recordId: String): ByteArray =
        "$field|$recordId".toByteArray(Charsets.UTF_8)

    /**
     * 加密一个字段。
     *
     * @param key 库级 key32（由 [Container.deriveKey] 派生，或在 [KeySession] 里）
     * @param aad 见 [aad]；**强烈建议填**，留空等于放弃防搬运保护
     */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): String =
        Aead.b64(Aead.gcmEncrypt(Aead.subKey(key, Aead.Domains.ENC), plaintext, aad))

    /**
     * 解密一个字段。
     *
     * @param algId 密文所用算法标识；`null` = 本组件当前（也是唯一）的 GCM 格式。
     *   显式传入非 GCM 的标识会 fail-closed 抛 [UnsupportedFormatException]，
     *   **不猜、不降级**——为将来算法迁移预留入口。
     * @throws Aead.IntegrityException 密文被篡改 / AAD 不符 / key 不对
     * @throws UnsupportedFormatException algId 不是本版本认识的算法
     */
    fun open(key: ByteArray, aad: ByteArray, payloadB64: String, algId: String? = null): ByteArray {
        if (algId != null && algId != ALG_AES_256_GCM) {
            throw UnsupportedFormatException(
                "未知的字段算法：$algId（本版本仅支持 $ALG_AES_256_GCM）",
            )
        }
        return Aead.gcmDecrypt(Aead.subKey(key, Aead.Domains.ENC), Aead.unb64(payloadB64), aad)
    }

    /**
     * 解密并归一异常：把 GCM 认证失败统一成 [MacVerificationException]。
     *
     * 用途是**兼容 penly 既有的调用方契约**——`ItemCipher.decField` 的调用方
     * 一律按 `MacVerificationException` 做 fail-closed 处理（历史格式的 MAC
     * 校验失败也是这个类型），新增 GCM 路径后不该让它们多 catch 一种异常。
     */
    fun openOrThrowMac(key: ByteArray, aad: ByteArray, payloadB64: String, algId: String? = null): ByteArray =
        try {
            open(key, aad, payloadB64, algId)
        } catch (_: Aead.IntegrityException) {
            throw MacVerificationException()
        }

    /** 本组件当前的字段算法标识（与 [Container.DATA_ALG_AES_256_GCM] 同值） */
    const val ALG_AES_256_GCM = Container.DATA_ALG_AES_256_GCM

    // ---------------- 批量重加密（#16）----------------

    /**
     * 一条记录的全部字段重加密：**改密（换钥）与格式迁移共用同一入口**。
     *
     * 输入输出都是 `字段名 → 密文` 的映射，本组件不认识记录是什么（`VaultItem`、
     * 日记条目、任何东西都行）——它只要求调用方给出「记录 id + 字段到密文的映射」。
     *
     * ## 为什么值得抽出来
     * 「逐字段解密再重加密」这个循环本身只有十行，但它内嵌三条**必须每次都做对**
     * 的纪律，散落各处时极易漏：
     * 1. **fail-closed**：任一字段解不开（篡改 / 坏数据）→ 抛异常，由调用方中止整体操作、
     *    磁盘保持原状。绝不"跳过坏的继续"——那会把损坏静默固化成看似正常的数据。
     * 2. **空值语义**：密文为空串 = 该字段无内容，原样传回，不解密也不加密。
     * 3. **逐字段独立 AAD**：AAD 由 `(字段名, 记录id)` 现场拼装（见 [aad]），
     *    所以重加密后密文仍被钉在原位，跨字段搬移依旧会认证失败。
     *
     * ## 泛型化的意义
     * penly 的 `ItemCipher.reEncryptItems` 委托它（本批已改），于是 V2/V3 的格式分派
     * 逻辑只需写一次；「印迹记录」将来迁移自己字段格式时可直接复用，不必再抄一遍循环。
     *
     * @param oldKey 现用库级 key32
     * @param newKey 目标库级 key32（新旧同值 = 纯格式迁移）
     * @param recordId 记录 id（参与 AAD 拼装）
     * @param fields 该记录的 `字段名 → 现用密文` 映射（空串 = 无内容）
     * @param decrypt 单字段解密：`(key, aad, 现用密文) -> 明文`——由调用方注入其格式实现
     * @param encrypt 单字段加密：`(key, aad, 明文) -> 新密文`
     * @return `字段名 → 新密文`；输入里为空串的字段原样返回空串
     * @throws MacVerificationException / [Aead.IntegrityException] 任一字段完整性失败
     */
    fun reEncryptRecord(
        oldKey: ByteArray,
        newKey: ByteArray,
        recordId: String,
        fields: Map<String, String>,
        decrypt: (key: ByteArray, aad: ByteArray, payload: String) -> ByteArray,
        encrypt: (key: ByteArray, aad: ByteArray, plaintext: ByteArray) -> String,
    ): Map<String, String> = buildMap(fields.size) {
        for ((field, payload) in fields) {
            if (payload.isEmpty()) {
                put(field, "") // 空字段不参与加解密，语义与 seal 的调用方约定一致
                continue
            }
            val a = aad(field, recordId)
            val plain = decrypt(oldKey, a, payload)
            put(field, encrypt(newKey, a, plain))
        }
    }
}
