package com.beyondguo.penly

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.data.VaultStore

class PenlyApp : Application() {

    lateinit var repo: VaultRepository
        private set

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        repo = VaultRepository(VaultStore(this))
    }

    /**
     * 切后台延迟自动锁定（对齐小程序「切后台即清除」，给系统文件选择器/分享面板
     * 等短暂跳转留 15 秒往返时间，避免导入导出过程中被锁）。
     */
    fun onBackground() {
        handler.postDelayed({ repo.lock() }, AUTO_LOCK_DELAY_MS)
    }

    fun onForeground() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val AUTO_LOCK_DELAY_MS = 15_000L
    }
}

val Context.penly: PenlyApp
    get() = applicationContext as PenlyApp
