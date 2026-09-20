package com.beyondguo.penly.data

import android.util.Log
import com.beyondguo.penly.backup.BackupCodec
import com.beyondguo.penly.backup.BackupCodecV2
import com.beyondguo.penly.backup.BackupCrypto
import com.beyondguo.penly.backup.BackupData
import com.beyondguo.penly.backup.BackupFile
import com.beyondguo.penly.backup.BackupFormatException
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.crypto.CryptoV2
import com.beyondguo.penly.crypto.KeyUnavailableException
import com.beyondguo.penly.crypto.KeyWrapper
import com.beyondguo.penly.crypto.KeystoreEnvelope
import com.beyondguo.penly.crypto.AndroidKeyStoreWrapper
import com.beyondguo.penly.crypto.MacVerificationException
import com.beyondguo.penly.crypto.SessionManager
import com.beyondguo.penly.crypto.WrongPasswordException
import com.beyondguo.penly.search.Embedder
import com.beyondguo.penly.search.EmbedderFactory
import com.beyondguo.penly.search.NoopEmbedder
import com.beyondguo.penly.search.SearchEntry
import com.beyondguo.penly.search.SearchIndex
import com.beyondguo.penly.search.SearchOutcome
import com.beyondguo.penly.search.SmartSearcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 解锁结果（v5.0 TEE 信封起三分）：
 * - [Success]：会话已建立；
 * - [WrongPassword]：密码因素层失败（密码错误或数据被篡改）——普通重试路径；
 * - [KeyUnavailable]：设备因素层失败（换机/恢复出厂/TEE 密钥被删）——信封永久不可解，
 *   唯一出路 = 备份恢复，UI 必须走专用降级对话框，绝不与「密码错误」混淆。
 */
sealed class UnlockResult {
    data object Success : UnlockResult()
    data object WrongPassword : UnlockResult()
    data object KeyUnavailable : UnlockResult()
}

/**
 * 印迹业务仓库（对应小程序 services/vault.js + 部分 utils/crypto.js 业务封装）。
 * 所有读写只接触密文；明文与密钥仅在会话内存中。
 *
 * ## v2 双槽位（影子保险库）
 *
 * 存储层面存在两个**无语义**槽位 [Slot.A] / [Slot.B]：
 * - 真库落在哪个槽位由初始化时随机决定，另一槽位承载占位数据（未设置应急密码）
 *   或影子数据（已设置应急密码）—— 因此磁盘上无法区分主次，也看不出用户到底
 *   设没设应急密码。
 * - 解锁时**并发**派生两个槽位，且必须等两路都跑完才判定结果，使总耗时恒定为
 *   `max(两次派生)`。一旦短路返回，掐表就能判断"刚才输入的是不是应急密码"。
 *
 * 每个槽位的 meta 都有一组 `aux*` 字段保存「另一槽位的密码」（用本槽位密钥加密）：
 * - 真库槽位：另一槽位的密码（占位随机密码 / 应急密码）→ 解锁真库后可自动重生成影子数据
 * - 影子槽位：一段随机诱饵 → 应急密码只能走到诱饵，**无法触达真库**
 *
 * 这种单向性正是 duress 需要的：拿到应急密码的人打不开真库；
 * 而拿到主密码的人本来就已经赢了，不再构成额外风险。
 */
class VaultRepository(private val store: VaultStore) {

    val unlocked get() = SessionManager.unlocked

    /**
     * TEE 信封包装器（v5.0 #30）：生产实现为 AndroidKeyStore（TEE/StrongBox），
     * wrapKey 生成于硬件内、永不导出。信封未启用时本字段不被触碰。
     */
    private val keyWrapper: KeyWrapper by lazy { AndroidKeyStoreWrapper(store.appContext) }

    private companion object {
        /** 空槽位派生用的固定 salt：让"空槽位"也走一次完整 PBKDF2，保证分支形状一致 */
        const val DUMMY_SALT_B64 = "AAAAAAAAAAAAAAAAAAAAAA=="
    }

    // ---------------- meta / 迁移 / 初始化 / 解锁 ----------------

    /** 当前槽位的 meta；未解锁时退回槽位 A（再退回 B），仅供 UI 判空使用 */
    suspend fun meta(): VaultMeta? {
        val slot = SessionManager.activeSlotOrNull()
        if (slot != null) return store.readMeta(slot)
        return store.readMeta(Slot.A) ?: store.readMeta(Slot.B)
    }

    suspend fun isInitialized(): Boolean =
        store.readMeta(Slot.A)?.initialized == true ||
            store.readMeta(Slot.B)?.initialized == true ||
            store.readLegacy()?.first?.initialized == true

    /**
     * 是否需要输入密码：任一槽位为 custom 即需要。
     * 锁屏/设置页用它决定 UI 形态，不依赖"哪个槽位是真库"这一秘密。
     */
    suspend fun requiresPassword(): Boolean =
        store.readMeta(Slot.A)?.pwdMode == VaultMeta.MODE_CUSTOM ||
            store.readMeta(Slot.B)?.pwdMode == VaultMeta.MODE_CUSTOM ||
            store.readLegacy()?.first?.pwdMode == VaultMeta.MODE_CUSTOM

    /**
     * v1 → v2 迁移：把 legacy 单槽位搬到**随机槽位**，并用无人知晓的随机密码
     * 建立另一槽位的占位数据，然后删除 legacy key。
     *
     * 关键：两槽位 [VaultMeta.createdAt] 取同一个值，避免"更早创建的是真库"成为规律。
     * 数据本身零变换（salt/verify/items 原样搬运），老用户的主密码照常解锁。
     */
    suspend fun migrateIfNeeded() {
        val legacy = store.readLegacy() ?: return
        val real = Slot.random()
        val peer = real.other()
        val now = System.currentTimeMillis()
        val createdAt = legacy.first.createdAt.takeIf { it > 0 } ?: now

        val peerPassword = CryptoEngine.randomHex(32)
        val peerSaltB64 = CryptoEngine.randomSaltB64()
        val peerKey = CryptoEngine.deriveKeyB64(peerPassword, peerSaltB64)
        val (peerVerify, peerVerifyIv) = CryptoEngine.makeVerify(peerKey)
        val peerItems = ItemCipher.encryptEntries(
            ShadowVaultGenerator.generate(legacy.second),
            peerKey,
            VaultMeta.SCHEMA_V2, // legacy 数据是 CBC 格式，搬运零变换
        )
        peerKey.fill(0)

        // 双槽位原子提交：v1 数据搬运、占位影子建立与 legacy 清除在**同一次** edit
        // 事务内生效——中途死进程不会留下「legacy 已删、v2 未写全」的半迁移状态
        // （那会导致老用户数据看起来凭空消失）。
        store.commitSlots {
            write(
                real,
                meta = legacy.first.copy(
                    schemaVersion = VaultMeta.SCHEMA_V2,
                    auxSaltB64 = "",
                    auxSecretEnc = "",
                    auxSecretIv = "",
                ),
                items = legacy.second,
            )
            write(
                peer,
                meta = VaultMeta(
                    saltB64 = peerSaltB64,
                    verifyB64 = peerVerify,
                    verifyIvB64 = peerVerifyIv,
                    pwdMode = legacy.first.pwdMode,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    schemaVersion = VaultMeta.SCHEMA_V2,
                ),
                items = peerItems,
            )
            dropLegacy()
        }
    }

