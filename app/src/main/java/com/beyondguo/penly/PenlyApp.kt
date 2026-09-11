package com.beyondguo.penly

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.beyondguo.penly.data.AppPrefs
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.data.VaultStore
import com.beyondguo.penly.util.ClipboardGuard

class PenlyApp : Application() {

    lateinit var repo: VaultRepository
        private set

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 处于 started 状态的 Activity 计数（v3.0 项目⑤ 前置修复）：
     * >0 = 应用在前台（含被自家页面遮挡，如扫码取景页/未来的 Autofill 解锁浮层）。
     *
     * 旧实现只在 MainActivity 的 onStart/onStop 里判定——被自家 Activity（扫码页）遮挡
     * 也会触发 onStop，15 秒后锁库：扫码对焦超过 15 秒 → 扫码成功但 saveEntry 撞上
     * VaultLockedException，被误报成"不是有效的 2FA 二维码"；按返回还会看到莫名其妙的锁屏。
     * 现按「启动计数是否归零」判定真正的前后台，自家页面互相遮挡不再锁库。
     */
    private var startedActivities = 0

    private val lifecycleTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities == 0) onBackground()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        repo = VaultRepository(VaultStore(this))
        // 应用级偏好（剪贴板自动清除开关等）：订阅 DataStore 维持内存缓存
        AppPrefs.init(this)
        registerActivityLifecycleCallbacks(lifecycleTracker)
    }

    /**
     * 切后台延迟自动锁定（对齐小程序「切后台即清除」，给系统文件选择器/分享面板
     * 等短暂跳转留 15 秒往返时间，避免导入导出过程中被锁）。
     * 触发时机 = started Activity 计数归零（应用真正进入后台）。
     */
    fun onBackground() {
        handler.postDelayed({ repo.lock() }, AUTO_LOCK_DELAY_MS)
    }

    fun onForeground() {
        handler.removeCallbacksAndMessages(null)
        // 剪贴板后台清除失败的补偿路径：回前台第一时间清掉（v3.0 项目②第三层保障）
        ClipboardGuard.onForeground(this)
    }

    companion object {
        const val AUTO_LOCK_DELAY_MS = 15_000L
    }
}

val Context.penly: PenlyApp
    get() = applicationContext as PenlyApp
