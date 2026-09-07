package com.beyondguo.penly.security

import com.beyondguo.penly.data.ItemSecurityState
import com.beyondguo.penly.data.SecurityReport
import com.beyondguo.penly.data.VaultRepository
import kotlinx.coroutines.delay
import java.security.MessageDigest

/**
 * 聚合扫描：本地（弱/复用）+ 联网（泄露）。
 *
 * 联网部分仅当用户显式开启 [allowNetwork] 才执行；相同密码只查一次（本地去重），
 * 每个唯一密码之间间隔 200ms，避免触发 HIBP 限流。
 */
object SecurityScanner {
    private const val NETWORK_INTERVAL_MS = 200L

    suspend fun scan(
        repo: VaultRepository,
        allowNetwork: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SecurityReport {
        val items = repo.items() // VaultItem（密文）
        val plain = items.map { repo.decryptItem(it) } // PlainEntry（明文 secret）

        val sha = MessageDigest.getInstance("SHA-256")
        val reusedSet = PasswordHealth.reusedHashes(plain.map { it.secret })

        val breachedBySecret = HashMap<String, Int>()
        if (allowNetwork) {
            val unique = plain.map { it.secret }.toSet()
            unique.forEachIndexed { i, secret ->
                breachedBySecret[secret] = BreachChecker.breachCount(secret)
                onProgress(i + 1, unique.size)
                delay(NETWORK_INTERVAL_MS)
            }
        }

        var breachedCount = 0
        var reusedCount = 0
        var weakCount = 0
        val states = plain.map { pe ->
            val breached = breachedBySecret[pe.secret] ?: 0
            val reused = sha256Hex(sha, pe.secret) in reusedSet
            val weak = PasswordHealth.isWeak(pe.secret)
            if (breached > 0) breachedCount++
            if (reused) reusedCount++
            if (weak) weakCount++
            ItemSecurityState(pe.id, breached, reused, weak)
        }

        return SecurityReport(
            scannedAt = System.currentTimeMillis(),
            totalCount = plain.size,
            breachedCount = breachedCount,
            reusedCount = reusedCount,
            weakCount = weakCount,
            states = states,
        )
    }

    private fun sha256Hex(sha: MessageDigest, secret: String): String {
        sha.reset()
        return sha.digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