    /**
     * 首次初始化：**同时**建立两个槽位。
     * - [master] 落在随机选中的真库槽位
     * - 另一槽位用 256 位随机密码（生成即弃，无人知晓）加密影子数据，作为占位
     *
     * 于是"未设置应急密码"与"已设置应急密码"在磁盘上完全同构 —— 这是
     * 影子保险库不可证伪性的地基。
     */
    suspend fun initVault(master: String, mode: String) {
        require(master.length >= CryptoEngine.MASTER_MIN_LEN) { "主密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位" }

        val real = Slot.random()
        val peer = real.other()
        val now = System.currentTimeMillis()

        val realSaltB64 = CryptoEngine.randomSaltB64()
        val realKey = CryptoEngine.deriveKeyB64(master, realSaltB64)
        val (realVerify, realVerifyIv) = CryptoEngine.makeVerify(realKey)

        val peerPassword = CryptoEngine.randomHex(32)
        val peerSaltB64 = CryptoEngine.randomSaltB64()
        val peerKey = CryptoEngine.deriveKeyB64(peerPassword, peerSaltB64)
        val (peerVerify, peerVerifyIv) = CryptoEngine.makeVerify(peerKey)

        // 真库此刻为空 → 影子也为空；新库从建立起就是 GCM 格式（无历史包袱）
        val peerItems = ItemCipher.encryptEntries(
            ShadowVaultGenerator.generate(emptyList()),
            peerKey,
            VaultMeta.SCHEMA_V3,
        )
        val auxReal = CryptoEngine.aesEncrypt(peerPassword, realKey)
        val auxPeer = CryptoEngine.aesEncrypt(CryptoEngine.randomHex(32), peerKey) // 诱饵
        peerKey.fill(0)

        val realMeta = VaultMeta(
            saltB64 = realSaltB64,
            verifyB64 = realVerify,
            verifyIvB64 = realVerifyIv,
            pwdMode = mode,
            createdAt = now,
            updatedAt = now,
            schemaVersion = VaultMeta.SCHEMA_V3,
            auxSaltB64 = peerSaltB64,
            auxSecretEnc = auxReal.dataB64,
            auxSecretIv = auxReal.ivB64,
        )
        val peerMeta = VaultMeta(
            saltB64 = peerSaltB64,
            verifyB64 = peerVerify,
            verifyIvB64 = peerVerifyIv,
            pwdMode = mode,
            createdAt = now, // 与真库槽位同值：不允许"更早的是真库"成为规律
            updatedAt = now,
            schemaVersion = VaultMeta.SCHEMA_V3,
            auxSaltB64 = realSaltB64,
            auxSecretEnc = auxPeer.dataB64,
            auxSecretIv = auxPeer.ivB64,
        )

        // 双槽位原子提交：清场（含 legacy 残留）与四个 key 的写入在**同一次** edit
        // 事务内生效——中途进程死亡只会留下「全部旧值」或「全部新值」，不存在
        // 「真库已写、影子未建」的半初始化状态（P0 失效窗口②的根治）。
        store.commitSlots {
            resetAll() // 清掉可能的残留，保证两个槽位从零开始同生同构
            dropLegacy()
            write(real, meta = realMeta, items = emptyList())
            write(peer, meta = peerMeta, items = peerItems)
        }

        SessionManager.establish(realKey, real)
    }

    /**
     * 用主密码或应急密码解锁。
     *
     * **恒定时间要求**：两路派生并发执行，且必须都 [kotlinx.coroutines.Deferred.await]
     * 完成才判定结果。绝不能"先试 A、成功就返回" —— 那样真密码耗时 1 次派生、
     * 应急密码耗时 2 次，掐表即可分辨，duress 当场破功。
     */
    suspend fun unlock(master: String): UnlockResult = coroutineScope {
        val jobA = async(Dispatchers.Default) { tryUnlock(Slot.A, master) }
        val jobB = async(Dispatchers.Default) { tryUnlock(Slot.B, master) }
        val a = jobA.await() // 不得短路：两路都必须跑完
        val b = jobB.await()
        val keyA = a.key
        val keyB = b.key
        val hit: Pair<Slot, ByteArray>? = if (keyA != null && keyB != null) {
            // 两槽位被同一个密码解开 = 主密码与应急密码相同（异常状态）。
            // 必须挑出主库槽位：若固定选 A，真库在 B 时用户就被送进影子库，
            // 之后 isPrimary() 恒 false，改密码 / 设应急密码等操作会全部被拒。
            // 该分支只有异常数据才会走进；正常数据两槽位密码必然不同（见两处同密校验）。
            val aIsPrimary = withContext(Dispatchers.Default) { isPrimarySlot(Slot.A, keyA) }
            if (aIsPrimary) Slot.A to keyA else Slot.B to keyB
        } else if (keyA != null) {
            Slot.A to keyA
        } else if (keyB != null) {
            Slot.B to keyB
        } else {
            null
        }
        val target = hit ?: run {
            // 两路皆空：区分密码错误与设备因素失效（信封启用后的 TEE 失效走降级流程）
            return@coroutineScope if (a.deviceFactorFailed || b.deviceFactorFailed) {
                UnlockResult.KeyUnavailable
            } else {
                UnlockResult.WrongPassword
            }
        }
        // 补建必须在 establish **之前**完成：establish 后 UI 立即开始读库
        // （needsFormatMigration → isPrimaryCached），若 aux 尚未落盘（导入后
        // 首次解锁的场景），瞬态 false 会被会话缓存污染，此后改密/设应急密码/
        // 启用信封等主库操作全部静默失效——「导入后变成影子库」的根因。
        // 补建完成后再建立会话，竞态窗口不复存在。
        withContext(Dispatchers.Default) {
            ensureAuxProvisioned(target.first, target.second)
        }
        SessionManager.establish(target.second, target.first)
        primaryCache = null
        // 心跳（#43 死信开关）：任何成功解锁 = 活人证明，自动续期
        store.markHeirBeat(System.currentTimeMillis())
        // 检索引擎懒加载（首次解锁时装载 ~24MB 模型）+ 索引后台重建：
        // 走独立 scope，**不阻塞 unlock 返回**；失败自动降级 Noop（纯关键词检索）
        ensureSemanticEngineAsync()
        UnlockResult.Success
    }

    /** 检索后台任务 scope：SupervisorJob，任务失败不影响其它任务与解锁主链路 */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun ensureSemanticEngineAsync() {
        Log.d("PenlySearch", "解锁成功，开始懒加载检索引擎")
        searchScope.launch {
            if (!embedder.isReady) {
                val t0 = System.currentTimeMillis()
                val created = EmbedderFactory.create(store.appContext)
                if (created.isReady) {
                    embedder.close()
                    embedder = created
                    Log.d("PenlySearch", "Embedder 就绪：bge-small-zh-v1.5(int8)，装载耗时=${System.currentTimeMillis() - t0}ms")
                } else {
                    Log.w("PenlySearch", "Embedder 装载失败，降级为纯关键词检索")
                }
            }
            if (embedder.isReady) rebuildSearchIndexInternal()
        }
    }

    /**
     * 单槽位解锁尝试结果：[key] 非 null 即解开；[deviceFactorFailed] 标记该槽位
     * 信封的 TEE 层失效（密码因素成立但设备密钥不可用）——两槽位任一标记即触发
     * 解锁降级路径。
     */
    private class TryOutcome(val key: ByteArray?, val deviceFactorFailed: Boolean = false)

    /**
     * 尝试用 [master] 解开 [slot]。
     *
     * 双格式分派（v5.0 #30）：
     * - 信封存在 → **唯一门禁 = [KeystoreEnvelope.unseal]**（Argon2id 64MiB + TEE 双因素）。
     *   PBKDF2 verify 链此时退为槽位间内部校验，不再参与解锁判定——否则双锁并存、
     *   弱锁照用，信封的离线防护形同虚设。
     * - 信封缺失（未启用/存量库）→ 原 PBKDF2 + verifyMaster 链，行为与 v4 完全一致。
     * 空槽位保持完整派生形状（信封启用后两槽位必然同构，该分支只在未启用时可达）。
     */
    private suspend fun tryUnlock(slot: Slot, master: String): TryOutcome {
        val env = store.readEnvelope(slot)
        if (env != null) {
            return try {
                TryOutcome(KeystoreEnvelope.unseal(keyWrapper, master.toByteArray(Charsets.UTF_8), env))
            } catch (e: WrongPasswordException) {
                TryOutcome(null)
            } catch (e: KeyUnavailableException) {
                TryOutcome(null, deviceFactorFailed = true)
            }
        }
        val m = store.readMeta(slot)
        val saltB64 = m?.saltB64 ?: DUMMY_SALT_B64
        val key = CryptoEngine.deriveKeyB64(master, saltB64)
        val ok = m != null && CryptoEngine.verifyMaster(key, m.verifyB64, m.verifyIvB64)
        if (ok) return TryOutcome(key)
        key.fill(0)
        return TryOutcome(null)
    }

    /**
     * 仅校验密码是否正确（主密码、应急密码皆可），**不改变当前会话**。
     *
     * 用于"设置页验证一次主密码"这类场景：若改用 [unlock]，输错时会把会话切到另一槽位。
     * 同样要求恒定时间 —— 校验结果本身也在泄露"这个密码是不是应急密码"。
     */
    suspend fun verifyPassword(master: String): Boolean = coroutineScope {
        val jobA = async(Dispatchers.Default) { tryUnlock(Slot.A, master) }
        val jobB = async(Dispatchers.Default) { tryUnlock(Slot.B, master) }
        val keyA = jobA.await() // 不得短路
        val keyB = jobB.await()
        val ok = keyA.key != null || keyB.key != null
        keyA.key?.fill(0)
        keyB.key?.fill(0)
        ok
    }

