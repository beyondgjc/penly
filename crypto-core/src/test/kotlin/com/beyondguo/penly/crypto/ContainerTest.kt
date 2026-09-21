package com.beyondguo.penly.crypto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自描述密文容器（#17）测试。
 *
 * 覆盖三类：往返正确性、篡改拒收（fail-closed）、**未知格式一律拒绝**（不猜不降级）。
 */
class ContainerTest {

    private val password = "correct-horse-battery".toByteArray()

    /** 用最轻档位建库（INTERACTIVE 32MiB），避免测试慢 */
    private fun newHeader() = Container.createHeader(Profile.INTERACTIVE)

    // ---------------- 往返 ----------------

    /** 建库 → 派生 → 加密 → 解密，明文逐字节还原 */
    @Test
    fun `seal open roundtrip restores plaintext`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val aad = "feeling|rec-1".toByteArray()
        val plain = "其实有点孤独".toByteArray(Charsets.UTF_8)

        val ct = Container.seal(header, key, aad, plain)
        assertArrayEquals(plain, Container.open(header, key, aad, ct))
    }

    /** 空明文也要能往返（感受字段允许空？至少机制上不能崩） */
    @Test
    fun `empty plaintext roundtrips`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val aad = "feeling|rec-empty".toByteArray()
        val ct = Container.seal(header, key, aad, ByteArray(0))
        assertArrayEquals(ByteArray(0), Container.open(header, key, aad, ct))
    }

    /** 同一明文两次加密 → 密文不同（nonce 随机），但都能解开 */
    @Test
    fun `nonce is random per seal but both decrypt`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val aad = "feeling|rec-2".toByteArray()
        val plain = "今天很好".toByteArray()

        val c1 = Container.seal(header, key, aad, plain)
        val c2 = Container.seal(header, key, aad, plain)
        assertFalse(c1.payloadB64 == c2.payloadB64) // 随机 nonce：确定性加密会泄露"两条记录相同"
        assertArrayEquals(plain, Container.open(header, key, aad, c1))
        assertArrayEquals(plain, Container.open(header, key, aad, c2))
    }

    /** 载荷布局：12B nonce + 密文 + 16B tag */
    @Test
    fun `payload layout is nonce plus ct plus tag`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val plain = "abc".toByteArray()
        val ct = Container.seal(header, key, ByteArray(0), plain)
        assertEquals(12 + plain.size + 16, Aead.unb64(ct.payloadB64).size)
    }

    // ---------------- fail-closed ----------------

    /** AAD 不符 → IntegrityException（字段隔离：防密文跨记录/跨字段搬移） */
    @Test
    fun `wrong aad is rejected`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val ct = Container.seal(header, key, "feeling|rec-1".toByteArray(), "秘密".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            Container.open(header, key, "feeling|rec-2".toByteArray(), ct)
        }
    }

    /** 密文位翻转 → IntegrityException */
    @Test
    fun `tampered payload is rejected`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val aad = "feeling|rec-3".toByteArray()
        val ct = Container.seal(header, key, aad, "秘密".toByteArray())

        val raw = Aead.unb64(ct.payloadB64).copyOf()
        raw[raw.size - 3] = (raw[raw.size - 3].toInt() xor 1).toByte()
        val tampered = ct.copy(payloadB64 = Aead.b64(raw))
        assertThrows(Aead.IntegrityException::class.java) {
            Container.open(header, key, aad, tampered)
        }
    }

    /** 错误密码派生的 key → 解不开（且抛的是完整性异常，不是乱码） */
    @Test
    fun `wrong password cannot open`() {
        val header = newHeader()
        val goodKey = Container.deriveKey(header, password)
        val badKey = Container.deriveKey(header, "wrong-password".toByteArray())
        val aad = "feeling|rec-4".toByteArray()
        val ct = Container.seal(header, goodKey, aad, "秘密".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            Container.open(header, badKey, aad, ct)
        }
    }

    /** 跨库密文不可互换（不同 salt → 不同 key） */
    @Test
    fun `ciphertext from another vault cannot be opened`() {
        val h1 = newHeader()
        val h2 = newHeader()
        val k1 = Container.deriveKey(h1, password)
        val k2 = Container.deriveKey(h2, password)
        val aad = "feeling|rec-5".toByteArray()
        val ct = Container.seal(h1, k1, aad, "秘密".toByteArray())
        assertThrows(Aead.IntegrityException::class.java) {
            Container.open(h2, k2, aad, ct)
        }
    }

    // ---------------- 未知格式：不猜、不降级 ----------------

    /** 未知容器版本 → UnsupportedFormatException（不尝试按当前版本解读） */
    @Test
    fun `unknown header version is rejected`() {
        val header = newHeader().copy(headerV = 99)
        val key = Container.deriveKey(newHeader(), password)
        assertThrows(UnsupportedFormatException::class.java) {
            Container.seal(header, key, ByteArray(0), "x".toByteArray())
        }
    }

    /** 未知 KDF 算法 → UnsupportedFormatException（不回落 argon2id 试试看） */
    @Test
    fun `unknown kdf alg is rejected`() {
        val header = newHeader().let { it.copy(kdf = it.kdf.copy(alg = "scrypt-future")) }
        assertThrows(UnsupportedFormatException::class.java) {
            Container.deriveKey(header, password)
        }
    }

    /** 未知数据算法 → UnsupportedFormatException（seal / open 两路都要挡） */
    @Test
    fun `unknown data alg is rejected on seal and open`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val bad = header.copy(dataAlg = "chacha20-poly1305")
        assertThrows(UnsupportedFormatException::class.java) {
            Container.seal(bad, key, ByteArray(0), "x".toByteArray())
        }
        // open：库头认识但字段级 algId 不认识
        val good = Container.seal(header, key, ByteArray(0), "x".toByteArray())
        assertThrows(UnsupportedFormatException::class.java) {
            Container.open(header, key, ByteArray(0), good.copy(algId = "future-alg"))
        }
    }

    /** 字段级 algId 显式填成当前算法 → 正常解开（预留的迁移通道可用） */
    @Test
    fun `explicit algId matching current alg still opens`() {
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val aad = "feeling|rec-6".toByteArray()
        val plain = "秘密".toByteArray()
        val ct = Container.seal(header, key, aad, plain)
            .copy(algId = Container.DATA_ALG_AES_256_GCM)
        assertArrayEquals(plain, Container.open(header, key, aad, ct))
    }

    // ---------------- 自描述：参数在数据里，不在代码里 ----------------

    /**
     * **核心不变量**：库头带着自己的参数走。
     *
     * 场景 = "将来把 INTERACTIVE 档位的内存代价改了"。构造一个用旧数值
     * 的库头，用 [Container.deriveKey] 仍能正确派生出 key——
     * 因为它读的是 header 里的数值，不是当前档位定义。
     */
    @Test
    fun `header carries its own params independent of profile definition`() {
        val header = newHeader()
        // 手工改掉库头里的数值（模拟"这是另一个版本写的库"）
        val custom = header.copy(
            kdf = header.kdf.copy(memoryKiB = 8192, iterations = 1, parallelism = 1),
        )
        val key = Container.deriveKey(custom, password)
        val aad = "feeling|rec-7".toByteArray()
        val plain = "秘密".toByteArray()
        val ct = Container.seal(custom, key, aad, plain)
        // 用同一库头解 → 成功（参数从数据里读到了）
        assertArrayEquals(plain, Container.open(custom, key, aad, ct))
    }

    /**
     * 库头 JSON 往返保真。
     *
     * ⚠️ **实测发现**：`Json {}` 默认 `encodeDefaults=false`，所以 `alg` /
     * `dataAlg` / `headerV` 这些**等于默认值的字段不落盘**（本项目既有陷阱，
     * 见工作区 AGENTS.md「BackupCodec 未开 encodeDefaults」条）。
     * 对库头而言这削弱了自描述性——"这库用的什么算法"要靠读代码默认值才知道。
     *
     * 结论：**写库头时必须显式开 `encodeDefaults = true`**（见
     * [header json with encodeDefaults is fully self describing]）。
     * 本条测试反之固定了"默认配置下会省略"这一事实，防止有人误以为
     * 默认配置就能满足自描述需求。
     */
    @Test
    fun `header json roundtrip preserves everything`() {
        val header = newHeader()
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(VaultHeader.serializer(), header)
        val decoded = json.decodeFromString(VaultHeader.serializer(), encoded)
        // 往返保真（缺省字段反序列化时由默认值补回，故相等仍成立）
        assertEquals(header, decoded)
        // 默认配置下 alg/dataAlg 等于默认值 → 不落盘
        assertFalse(encoded.contains("argon2id"))
        assertFalse(encoded.contains("payload"))
        // 但 salt / 数值必须落盘（它们不是默认值，缺了就真解不开）
        assertTrue(encoded.contains(header.kdf.saltB64))
        assertTrue(encoded.contains(header.kdf.memoryKiB.toString()))
    }

    /**
     * **写库头必须用 `encodeDefaults = true`** —— 让"这个库用什么算法/版本"
     * 变成磁盘上的显式事实，而不是靠读代码默认值推断。
     *
     * 这条纪律对容器尤其重要：解析方可能是**另一个 App、另一个版本**，
     * 它不该被迫去猜"没写出来的字段等于什么"。
     */
    @Test
    fun `header json with encodeDefaults is fully self describing`() {
        val header = newHeader()
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(VaultHeader.serializer(), header)
        assertTrue(encoded.contains("argon2id"))
        assertTrue(encoded.contains("aes-256-gcm"))
        assertTrue(encoded.contains("\"headerV\":1"))
        val decoded = json.decodeFromString(VaultHeader.serializer(), encoded)
        assertEquals(header, decoded)
    }

    /** 字段密文 JSON 往返：algId 缺省时不落盘（与项目既有隐式契约一致） */
    @Test
    fun `field ciphertext json omits default algId`() {
        val json = Json { ignoreUnknownKeys = true }
        val ct = FieldCiphertext(payloadB64 = "AAA=")
        val encoded = json.encodeToString(FieldCiphertext.serializer(), ct)
        assertFalse(encoded.contains("algId"))

        val decoded = json.decodeFromString(FieldCiphertext.serializer(), encoded)
        assertEquals("AAA=", decoded.payloadB64)
        assertEquals(null, decoded.algId)
    }

    /** 端到端：库头 + 密文 两个 JSON 一起落盘再读回，仍能解开 */
    @Test
    fun `full persistence roundtrip header and ct`() {
        val json = Json { ignoreUnknownKeys = true }
        val header = newHeader()
        val key = Container.deriveKey(header, password)
        val aad = "feeling|rec-8".toByteArray()
        val plain = "记录一条感受".toByteArray(Charsets.UTF_8)

        val headerJson = json.encodeToString(VaultHeader.serializer(), header)
        val ct = Container.seal(header, key, aad, plain)
        val ctJson = json.encodeToString(FieldCiphertext.serializer(), ct)

        // 模拟重开 App：从 JSON 读回，用密码重新派生
        val header2 = json.decodeFromString(VaultHeader.serializer(), headerJson)
        val ct2 = json.decodeFromString(FieldCiphertext.serializer(), ctJson)
        val key2 = Container.deriveKey(header2, password)
        assertArrayEquals(plain, Container.open(header2, key2, aad, ct2))
    }
}
