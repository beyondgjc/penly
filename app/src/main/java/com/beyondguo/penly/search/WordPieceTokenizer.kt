package com.beyondguo.penly.search

/**
 * BERT 风格 WordPiece 分词器（纯 Kotlin，JVM / Android 通用）。
 *
 * 与 HuggingFace `BertTokenizerFast` 对齐的预处理流水线：
 * 1. 清洗：剔除控制字符、空白归一
 * 2. 中文字符前后补空格 → 每个汉字独立成词（中文 BERT 词典是字级的）
 * 3. 小写化 + 去重音（NFD 分解后丢弃组合符）
 * 4. 按空白 / 标点切词
 * 5. WordPiece 贪心最长匹配（延续片段带 `##` 前缀；任一片段匹配失败则整词 → [UNK]）
 *
 * 仅依赖 java.lang.Character / java.text.Normalizer，无任何平台 API ——
 * JVM 单测与端上跑的是同一份代码，评测结论可直接迁移到真机。
 */
class WordPieceTokenizer(vocabText: String, private val maxLen: Int = 256) {

    private val vocab: Map<String, Int> = vocabText.lineSequence()
        .filter { it.isNotEmpty() }
        .mapIndexed { index, token -> token to index }
        .toMap()

    private val unkId: Int = requireNotNull(vocab[UNK]) { "vocab 缺少 $UNK" }
    private val clsId: Int = requireNotNull(vocab[CLS]) { "vocab 缺少 $CLS" }
    private val sepId: Int = requireNotNull(vocab[SEP]) { "vocab 缺少 $SEP" }

    /** 一次编码的完整结果：模型三个输入全部给出 */
    class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    fun encode(text: String): Encoding {
        val pieces = tokenize(text).take(maxLen - 2) // 留给 [CLS]/[SEP]
        val n = pieces.size + 2
        val ids = LongArray(n)
        ids[0] = clsId.toLong()
        pieces.forEachIndexed { i, p -> ids[i + 1] = (vocab[p] ?: unkId).toLong() }
        ids[n - 1] = sepId.toLong()
        return Encoding(ids, LongArray(n) { 1 }, LongArray(n))
    }

    /** 文本 → WordPiece 词元序列（不含 [CLS]/[SEP]） */
    fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        for (word in basicTokenize(text)) {
            if (isPunct(word[0])) out.add(word) else out.addAll(wordPiece(word))
        }
        return out
    }

    // ---------- Basic Tokenizer ----------

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        for (c in text) {
            if (c == '\u0000' || Character.isISOControl(c)) continue
            cleaned.append(if (c.isWhitespace()) ' ' else c)
        }
        // 中文前后补空格 + 小写，一次遍历完成
        val spaced = StringBuilder(cleaned.length * 2)
        for (c in cleaned) {
            if (isCjk(c)) spaced.append(' ').append(c).append(' ')
            else spaced.append(c.lowercaseChar())
        }
        // 去重音：NFD 分解后丢弃组合符
        val noAccent = java.text.Normalizer.normalize(spaced.toString(), java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        // 按空白切词，再把词内的标点单独切开
        val words = mutableListOf<String>()
        for (raw in noAccent.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var buf = StringBuilder()
            for (c in raw) {
                if (isPunct(c)) {
                    if (buf.isNotEmpty()) { words.add(buf.toString()); buf = StringBuilder() }
                    words.add(c.toString())
                } else buf.append(c)
            }
            if (buf.isNotEmpty()) words.add(buf.toString())
        }
        return words
    }

    private fun wordPiece(word: String): List<String> {
        if (word.length > MAX_CHARS_PER_WORD) return listOf(UNK)
        val out = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched: String? = null
            while (start < end) {
                val sub = word.substring(start, end)
                val candidate = if (start > 0) "##$sub" else sub
                if (vocab.containsKey(candidate)) { matched = candidate; break }
                end--
            }
            if (matched == null) return listOf(UNK) // 整词降级为 UNK（BERT 规范）
            out.add(matched)
            start = end
        }
        return out
    }

    private fun isPunct(c: Char): Boolean {
        if (c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~') return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }
    }

    private fun isCjk(c: Char): Boolean = when (c.code) {
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0x20000..0x2A6DF,
        in 0x2A700..0x2B73F, in 0x2B740..0x2B81F, in 0x2B820..0x2CEAF,
        in 0xF900..0xFAFF, in 0x2F800..0x2FA1F,
        -> true
        else -> false
    }

    companion object {
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val UNK = "[UNK]"
        private const val MAX_CHARS_PER_WORD = 100
    }
}
