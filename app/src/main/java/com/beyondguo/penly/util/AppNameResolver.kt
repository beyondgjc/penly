package com.beyondguo.penly.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 包名 → 应用显示名（v4.0：自动填充保存的条目标题用应用名替代包名）。
 *
 * 保存时机单次解析（PackageManager 一次 IPC，量级可忽略）；结果进程内缓存，
 * 同包名重复保存不再重复查询。解析失败（应用已卸载/系统限制）返回 null，
 * 调用方用包名兜底——不丢信息、不猜测。
 */
object AppNameResolver {

    private val cache = ConcurrentHashMap<String, String>()

    /** 解析应用显示名；失败返回 null。阻塞 IPC 放 IO 线程。 */
    suspend fun label(context: Context, pkg: String): String? {
        if (pkg.isBlank()) return null
        cache[pkg]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }?.also { cache[pkg] = it }
    }
}