    /**
     * 校验 [master] 是不是「当前已解锁金库」（会话槽位）的密码。
     *
     * 与 [verifyPassword]（任一槽位匹配即过）不同：只认当前会话金库自己的密码——
     * 指纹解锁缓存副本的场景必须用它，保证「指纹永远只打开当前正在看的这份」，
     * 在主库会话输应急密码会被拒（应急密码开的是另一槽位），反之亦然。
     */
    suspend fun verifyCurrentVaultPassword(master: String): Boolean {
        val slot = SessionManager.activeSlotOrNull() ?: return false
        // 信封启用时本函数走 unseal 链；TEE 中途失效的边缘态按「验证失败」处理
        //（此时会话本身早已建立过，设备因素已被证明，极小概率不必向上传播）
        val outcome = withContext(Dispatchers.Default) { tryUnlock(slot, master) }
        val ok = outcome.key != null
        outcome.key?.fill(0)
        return ok
    }

    /** default 模式一键解锁（内置默认主密码）；default 库启用信封后同样可能 KeyUnavailable */
    suspend fun unlockDefault(): UnlockResult {
        if (requiresPassword()) return UnlockResult.WrongPassword
        return unlock(CryptoEngine.ANDROID_DEFAULT_MASTER)
    }

    // ---------------- TEE 信封（v5.0 #30：硬件级保护，显式启用） ----------------

    /** 当前会话槽位是否已启用信封（启用时两槽位成对写入，正常态两边一致） */
    suspend fun envelopeEnabled(): Boolean {
        val slot = SessionManager.activeSlotOrNull() ?: return false
        return store.readEnvelope(slot) != null
    }

    /** 最近一次成功导出备份的时间戳；0 = 从未导出（备份前置检查依据） */
    suspend fun lastExportAt(): Long = store.readLastExportAt()

    /** 导出成功后调用，供「启用信封前必须已有备份」前置检查 */
    suspend fun markExported() = store.setLastExportAt(System.currentTimeMillis())

    /**
     * 启用 TEE 信封（显式一次性动作，仅主库会话）：
     * 1. [verifyCurrentVaultPassword] 门禁（此刻信封未启用，走 PBKDF2 旧链验证）；
     * 2. 真库信封 = seal(主密码, 会话 key32)；影子信封 = seal(aux 秘密, 影子 key32)——
     *    aux 秘密即应急密码（或无人知晓的占位随机密码），因此应急密码解锁影子
     *    同样获得双因素防护，两槽位磁盘结构保持同构（不可证伪性不破）；
     * 3. [VaultStore.commitSlots] 原子写入双槽位信封——中途任何失败，磁盘保持
     *    「未启用」原状，下次重来即可。
     *
     * ⚠️ 启用即生效的失效语义（UI 必须前置告知）：换机/恢复出厂 → TEE wrapKey 销毁 →
     * 信封永久不可解 → 唯一出路 = 备份恢复。这正是启用前强制「已导出备份」检查的原因。
     */
    suspend fun enableEnvelope(master: String): String? = withContext(Dispatchers.Default) {
        if (!isPrimaryCached()) return@withContext null // 影子会话静默 noop（中性，不暴露影子库）
        val slot = SessionManager.activeSlotOrNull() ?: return@withContext "印迹未解锁"
        val key32 = SessionManager.requireKey()
        if (!verifyCurrentVaultPassword(master)) return@withContext "主密码错误"
        val m = store.readMeta(slot) ?: return@withContext "印迹尚未初始化"
        val peerMeta = store.readMeta(slot.other()) ?: return@withContext "印迹尚未初始化"

        val auxSecret = readAuxSecret(m, key32) ?: return@withContext "辅助凭证缺失，无法启用"
        val peerKey = CryptoEngine.deriveKeyB64(auxSecret, peerMeta.saltB64)
        val envReal = KeystoreEnvelope.seal(keyWrapper, master.toByteArray(Charsets.UTF_8), key32)
        val envPeer = KeystoreEnvelope.seal(keyWrapper, auxSecret.toByteArray(Charsets.UTF_8), peerKey)
        peerKey.fill(0)

        store.commitSlots {
            write(slot, envelope = envReal)
            write(slot.other(), envelope = envPeer)
        }
        null
    }

    /**
     * 关闭信封：门禁回落 PBKDF2 旧链。key32 与条目数据零影响（key 从未变过）。
     * 仅主库会话；双槽位同事务删除，保持结构对称。
     */
    suspend fun disableEnvelope(): String? = withContext(Dispatchers.Default) {
        if (!isPrimaryCached()) return@withContext null
        val slot = SessionManager.activeSlotOrNull() ?: return@withContext "印迹未解锁"
        store.commitSlots {
            dropEnvelope(slot)
            dropEnvelope(slot.other())
        }
        null
    }

    /** Autofill 匹配索引条目：仅含非敏感明文（标题），不含任何密文字段 */
    data class AutofillMatch(val itemId: String, val title: String)

    /**
     * Autofill 锁定态匹配索引（v3.0 项目⑤）：**无需解锁**——直接读双槽位原始存储，
     * 按 [packageName] 过滤条目的 appPackage（明文字段），返回命中条目的 id+标题。
     *
     * 设计要点：
     * - 匹配发生在解锁之前，服务据此决定"弹验证卡片 / 静默"（用户预期：没存过就不提示）
     * - 只暴露标题与条目 id——账号/密码/TOTP 均为密文，锁定态不可见
     * - 影子槽位的诱饵条目 appPackage 为空串，永不匹配真实包名，无影子泄露路径
     * - 手动创建的条目 appPackage 为空串 → 不参与包名匹配（V2 再做关键词/域名匹配）
     */
    suspend fun autofillMatchIndex(packageName: String): List<AutofillMatch> {
        if (packageName.isBlank()) return emptyList()
        val out = mutableListOf<AutofillMatch>()
        for (slot in listOf(Slot.A, Slot.B)) {
            for (item in store.readItems(slot)) {
                if (item.appPackage == packageName) {
                    out.add(AutofillMatch(item.id, item.title))
                }
            }
        }
        return out
    }

    fun lock() {
        searchIndex.clear() // 锁定即销毁：向量从不落盘，清空内存即可，无残留风险
        formatCache = null
        SessionManager.lock()
    }

    // ---------------- 影子保险库 ----------------

    /**
     * 当前会话是否位于**主槽位**（aux 秘密能真正打开另一槽位）。
     *
     * 判断方式：取出本槽位的 aux 秘密去验证另一槽位的校验串。
     * 影子槽位的 aux 是诱饵，必然验证失败。
     *
     * 该判断**只在内存中进行**，不写入任何持久化介质 —— 一旦落盘，
     * "哪个槽位是真库"这一秘密即告泄露。
     *
     * ⚠️ 仅可用于"是否允许某项操作"的门禁判断。**严禁用于向用户展示状态**
     * （例如"你当前位于影子库"），那等于当面告诉胁迫者。
     */
    suspend fun isPrimary(): Boolean {
        val slot = SessionManager.activeSlotOrNull() ?: return false
        return isPrimarySlot(slot, SessionManager.requireKey())
    }

    /**
     * [isPrimary] 的底层实现：给定槽位及其密钥，判断该槽位是否为主库。
     *
     * 与 [isPrimary] 的区别是不从 [SessionManager] 取槽位与密钥——解锁判定途中
     * 会话尚未建立，同样需要判断"这个槽位是不是主库"。
     */
    private suspend fun isPrimarySlot(slot: Slot, key: ByteArray): Boolean {
        val m = store.readMeta(slot) ?: return false
        val peerMeta = store.readMeta(slot.other()) ?: return false
        val aux = readAuxSecret(m, key) ?: return false
        val peerKey = CryptoEngine.deriveKeyB64(aux, peerMeta.saltB64)
        val ok = CryptoEngine.verifyMaster(peerKey, peerMeta.verifyB64, peerMeta.verifyIvB64)
        peerKey.fill(0)
        return ok
    }

    /**
     * [isPrimary] 的会话级缓存。
     *
     * 每次判断都要多跑一次 PBKDF2（100k），而槽位在会话期间不会变，
     * 因此算一次即可 —— 否则每次保存条目都要白付 100ms 级开销。
     * 只驻内存；会话一结束（[SessionManager.isUnlocked] 为 false）立即失效。
     */
    @Volatile
    private var primaryCache: Boolean? = null

    /**
     * 当前会话的条目加密格式（会话级缓存，惰性从槽位 meta 读取）。
     * 读/写路径据此分派 CBC（V2）/ GCM（V3）；迁移、导入重建后主动刷新。
     * 只驻内存，[lock] 即清——与新会话的磁盘格式天然重新对齐。
     */
    @Volatile
    private var formatCache: Int? = null

    private suspend fun currentFormat(): Int {
        formatCache?.let { return it }
        val fmt = SessionManager.activeSlotOrNull()
            ?.let { store.readMeta(it)?.schemaVersion }
            ?: VaultMeta.SCHEMA_V1
        formatCache = fmt
        return fmt
    }

    // ---- 端内 AI 检索：索引纯内存，不落盘（见《印迹_端内AI检索_技术方案.md》§3.2）----
    private val searchIndex = SearchIndex()

