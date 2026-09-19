package com.beyondguo.penly.passkey

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * WebAuthn Level 3 最小实现（#46 编解码层）——ES256 软件密钥对 + attestation fmt=none。
 *
 * 纯 JVM（JCA），无 Android 依赖，可在 host 单测直接验证；#47 的
 * ProviderService / PasskeyActivity 只做请求解析与响应包装，密码学部分全部收口到这里。
 *
 * 覆盖范围（第一期 passkey-only）：
 * - 注册（create）：authData(rpidHash|flags|0|aaguid|credId|COSE 公钥) + attestationObject(fmt=none)
 * - 断言（get）：authData(rpIdHash|flags|signCount) + ES256 签名（ASN.1 DER，WebAuthn §6.5.6）
 * - clientDataJSON：固定键序 {type, challenge, origin, crossOrigin}
 */
object WebAuthn {

    class WebAuthnException(message: String) : Exception(message)

    // COSE_Key 常量（RFC 9052 / WebAuthn §6.4.1）
    const val COSE_KTY_EC2 = 2
    const val COSE_ALG_ES256 = -7
    const val COSE_CRV_P256 = 1

    // authenticatorData flags（WebAuthn §6.1）
    const val FLAG_UP = 0x01
    const val FLAG_UV = 0x04
    const val FLAG_BE = 0x08
    const val FLAG_BS = 0x10
    const val FLAG_AT = 0x40

    const val TYPE_CREATE = "webauthn.create"
    const val TYPE_GET = "webauthn.get"

    // ---------------- 密钥 ----------------

