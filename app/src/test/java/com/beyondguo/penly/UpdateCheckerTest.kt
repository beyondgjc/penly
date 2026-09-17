package com.beyondguo.penly

import com.beyondguo.penly.util.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的版本比较：语义化、容忍 v 前缀与段数不齐。
 */
class UpdateCheckerTest {

    @Test
    fun newer_detected() {
        assertTrue(UpdateChecker.isNewer("v4.0.0", "3.0.0"))
        assertTrue(UpdateChecker.isNewer("3.0.1", "3.0.0"))
        assertTrue(UpdateChecker.isNewer("4.0", "3.9.9"))
        assertTrue(UpdateChecker.isNewer("V10.0.0", "9.9.9"))
        assertTrue(UpdateChecker.isNewer("3.1", "3.0.0")) // 段数不齐补零比较
    }

    @Test
    fun same_or_older_not_flagged() {
        assertFalse(UpdateChecker.isNewer("3.0.0", "3.0.0"))
        assertFalse(UpdateChecker.isNewer("v3.0.0", "3.0.0"))
        assertFalse(UpdateChecker.isNewer("3.0", "3.0.0")) // 补零后相等 → 不提示
        assertFalse(UpdateChecker.isNewer("2.9.9", "3.0.0"))
    }

    @Test
    fun malformed_segments_treated_as_zero() {
        assertTrue(UpdateChecker.isNewer("3.0.1", "3.0.x")) // x → 0
        assertFalse(UpdateChecker.isNewer("3.0.0", "3.0.x"))
    }

    @Test
    fun prerelease_suffix_stripped() {
        // -beta 等预发布段剥离后比较（4.0.0-beta ≈ 4.0.0，不重复提示；简化语义，非完整 semver）
        assertFalse(UpdateChecker.isNewer("4.0.0-beta", "4.0.0"))
    }
}
