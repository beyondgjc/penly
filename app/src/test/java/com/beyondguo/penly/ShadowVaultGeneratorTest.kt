package com.beyondguo.penly

import com.beyondguo.penly.data.ShadowVaultGenerator
import com.beyondguo.penly.data.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影子数据生成器测试。
 *
 * 这些断言保护的是"结构镜像"这一核心不变量：影子数据若在数量、分类分布或
 * 时间戳形态上与真库对不上，就会被一眼看穿，整个影子保险库随之失效。
 */
class ShadowVaultGeneratorTest {

    private val DAY_MS = 24L * 60 * 60 * 1000L

    private fun item(category: String): VaultItem = VaultItem(
        id = "id_${category}_${System.nanoTime()}",
        title = "某条目",
        category = category,
    )

    @Test
    fun emptySource_producesEmptyShadow() {
        assertTrue(ShadowVaultGenerator.generate(emptyList()).isEmpty())
    }

    @Test
    fun mirrorsItemCountAndCategoryDistribution() {
        val source = listOf(
            item("邮箱"), item("邮箱"), item("邮箱"),
            item("支付"), item("支付"),
            item("社交"),
        )
        val shadow = ShadowVaultGenerator.generate(source, now = 1_000_000_000_000L)

        assertEquals("条目数量必须与真库一致", source.size, shadow.size)
        assertEquals(
            "各分类出现次数必须与真库一致",
            source.groupingBy { it.category }.eachCount(),
            shadow.groupingBy { it.category }.eachCount(),
        )
    }

    @Test
    fun timestampsSpreadWithin90DaysAndOrdered() {
        val now = System.currentTimeMillis()
        val shadow = ShadowVaultGenerator.generate(List(30) { item("默认") }, now = now)

        shadow.forEach {
            assertTrue("createdAt 应落在 90 天窗口内", it.createdAt in (now - 91 * DAY_MS)..now)
            assertTrue("createdAt <= updatedAt", it.createdAt <= it.updatedAt)
            assertTrue("updatedAt 不应超过 now", it.updatedAt <= now)
        }
        // 全部挤在同一时刻等于自报"这是刚生成的"
        assertTrue("时间戳必须分散", shadow.distinctBy { it.createdAt }.size > 1)
    }

    @Test
    fun contentLooksPlausibleButIsUnusable() {
        val shadow = ShadowVaultGenerator.generate(List(5) { item("邮箱") })

        shadow.forEach {
            assertTrue("账号应形如 user_xxxx", it.account.startsWith("user_"))
            assertEquals("密码长度应为 16", 16, it.secret.length)
            assertTrue("备注留空更自然", it.note.isEmpty())
            assertTrue("标题不得留空", it.title.isNotBlank())
        }
        // 生成的密码互不相同：一批影子数据全用同一个密码同样是可疑特征
        assertEquals(5, shadow.map { it.secret }.toSet().size)
    }

    @Test
    fun generatedIdsAreUnique() {
        val shadow = ShadowVaultGenerator.generate(List(50) { item("默认") })
        assertEquals(50, shadow.map { it.id }.toSet().size)
    }

    @Test
    fun needsRegenRespectsThreshold() {
        assertFalse("数量相同不必重建", ShadowVaultGenerator.needsRegen(10, 10))
        assertFalse("差 1 条不必重建", ShadowVaultGenerator.needsRegen(10, 9))
        assertTrue("差 3 条应重建", ShadowVaultGenerator.needsRegen(10, 7))
        assertTrue("差 30% 应重建", ShadowVaultGenerator.needsRegen(100, 70))
        assertFalse("差 15% 不必重建", ShadowVaultGenerator.needsRegen(100, 85))
        assertTrue("影子为空且主库有数据应重建", ShadowVaultGenerator.needsRegen(3, 0))
    }
}
