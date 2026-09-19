package com.beyondguo.penly.passkey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.Signature
import java.util.Base64

/**
 * #46 WebAuthn/CBOR 编解码层单测（纯 JVM）。
 * CBOR 用 RFC 8949 固定向量钉死；WebAuthn 用结构断言 + JCA 签名交叉验证钉死。
 */
class WebAuthnTest {

    // ================= CBOR：RFC 8949 固定向量 =================

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { i -> v[i].toByte() }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) = assertArrayEquals(expected, actual)

    @Test
    fun `cbor rfc vectors`() {
        assertBytes(bytes(0x01), WebCbor.encode(1))
        assertBytes(bytes(0x18, 0x18), WebCbor.encode(24))
        assertBytes(bytes(0x19, 0x03, 0xE8), WebCbor.encode(1000))
        assertBytes(bytes(0x26), WebCbor.encode(-7))
        // RFC §A.1：-1000 → 39 03 e7（major1/info25 头字节 = 0x39）
        assertBytes(bytes(0x39, 0x03, 0xE7), WebCbor.encode(-1000))
        assertBytes(bytes(0x61, 0x61), WebCbor.encode("a"))
        assertBytes(bytes(0x44, 0x01, 0x02, 0x03, 0x04), WebCbor.encode(bytes(1, 2, 3, 4)))
        assertBytes(bytes(0x83, 0x01, 0x02, 0x03), WebCbor.encode(listOf(1, 2, 3)))
        assertBytes(bytes(0x80), WebCbor.encode(emptyList<Any?>()))
        assertBytes(bytes(0xF5), WebCbor.encode(true))
        assertBytes(bytes(0xF6), WebCbor.encode(null))
        // RFC §A.1：{"a":1, "b":[2,3]} → A2 6161 01 6162 8202 03
        assertBytes(
            bytes(0xA2, 0x61, 0x61, 0x01, 0x61, 0x62, 0x82, 0x02, 0x03),
            WebCbor.encode(mapOf("a" to 1, "b" to listOf(2, 3))),
        )
        // COSE 头部：{1:2, 3:-7} → A1 01 02 03 26
        assertBytes(bytes(0xA2, 0x01, 0x02, 0x03, 0x26), WebCbor.encode(mapOf(1 to 2, 3 to -7)))
    }

    @Test
    fun `cbor roundtrip all types`() {
        val value: Map<Any?, Any?> = mapOf(
            1 to 2,
            -3 to "文本串",
            "bin" to bytes(0xDE, 0xAD, 0xBE, 0xEF),
            "list" to listOf(1, -2, true, null, "x"),
            "big" to 4_000_000_000L,
            1000 to "int-key",
        )
        val decoded = WebCbor.decode(WebCbor.encode(value)) as Map<*, *>
        // ByteArray 是引用相等，不能整 Map equals——逐字段断言
        assertEquals(2, decoded[1])
        assertEquals("文本串", decoded[-3])
        assertArrayEquals(bytes(0xDE, 0xAD, 0xBE, 0xEF), decoded["bin"] as ByteArray)
        assertEquals(listOf(1, -2, true, null, "x"), decoded["list"])
        assertEquals(4_000_000_000L, decoded["big"])
        assertEquals("int-key", decoded[1000])
    }

    @Test
    fun `cbor canonical map key ordering`() {
        // 键序按编码字节排序："b"(0x62) < "a"(0x61) 原序插入 → 输出应重排为 a 在前
        val out = WebCbor.encode(mapOf("b" to 1, "a" to 2))
        assertBytes(bytes(0xA2, 0x61, 0x61, 0x02, 0x61, 0x62, 0x01), out)
    }

    @Test
    fun `cbor rejects trailing garbage and truncation`() {
        try {
            WebCbor.decode(bytes(0x01, 0x02))
            fail("尾部多余字节应抛异常")
        } catch (_: WebCbor.CborException) {
        }
        try {
            WebCbor.decode(byteArrayOf())
            fail("空输入应抛异常")
        } catch (_: WebCbor.CborException) {
        }
        // 长度声明超出实际（字节串 0x45=5B，只给 2B）
        try {
            WebCbor.decode(bytes(0x45, 0x01, 0x02))
            fail("截断数据应抛异常")
        } catch (_: WebCbor.CborException) {
        }
    }

    // ================= WebAuthn：结构 =================

    private val rpId = "example.com"

    @Test
    fun `cose public key structure and encoding`() {
        val kp = WebAuthn.generateKeyPair()
        val cose = WebAuthn.cosePublicKey(kp.public)
        val out = WebCbor.encode(cose)
        // A5 | 01 02 | 03 26 | 20 01 | 21 5820 <x32> | 22 5820 <y32> = 77 字节
        // （32B 字节串头要 2 字节：0x58 0x20；键序 1,3,-1,-2,-3）
        assertEquals(77, out.size)
        assertBytes(
            bytes(0xA5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21),
            out.copyOfRange(0, 8),
        )
        val decoded = WebCbor.decode(out) as Map<*, *>
        assertEquals(2, decoded[1])
        assertEquals(-7, decoded[3])
        assertEquals(1, decoded[-1])
        val x = decoded[-2] as ByteArray
        val y = decoded[-3] as ByteArray
        assertEquals(32, x.size)
        assertEquals(32, y.size)
        val ec = kp.public as java.security.interfaces.ECPublicKey
        assertEquals(ec.w.affineX, BigInteger(1, x))
        assertEquals(ec.w.affineY, BigInteger(1, y))
    }

    @Test
    fun `registration authData structure`() {
        val kp = WebAuthn.generateKeyPair()
        val credId = ByteArray(16) { it.toByte() }
        val ad = WebAuthn.registrationAuthData(rpId, credId, WebAuthn.cosePublicKey(kp.public))
        // 37 + aaguid(16) + len(2) + credId(16) + cose(77)
        assertEquals(148, ad.size)
        assertArrayEquals(WebAuthn.rpIdHash(rpId), ad.copyOfRange(0, 32))
        val flags = ad[32].toInt() and 0xFF
        assertEquals(WebAuthn.FLAG_UP or WebAuthn.FLAG_UV or WebAuthn.FLAG_BE or WebAuthn.FLAG_BS or WebAuthn.FLAG_AT, flags)
        // signCount 恒 0 + aaguid 全零
        assertTrue(ad.copyOfRange(33, 53).all { it.toInt() == 0 })
        // credIdLen(2 BE) = 16
        assertEquals(16, ((ad[53].toInt() and 0xFF) shl 8) or (ad[54].toInt() and 0xFF))
        assertArrayEquals(credId, ad.copyOfRange(55, 71))
        val cose = WebCbor.decode(ad.copyOfRange(71, ad.size)) as Map<*, *>
        assertEquals(2, cose[1])
    }

    @Test
    fun `assertion authData structure`() {
        val ad = WebAuthn.assertionAuthData(rpId, 0x01020304)
        assertEquals(37, ad.size)
        assertArrayEquals(WebAuthn.rpIdHash(rpId), ad.copyOfRange(0, 32))
        val flags = ad[32].toInt() and 0xFF
        assertEquals(WebAuthn.FLAG_UP or WebAuthn.FLAG_UV or WebAuthn.FLAG_BE or WebAuthn.FLAG_BS, flags)
        assertTrue((flags and WebAuthn.FLAG_AT) == 0)
        assertBytes(bytes(0x01, 0x02, 0x03, 0x04), ad.copyOfRange(33, 37))
    }

    @Test
    fun `attestation object fmt none`() {
        val kp = WebAuthn.generateKeyPair()
        val ad = WebAuthn.registrationAuthData(rpId, bytes(1, 2, 3), WebAuthn.cosePublicKey(kp.public))
        val decoded = WebCbor.decode(WebAuthn.attestationObject(ad)) as Map<*, *>
        assertEquals("none", decoded["fmt"])
        assertTrue((decoded["attStmt"] as Map<*, *>).isEmpty())
        assertArrayEquals(ad, decoded["authData"] as ByteArray)
    }

    @Test
    fun `clientDataJson field order and values`() {
        val challenge = "挑战字节!".toByteArray(Charsets.UTF_8)
        val cdj = WebAuthn.clientDataJson(WebAuthn.TYPE_CREATE, challenge, "https://$rpId")
        val j = Json.parseToJsonElement(cdj).jsonObject
        assertEquals("webauthn.create", j["type"]!!.jsonPrimitive.content)
        assertEquals("https://$rpId", j["origin"]!!.jsonPrimitive.content)
        assertEquals("false", j["crossOrigin"]!!.jsonPrimitive.content)
        // challenge base64url 可逆
        val back = Base64.getUrlDecoder().decode(j["challenge"]!!.jsonPrimitive.content)
        assertArrayEquals(challenge, back)
    }

    // ================= WebAuthn：签名与密钥存取 =================

    @Test
    fun `signAssertion outputs DER verified by JCA`() {
        val kp = WebAuthn.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.private.encoded)

        // PKCS8 存取还原无损
        val restored = WebAuthn.privateKeyFromPkcs8(Base64.getDecoder().decode(privB64))
        assertArrayEquals(kp.private.encoded, restored.encoded)

        val authData = WebAuthn.assertionAuthData(rpId, 5)
        val cdj = WebAuthn.clientDataJson(WebAuthn.TYPE_GET, "challenge".toByteArray(), "https://$rpId")
        val sig = WebAuthn.signAssertion(Base64.getDecoder().decode(privB64), authData, cdj)
        // WebAuthn §6.5.6：ES256 断言签名 = ASN.1 DER Ecdsa-Sig-Value（非 raw 64B）
        assertEquals(0x30, sig[0].toInt() and 0xFF)
        assertTrue(sig.size in 68..72)

        val signed = authData + WebAuthn.sha256(cdj.toByteArray(Charsets.UTF_8))
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(kp.public)
        v.update(signed)
        assertTrue(v.verify(sig))

        // 篡改签名数据 → verify 拒绝
        val bad = signed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val v2 = Signature.getInstance("SHA256withECDSA")
        v2.initVerify(kp.public)
        v2.update(bad)
        assertFalse(v2.verify(sig))
    }

    @Test
    fun `raw and der signature roundtrip lossless`() {
        // 固定 raw r||s 与 DER 互转工具（WebAuthn 断言签名走 DER，此工具仅作编解码备用）
        val raw = ByteArray(64) { (it * 7).toByte() }
        val der = WebAuthn.rawToDer(raw)
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertArrayEquals(raw, WebAuthn.derToRaw(der))
    }

    @Test
    fun `signAssertionHash matches signAssertion and verifies via JCA`() {
        val kp = WebAuthn.generateKeyPair()
        val authData = WebAuthn.assertionAuthData(rpId, 7)
        val cdj = WebAuthn.clientDataJson(WebAuthn.TYPE_GET, "chal".toByteArray(), "https://$rpId")
        val cdHash = WebAuthn.sha256(cdj.toByteArray(Charsets.UTF_8))
        // ECDSA 随机化签名：同输入两次结果不同，但两者都对 (authData||cdHash) 验签通过
        val a = WebAuthn.signAssertion(kp.private.encoded, authData, cdj)
        val b = WebAuthn.signAssertionHash(kp.private.encoded, authData, cdHash)
        // WebAuthn §6.5.6：ES256 断言签名必须是 ASN.1 DER（raw r||s 会被 RP 端拒验）
        for (sigBytes in listOf(a, b)) {
            assertEquals(0x30, sigBytes[0].toInt() and 0xFF)
            assertTrue(sigBytes.size in 68..72)
        }
        // 注意 Signature.verify() 会重置对象，每次验签须重新 update
        for (sigBytes in listOf(a, b)) {
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(kp.public)
            v.update(authData + cdHash)
            assertTrue(v.verify(sigBytes))
        }
    }

    @Test
    fun `response json parses and roundtrips`() {
        val kp = WebAuthn.generateKeyPair()
        val credId = bytes(0xAA, 0xBB)
        val userHandle = bytes(0x11, 0x22)
        val cose = WebAuthn.cosePublicKey(kp.public)
        val authData = WebAuthn.registrationAuthData(rpId, credId, cose)
        val attObj = WebAuthn.attestationObject(authData)
        val cdj = WebAuthn.clientDataJson(WebAuthn.TYPE_CREATE, "c1".toByteArray(), "https://$rpId")
        val reg = Json.parseToJsonElement(
            WebAuthn.registrationJson(credId, authData, kp.public.encoded, attObj, cdj),
        ).jsonObject
        assertEquals("public-key", reg["type"]!!.jsonPrimitive.content)
        assertArrayEquals(credId, Base64.getUrlDecoder().decode(reg["id"]!!.jsonPrimitive.content))
        val resp = reg["response"]!!.jsonObject
        // Chrome（MakeCredentialResponseFromValue）强校验：publicKey 必须是 SPKI DER
        // 且与 attObj 内 COSE 公钥解析结果逐字节一致；authenticatorData 与 attObj 内一致
        assertArrayEquals(authData, Base64.getUrlDecoder().decode(resp["authenticatorData"]!!.jsonPrimitive.content))
        assertArrayEquals(kp.public.encoded, Base64.getUrlDecoder().decode(resp["publicKey"]!!.jsonPrimitive.content))
        assertArrayEquals(attObj, Base64.getUrlDecoder().decode(resp["attestationObject"]!!.jsonPrimitive.content))
        assertArrayEquals(cdj.toByteArray(), Base64.getUrlDecoder().decode(resp["clientDataJSON"]!!.jsonPrimitive.content))
        assertEquals(WebAuthn.COSE_ALG_ES256, resp["publicKeyAlgorithm"]!!.jsonPrimitive.content.toInt())
        // Chrome 必填：transports 数组 + 顶层 clientExtensionResults 对象
        assertTrue(resp["transports"]!!.jsonArray.isNotEmpty())
        assertTrue(reg["clientExtensionResults"]!!.jsonObject.isEmpty())

        val sig = WebAuthn.signAssertion(kp.private.encoded, WebAuthn.assertionAuthData(rpId, 1), cdj)
        val asr = Json.parseToJsonElement(
            WebAuthn.assertionJson(credId, userHandle, WebAuthn.assertionAuthData(rpId, 1), cdj, sig),
        ).jsonObject
        val aResp = asr["response"]!!.jsonObject
        assertArrayEquals(userHandle, Base64.getUrlDecoder().decode(aResp["userHandle"]!!.jsonPrimitive.content))
        assertArrayEquals(sig, Base64.getUrlDecoder().decode(aResp["signature"]!!.jsonPrimitive.content))
        // Chrome 必填：顶层 clientExtensionResults 对象
        assertTrue(asr["clientExtensionResults"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `malformed inputs fail closed`() {
        try {
            WebAuthn.derToRaw(bytes(0x02, 0x01, 0x00))
            fail("非 SEQUENCE 应抛异常")
        } catch (_: WebAuthn.WebAuthnException) {
        }
        try {
            WebAuthn.rawToDer(bytes(1, 2, 3))
            fail("raw 非 64 字节应抛异常")
        } catch (_: WebAuthn.WebAuthnException) {
        }
        try {
            WebAuthn.registrationAuthData(rpId, ByteArray(0), WebAuthn.cosePublicKey(WebAuthn.generateKeyPair().public))
            fail("空 credId 应抛异常")
        } catch (_: WebAuthn.WebAuthnException) {
        }
    }
}
