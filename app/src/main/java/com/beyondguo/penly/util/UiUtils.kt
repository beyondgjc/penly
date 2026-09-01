package com.beyondguo.penly.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clearHandler = Handler(Looper.getMainLooper())

/** 复制敏感内容：Android 13+ 标记敏感（不进剪贴板预览/云剪贴板），60 秒后自动清空 */
fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
    // TODO 应用在后台获取不到剪切板
    clearHandler.postDelayed({
        val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == text) cm.clearPrimaryClip()
    }, 60_000)
}

fun formatTime(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))

/**
 * 备份文件直接写入公共「下载」目录（MediaStore，API 29+ 免存储权限）。
 * 不跳系统文件选择器 —— 部分国产 ROM（如 MIUI）会在跳转后立即回收源进程导致"闪退"。
 * 返回用户可感知的保存位置描述。
 */
fun writeToDownloads(context: Context, fileName: String, content: String): String {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("无法创建下载文件")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IllegalStateException("无法写入文件")
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    values.clear()
    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return "下载/$fileName"
}
