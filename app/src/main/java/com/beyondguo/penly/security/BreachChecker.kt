package com.beyondguo.penly.security

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * 泄露哨兵 · 联网校验部分。
 *
 * 对接 HaveIBeenPwned 的 Pwned Passwords「区间查询」（k-匿名）：
 * 只把密码 SHA-1 的**前 5 位**发给服务端，服务端返回所有「前 5 位相同」的
 * 后缀 + 泄露次数；本端在本地比对完整后缀，从而判断该密码是否出现在泄露库中。
 * 密码本身、完整哈希均不出端。
 *
 * 这是产品首个联网功能，按《印迹Android_v2技术方案.md》§5 的合规要求实现：
 * - 默认关闭，仅用户显式开启后才联网
 * - 仅 HTTPS 到 api.pwnedpasswords.com（见 network_security_config.xml）
 * - 请求体仅 5 位哈希前缀；HIBP 限流（429）时指数退避重试
 */
object BreachChecker {
    private const val TAG = "BreachChecker"
    private const val RANGE_API = "https://api.pwnedpasswords.com/range/%s"
    private const val USER_AGENT = "penly/1.0 (android password-health)"
    private const val MAX_RETRIES = 3
    private const val BACKOFF_BASE_MS = 1500L

    /** SHA-1 十六进制大写（仅本端计算，完整哈希不出端） */
    fun sha1Hex(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * 查询某密码的泄露次数。
     * @return 泄露次数（>0）；0 表示未出现在库中；-1 表示网络/服务异常（不可判定）
     */
    suspend fun breachCount(secret: String): Int = withContext(Dispatchers.IO) {
        val hash = sha1Hex(secret)
        val prefix = hash.take(5)
        val suffix = hash.drop(5)
        repeat(MAX_RETRIES) { attempt ->
            try {
                val conn = (URL(RANGE_API.format(prefix))).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                when (val code = conn.responseCode) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        return@withContext parseBreachCount(body, suffix)
                    }
                    429 -> {
                        conn.disconnect()
                        if (attempt < MAX_RETRIES - 1) delay(BACKOFF_BASE_MS * (attempt + 1))
                    }
                    else -> {
                        Log.w(TAG, "range api unexpected code $code")
                        conn.disconnect()
                        return@withContext -1
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "range api error: ${e.message}")
                return@withContext -1
            }
        }
        -1
    }

    /** 解析区间响应：每行 `SUFFIX:COUNT`，找到与 [suffix] 匹配的行返回其次数；无匹配返回 0（可单测） */
    internal fun parseBreachCount(body: String, suffix: String): Int {
        for (line in body.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith(suffix, ignoreCase = true)) {
                return t.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }
}
