package com.beyondguo.penly.util

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * MIUI「后台弹出界面」权限（AppOps op 10008）检测与引导。
 *
 * 为什么需要它：其他应用唤起系统 autofill 时，印迹要在后台弹出解锁/保存界面；
 * MIUI 对「后台启动 Activity」有独有拦截（标准 Android 无此限制），未授权时弹窗
 * 被静默吞掉、无任何回调（PenlyAutofillService 曾用通知兜底，用户拍板移除）——
 * 只能引导用户手动放行。
 *
 * op 10008 是 MIUI 私有编号，标准 Android 上不存在该 op —— 相关入口仅 MIUI 显示。
 */
object MiuiBgUi {

    private const val OP_BG_UI = 10008

    /** 是否小米系 ROM（MIUI/HyperOS）：按品牌/厂商判定，覆盖 Xiaomi / Redmi / POCO */
    fun isMiui(): Boolean {
        val manufacturer = Build.MANUFACTURER ?: return false
        val brand = Build.BRAND ?: return false
        return manufacturer.equals("Xiaomi", ignoreCase = true) ||
            brand.contains("xiaomi", ignoreCase = true) ||
            brand.contains("redmi", ignoreCase = true) ||
            brand.contains("poco", ignoreCase = true)
    }

    /**
     * 「后台弹出界面」是否已允许。
     * 非 MIUI / 查询异常一律返回 true（不显示引导，避免在标准系统上误报）。
     */
    fun isAllowed(context: Context): Boolean {
        if (!isMiui()) return true
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // op 10008 是 MIUI 私有编号、无公开字符串名；int 重载 checkOpNoThrow(int, int, String)
            // 在较新 compileSdk 的公开 API 中不可见（标准公开版只留 String 名重载）——反射绑定，
            // 运行时 MIUI framework 必有此方法（adb `appops get <pkg> 10008` 即走它）
            val mode = AppOpsManager::class.java
                .getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
                .invoke(ops, OP_BG_UI, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 打开权限设置页：优先 MIUI 权限编辑器（逐项开关），失败回退应用详情页（所有 ROM 都有）。
     * 返回实际使用的跳转结果描述（供 Toast 提示用户下一步去哪）。
     */
    fun openPermissionPage(activity: Activity): String {
        // 1) MIUI 专用权限编辑页：最短路径，可直接看到「后台弹出界面」开关
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", activity.packageName)
            }
            activity.startActivity(intent)
            return "已打开 权限管理，请允许「后台弹出界面」"
        } catch (_: Exception) {
        }
        // 2) 应用详情页兜底：从「权限管理」二级页进入找「后台弹出界面」
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
            return "已打开 应用信息，请进入 权限管理 开启「后台弹出界面」"
        } catch (_: Exception) {
        }
        return "无法打开设置页，请到 系统设置 → 应用管理 → 印迹 手动开启"
    }
}
