package com.beyondguo.penly

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.beyondguo.penly.ui.AppRoot
import com.beyondguo.penly.ui.theme.PenlyTheme

/**
 * 单 Activity 宿主。继承 FragmentActivity 以支持 androidx.biometric 的 BiometricPrompt。
 * release 包 FLAG_SECURE：印迹内容不进系统截图/最近任务预览（debug 放开以便自动化测试）。
 *
 * 说明：文件选择等 Activity Result 一律用标准新 API（registerForActivityResult /
 * rememberLauncherForActivityResult），无需自定义 requestCode。历史上曾因
 * biometric 1.1.0 把 fragment 钉在 1.2.5（其 FragmentActivity 强校验 requestCode
 * 仅低 16 位）而与 registry 的随机 requestCode 冲突导致 crash；biometric 升到
 * 1.2.0-beta01（fragment ≥1.3.0 已移除该校验）后冲突不复存在。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 防截屏仅正式包启用（debug 包放开以便 adb 自动化测试截图）
        val isDebuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        // 沉浸式：状态栏透明，内容延伸到系统栏后面；应用为浅色主题，状态栏图标用深色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        setContent {
            PenlyTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        penly.onForeground()
    }

    override fun onStop() {
        super.onStop()
        penly.onBackground()
    }
}
