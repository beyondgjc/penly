package com.beyondguo.penly.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * 应用内检查更新（v4.0）：GitHub Releases 作为版本事实源。
 *
 * 流程：请求 releases/latest API → 与本机 versionName 语义化比较 →
 * 有新版弹确认 → 跳浏览器下载（不在应用内下载 APK：规避「未知来源」安装
 * 权限的复杂度与攻击面，浏览器下载走系统完整安全链）。
 *
 * 容错：仓库私有 / 无网络 / 限流时 API 不可达 → 返回 null，调用方直接
 * 跳发布页（浏览器有 GitHub 会话即可见），把「是否更新」的判断交给用户。
 */
object UpdateChecker {

    private const val REPO = "beyondgjc/penly"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASE_PAGE = "https://github.com/$REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("name") val name: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
    )

    /**
     * 拉取最新 release。任何失败（网络/私有仓库 404/限流/解析）返回 null——
     * 调用方据此走「直接跳发布页」的兜底路径。
     */
    suspend fun fetchLatest(): GhRelease? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "penly-app") // GitHub API 要求 UA
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@withContext null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString(GhRelease.serializer(), text)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 语义化版本比较：remote 是否比 current 新。
     * 容忍 v 前缀与段数不齐（v4.0 vs 3.0.0 → 按 4.0.0 vs 3.0.0 比较）。
     * 相等返回 false（不更新）。
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = segments(remote)
        val c = segments(current)
        for (i in 0 until max(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun segments(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-') // 剥离 -beta 等预发布段
            .split('.').map { it.toIntOrNull() ?: 0 }

    /** 跳系统浏览器（默认浏览器）打开下载页；[url] 缺省为最新发布页 */
    fun openInBrowser(activity: Activity, url: String = RELEASE_PAGE) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
