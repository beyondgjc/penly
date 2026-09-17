package com.beyondguo.penly.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * MIUI「后台弹出界面」权限引导。
 *
 * 为什么需要它：其他应用唤起系统 autofill 时，印迹要在后台弹出解锁/保存界面；
 * MIUI 对「后台启动 Activity」有独有拦截（标准 Android 无此限制），未授权时弹窗
 * 被静默吞掉、无任何回调（PenlyAutofillService 曾用通知兜底，用户拍板移除）——
 * 只能引导用户手动放行。
 *
 * 设计拍板（2026-09-17）：**不做本地状态检测**——op 10008 是 MIUI 私有编号，
 * 各版本 ROM 对该 op 的判定语义不一致，误判比不判更糟。只负责「跳到正确的页
 * 面」，允许与否由用户在系统页操作、以系统实际行为为准。
 *
 * op 10008 在标准 Android 上不存在 —— 引导仅 MIUI（含 Redmi/POCO）触发。
 */
object MiuiBgUi {

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
     * 打开权限设置页：优先 MIUI 权限编辑器（逐项开关），失败回退应用详情页（所有 ROM 都有）。
     * 返回实际使用的跳转结果描述（供 Toast 提示用户当前落在哪个页面）。
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
