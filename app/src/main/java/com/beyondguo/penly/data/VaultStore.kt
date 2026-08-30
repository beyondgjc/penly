package com.beyondguo.penly.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.penlyDataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(
    name = "penly_vault",
)

/**
 * 本地持久化（对应小程序 services/localStore.js 的 wx.storage）：
 * 只存密文 meta + 密文条目数组，结构与小程序同构；会话密钥永不落盘。
 */
class VaultStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val META_KEY = stringPreferencesKey("vault_meta")
        private val ITEMS_KEY = stringPreferencesKey("vault_items")
    }

    suspend fun readMeta(): VaultMeta? {
        val raw = context.penlyDataStore.data.first()[META_KEY] ?: return null
        return try {
            json.decodeFromString(VaultMeta.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun writeMeta(meta: VaultMeta?) {
        context.penlyDataStore.edit { p ->
            if (meta == null) p.remove(META_KEY)
            else p[META_KEY] = json.encodeToString(VaultMeta.serializer(), meta)
        }
    }

    suspend fun readItems(): List<VaultItem> {
        val raw = context.penlyDataStore.data.first()[ITEMS_KEY] ?: return emptyList()
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(VaultItem.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun writeItems(items: List<VaultItem>) {
        context.penlyDataStore.edit { p ->
            p[ITEMS_KEY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(VaultItem.serializer()),
                items,
            )
        }
    }

    suspend fun upsertItem(item: VaultItem) {
        val arr = readItems().toMutableList()
        val i = arr.indexOfFirst { it.id == item.id }
        if (i >= 0) arr[i] = item else arr.add(item)
        writeItems(arr)
    }

    suspend fun deleteItem(id: String) {
        writeItems(readItems().filterNot { it.id == id })
    }

    suspend fun clearAll() {
        context.penlyDataStore.edit { p ->
            p.remove(META_KEY)
            p.remove(ITEMS_KEY)
        }
    }
}
