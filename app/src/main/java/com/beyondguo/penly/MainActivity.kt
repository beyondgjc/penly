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
 * release 包 FLAG_SECURE：密码箱内容不进系统截图/最近任务预览（debug 放开以便自动化测试）。
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
