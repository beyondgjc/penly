package com.beyondguo.penly.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数档位（#18）测试。
 *
 * 最要紧的一条是 [sensitive matches penly kek exactly] —— penly 本机 KEK
 * 靠这三个数值解开**所有存量信封**，档位定义与它不一致 = 全部用户解不开库。
 */
class ProfileTest {

    /**
     * **闸门测试**：SENSITIVE 必须与 penly 本机 KEK 现用值一字不差。
     *
     * 这三个数字是线上格式（存量信封的盐/参数已在用户设备上），改档位定义
     * 会让 `DoubleEnvelope.unseal` 派生出的 KEK 与加密时不同 → 解不开。
     */
    @Test
    fun `sensitive matches penly kek exactly`() {
        assertEquals(64 * 1024, Profile.SENSITIVE.memoryKiB)
        assertEquals(3, Profile.SENSITIVE.iterations)
        assertEquals(1, Profile.SENSITIVE.parallelism)
    }

    /** 档位强度单调：内存代价越高越难暴力（防止有人调错顺序） */
    @Test
    fun `tiers are ordered by strength`() {
        assertTrue(Profile.INTERACTIVE.memoryKiB < Profile.SENSITIVE.memoryKiB)
        assertTrue(Profile.SENSITIVE.memoryKiB < Profile.PARANOID.memoryKiB)
        assertTrue(Profile.INTERACTIVE.iterations <= Profile.SENSITIVE.iterations)
        assertTrue(Profile.SENSITIVE.iterations <= Profile.PARANOID.iterations)
    }

    /** byId 往返：每个档位的 id 都能查回自己 */
    @Test
    fun `byId roundtrip for every entry`() {
        for (p in Profile.entries) {
            assertEquals(p, Profile.byId(p.id))
        }
    }

    /** 未知 id → null（不猜、不降级；由调用方决定怎么处理） */
    @Test
    fun `byId returns null for unknown id`() {
        assertNull(Profile.byId("not-a-profile"))
        assertNull(Profile.byId(""))
    }

    /** derive 产出 32B，且同输入同输出（确定性） */
    @Test
    fun `derive is deterministic and 32 bytes`() {
        val pwd = "master".toByteArray()
        val salt = ByteArray(16) { 0x11 }
        // 用最轻档位跑（INTERACTIVE 32MiB，测试里约百毫秒级）
        val k1 = Profile.INTERACTIVE.derive(pwd, salt)
        val k2 = Profile.INTERACTIVE.derive(pwd, salt)
        assertEquals(32, k1.size)
        assertTrue(k1.contentEquals(k2))
    }

    /** 盐敏感：不同 salt → 不同 key（防"盐没接进去"这类静默错误） */
    @Test
    fun `derive is salt sensitive`() {
        val pwd = "master".toByteArray()
        val a = Profile.INTERACTIVE.derive(pwd, ByteArray(16) { 0x11 })
        val b = Profile.INTERACTIVE.derive(pwd, ByteArray(16) { 0x22 })
        assertTrue(!a.contentEquals(b))
    }

    /** toSpec 展开：档位名不落盘，落盘的是显式数值 */
    @Test
    fun `toSpec expands to explicit numbers`() {
        val spec = Profile.SENSITIVE.toSpec("c2FsdA==")
        assertEquals(Container.KDF_ARGON2ID, spec.alg)
        assertEquals("c2FsdA==", spec.saltB64)
        assertEquals(64 * 1024, spec.memoryKiB)
        assertEquals(3, spec.iterations)
        assertEquals(1, spec.parallelism)
        // 数值里不该出现档位名（"sensitive"）——确保没把档位名混进数据
        assertTrue(!spec.toString().contains("sensitive"))
    }

    /** id 是稳定标识，不得为空 */
    @Test
    fun `ids are non blank and unique`() {
        val ids = Profile.entries.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(Profile.byId("interactive"))
    }
}
