package com.beyondguo.penly.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PasswordHealthTest {

    @Test
    fun isWeak_rules() {
        assertTrue(PasswordHealth.isWeak("123")) // 太短
        assertTrue(PasswordHealth.isWeak("12345678")) // 纯数字
        assertTrue(PasswordHealth.isWeak("abcdefgh")) // 纯字母
        assertTrue(PasswordHealth.isWeak("password")) // 常见弱密码
        assertFalse(PasswordHealth.isWeak("Tr0ub4dour!!")) // 混合且够长
        assertFalse(PasswordHealth.isWeak("correct horse")) // 够长、含空格、非纯字母
    }

    @Test
    fun reusedHashes_detectsDuplicates() {
        val dup = PasswordHealth.reusedHashes(listOf("a", "a", "b"))
        assertEquals(1, dup.size)
        assertEquals(hashOf("a"), dup.first())

        val none = PasswordHealth.reusedHashes(listOf("a", "b", "c"))
        assertEquals(0, none.size)
    }

    private fun hashOf(secret: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        return sha.digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
