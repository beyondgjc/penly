package com.beyondguo.penly.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.beyondguo.penly.crypto.DoubleEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.penlyDataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(
    name = "penly_vault",
)

/**
 * 本地持久化（对应小程序 services/localStore.js 的 wx.storage）：
 * 只存密文 meta + 密文条目数组，结构与小程序同构；会话密钥永不落盘。
 *
 * v2 起改为**双槽位**（[Slot.A] / [Slot.B]）：
 * - key 名刻意无语义（`vm_0`/`vi_0`、`vm_1`/`vi_1`），不出现 real / shadow / meta / items 等字样，
 *   避免从文件名判断主次。
 * - 两个槽位在初始化时同时创建、始终都有数据，使「是否设置了应急密码」在磁盘上不可区分。
 */
class VaultStore(private val context: Context) {

    /** 供 VaultRepository 等读取 assets（端内模型装载）用；应用级 context，防泄漏 Activity */
    internal val appContext: Context = context.applicationContext

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        // v2 双槽位：key 名无语义
        fun metaKey(slot: Slot) = stringPreferencesKey("vm_${slot.index}")
        fun itemsKey(slot: Slot) = stringPreferencesKey("vi_${slot.index}")

        // v5.0 TEE 信封：每槽位一份（ve_<slot>），缺 key = 未启用信封（解锁回落 PBKDF2 旧链）。
        // 键名同样无语义（ve_ 不暗示任何主次），两槽位在启用时**成对**写入，保持不可证伪性。
        fun envelopeKey(slot: Slot) = stringPreferencesKey("ve_${slot.index}")

        // 最近一次成功导出备份的时间戳（备份前置检查：启用信封前必须有已导出备份）
        val LAST_EXPORT_KEY = stringPreferencesKey("last_export_at")

        // 遗产交接（heir_ 前缀；"legacy_" 已被 v1 迁移数据占用，避免歧义）
        val HEIR_INTERVAL_KEY = androidx.datastore.preferences.core.intPreferencesKey("heir_interval_days")
        val HEIR_LAST_BEAT_KEY = androidx.datastore.preferences.core.longPreferencesKey("heir_last_beat")
        val HEIR_PKG_AT_KEY = androidx.datastore.preferences.core.longPreferencesKey("heir_pkg_at")