    /** 生成 P-256（secp256r1）密钥对；私钥经 PKCS8 DER(base64) 落库，公钥进 COSE */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun privateKeyFromPkcs8(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun rpIdHash(rpId: String): ByteArray = sha256(rpId.toByteArray(Charsets.UTF_8))

    // ---------------- COSE 公钥 ----------------

    /** ES256 公钥 → COSE_Key（kty=EC2, alg=ES256, crv=P-256, x/y 各 32B） */
    fun cosePublicKey(pub: PublicKey): Map<Int, Any?> {
        val ec = pub as? ECPublicKey ?: throw WebAuthnException("非 EC 公钥")
        return mapOf(
            1 to COSE_KTY_EC2,
            3 to COSE_ALG_ES256,
            -1 to COSE_CRV_P256,
            -2 to coord32(ec.w.affineX),
            -3 to coord32(ec.w.affineY),
        )
    }

    /** BigInteger → 定长 32B 无符号大端（处理符号位补零/前导零） */
    private fun coord32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> throw WebAuthnException("EC 坐标长度异常：${b.size}")
        }
    }

    // ---------------- authenticatorData ----------------

    /**
     * 注册用 authData：
     * rpIdHash(32) | flags(1) | signCount(4，恒 0) | aaguid(16 全零) | credIdLen(2 BE) | credId | COSE 公钥
     * flags = UP|UV|BE|BS|AT —— BE/BS 置位是诚实语义：私钥随金库加密存储、可随备份恢复。
     */
    fun registrationAuthData(rpId: String, credId: ByteArray, cosePubKey: Map<Int, Any?>): ByteArray {
        if (credId.isEmpty() || credId.size > 1023) throw WebAuthnException("credentialId 长度非法（${credId.size}）")
        val flags = FLAG_UP or FLAG_UV or FLAG_BE or FLAG_BS or FLAG_AT
        val attested = ByteArray(16) + // aaguid：无 AAGUID 的软件凭证用全零
            byteArrayOf((credId.size shr 8).toByte(), credId.size.toByte()) + credId +
            WebCbor.encode(cosePubKey)
        return rpIdHash(rpId) + byteArrayOf(flags.toByte()) + ByteArray(4) + attested
    }

    /** 断言用 authData：rpIdHash(32) | flags(1) | signCount(4 BE)，无 attestedCredentialData */
    fun assertionAuthData(rpId: String, signCount: Int): ByteArray {
        val flags = FLAG_UP or FLAG_UV or FLAG_BE or FLAG_BS
        return rpIdHash(rpId) + byteArrayOf(flags.toByte()) +
            byteArrayOf(
                (signCount ushr 24).toByte(),
                (signCount ushr 16).toByte(),
                (signCount ushr 8).toByte(),
                signCount.toByte(),
            )
    }

    // ---------------- attestation / 签名 / 响应 ----------------

    /** fmt=none attestationObject：{fmt, attStmt:{}, authData}（密钥来源证明不提供，RP 端信任 none） */
    fun attestationObject(authData: ByteArray): ByteArray = WebCbor.encode(
        mapOf(
            "fmt" to "none",
            "attStmt" to emptyMap<Any, Any?>(),
            "authData" to authData,
        ),
    )

    /**
     * 断言签名 = ES256(priv, authData || SHA256(clientDataJSON))，输出 ASN.1 DER。
     * JCA「SHA256withECDSA」自带内层哈希，喂原始拼接即可。
     */
    fun signAssertion(privPkcs8: ByteArray, authData: ByteArray, clientDataJson: String): ByteArray =
        signAssertionHash(privPkcs8, authData, sha256(clientDataJson.toByteArray(Charsets.UTF_8)))

    /**
     * 断言签名（调用方已持 clientDataHash 变体）。
     * 输出 = **ASN.1 DER** Ecdsa-Sig-Value——WebAuthn §6.5.6 规定 ES256 断言签名
     * 必须是 DER（混淆 FIDO U2F 的 raw r||s 会导致 RP 端 cryptography 库验签失败，
     * 2026-09-19 模拟器实测 + py_webauthn verify_authentication_response 源码实锤：
     * challenge/origin/rpIdHash/signCount 全过后仍抛 InvalidSignature）。
     * JCA「SHA256withECDSA」的 sign() 输出恰为 DER，直接透传，勿再转 raw。
     *
     * Chrome（CredMan 路径，CredManHelper.java）会把响应里的 clientDataJSON 覆盖为
     * 浏览器自生成的版本，并把 SHA256(该版本) 放在请求 extras 的
     * androidx.credentials.BUNDLE_KEY_CLIENT_DATA_HASH —— 签名必须对这份哈希
     * （2026-09-19 实测 cdHash=32B 非空）。
     */
    fun signAssertionHash(privPkcs8: ByteArray, authData: ByteArray, clientDataHash: ByteArray): ByteArray {
        val priv = privateKeyFromPkcs8(privPkcs8)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(priv)
        sig.update(authData + clientDataHash)
        return sig.sign()
    }

    /** base64url（无 padding）——WebAuthn 各 JSON 字段的标准编码 */
    fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** clientDataJSON：固定键序 {type, challenge, origin, crossOrigin:false} */
    fun clientDataJson(type: String, challenge: ByteArray, origin: String): String {
        val chal = b64Url(challenge)
        return "{\"type\":\"${jsonEscape(type)}\",\"challenge\":\"$chal\"," +
            "\"origin\":\"${jsonEscape(origin)}\",\"crossOrigin\":false}"
    }

    /**
     * PublicKeyCredential 注册响应（PublicKeyCredentialJSON）。
     * W3C 规范多为可选，但 Chrome（CredMan 路径，MakeCredentialResponseFromValue，
     * components/webauthn/json/value_conversions.cc）把以下字段当必填/强校验
     * （2026-09-19 模拟器实测 + Chromium 源码实锤）：
     * - publicKey：**SPKI DER**（X.509 公钥编码），不是 COSE 字节！Chrome 会从
     *   attObj 解析 COSE 公钥并算出 SPKI，与我们传的逐字节对比，不一致即报
     *   "field missing or invalid: publicKey"；ES256/Ed25519 缺失同样报错。
     *   （Android 侧 kp.public.encoded 即 SPKI DER）
     * - authenticatorData：与 attObj 内 authData 逐字节一致（Chrome 同样对比）
     * - publicKeyAlgorithm：必须等于 Chrome 从 attObj 解析出的算法（-7）
     * - transports：必填数组（如 ["internal","hybrid"]）
     * - clientExtensionResults：必填对象（顶层字段，非 response 内），传 {}
     */
    fun registrationJson(
        credId: ByteArray,
        authData: ByteArray,
        spkiDer: ByteArray,
        attObj: ByteArray,
        clientData: String,
    ): String =
        "{\"id\":\"${b64Url(credId)}\",\"rawId\":\"${b64Url(credId)}\",\"type\":\"public-key\"," +
            "\"response\":{\"authenticatorData\":\"${b64Url(authData)}\"," +
            "\"publicKey\":\"${b64Url(spkiDer)}\"," +
            "\"attestationObject\":\"${b64Url(attObj)}\"," +
            "\"clientDataJSON\":\"${b64Url(clientData.toByteArray(Charsets.UTF_8))}\"," +
            "\"publicKeyAlgorithm\":$COSE_ALG_ES256," +
            "\"transports\":[\"internal\",\"hybrid\"]}," +
            "\"clientExtensionResults\":{}}"

    /** PublicKeyCredential 断言响应 */
    fun assertionJson(
        credId: ByteArray,
        userHandle: ByteArray,
        authData: ByteArray,
        clientData: String,
        signature: ByteArray,
    ): String =
        "{\"id\":\"${b64Url(credId)}\",\"rawId\":\"${b64Url(credId)}\",\"type\":\"public-key\"," +
            "\"response\":{\"authenticatorData\":\"${b64Url(authData)}\"," +
            "\"clientDataJSON\":\"${b64Url(clientData.toByteArray(Charsets.UTF_8))}\"," +
            "\"signature\":\"${b64Url(signature)}\"," +
            "\"userHandle\":\"${b64Url(userHandle)}\"}," +
            // Chrome（GetAssertionResponseFromValue）把顶层 clientExtensionResults 当必填对象
            "\"clientExtensionResults\":{}}"

    // ---------------- DER ↔ raw 互转（ES256 签名格式） ----------------

    /** JCA 输出 DER（SEQUENCE{r,s}）→ WebAuthn raw r||s（各定长 32B） */
    fun derToRaw(der: ByteArray): ByteArray {
        if (der.isEmpty() || der[0] != 0x30.toByte()) throw WebAuthnException("签名 DER 结构错误")
        var pos = skipDerLen(der, 1)
        pos = expect(der, pos, 0x02)
        val (rLen, rNext) = readDerLen(der, pos)
        pos = rNext
        if (pos + rLen > der.size) throw WebAuthnException("签名 DER 截断（r）")
        val r = pad32(der.copyOfRange(pos, pos + rLen))
        pos += rLen
        pos = expect(der, pos, 0x02)
        val (sLen, sNext) = readDerLen(der, pos)
        pos = sNext
        if (pos + sLen > der.size) throw WebAuthnException("签名 DER 截断（s）")
        val s = pad32(der.copyOfRange(pos, pos + sLen))
        return r + s
    }

    /** raw r||s（64B）→ DER（供把 raw 签名喂回 JCA verify 做交叉验证） */
    fun rawToDer(raw: ByteArray): ByteArray {
        if (raw.size != 64) throw WebAuthnException("raw 签名应为 64 字节（实际 ${raw.size}）")
        val r = derInt(raw.copyOfRange(0, 32))
        val s = derInt(raw.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun expect(b: ByteArray, pos: Int, tag: Int): Int {
        if (pos >= b.size || (b[pos].toInt() and 0xFF) != tag) throw WebAuthnException("签名 DER 结构错误（位置 $pos）")
        return pos + 1
    }

    /** 跳过长度字段，返回下一位置 */
    private fun skipDerLen(b: ByteArray, pos: Int): Int {
        val first = b[pos].toInt() and 0xFF
        return if (first < 0x80) pos + 1 else pos + 1 + (first and 0x7F)
    }

    /** 读长度字段，返回 (长度, 下一位置) */
    private fun readDerLen(b: ByteArray, pos: Int): Pair<Int, Int> {
        val first = b[pos].toInt() and 0xFF
        return when {
            first < 0x80 -> first to pos + 1
            first == 0x80 -> throw WebAuthnException("签名 DER 不定长")
            else -> {
                val n = first and 0x7F
                if (n > 4) throw WebAuthnException("签名 DER 长度字段异常")
                var v = 0
                for (i in 0 until n) v = (v shl 8) or (b[pos + 1 + i].toInt() and 0xFF)
                v to pos + 1 + n
            }
        }
    }

    private fun pad32(b: ByteArray): ByteArray = when {
        b.size == 32 -> b
        b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
        b.size < 32 -> ByteArray(32 - b.size) + b
        else -> throw WebAuthnException("签名分量长度异常：${b.size}")
    }

    /** 32B 无符号分量 → 最小 DER INTEGER 内容（去多余前导零 + 正数符号位处理） */
    private fun derInt(component: ByteArray): ByteArray {
        var start = 0
        while (start < component.size - 1 && component[start] == 0.toByte()) start++
        val content = component.copyOfRange(start, component.size)
        val withSign = if (content[0].toInt() and 0x80 != 0) byteArrayOf(0) + content else content
        return byteArrayOf(0x02, withSign.size.toByte()) + withSign
    }

    // ---------------- JSON 转义 ----------------

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
