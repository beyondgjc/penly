package com.beyondguo.penly.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 顶层委托（铁律：DataStore 委托禁止声明在类体内，否则同文件多 DataStore 触发
// FileStorage.activeFiles 校验崩溃，见 2026-09-07 P0 修复记录）
private val Context.appPrefsDataStore by preferencesDataStore(name = "app_prefs")

/**
 * 应用级偏好（与金库数据隔离）：剪贴板自动清除等 UI/安全开关。
 *
 * 内存缓存的必要性：剪贴板清除任务在主线程 Handler 里执行，需要同步读取开关；
 * 主线程 runBlocking DataStore 会卡 UI，故由 init() 常驻订阅维持缓存，读取走内存。
 */
object AppPrefs {
    private val KEY_CLIPBOARD_AUTO_CLEAR = booleanPreferencesKey("clipboard_auto_clear")

    @Volatile
    var clipboardAutoClear: Boolean = true
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 在 Application.onCreate 调用一次：常驻订阅 DataStore，维持内存缓存 */
    fun init(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            appContext.appPrefsDataStore.data.collect { prefs ->
                clipboardAutoClear = prefs[KEY_CLIPBOARD_AUTO_CLEAR] ?: true
                android.util.Log.d("PenlyClipboard", "AppPrefs 缓存更新: clipboardAutoClear=$clipboardAutoClear")
            }
        }
    }

    /** 写入 DataStore 并同步刷新内存缓存（立即生效，不等 collect 回调） */
    suspend fun setClipboardAutoClear(context: Context, enabled: Boolean) {
        context.applicationContext.appPrefsDataStore.edit {
            it[KEY_CLIPBOARD_AUTO_CLEAR] = enabled
        }
        clipboardAutoClear = enabled
    }
}
