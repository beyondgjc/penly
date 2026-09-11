package com.beyondguo.penly.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.beyondguo.penly.data.AppPrefs

/**
 * 剪贴板自动清除（v3.0 项目②）。
 *
 * 平台事实：Android 10+ 收紧的是「读」剪贴板（后台 getPrimaryClip 恒为 null），
 * 「写/清」在原生 AOSP 不受焦点限制——Bitwarden / KeePassDX 的自动清除同款机制。
 * 旧实现的 bug 正是踩了读限制：延时任务先读内容做比对，后台读恒 null，
 * 比对失败导致 clearPrimaryClip 永远不执行（原 TODO 注释的根因）。
 *
 * 三层保障：
 * 1) 进程内延时 60s 清除，runCatching 包住（MIUI 等 OEM 可能对后台 clear 抛异常）
 * 2) clear 被拒时退化为写入空 clip（同为写操作，AOSP 允许），粘贴得到空串等效清空
 * 3) 仍失败则记补偿标志，下次回前台（PenlyApp.onForeground）第一时间清掉，绝不永久残留
 *
 * 连续复制语义：以最后一次复制为准（新任务顶掉旧任务）。
 */
object ClipboardGuard {
    private const val TAG = "PenlyClipboard"
    private const val DELAY_MS = 60_000L

    // 独立 handler：PenlyApp.onForeground 的 removeCallbacksAndMessages(null)
    // 会清空它自己的自动锁定队列，两个调度器互不误伤
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    @Volatile
    private var needForegroundCompensation = false

    /** 复制敏感内容后调用：排定一次性清除任务 */
    fun scheduleClear(context: Context) {
        pending?.let { handler.removeCallbacks(it) }
        val appContext = context.applicationContext
        val task = Runnable { clearNow(appContext, "定时清除") }
        pending = task
        handler.postDelayed(task, DELAY_MS)
    }

    /** 撤销未执行的清除任务（开关关闭时调用） */
    fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /** 回前台补偿：后台清除失败时，回到印迹的第一时间清掉 */
    fun onForeground(context: Context) {
        if (!needForegroundCompensation) return
        needForegroundCompensation = false
        clearNow(context.applicationContext, "回前台补偿清除")
    }

    private fun clearNow(context: Context, reason: String) {
        pending = null
        if (!AppPrefs.clipboardAutoClear) {
            Log.d(TAG, "$reason 跳过：开关已关闭")
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val cleared = runCatching { cm.clearPrimaryClip() }
            .onSuccess { Log.d(TAG, "$reason：clearPrimaryClip 成功") }
            .isSuccess
        if (!cleared) {
            val fallback = runCatching {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }.onSuccess { Log.d(TAG, "$reason：clear 被拒，已写入空 clip 兜底") }.isSuccess
            if (!fallback) {
                needForegroundCompensation = true
                Log.w(TAG, "$reason：清除与空写入均失败，留待回前台补偿")
            }
        }
    }
}
