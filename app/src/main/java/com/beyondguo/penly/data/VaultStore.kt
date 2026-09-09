package com.beyondguo.penly.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    /** 清空两个槽位与 legacy 残留 */
    suspend fun clearAll() {
        context.penlyDataStore.edit { p ->
            p.remove(metaKey(Slot.A))
            p.remove(itemsKey(Slot.A))
            p.remove(metaKey(Slot.B))
            p.remove(itemsKey(Slot.B))
            p.remove(LEGACY_META_KEY)
            p.remove(LEGACY_ITEMS_KEY)
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
}
