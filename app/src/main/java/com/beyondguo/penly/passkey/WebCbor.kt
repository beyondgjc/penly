package com.beyondguo.penly.passkey

/**
 * 最小 CBOR 编解码（RFC 8949 子集）——只覆盖 WebAuthn 需要的类型：
 * 正/负整数、字节串、文本串、数组、Map、null、bool。
 *
 * 设计取舍（v5.0-② #46）：
 * - 编码恒为定长最小形式（不用不定长数组/Map），Map 键按「编码后的字节序」排序输出
 *   （RFC 8949 §4.2 canonical），同一输入字节级确定，便于固定向量互验；
 * - 解码端整数放得进 Int 返回 Int，否则 Long（COSE/authData 取值都在 Int 范围）；
 * - 不支持标签（major 6）、浮点、不定长——fmt=none 链路用不到，遇到即抛
 *   [CborException]（fail-closed，不做静默容错）。
 */
object WebCbor {

    class CborException(message: String = "CBOR 编解码失败") : Exception(message)

    // ---------------- 编码 ----------------

    /** Kotlin 类型映射：Int/Long→整数，ByteArray→字节串，String→文本串，List→数组，Map→Map */
    fun encode(value: Any?): ByteArray = when (value) {
        null -> byteArrayOf(0xF6.toByte())
        is Boolean -> byteArrayOf(if (value) 0xF5.toByte() else 0xF4.toByte())
        is Int -> number(value.toLong(), major = if (value >= 0) 0 else 1)
        is Long -> number(value, major = if (value >= 0) 0 else 1)
        is ByteArray -> tagged(2, value.size) + value
        is String -> {
            val raw = value.toByteArray(Charsets.UTF_8)
            tagged(3, raw.size) + raw
        }
        is List<*> -> {
            var out = tagged(4, value.size)
            for (v in value) out += encode(v)
            out
        }
        is Map<*, *> -> {
            // canonical：按键的编码字节序排序（长度优先作为前缀规则的自然结果）
            val sorted = value.entries
                .map { encode(it.key) to encode(it.value) }
                .sortedWith { a, b -> lexCompare(a.first, b.first) }
            var out = tagged(5, value.size)
            for ((k, v) in sorted) {
                out += k
                out += v
            }
            out
        }
        else -> throw CborException("不支持的 CBOR 类型：${value::class.java.name}")
    }

    /** 有符号值 v 以指定 major 编码；major=1 时内部折算 -1-v（RFC 8949 负数语义） */
    private fun number(v: Long, major: Int): ByteArray {
        val u = if (major == 1) -1 - v else v
        require(u >= 0) { "内部错误：number() 编码值越界（v=$v, major=$major）" }
        val mt = major shl 5
        return when {
            u < 24 -> byteArrayOf((mt or u.toInt()).toByte())
            u <= 0xFF -> byteArrayOf((mt or 24).toByte(), u.toByte())
            u <= 0xFFFF -> byteArrayOf((mt or 25).toByte()) + be(u, 2)
            u <= 0xFFFFFFFFL -> byteArrayOf((mt or 26).toByte()) + be(u, 4)
            else -> byteArrayOf((mt or 27).toByte()) + be(u, 8)
        }
    }

    private fun tagged(major: Int, len: Int): ByteArray = number(len.toLong(), major)

    /** n 字节大端 */
    private fun be(u: Long, n: Int): ByteArray =
        ByteArray(n) { i -> ((u shr (8 * (n - 1 - i))) and 0xFF).toByte() }

    /** 字节串字典序（逐字节按无符号比较；前缀短者在前——canonical 排序规则） */
    private fun lexCompare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    // ---------------- 解码 ----------------

    /** 解码单个顶层项；尾部有多余字节 → [CborException] */
    fun decode(bytes: ByteArray): Any? {
        val p = Parser(bytes)
        val v = p.readItem()
        if (!p.atEnd()) throw CborException("CBOR 尾部有多余字节")
        return v
    }

    private class Parser(private val b: ByteArray) {
        var pos = 0

        fun atEnd(): Boolean = pos == b.size

        private fun u8(): Int {
            if (pos >= b.size) throw CborException("CBOR 数据提前结束")
            return b[pos++].toInt() and 0xFF
        }

        /** additional info → 长度/值（info<24 内联） */
        private fun len(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> u8().toLong()
            info == 25 -> ((u8() shl 8) or u8()).toLong()
            info == 26 -> ((u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()).toLong() and 0xFFFFFFFFL
            info == 27 -> {
                var v = 0L
                repeat(8) { v = (v shl 8) or u8().toLong() }
                v
            }
            else -> throw CborException("不支持的 CBOR additional info（$info，标签/不定长/浮点）")
        }

        fun readItem(): Any? {
            val ib = u8()
            val mt = ib shr 5
            val info = ib and 0x1F
            return when (mt) {
                0 -> norm(len(info))
                1 -> norm(-1 - len(info))
                2 -> bytes(info)
                3 -> {
                    val n = len(info).toInt()
                    val raw = slice(n)
                    String(raw, Charsets.UTF_8)
                }
                4 -> {
                    val n = len(info).toInt()
                    val list = ArrayList<Any?>(n)
                    repeat(n) { list.add(readItem()) }
                    list
                }
                5 -> {
                    val n = len(info).toInt()
                    val map = LinkedHashMap<Any?, Any?>(n)
                    repeat(n) {
                        val k = readItem() ?: throw CborException("CBOR Map 键为 null")
                        map[k] = readItem()
                    }
                    map
                }
                7 -> when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    else -> throw CborException("不支持的 CBOR 简单值（info=$info）")
                }
                else -> throw CborException("不支持的 CBOR major type（$mt）")
            }
        }

        private fun bytes(info: Int): ByteArray {
            val n = len(info).toInt()
            return slice(n)
        }

        private fun slice(n: Int): ByteArray {
            if (n < 0 || pos + n > b.size) throw CborException("CBOR 数据提前结束")
            val out = b.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        /** 放得进 Int 就返回 Int，否则 Long */
        private fun norm(v: Long): Any = if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
    }
}