    /** 向量化实现；二期接入端侧模型时替换，索引无需任何迁移 */
    @Volatile
    private var embedder: Embedder = NoopEmbedder

    private suspend fun isPrimaryCached(): Boolean {
        if (!SessionManager.isUnlocked()) {
            primaryCache = null
            return false
        }
        primaryCache?.let { return it }
        val v = isPrimary()
        // false 有两种语义：影子会话（真 false，可缓存至本会话结束）与 aux 未就绪
        // （导入后等补建的**瞬态** false——不可缓存，否则补建完成后仍被旧值卡住，
        // 主库操作全部静默失效）。仅当 aux 已就绪时才允许缓存 false。
        if (v) {
            primaryCache = true
        } else {
            val m = SessionManager.activeSlotOrNull()?.let { store.readMeta(it) }
            if (m != null && m.auxSecretEnc.isNotBlank()) primaryCache = false
        }
        return v
    }

    /**
     * 设置 / 重设应急密码（只能在主槽位会话中调用）。
     *
     * 只替换另一槽位的密钥与密文，**保留其 [VaultMeta.createdAt]**
     * —— 否则"设置应急密码"这一动作会在磁盘上留下时间戳痕迹，
     * 使"影子库是后来才建的"被推断出来。
     */
    suspend fun setDuressPassword(duress: String): String? =
        withContext(Dispatchers.Default) { setDuressPasswordInternal(duress) }

    private suspend fun setDuressPasswordInternal(duress: String): String? {
        if (duress.length < CryptoEngine.MASTER_MIN_LEN) {
            return "应急密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位"
        }
        if (!isPrimaryCached()) return null // 影子会话静默 noop：不写任何槽位，返回成功以避免暴露当前是影子库

        val slot = SessionManager.activeSlotOrNull() ?: return "印迹未解锁"
        val peer = slot.other()
        val m = store.readMeta(slot) ?: return "印迹尚未初始化"
        val key = SessionManager.requireKey()

        // 应急密码不得与主密码相同：一旦相同，两个槽位会被同一密码同时解开，
        // 解锁时无法凭密码区分主次，可能把用户送进影子库（详见 unlock 的同密码分支）。
        // 判据：用主库 salt 派生应急密码，若结果与当前会话密钥一致，则两者是同一密码。
        val duressKey = CryptoEngine.deriveKeyB64(duress, m.saltB64)
        val sameAsMaster = duressKey.contentEquals(key)
        duressKey.fill(0)
        if (sameAsMaster) return "应急密码不能与主密码相同"

        val oldPeerMeta = store.readMeta(peer)
        val fmt = currentFormat() // 影子条目格式与真库保持一致

        val newSaltB64 = CryptoEngine.randomSaltB64()
        val newKey = CryptoEngine.deriveKeyB64(duress, newSaltB64)
        val (newVerify, newVerifyIv) = CryptoEngine.makeVerify(newKey)
        val shadowItems = ItemCipher.encryptEntries(
            ShadowVaultGenerator.generate(store.readItems(slot)),
            newKey,
            fmt,
        )
        val auxPeer = CryptoEngine.aesEncrypt(CryptoEngine.randomHex(32), newKey) // 诱饵
        val now = System.currentTimeMillis()

        val auxReal = CryptoEngine.aesEncrypt(duress, key)

        // 双槽位原子提交：影子重建（items+meta）与真库 aux 凭证写入在**同一次** edit
        // 事务内生效——中途死进程不会留下「影子已重建、真库 aux 仍指旧 salt」的
        // 断链半状态（P0 失效窗口②的根治，链断即静默门禁自锁）。
        store.commitSlots {
            write(
                peer,
                meta = VaultMeta(
                    saltB64 = newSaltB64,
                    verifyB64 = newVerify,
                    verifyIvB64 = newVerifyIv,
                    pwdMode = m.pwdMode, // 与主槽位保持一致，避免影子库停留在旧模式
                    createdAt = oldPeerMeta?.createdAt ?: now, // 保持：假装它一直存在
                    updatedAt = oldPeerMeta?.updatedAt ?: now, // 冻结：设应急密码不得在磁盘留时间戳痕迹（否则 updatedAt>createdAt 即暴露"已设应急"）
                    schemaVersion = fmt,
                    auxSaltB64 = m.saltB64,
                    auxSecretEnc = auxPeer.dataB64,
                    auxSecretIv = auxPeer.ivB64,
                ),
                items = shadowItems,
            )
            write(
                slot,
                meta = m.copy(
                    auxSaltB64 = newSaltB64,
                    auxSecretEnc = auxReal.dataB64,
                    auxSecretIv = auxReal.ivB64,
                ),
            )
        }
        newKey.fill(0)
        return null
    }

    /**
     * 主库条目数变化超过阈值时，自动重生成影子数据。
     *
     * 必须自动：影子数据若长期静止，攻击者对比两次磁盘快照即可识别"哪个槽位在变"。
     */
    private suspend fun syncShadowIfNeeded() {
        val slot = SessionManager.activeSlotOrNull() ?: return
        if (!isPrimaryCached()) return // 影子库会话不得回写真库
        val peer = slot.other()
        val peerMeta = store.readMeta(peer) ?: return
        val realItems = store.readItems(slot)
        if (!ShadowVaultGenerator.needsRegen(realItems.size, store.readItems(peer).size)) return

        val m = store.readMeta(slot) ?: return
        val aux = readAuxSecret(m, SessionManager.requireKey()) ?: return
        val peerKey = CryptoEngine.deriveKeyB64(aux, peerMeta.saltB64)
        store.writeItems(
            peer,
            ItemCipher.encryptEntries(ShadowVaultGenerator.generate(realItems), peerKey, currentFormat()),
        )
        peerKey.fill(0)
    }

    /**
     * 补齐 aux 凭证：老数据迁移或备份导入后，落地时不知道主密码，aux 字段为空；
     * 首次解锁拿到密钥后在此补建另一槽位的占位数据与凭证。
     */
    private suspend fun ensureAuxProvisioned(slot: Slot, key: ByteArray) {
        val m = store.readMeta(slot) ?: return
        if (m.auxSecretEnc.isNotBlank()) return
        val peer = slot.other()
        val now = System.currentTimeMillis()
        val createdAt = m.createdAt.takeIf { it > 0 } ?: now

        val peerPassword = CryptoEngine.randomHex(32)
        val peerSaltB64 = CryptoEngine.randomSaltB64()
        val peerKey = CryptoEngine.deriveKeyB64(peerPassword, peerSaltB64)
        val (peerVerify, peerVerifyIv) = CryptoEngine.makeVerify(peerKey)
        val peerItems = ItemCipher.encryptEntries(
            ShadowVaultGenerator.generate(store.readItems(slot)),
            peerKey,
            m.schemaVersion, // 占位影子与真库格式一致
        )
        val auxPeer = CryptoEngine.aesEncrypt(CryptoEngine.randomHex(32), peerKey) // 诱饵
        peerKey.fill(0)
        val auxReal = CryptoEngine.aesEncrypt(peerPassword, key)

        // 双槽位原子提交：占位影子建立（items+meta）与真库 aux 凭证写入在**同一次**
        // edit 事务内生效。原实现靠 commit-last 自愈（auxSecretEnc 最后写、空则下轮
        // 重跑），原子化后无中途态，自愈语义自然升级为「全或无」。
        store.commitSlots {
            write(
                peer,
                meta = VaultMeta(
                    saltB64 = peerSaltB64,
                    verifyB64 = peerVerify,
                    verifyIvB64 = peerVerifyIv,
                    pwdMode = m.pwdMode,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    schemaVersion = m.schemaVersion,
                    auxSaltB64 = m.saltB64,
                    auxSecretEnc = auxPeer.dataB64,
                    auxSecretIv = auxPeer.ivB64,
                ),
                items = peerItems,
            )
            write(
                slot,
                meta = m.copy(
                    auxSaltB64 = peerSaltB64,
                    auxSecretEnc = auxReal.dataB64,
                    auxSecretIv = auxReal.ivB64,
                ),
            )
        }
    }

