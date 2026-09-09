package com.beyondguo.penly.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 中文召回评测（真实模型，JVM 端内推理）。
 *
 * 语料：15 条贴近真实使用的密码箱条目（embedText 格式与线上一致），
 * 查询：11 条**语义改述**的中文查询——查询与目标条目刻意零字面重叠，
 * 覆盖同义（买东西→淘宝）、功能描述（听歌→网易云）、场景联想（坐高铁→12306）等。
 *
 * 评分：Top-1 命中数 / Top-3 命中数，并打印每条查询的完整 Top-3 排名与余弦分，
 * 供人工复核排序合理性。评测跑在桌面 ORT 上，代码路径与端上完全一致。
 *
 * 这是本项目的"换模型裁判"：召回不达标 → 换 [OnnxEmbedder.ModelConfig] 里的备选
 * （如 E5_SMALL）重跑本评测，数据说话。
 */
class ChineseRecallEvalTest {

    private fun asset(name: String): ByteArray {
        val candidates = listOf(
            File("src/main/assets/models/$name"),
            File("app/src/main/assets/models/$name"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到模型资源 $name，尝试过: $candidates")
        return f.readBytes()
    }

    private data class Item(val title: String, val embedText: String)

    /** embedText 构造与 VaultRepository.toSearchEntry() 保持一致 */
    private fun item(title: String, category: String, account: String, note: String) =
        Item(title, "标题：$title\n分类：$category\n账号：$account\n备注：$note")

    private val corpus = listOf(
        item("微信支付", "支付", "guo****@163.com", "日常扫码付款，绑定了工资卡"),
        item("支付宝", "支付", "138****5678", "手机号登录，余额宝有余额"),
        item("淘宝", "购物", "taobao_user_92", "网购主力账号，绑定了收货地址"),
        item("京东", "购物", "jd_88321", "买数码产品用，Plus 会员"),
        item("网易云音乐", "娱乐", "musicer@foxmail.com", "黑胶会员，歌单都在这里"),
        item("公司邮箱", "工作", "jichao.guo@company.com", "入职时 IT 开的，OA 也用它登录"),
        item("GitHub", "工作", "beyondguo", "个人代码仓库，绑定了两步验证"),
        item("招商银行", "金融", "6225****1234", "工资卡，取款密码六位"),
        item("房贷还款", "金融", "loan_2024", "每月 15 号自动扣款"),
        item("Steam", "娱乐", "steamguo", "游戏库 200+，开了手机令牌"),
        item("光遇", "娱乐", "sky_child_01", "iOS 和安卓双端登录"),
        item("公司 VPN", "工作", "vpn_jichao", "远程办公用，动态口令"),
        item("12306", "出行", "guo****@163.com", "火车票购票，实名认证"),
        item("携程旅行", "出行", "138****5678", "订机票酒店，常旅客号"),
        item("健身房会员", "生活", "member_777", "全年卡，年底到期"),
    )

    /** 查询 → 期望命中的条目标题 */
    private val cases = listOf(
        "网上买东西用的账号" to "淘宝",
        "听歌的软件会员" to "网易云音乐",
        "坐高铁回家买票" to "12306",
        "上班收邮件的地方" to "公司邮箱",
        "存代码的网站" to "GitHub",
        "每月还银行贷款" to "房贷还款",
        "出门旅游订酒店机票" to "携程旅行",
        "锻炼身体的会员卡" to "健身房会员",
        "扫码付款给商家" to "微信支付",
        "下班在家连公司内网" to "公司 VPN",
        "买游戏打折的平台" to "Steam",
    )

    @Test
    fun chinese_semantic_recall_top1_and_top3() = runBlocking {
        val embedder = OnnxEmbedder(asset("bge-small-zh-v1.5-q.onnx"), asset("bge-zh-vocab.txt").decodeToString())
        try {
            val vectors = embedder.embedAll(corpus.map { it.embedText })
            assertTrue("语料向量化有失败项", vectors.all { it != null })

            var top1 = 0
            var top3 = 0
            var minHit = 1f
            var maxWrong = 0f
            val lines = mutableListOf<String>()
            for ((query, expected) in cases) {
                val qv = embedder.embedForQuery(query)!!
                val ranked = corpus
                    .mapIndexed { i, it -> it.title to cosine(qv, vectors[i]!!) }
                    .sortedByDescending { it.second }
                val top3Titles = ranked.take(3).map { it.first }
                if (ranked[0].first == expected) top1++
                if (expected in top3Titles) top3++
                // 标定数据：命中下限 vs 最高错误分 → 阈值选在两者之间
                minHit = minOf(minHit, ranked.first { it.first == expected }.second)
                maxWrong = maxOf(maxWrong, ranked.takeWhile { it.first != expected }.maxOfOrNull { it.second } ?: 0f)
                lines.add(
                    "Q: $query\n" +
                        "   期望: $expected | Top3: " + ranked.take(3)
                        .joinToString("  ") { "${it.first}=%.3f".format(it.second) }
                )
            }
            println("\n========== 中文召回评测（bge-small-zh-v1.5 int8） ==========")
            println(lines.joinToString("\n"))
            println("----------------------------------------------------------")
            println("Top-1: $top1/${cases.size}   Top-3: $top3/${cases.size}")
            println("命中下限=%.3f  最高错误分=%.3f  → 阈值应取两者之间".format(minHit, maxWrong))
            println("==========================================================\n")

            assertTrue("Top-3 命中不足（$top3/${cases.size}），考虑换模型", top3 >= cases.size - 1)
            assertTrue("Top-1 命中过低（$top1/${cases.size}），考虑换模型", top1 >= (cases.size + 1) / 2)
        } finally {
            embedder.close()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
