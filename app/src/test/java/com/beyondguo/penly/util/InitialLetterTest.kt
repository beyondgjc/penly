package com.beyondguo.penly.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialLetterTest {

    @Test
    fun latinTitle_usesUppercaseInitial() {
        assertEquals("S", InitialLetter.of("Steam"))
        assertEquals("S", InitialLetter.of("steam")) // 小写归一
        assertEquals("G", InitialLetter.of("Gmail"))
    }

    @Test
    fun chineseTitle_usesPinyinInitial() {
        assertEquals("W", InitialLetter.of("微信"))
        assertEquals("Z", InitialLetter.of("支付宝"))
        assertEquals("T", InitialLetter.of("淘宝"))
    }

    @Test
    fun nonLetterTitle_goesToOtherGroup() {
        assertEquals(InitialLetter.OTHER, InitialLetter.of("12345"))
        assertEquals(InitialLetter.OTHER, InitialLetter.of("@qq"))
        assertEquals(InitialLetter.OTHER, InitialLetter.of(""))
        assertEquals(InitialLetter.OTHER, InitialLetter.of("   "))
    }

    /**
     * 核心回归点：无论多少条目、首字多分散，分组数都必须被 A–Z+# 兜住，
     * 否则右侧索引条会被撑爆（这正是按汉字首字分组的旧问题）。
     */
    @Test
    fun groupCount_isAlwaysBoundedBy27() {
        val titles = listOf(
            "微信", "支付宝", "淘宝", "京东", "微博", "网易邮箱", "公司VPN", "服务器",
            "百度网盘", "抖音", "知乎", "钉钉", "企业微信", "招商银行", "工商银行",
            "中国移动", "电信宽带", "社保账户", "公积金", "学信网", "飞书", "云盘备份",
            "路由器", "门禁卡", "车管所", "医保电子凭证", "Steam", "Gmail", "Apple",
            "GitHub", "Notion", "QQ", "B站", "12306", "1Password",
        )
        val keys = titles.map { InitialLetter.of(it) }.toSet()
        assertTrue(
            "分组数必须 ≤27（A–Z + #），实际 ${keys.size}：$keys",
            keys.size <= 27,
        )
    }
}