    private fun readAuxSecret(meta: VaultMeta, key: ByteArray): String? {
        if (meta.auxSecretEnc.isBlank()) return null
        return try {
            CryptoEngine.aesDecrypt(
                CryptoEngine.EncPayload(meta.auxSecretIv, meta.auxSecretEnc),
                key,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- 端内 AI 检索（一期：纯内存索引，不落盘） ----------------

    /**
     * 重建**当前解锁槽位**的检索索引。
     *
     * 只索引当前槽位——真库与影子库各自独立，严禁跨槽位混合，
     * 否则影子会话会读到真库语义，直接破坏不可证伪性（方案 §4）。
     * 影子库会话同样会走到这里，索引的是影子自己的条目，行为与真库一致。
     */
    suspend fun rebuildSearchIndex() = withContext(Dispatchers.Default) {
        if (!embedder.isReady) return@withContext
        searchIndex.clear()
        rebuildSearchIndexInternal()
    }

    private suspend fun rebuildSearchIndexInternal() {
        val slot = SessionManager.activeSlotOrNull() ?: return
        val entries = ArrayList<SearchEntry>()
        for (item in store.readItems(slot)) entries.add(item.toSearchEntry())
        val t0 = System.currentTimeMillis()
        val vectors = embedder.embedAll(entries.map { it.embedText })
        entries.forEachIndexed { i, e -> searchIndex.put(e, vectors.getOrNull(i)) }
        // 只打计数与耗时，不落任何条目内容
        Log.d(
            "PenlySearch",
            "检索索引已重建：slot=${if (slot == Slot.A) "A" else "B"}，" +
                "条目=${entries.size}，向量缺失=${vectors.count { it == null }}，" +
                "耗时=${System.currentTimeMillis() - t0}ms",
        )
    }

    /** 条目变更后增量更新索引；索引尚未建立则跳过（下次解锁会全量重建） */
    private suspend fun updateSearchIndex(itemId: String) {
        if (!embedder.isReady || searchIndex.size == 0) return
        val slot = SessionManager.activeSlotOrNull() ?: return
        val item = store.readItems(slot).firstOrNull { it.id == itemId }
        if (item == null) {
            searchIndex.remove(itemId)
            return
        }
        val entry = item.toSearchEntry()
        if (searchIndex.contains(itemId) && searchIndex.isUpToDate(entry)) return
        searchIndex.put(entry, embedder.embed(entry.embedText))
    }

    /** 语义检索：语义命中优先 + 关键词补充；未启用语义时自动降级为纯关键词 */
    suspend fun smartSearch(query: String): SearchOutcome =
        SmartSearcher(embedder, searchIndex).search(query)

    /** 语义检索是否可用（UI 据此决定是否展示信任标签与「为什么匹配」） */
    fun isSemanticReady(): Boolean = embedder.isReady && searchIndex.hasVectors

    /**
     * 替换向量化实现（二期接入端侧模型 / 更换模型时调用）。
     *
     * 由于索引不落盘，换模型**不需要任何数据迁移**——清空后下次解锁即按新模型重算。
     */
    fun setEmbedder(newEmbedder: Embedder) {
        embedder.close()
        embedder = newEmbedder
        searchIndex.clear()
    }

    /**
     * 构造待索引条目。
     *
     * `secret` **明确不入索引**：密码本身没有检索语义，入索引只会扩大敏感面（方案 §3.1）。
     */
    private suspend fun VaultItem.toSearchEntry(): SearchEntry {
        // 逐条降级（review C2）：单条解密/验签失败只影响该条索引，不让后台重建崩掉
        val key = SessionManager.requireKey()
        val fmt = currentFormat()
        val account = if (accountEnc.isBlank()) "" else runCatching {
            ItemCipher.decField(fmt, ItemCipher.F_ACCOUNT, id, accountEnc, accountIv, accountMac, key)
        }.getOrDefault("")
        val note = if (noteEnc.isBlank()) "" else runCatching {
            ItemCipher.decField(fmt, ItemCipher.F_NOTE, id, noteEnc, noteIv, noteMac, key)
        }.getOrDefault("")
        return SearchEntry(
            itemId = id,
            keywordText = listOf(title, category, account).joinToString(" ").lowercase(),
            embedText = buildEmbedText(title, category, account, note),
        )
    }

    /** 嵌入文本：结构化短句；模板与字段权重需用中文语料实测标定（方案 §3.1） */
    private fun buildEmbedText(title: String, category: String, account: String, note: String): String =
        buildString {
            append("标题：").append(title)
            append("\n分类：").append(category.ifBlank { "默认" })
            if (account.isNotBlank()) append("\n账号：").append(account)
            if (note.isNotBlank()) append("\n备注：").append(note)
        }

    // ---------------- 条目 CRUD ----------------

    suspend fun items(): List<VaultItem> =
        store.readItems(SessionManager.requireSlot()).sortedByDescending { it.updatedAt }

    suspend fun item(id: String): VaultItem? =
        store.readItems(SessionManager.requireSlot()).firstOrNull { it.id == id }

    /**
     * 解密条目；未解锁抛 [com.beyondguo.penly.crypto.VaultLockedException]。
     * 按当前会话格式分派（V2=CBC+MAC / V3=GCM，见 [ItemCipher]）；完整性失败
     * 统一抛 MacVerificationException——展示面由调用方逐条降级，写回面 fail-closed。
     */
    suspend fun decryptItem(item: VaultItem): PlainEntry =
        ItemCipher.decryptItem(item, SessionManager.requireKey(), currentFormat())

    /** 仅解密账号字段（列表副标题用，比整条解密轻量）；完整性语义同 [decryptItem] */
    suspend fun decryptAccount(item: VaultItem): String {
        if (item.accountEnc.isBlank()) return ""
        return ItemCipher.decField(
            currentFormat(),
            ItemCipher.F_ACCOUNT,
            item.id,
            item.accountEnc,
            item.accountIv,
            item.accountMac,
            SessionManager.requireKey(),
        )
    }

    /** 新增或更新（title/category 明文索引，account/secret/note/totp 加密；totp 参数非敏感明文存储） */
    suspend fun saveEntry(
        id: String?,
        title: String,
        category: String,
        account: String,
        secret: String,
        note: String,
        totpSecret: String = "",
        totpDigits: Int = 0,
        totpPeriod: Int = 0,
        totpAlgo: String = "",
        appPackage: String = "",
    ): String {
        val key = SessionManager.requireKey()
        val slot = SessionManager.requireSlot()
        val now = System.currentTimeMillis()
        val old = id?.let { item(it) }
        val itemId = old?.id ?: CryptoEngine.genId()
        // 字段加密按当前会话格式分派（V2=CBC+MAC / V3=GCM）；GCM 的 AAD 绑定条目 id，
        // 所以必须先定 id 再加密
        val fmt = currentFormat()
        val a = ItemCipher.encField(fmt, ItemCipher.F_ACCOUNT, itemId, account, key)
        val s = ItemCipher.encField(fmt, ItemCipher.F_SECRET, itemId, secret, key)
        val n = ItemCipher.encField(fmt, ItemCipher.F_NOTE, itemId, note, key)
        val t = ItemCipher.encField(fmt, ItemCipher.F_TOTP, itemId, totpSecret, key)
        val newItem = VaultItem(
            id = itemId,
            title = title.trim(),
            category = category.trim().ifBlank { "默认" },
            accountEnc = a.dataB64, accountIv = a.ivB64, accountMac = a.macB64,
            secretEnc = s.dataB64, secretIv = s.ivB64, secretMac = s.macB64,
            noteEnc = n.dataB64, noteIv = n.ivB64, noteMac = n.macB64,
            totpEnc = t.dataB64, totpIv = t.ivB64, totpMac = t.macB64,
            totpDigits = totpDigits, totpPeriod = totpPeriod,
            totpAlgo = totpAlgo,
            appPackage = appPackage,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        store.upsertItem(slot, newItem)
        // 影子同步含 PBKDF2，必须离开主线程；且只在条目数差异越阈时才真的动手
        withContext(Dispatchers.Default) {
            syncShadowIfNeeded()
            updateSearchIndex(newItem.id)
        }
        return newItem.id
    }

    suspend fun deleteItem(id: String) {
        store.deleteItem(SessionManager.requireSlot(), id)
        withContext(Dispatchers.Default) {
            syncShadowIfNeeded()
            updateSearchIndex(id)
        }
    }

    // ---------------- Passkey（v5.0-②） ----------------

    /** Passkey 条目的界面/Provider 视图（不含私钥——签名时按 id 现解） */
    data class PasskeyInfo(
        val id: String,
        val rpId: String,
        val rpName: String,
        val userName: String,
        val credIdB64: String,
        val userHandleB64: String,
        val signCount: Int,
        val createdAt: Long,
    )

    /**
     * 保存 passkey 条目（注册流程由 PasskeyActivity 调入，金库必须已解锁）。
     * 私钥（PKCS8 DER base64）走 passkey 字段加密，与账密同待遇——换机/备份恢复
     * 后随金库回来（2026-09-19 拍板：软件密钥对，不硬件绑定）。
     * rpId/credId/userHandle 明文：解锁后 begin 后续阶段按 rpId 过滤无需解密。
     */
    suspend fun savePasskey(
        rpId: String,
        rpName: String,
        userName: String,
        userHandleB64: String,
        credIdB64: String,
        privPkcs8B64: String,
    ): String {
        val key = SessionManager.requireKey()
        val slot = SessionManager.requireSlot()
        val fmt = currentFormat()
        val itemId = CryptoEngine.genId()
        val p = ItemCipher.encField(fmt, ItemCipher.F_PASSKEY, itemId, privPkcs8B64, key)
        val a = ItemCipher.encField(fmt, ItemCipher.F_ACCOUNT, itemId, userName, key)
        val now = System.currentTimeMillis()
        val item = VaultItem(
            id = itemId,
            title = rpName.ifBlank { rpId },
            category = "passkey",
            accountEnc = a.dataB64, accountIv = a.ivB64, accountMac = a.macB64,
            rpId = rpId,
            credIdB64 = credIdB64,
            userHandleB64 = userHandleB64,
            signCount = 0,
            passkeyEnc = p.dataB64, passkeyIv = p.ivB64, passkeyMac = p.macB64,
            createdAt = now,
            updatedAt = now,
        )
        store.upsertItem(slot, item)
        withContext(Dispatchers.Default) { syncShadowIfNeeded() }
        return itemId
    }

    /** 当前槽位的全部 passkey 条目（userName 解密；私钥不解——列表展示用不到） */
    suspend fun listPasskeys(): List<PasskeyInfo> {
        val key = SessionManager.requireKey()
        val slot = SessionManager.requireSlot()
        val fmt = currentFormat()
        return store.readItems(slot)
            .filter { it.rpId.isNotBlank() }
            .map {
                PasskeyInfo(
                    id = it.id,
                    rpId = it.rpId,
                    rpName = it.title,
                    userName = ItemCipher.decField(
                        fmt, ItemCipher.F_ACCOUNT, it.id,
                        it.accountEnc, it.accountIv, it.accountMac, key,
                    ),
                    credIdB64 = it.credIdB64,
                    userHandleB64 = it.userHandleB64,
                    signCount = it.signCount,
                    createdAt = it.createdAt,
                )
            }
    }

    /** 按 rpId 过滤（已解锁态；begin 阶段的泛化 entry 不做任何数据访问） */
    suspend fun passkeysForRpId(rpId: String): List<PasskeyInfo> =
        listPasskeys().filter { it.rpId == rpId }

    /** 取私钥（PKCS8 DER base64）——签名时现解，用后即弃，不缓存 */
    suspend fun passkeyPrivB64(id: String): String? {
        val key = SessionManager.requireKey()
        val slot = SessionManager.requireSlot()
        val fmt = currentFormat()
        val item = store.readItems(slot).firstOrNull { it.id == id } ?: return null
        return ItemCipher.decField(
            fmt, ItemCipher.F_PASSKEY, item.id,
            item.passkeyEnc, item.passkeyIv, item.passkeyMac, key,
        )
    }

    /** 断言成功后回写签名计数（防克隆指标；单键 upsert 原子，不触发影子同步） */
    suspend fun bumpSignCount(id: String, newCount: Int) {
        val slot = SessionManager.requireSlot()
        val item = store.readItems(slot).firstOrNull { it.id == id } ?: return
        store.upsertItem(slot, item.copy(signCount = newCount, updatedAt = System.currentTimeMillis()))
    }

    // ---------------- 修改主密码 / 重置 ----------------

    /**
     * 设置/修改主密码：旧密钥解密全部记录 → 新密钥重加密 → 更新 meta（新 salt + 新校验串）。
     * 只作用于**当前槽位**；aux 秘密用新密钥重新加密，保证影子数据仍可自动同步。
     */
    suspend fun changeMasterPassword(oldPlain: String?, newPlain: String): String? =
        withContext(Dispatchers.Default) {
            // 整体移出 Main：PBKDF2×2（各 10 万次迭代）+ 全量条目重加密 + JSON 编码
            // 都是 CPU 密集工作，评审定案随原子写一并归位（原先整段跑在调用方上下文）
            if (!isPrimaryCached()) return@withContext null // 影子会话静默 noop：既不破坏主↔影关联，也不暴露当前是影子库
            if (newPlain.length < CryptoEngine.MASTER_MIN_LEN) {
                return@withContext "主密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位"
            }
            val slot = SessionManager.activeSlotOrNull() ?: return@withContext "印迹未解锁"
            val m = store.readMeta(slot) ?: return@withContext "印迹尚未初始化"
            val peer = slot.other()
            val oldMaster = oldPlain ?: CryptoEngine.ANDROID_DEFAULT_MASTER
            val oldKey = CryptoEngine.deriveKeyB64(oldMaster, m.saltB64)
            if (!CryptoEngine.verifyMaster(oldKey, m.verifyB64, m.verifyIvB64)) {
                oldKey.fill(0)
                return@withContext "旧主密码错误"
            }
            val auxSecret = readAuxSecret(m, oldKey)
            // 新主密码不得与应急密码相同，理由同上：两槽位同密码会让解锁槽位不可判。
            // 未设置应急密码时 auxSecret 是无人知晓的随机密码，不可能与用户输入相等。
            if (auxSecret != null && auxSecret == newPlain) {
                oldKey.fill(0)
                return@withContext "主密码不能与应急密码相同"
            }

            val newSaltB64 = CryptoEngine.randomSaltB64()
            val newKey = CryptoEngine.deriveKeyB64(newPlain, newSaltB64)
            val (newVerify, newVerifyIv) = CryptoEngine.makeVerify(newKey)
            val fmt = currentFormat() // 改密不换格式：CBC 库改密仍 CBC，GCM 库改密仍 GCM
            val reEnc = try {
                ItemCipher.reEncryptItems(store.readItems(slot), oldKey, newKey, fmt, fmt)
            } catch (e: MacVerificationException) {
                // 有记录被篡改/损坏：整个改密中止，磁盘保持原状（以前这类记录会静默变乱码）
                oldKey.fill(0)
                newKey.fill(0)
                return@withContext e.message
            }
            oldKey.fill(0)
            val ts = System.currentTimeMillis()

            val auxEnc = auxSecret?.let { CryptoEngine.aesEncrypt(it, newKey) }
            // 信封已启用：改密必须同步重封真库信封（同一次原子事务）——否则旧密码
            // 派生的 KEK 仍能解开信封，改密等于没改。影子信封不动（影子密码没变）。
            val envNew = if (store.readEnvelope(slot) != null) {
                KeystoreEnvelope.seal(keyWrapper, newPlain.toByteArray(Charsets.UTF_8), newKey)
            } else {
                null
            }
            val slotMeta = m.copy(
                saltB64 = newSaltB64,
                verifyB64 = newVerify,
                verifyIvB64 = newVerifyIv,
                pwdMode = VaultMeta.MODE_CUSTOM,
                updatedAt = ts,
                auxSecretEnc = auxEnc?.dataB64 ?: m.auxSecretEnc,
                auxSecretIv = auxEnc?.ivB64 ?: m.auxSecretIv,
            )
            // 同步对端 meta 的 pwdMode / updatedAt，使两槽位明文结构保持对称。
            // 否则 changeMaster 后 real.pwdMode=CUSTOM 而 peer 停在旧模式，两槽位明文
            // 不对称会泄露双槽位设计，破坏 §3.4 不可证伪性。
            val peerMeta = store.readMeta(peer)?.copy(
                pwdMode = VaultMeta.MODE_CUSTOM,
                updatedAt = ts,
            )

            // 双槽位原子提交：items 重加密结果、本槽位新 meta（新 salt+新校验串）与
            // 对端 meta 同步在**同一次** edit 事务内生效——中途死进程不会留下
            // 「items 已重加密而 meta（新 salt）未写」的半状态：那会让唯一可用的
            // 密码永久失效、全部数据不可解密（P0 失效窗口①的根治）。
            store.commitSlots {
                write(slot, meta = slotMeta, items = reEnc, envelope = envNew)
                peerMeta?.let { write(peer, meta = it) }
            }
            SessionManager.establish(newKey, slot) // 槽位不变
            return@withContext null
        }

    /**
     * 导入前预检：验证备份密码，确保「解得开才导入」。
     * v1/v2 备份按顶层 version 自动分发（[BackupCodecV2.decodeAny]），两端各有实现：
     * v1 走 MAC 验证（[BackupCodec.verifyPassword]），v2 走 verify 槽位 GCM 验证。
     * 返回 null = 通过，可执行 [importJson]；非 null = 可直接展示的错误文案。
     * 密钥派生（PBKDF2/Argon2id）走 Default 调度，避免阻塞调用方。
     */
    suspend fun verifyBackupPassword(text: String, password: String): String? =
        withContext(Dispatchers.Default) {
            when (
                try {
                    BackupCodecV2.decodeAny(text)
                } catch (e: BackupFormatException) {
                    return@withContext e.message ?: "备份文件无效"
                }
            ) {
                is BackupCodecV2.ParsedBackup.V1 -> BackupCodec.verifyPassword(text, password)
                is BackupCodecV2.ParsedBackup.V2 -> BackupCodecV2.verifyPassword(text, password)
            }
        }

    /** 重置印迹：清空两个槽位（忘记主密码场景） */
    suspend fun resetVault() {
        store.clearAll()
        lock() // 顺带清空检索索引
    }

    // ---------------- 存储格式迁移（CBC → GCM，显式一次性） ----------------

    /**
     * 是否需要条目格式迁移（SCHEMA_V2 的 CBC+MAC → SCHEMA_V3 的 GCM）。
     * 仅主库会话返回 true——影子会话静默跳过（不弹窗、不迁移，避免在胁迫场景
     * 产生重复异常信号；影子库格式由下次主库会话的影子同步自然对齐）。
     */
    suspend fun needsFormatMigration(): Boolean =
        SessionManager.isUnlocked() && isPrimaryCached() &&
            meta()?.schemaVersion == VaultMeta.SCHEMA_V2

    /**
     * 条目格式迁移：CBC+MAC（V2）→ GCM 逐字段（V3）。**密钥全程不变**
     * （key = PBKDF2(主密码, 同一 salt)）——verify 链、解锁流程、指纹缓存、aux
     * 凭证零扰动，这正是 fail-safe 的根基：任何一步失败，磁盘都保持完整 V2 状态，
     * V2 读取链路照常可用，下次进入重新询问。
     *
     * - 真库：会话密钥重加密全部条目（逐条严格验旧 MAC，损坏即中止）；
     * - 影子槽位：aux 链健康时用影子自己的密钥同步升级；aux 缺失时影子整体
     *   保持旧格式（读写自洽，不产生混合格式槽位）；
     * - 双槽位在同一次 commitSlots 事务内落盘，中途 kill 不留半迁移状态。
     *
     * @return null = 成功（或已是新格式 / 影子会话 noop）；非 null = 可展示的错误文案
     */
    suspend fun migrateStorageFormat(): String? = withContext(Dispatchers.Default) {
        val slot = SessionManager.activeSlotOrNull() ?: return@withContext "印迹未解锁"
        if (!isPrimaryCached()) return@withContext null // 影子会话静默 noop（语义同改密）
        val m = store.readMeta(slot) ?: return@withContext "印迹尚未初始化"
        if (m.schemaVersion >= VaultMeta.SCHEMA_V3) return@withContext null
        val key = SessionManager.requireKey()
        val peer = slot.other()

        // 真库：V2 → V3（旧 MAC 逐条验证，任何一条不符即中止，磁盘原状）
        val reEnc = try {
            ItemCipher.reEncryptItems(store.readItems(slot), key, key, m.schemaVersion, VaultMeta.SCHEMA_V3)
        } catch (e: MacVerificationException) {
            return@withContext e.message ?: "存在无法解密的记录，升级已中止，数据未变动"
        }

        // 影子槽位：aux 链健康才同步升级（影子 items 用影子自己的密钥重加密）。
        // 失败文案必须中性——不可提"影子"，否则当面暴露影子库存在。
        val peerMeta = store.readMeta(peer)
        var shadowReEnc: List<VaultItem>? = null
        if (peerMeta != null && peerMeta.schemaVersion < VaultMeta.SCHEMA_V3) {
            val aux = readAuxSecret(m, key)
            if (aux != null) {
                val peerKey = CryptoEngine.deriveKeyB64(aux, peerMeta.saltB64)
                shadowReEnc = try {
                    ItemCipher.reEncryptItems(
                        store.readItems(peer),
                        peerKey,
                        peerKey,
                        peerMeta.schemaVersion,
                        VaultMeta.SCHEMA_V3,
                    )
                } catch (e: MacVerificationException) {
                    peerKey.fill(0)
                    return@withContext e.message ?: "存在无法解密的记录，升级已中止，数据未变动"
                }
                peerKey.fill(0)
            }
        }

        // 双槽位原子提交：格式升级只改 schemaVersion + items 密文，salt/verify/aux 不动
        store.commitSlots {
            write(slot, meta = m.copy(schemaVersion = VaultMeta.SCHEMA_V3), items = reEnc)
            if (shadowReEnc != null && peerMeta != null) {
                write(peer, meta = peerMeta.copy(schemaVersion = VaultMeta.SCHEMA_V3), items = shadowReEnc)
            }
        }
        formatCache = VaultMeta.SCHEMA_V3
        null
    }

    // ---------------- 导出 / 导入 ----------------

    /**
     * 导出为 `private-vault-backup` v2 JSON（契约 v2：Argon2id + AES-256-GCM）。
     * 只导出**当前槽位**的明文条目，由 [BackupCodecV2.encode] 重新加密落盘。
     *
     * 密码来源（契约 v2 的 KDF 是 Argon2id，必须有明文主密码）：
     * - default 模式：用内置默认主密码（masterRef=android-def-v1），无需用户输入；
     * - custom 模式：会话只存派生密钥，明文已丢弃 → 必须由用户**重输**主密码
     *   （[masterPassword]，Bitwarden 同款语义）；导出前先校验，防止「输错密码
     *   导出一份自己都解不开的备份」。
     */
    suspend fun exportJson(masterPassword: String? = null): String {
        val slot = SessionManager.requireSlot()
        val m = store.readMeta(slot) ?: throw IllegalStateException("印迹尚未初始化")
        val isDefault = m.pwdMode == VaultMeta.MODE_DEFAULT
        val password = if (isDefault) {
            CryptoEngine.ANDROID_DEFAULT_MASTER
        } else {
            val typed = masterPassword
                ?: throw IllegalArgumentException("当前为主密码保护，导出需输入主密码")
            if (!verifyCurrentVaultPassword(typed)) {
                throw IllegalArgumentException("主密码错误，未导出任何数据")
            }
            typed
        }
        val entries = store.readItems(slot).map { decryptItem(it) }
        return BackupCodecV2.encode(
            entries = entries,
            masterRef = if (isDefault) CryptoEngine.MASTER_REF_ANDROID else null,
            password = password,
            vaultCreatedAt = m.createdAt,
        )
    }

    /**
     * 遗产交接（#43）：生成遗产恢复包。
     *
     * 包体 = 标准 v2 备份文件（BackupCodecV2 契约完全复用），唯一差异是
     * 加密「密码」为遗产密钥的 hex 形态（64 字符随机串，无人需要记住）、
     * masterRef = null（custom 语义）。恢复方收集 2 份 Shamir 分片重建 L 后，
     * 以 hex(L) 为密码走 [importJson] 即可导入——导入后本地解锁密码即 hex(L)，
     * 受托人界面应立即引导 changeMasterPassword(old=hex(L), new=新主密码) 完成接管。
     *
     * 必须在解锁会话内调用（读取金库全量条目）。
     */
    suspend fun exportLegacyPackage(legacyKeyHex: String): String {
        val slot = SessionManager.requireSlot()
        val m = store.readMeta(slot) ?: throw IllegalStateException("印迹尚未初始化")
        val entries = store.readItems(slot).map { decryptItem(it) }
        return BackupCodecV2.encode(
            entries = entries,
            masterRef = null,
            password = legacyKeyHex,
            vaultCreatedAt = m.createdAt,
        )
    }

    /** 遗产交接当前配置状态：intervalDays（0=未配置/不提醒，UI 最低可选 30 天）、lastBeat、恢复包生成时间 */
    suspend fun heirStatus(): Triple<Int, Long, Long> = Triple(
        store.readHeirIntervalDays(),
        store.readHeirLastBeat(),
        store.readHeirPkgAt(),
    )

    suspend fun setHeirInterval(days: Int) {
        store.setHeirIntervalDays(days)
        if (days > 0 && store.readHeirLastBeat() == 0L) {
            store.markHeirBeat(System.currentTimeMillis())
        }
    }

    /** 遗产恢复包最近一次生成时间（「已配置」判定 + 重新生成提示用） */
    suspend fun markHeirPkg() = store.markHeirPkg(System.currentTimeMillis())

    /** 关闭遗产交接：清除本机全部遗产状态，不影响金库数据（已导出的包/分片需自行处理） */
    suspend fun disableHeir() = store.clearHeir()

    /**
     * 导入备份并覆盖本地 —— v1/v2 按顶层 version 自动分发（[BackupCodecV2.decodeAny]）。
     * 导入完成即锁定；aux 凭证在首次解锁时补齐（见 [ensureAuxProvisioned]）。
     */
    suspend fun importJson(text: String, password: String? = null): ImportResult =
        when (
            try {
                BackupCodecV2.decodeAny(text)
            } catch (e: BackupFormatException) {
                throw ImportException(e.message ?: "备份文件无效")
            }
        ) {
            is BackupCodecV2.ParsedBackup.V1 -> importV1(text, password)
            is BackupCodecV2.ParsedBackup.V2 -> importV2(text, password)
        }

    /**
     * v1 备份导入（PBKDF2 + AES-256-CBC）。
     * 本地存储升到 GCM（SCHEMA_V3）后，v1 的 CBC 密文**不再能直落**——统一走
     * 「v1 全量解密（严格验 MAC）→ 新盐重派生 → GCM 重加密 → 重建双槽位」。
     * 本地解锁密码：custom 备份 = 备份密码；default 备份（含小程序来源）= 本地默认密码。
     */
    private suspend fun importV1(text: String, password: String?): ImportResult {
        val file = try {
            BackupCodec.decode(text)
        } catch (e: BackupFormatException) {
            throw ImportException(e.message ?: "备份文件无效")
        }
        val m = file.data.meta
        if (m == null) {
            store.clearAll()
            lock()
            return ImportResult(ImportType.WIPED, 0)
        }
        val items = file.data.items

        // 源解密密码：custom 备份 = 用户输入；default 备份 = 按 masterRef 解析的内置密码
        val sourceMaster = if (m.pwdMode != VaultMeta.MODE_DEFAULT) {
            password ?: throw ImportException("该备份使用自定义密码保护，请输入备份密码")
        } else {
            when (file.crypto.masterRef) {
                CryptoEngine.MASTER_REF_ANDROID -> CryptoEngine.ANDROID_DEFAULT_MASTER
                null, CryptoEngine.MASTER_REF_WXB ->
                    m.openid?.let { CryptoEngine.WXB_DEFAULT_PREFIX + it }
                        ?: throw ImportException("该备份为「默认保护」且缺少身份标识，无法解锁")
                else -> throw ImportException("未知的密钥来源（${file.crypto.masterRef}），无法解锁")
            }
        }
        val srcKey = CryptoEngine.deriveKeyB64(sourceMaster, m.saltB64)
        if (!CryptoEngine.verifyMaster(srcKey, m.verifyB64, m.verifyIvB64)) {
            throw ImportException("备份校验失败，文件可能已损坏")
        }
        val plain = try {
            ItemCipher.decryptItems(items, srcKey, VaultMeta.SCHEMA_V2)
        } catch (e: MacVerificationException) {
            // 备份内记录 MAC 与密文不符：拒绝导入，本地数据不受影响
            throw ImportException(e.message ?: "备份完整性校验失败")
        }

        // 本地目标密码：custom 备份 → 备份密码自身；default 备份（ANDROID/WXB）→ 转入本地默认保护
        val isCustom = m.pwdMode != VaultMeta.MODE_DEFAULT
        val localPwd = if (isCustom) sourceMaster else CryptoEngine.ANDROID_DEFAULT_MASTER
        val newSaltB64 = CryptoEngine.randomSaltB64()
        val localKey = CryptoEngine.deriveKeyB64(localPwd, newSaltB64)
        val (newVerify, newVerifyIv) = CryptoEngine.makeVerify(localKey)
        val now = System.currentTimeMillis()
        val realMeta = VaultMeta(
            saltB64 = newSaltB64,
            verifyB64 = newVerify,
            verifyIvB64 = newVerifyIv,
            pwdMode = if (isCustom) VaultMeta.MODE_CUSTOM else VaultMeta.MODE_DEFAULT,
            createdAt = m.createdAt,
            updatedAt = now,
        )
        rebuildBothSlots(realMeta, ItemCipher.encryptEntries(plain, localKey, VaultMeta.SCHEMA_V3))
        localKey.fill(0)
        srcKey.fill(0)
        SessionManager.lock()
        return ImportResult(
            if (isCustom) ImportType.RESTORED else ImportType.REENCRYPTED,
            plain.size,
        )
    }

    /**
     * v2 备份导入（Argon2id + AES-256-GCM）：GCM 密文无法直落本地 v1 CBC 存储，
     * 必须全量解密 → 用**新盐**重新派生本地密钥重加密 → 重建双槽位。
     * 本地解锁密码 = 备份密码；custom 备份 → [ImportType.RESTORED]，
     * default 备份 → [ImportType.REENCRYPTED]（小程序备份转入本地默认保护）。
     */
    private suspend fun importV2(text: String, password: String?): ImportResult {
        val file = try {
            BackupCodecV2.decode(text)
        } catch (e: BackupFormatException) {
            throw ImportException(e.message ?: "备份文件无效")
        }
        // 解密密码：default 备份用内置主密码（按 masterRef 解析，忽略输入值）；
        // custom 备份必须由用户输入。
        val sourcePwd = BackupCodecV2.resolveDefaultMaster(file) ?: password
            ?: throw ImportException("该备份使用自定义密码保护，请输入备份密码")
        // 预检：解得开才动本地数据（verify 槽位 GCM 验证）
        BackupCodecV2.verifyFile(file, sourcePwd)?.let { throw ImportException(it) }
        val entries = try {
            BackupCodecV2.decryptItems(file, sourcePwd)
        } catch (e: CryptoV2.IntegrityException) {
            // verify 槽位未动但条目密文被篡改：拒绝导入，本地数据不受影响
            throw ImportException("备份完整性校验失败，文件可能已损坏")
        }

        // 本地目标密码：custom / 本应用 default 备份 → 备份密码自身；
        // 小程序 default 备份 → 转入本地默认保护（对齐 v1 的 REENCRYPTED 语义）
        val isCustom = file.crypto.masterRef == null
        val localPwd = when (file.crypto.masterRef) {
            null -> sourcePwd
            CryptoEngine.MASTER_REF_ANDROID -> CryptoEngine.ANDROID_DEFAULT_MASTER
            CryptoEngine.MASTER_REF_WXB -> CryptoEngine.ANDROID_DEFAULT_MASTER
            else -> throw ImportException("未知的密钥来源（${file.crypto.masterRef}），无法导入")
        }
        val newSaltB64 = CryptoEngine.randomSaltB64()
        val localKey = CryptoEngine.deriveKeyB64(localPwd, newSaltB64)
        val (newVerify, newVerifyIv) = CryptoEngine.makeVerify(localKey)
        val now = System.currentTimeMillis()
        val realMeta = VaultMeta(
            saltB64 = newSaltB64,
            verifyB64 = newVerify,
            verifyIvB64 = newVerifyIv,
            pwdMode = if (isCustom) VaultMeta.MODE_CUSTOM else VaultMeta.MODE_DEFAULT,
            createdAt = file.exportedAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        rebuildBothSlots(realMeta, ItemCipher.encryptEntries(entries, localKey, VaultMeta.SCHEMA_V3))
        localKey.fill(0)
        SessionManager.lock()
        return ImportResult(
            if (isCustom) ImportType.RESTORED else ImportType.REENCRYPTED,
            entries.size,
        )
    }

    /** 清空两个槽位后重建：真实数据落随机槽位，另一槽位放无人能解的占位数据 */
    private suspend fun rebuildBothSlots(realMetaSource: VaultMeta, realItems: List<VaultItem>) {
        val real = Slot.random()
        val peer = real.other()
        val now = System.currentTimeMillis()
        val createdAt = realMetaSource.createdAt.takeIf { it > 0 } ?: now
        // 目标条目格式跟随传入 meta（导入路径 = SCHEMA_V3）；旧值兜底升到 V3
        val fmt = realMetaSource.schemaVersion
            .takeIf { it >= VaultMeta.SCHEMA_V2 }
            ?: VaultMeta.SCHEMA_V3

        val peerPassword = CryptoEngine.randomHex(32)
        val peerSaltB64 = CryptoEngine.randomSaltB64()
        val peerKey = CryptoEngine.deriveKeyB64(peerPassword, peerSaltB64)
        val (peerVerify, peerVerifyIv) = CryptoEngine.makeVerify(peerKey)
        val peerItems = ItemCipher.encryptEntries(ShadowVaultGenerator.generate(realItems), peerKey, fmt)
        peerKey.fill(0)

        // 双槽位原子提交：clearAll（覆盖语义）与双槽位四 key 的重建在**同一次** edit
        // 事务内生效——中途死进程不会留下「旧世界已清、新世界未写全」的半导入状态
        // （那会让用户以为备份损坏或数据丢失）。
        store.commitSlots {
            resetAll()
            write(
                real,
                meta = realMetaSource.copy(
                    schemaVersion = fmt,
                    createdAt = createdAt,
                    auxSaltB64 = "",
                    auxSecretEnc = "",
                    auxSecretIv = "",
                ),
                items = realItems,
            )
            write(
                peer,
                meta = VaultMeta(
                    saltB64 = peerSaltB64,
                    verifyB64 = peerVerify,
                    verifyIvB64 = peerVerifyIv,
                    pwdMode = realMetaSource.pwdMode,
                    createdAt = createdAt, // 与真库槽位同值
                    updatedAt = createdAt,
                    schemaVersion = fmt,
                ),
                items = peerItems,
            )
        }
    }

    // ---------------- 工具 ----------------
}
