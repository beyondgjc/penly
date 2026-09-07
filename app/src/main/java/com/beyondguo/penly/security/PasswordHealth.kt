package com.beyondguo.penly.security

import java.security.MessageDigest

/**
 * 本地密码健康检测（**不联网**）：
 * - 弱密码：长度 < 8 / 纯数字 / 纯字母 / 命中内置常见弱密码表
 * - 复用：相同密码（按 SHA-256 分组）出现 > 1 次
 *
 * 注：内置 [COMMON] 为代表性子集；《方案》提到的 assets 60KB top-10k 弱密码表未随工程提供，
 * 后续可替换/扩充为资源文件，不影响接口与判定逻辑。
 */
object PasswordHealth {
    private val COMMON = setOf(
        "123456", "password", "12345678", "qwerty", "abc123", "123456789", "111111",
        "123123", "admin", "1234567", "letmein", "welcome", "monkey", "dragon",
        "1234", "sunshine", "princess", "12345", "password1", "1234567890",
        "qwerty123", "1q2w3e4r", "qwertyuiop", "654321", "000000", "iloveyou",
        "666666", "888888", "coppeme", "azerty", "trustno1", "whatever",
        "11111111", "password123", "zaq12wsx", "1qaz2wsx", "passw0rd", "p@ssw0rd",
        "q1w2e3r4", "123qwe", "1q2w3e", "asdfgh", "asdfghjkl", "football",
        "baseball", "master", "shadow", "superman", "batman", "michael",
        "123456a", "a123456", "admin123", "root", "toor", "test", "test123",
        "guest", "user", "login", "changeme", "pass", "hello", "hello123",
        "qwerty1", "google", "yahoo", "baidu", "taobao", "123456aa", "aaaaaa",
        "1111111111", "7777777", "88888888", "5201314", "1314520", "woaini",
        "woaini1314", "qq123456", "123456q", "1qazxsw2", "1qazxs", "qazwsx",
        "abcd1234", "aabbcc", "112233", "123321", "2wsx3edc", "3edc4rfv",
        "password!", "p@ssword", "qazwsxedc", "1q2w3e4", "1234abcd", "q1w2e3",
        "1qaz2wsx", "qwerty12", "admin888", "123abc", "1a2b3c", "Admin@123",
        "P@ssw0rd", "Passw0rd", "Password1!", "qazwsx123", "1q2w3e4r5t",
    )

    fun isWeak(secret: String): Boolean {
        if (secret.length < 8) return true
        if (secret.all { it.isDigit() }) return true
        if (secret.all { it.isLetter() }) return true
        if (secret in COMMON) return true
        return false
    }

    /** 返回「出现次数 > 1」的密码 SHA-256 哈希集合（用于标记复用） */
    fun reusedHashes(secrets: List<String>): Set<String> {
        val sha = MessageDigest.getInstance("SHA-256")
        return secrets.groupingBy { sha256Hex(sha, it) }
            .eachCount()
            .filter { it.value > 1 }
            .keys
    }

    private fun sha256Hex(sha: MessageDigest, secret: String): String {
        sha.reset()
        return sha.digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
