package com.beyondguo.penly.util

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

/**
 * 列表分组用的首字母：**A–Z + #**。
 *
 * - 拉丁字母 → 大写（`Steam` → `S`）
 * - 汉字 → 拼音首字母（`微信` → `W`、`支付宝` → `Z`）
 * - 数字 / 符号 / 空标题 → [OTHER]
 *
 * ## 为什么必须归并到 A–Z
 *
 * 若按汉字首字分组，每个不同首字都是一个分组，条目一多就会产生几十个分组，
 * 右侧索引条会被撑爆（且大量分组对查找毫无帮助）。归并到 A–Z 后，
 * 索引条**恒定 ≤ 27 项**，与系统通讯录一致。
 *
 * 依赖 `pinyin4j`（纯 Java、无传递依赖、Maven Central 可得）；
 * 词典内置于 jar，无需初始化，首次调用时由类加载器装载。
 */
object InitialLetter {

    /** 非字母（数字 / 符号 / 空 / 无法取拼音）归入此分组 */
    const val OTHER = "#"

    /** 小写、无声调：`微` → ["wei"] */
    private val FORMAT = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    fun of(title: String): String {
        val c = title.trim().firstOrNull() ?: return OTHER
        if (c.isDigit()) return OTHER
        val upper = c.uppercaseChar()
        if (upper in 'A'..'Z') return upper.toString()
        return pinyinInitial(c) ?: OTHER
    }

    /** 汉字 → 拼音首字母；非汉字或无对应拼音时返回 null */
    private fun pinyinInitial(c: Char): String? {
        // 非汉字返回 null；多音字取第一个读音即可（分组场景无需精确）
        val py = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT) ?: return null
        val first = py.firstOrNull()?.firstOrNull()?.uppercaseChar() ?: return null
        return if (first in 'A'..'Z') first.toString() else null
    }
}