        // v1 单槽位：仅迁移期读取，迁移完成后删除
        val LEGACY_META_KEY = stringPreferencesKey("vault_meta")
        val LEGACY_ITEMS_KEY = stringPreferencesKey("vault_items")
    }

    // ---------------- v2 双槽位 ----------------

    suspend fun readMeta(slot: Slot): VaultMeta? {
        val raw = context.penlyDataStore.data.first()[metaKey(slot)] ?: return null
        return try {
            json.decodeFromString(VaultMeta.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun writeMeta(slot: Slot, meta: VaultMeta?) {
        context.penlyDataStore.edit { p ->
            if (meta == null) p.remove(metaKey(slot))
            else p[metaKey(slot)] = json.encodeToString(VaultMeta.serializer(), meta)
        }
    }

    suspend fun readItems(slot: Slot): List<VaultItem> {
        val raw = context.penlyDataStore.data.first()[itemsKey(slot)] ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(VaultItem.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun writeItems(slot: Slot, items: List<VaultItem>) {
        context.penlyDataStore.edit { p ->
            p[itemsKey(slot)] = json.encodeToString(ListSerializer(VaultItem.serializer()), items)
        }
    }

    suspend fun upsertItem(slot: Slot, item: VaultItem) {
        val arr = readItems(slot).toMutableList()
        val i = arr.indexOfFirst { it.id == item.id }
        if (i >= 0) arr[i] = item else arr.add(item)
        writeItems(slot, arr)
    }

    suspend fun deleteItem(slot: Slot, id: String) {
        writeItems(slot, readItems(slot).filterNot { it.id == id })
    }

    // ---------------- v5.0 TEE 信封 ----------------

    /** 读取槽位信封；null = 未启用信封（该槽位解锁走 PBKDF2 旧链） */
    suspend fun readEnvelope(slot: Slot): DoubleEnvelope.Envelope? {
        val raw = context.penlyDataStore.data.first()[envelopeKey(slot)] ?: return null
        return try {
            json.decodeFromString(DoubleEnvelope.Envelope.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    /** 信封独立写入（enableEnvelope 之外的场景一般不该用——信封变更必须进 commitSlots 事务） */
    suspend fun writeEnvelope(slot: Slot, envelope: DoubleEnvelope.Envelope?) {
        context.penlyDataStore.edit { p ->
            if (envelope == null) p.remove(envelopeKey(slot))
            else p[envelopeKey(slot)] = json.encodeToString(DoubleEnvelope.Envelope.serializer(), envelope)
        }
    }

    // ---------------- 备份前置检查 ----------------

    suspend fun readLastExportAt(): Long =
        context.penlyDataStore.data.first()[LAST_EXPORT_KEY]?.toLongOrNull() ?: 0L

    suspend fun setLastExportAt(ts: Long) {
        context.penlyDataStore.edit { p -> p[LAST_EXPORT_KEY] = ts.toString() }
    }

    // ---------------- 遗产交接（#43/#44，heir_ 前缀区别于 v1 legacy 迁移键） ----------------

    /** 死信开关间隔天数（0 = 关闭）；心跳 = 每次成功解锁自动续期 */
    suspend fun readHeirIntervalDays(): Int =
        context.penlyDataStore.data.first()[HEIR_INTERVAL_KEY] ?: 0

    suspend fun setHeirIntervalDays(days: Int) {
        context.penlyDataStore.edit { p ->
            if (days <= 0) p.remove(HEIR_INTERVAL_KEY) else p[HEIR_INTERVAL_KEY] = days
        }
    }

    suspend fun readHeirLastBeat(): Long =
        context.penlyDataStore.data.first()[HEIR_LAST_BEAT_KEY] ?: 0L

    suspend fun markHeirBeat(ts: Long) {
        context.penlyDataStore.edit { p -> p[HEIR_LAST_BEAT_KEY] = ts }
    }

    /** 遗产恢复包最近一次生成时间（0 = 尚未生成） */
    suspend fun readHeirPkgAt(): Long =
        context.penlyDataStore.data.first()[HEIR_PKG_AT_KEY] ?: 0L

    suspend fun markHeirPkg(ts: Long) {
        context.penlyDataStore.edit { p -> p[HEIR_PKG_AT_KEY] = ts }
    }

    /** 关闭遗产交接：清除本机全部遗产状态（间隔/心跳/恢复包记录），不影响金库数据 */
    suspend fun clearHeir() {
        context.penlyDataStore.edit { p ->
            p.remove(HEIR_INTERVAL_KEY)
            p.remove(HEIR_LAST_BEAT_KEY)
            p.remove(HEIR_PKG_AT_KEY)
        }
    }

    /** 清空两个槽位与 legacy 残留 */
    suspend fun clearAll() {
        context.penlyDataStore.edit { p ->
            p.remove(metaKey(Slot.A))
            p.remove(itemsKey(Slot.A))
            p.remove(envelopeKey(Slot.A))
            p.remove(metaKey(Slot.B))
            p.remove(itemsKey(Slot.B))
            p.remove(envelopeKey(Slot.B))
            p.remove(LEGACY_META_KEY)
            p.remove(LEGACY_ITEMS_KEY)
            p.remove(HEIR_INTERVAL_KEY)
            p.remove(HEIR_LAST_BEAT_KEY)
            p.remove(HEIR_PKG_AT_KEY)
        }
    }

    // ---------------- v1 legacy（仅迁移期使用） ----------------

    /** 存在 v1 单槽位数据时返回 (meta, items)，否则返回 null */
    suspend fun readLegacy(): Pair<VaultMeta, List<VaultItem>>? {
        val p = context.penlyDataStore.data.first()
        val rawMeta = p[LEGACY_META_KEY] ?: return null
        val meta = try {
            json.decodeFromString(VaultMeta.serializer(), rawMeta)
        } catch (_: Exception) {
            return null
        }
        val items = p[LEGACY_ITEMS_KEY]?.let { raw ->
            try {
                json.decodeFromString(ListSerializer(VaultItem.serializer()), raw)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
        return meta to items
    }

    suspend fun clearLegacy() {
        context.penlyDataStore.edit { p ->
            p.remove(LEGACY_META_KEY)
            p.remove(LEGACY_ITEMS_KEY)
        }
    }

    // ---------------- 双槽位原子提交（P0：跨 key 多写必须单事务） ----------------
    //
    // 背景见《评审_VaultRepository多key非原子写入》：initVault / rebuildBothSlots /
    // setDuressPassword / changeMasterPassword / ensureAuxProvisioned / migrateIfNeeded
    // 都要跨多个 key 写入，原先各自一次 edit{}——中途进程死亡会留下「半状态」：
    // ① changeMasterPassword items 写完 meta 未写 → 唯一可用密码失效，数据永久不可解密；
    // ② setDuressPassword / initVault 落在 aux 链断裂处 → isPrimary 恒 false，静默门禁自锁。
    //
    // DataStore 的 edit{} 本身是原子事务（CAS 单文件写）——把多 key 变更合并进**一次**
    // edit{} 即获得全或无语义。链路不变量 I（真库 key 解出对侧密码、对侧 verify 通过）
    // 与「合法影子槽位」在磁盘上不可区分，任何事后检测自愈都不可行，只能靠原子写。

    /**
     * 槽位写入载荷：JSON 已序列化的 meta / items / envelope。
     * null 表示**不改动**该 key（非删除）；单槽位至少要写一项或显式声明 drop。
     */
    class SlotWrite internal constructor(
        internal val metaJson: String?,
        internal val itemsJson: String?,
        internal val envelopeJson: String?,
        internal val dropEnvelope: Boolean,
    )

    /**
     * 双槽位事务的声明集。[write] / [resetAll] / [dropLegacy] 只做**声明与 JSON 编码**，
     * 真正的磁盘变更由 [VaultStore.commitSlots] 在**一次** edit{} 内统一生效。
     *
     * ⚠️ 编码必须发生在 edit 事务之外（commitSlots 先跑本 builder 拿到全部载荷，
     * 再进事务只做赋值）：transform 持有 DataStore coordinator 锁，全量条目的
     * 序列化是 CPU 工作，放进事务会无谓拖长锁持有时间。
     */
    class SlotPatchBuilder internal constructor(private val json: Json) {
        internal val writes = LinkedHashMap<Slot, SlotWrite>()
        internal var resetAllFlag = false
        internal var dropLegacyFlag = false

        /**
         * 写一个槽位：meta / items / envelope 至少给一个（或另行声明 [dropEnvelope]）；
         * 同槽位重复声明直接拒绝（防部分声明被静默覆盖）。
         * [envelope] 为 null 表示**不改动**该槽位信封 key；写信封请显式传值。
         */
        fun write(
            slot: Slot,
            meta: VaultMeta? = null,
            items: List<VaultItem>? = null,
            envelope: DoubleEnvelope.Envelope? = null,
        ) {
            require(meta != null || items != null || envelope != null) {
                "write(${slot.name}) 的 meta / items / envelope 不能同时为空"
            }
            require(slot !in writes) {
                "槽位 ${slot.name} 已声明写入：重复 write 会整体覆盖先前声明" +
                    "（第二次只给 meta 会静默丢掉已声明的 items）——如需组合请一次给全"
            }
            val metaJson = meta?.let { json.encodeToString(VaultMeta.serializer(), it) }
            val itemsJson = items?.let { json.encodeToString(ListSerializer(VaultItem.serializer()), it) }
            val envJson = envelope?.let { json.encodeToString(DoubleEnvelope.Envelope.serializer(), it) }
            writes[slot] = SlotWrite(metaJson, itemsJson, envJson, dropEnvelope = false)
        }

        /** 同事务删除槽位信封（disableEnvelope：门禁回落 PBKDF2 旧链，条目数据零影响） */
        fun dropEnvelope(slot: Slot) {
            if (slot in writes) {
                val w = writes.getValue(slot)
                writes[slot] = SlotWrite(w.metaJson, w.itemsJson, null, true)
            } else {
                writes[slot] = SlotWrite(null, null, null, true)
            }
        }

        /** 同事务先移除全部双槽位四 key 与信封（初始化/导入的「从零重建」语义） */
        fun resetAll() {
            resetAllFlag = true
        }

        /** 同事务移除 legacy key（v1 → v2 迁移的收尾步） */
        fun dropLegacy() {
            dropLegacyFlag = true
        }
    }

    /**
     * 双槽位原子提交：[patch] 内声明的全部变更在**一次** edit{} 事务内生效——
     * 中途进程死亡只会留下「全部旧值」或「全部新值」，不存在半状态。
     * [SlotPatchBuilder] 内的 JSON 编码在事务外（调用方 dispatcher）完成。
     */
    suspend fun commitSlots(patch: SlotPatchBuilder.() -> Unit) {
        val p = SlotPatchBuilder(json).apply(patch)
        require(p.writes.isNotEmpty() || p.resetAllFlag || p.dropLegacyFlag) {
            "空事务：commitSlots 不允许无操作提交"
        }
        context.penlyDataStore.edit { prefs ->
            if (p.resetAllFlag) {
                prefs.remove(metaKey(Slot.A))
                prefs.remove(itemsKey(Slot.A))
                prefs.remove(envelopeKey(Slot.A))
                prefs.remove(metaKey(Slot.B))
                prefs.remove(itemsKey(Slot.B))
                prefs.remove(envelopeKey(Slot.B))
            }
            p.writes.forEach { (slot, w) ->
                w.metaJson?.let { prefs[metaKey(slot)] = it }
                w.itemsJson?.let { prefs[itemsKey(slot)] = it }
                if (w.dropEnvelope) prefs.remove(envelopeKey(slot))
                else w.envelopeJson?.let { prefs[envelopeKey(slot)] = it }
            }
            if (p.dropLegacyFlag) {
                prefs.remove(LEGACY_META_KEY)
                prefs.remove(LEGACY_ITEMS_KEY)
            }
        }
    }
}
