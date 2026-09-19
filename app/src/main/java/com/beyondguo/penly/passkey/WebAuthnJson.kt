package com.beyondguo.penly.passkey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WebAuthn 请求 JSON 的最小解析（W3C WebAuthn L3 §5.4/§5.5 的 JSON 化变体，
 * 键名 camelCase、二进制字段 base64url）。
 *
 * 只取流程/展示需要的字段；格式非法时抛 [IllegalArgumentException]，
 * 由调用方转成凭据异常回传 RP（fail-closed，不做静默兜底）。
 */
internal object WebAuthnJson {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    /** 注册请求（PublicKeyCredentialCreationOptionsJSON） */
    data class CreateInfo(
        val rpId: String,
        val rpName: String,
        val userName: String,
        val userIdB64Url: String,
        val challengeB64Url: String,
    )

    fun parseCreate(requestJson: String): CreateInfo {
        val root = obj(requestJson)
        val rp = root["rp"]?.jsonObject ?: throw IllegalArgumentException("缺少 rp")
        val user = root["user"]?.jsonObject ?: throw IllegalArgumentException("缺少 user")
        return CreateInfo(
            rpId = rp["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 rp.id"),
            rpName = rp["name"]?.jsonPrimitive?.content ?: "",
            userName = user["name"]?.jsonPrimitive?.content ?: "",
            userIdB64Url = user["id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少 user.id"),
            challengeB64Url = root["challenge"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少 challenge"),
        )
    }

    /** 断言请求（PublicKeyCredentialRequestOptionsJSON） */
    data class GetInfo(
        val rpId: String,
        val challengeB64Url: String,
        val allowedCredIdsB64Url: List<String>,
    )

    fun parseGet(requestJson: String): GetInfo {
        val root = obj(requestJson)
        val rpId = root["rpId"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少 rpId")
        val allowed = root["allowCredentials"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            ?: emptyList()
        return GetInfo(
            rpId = rpId,
            challengeB64Url = root["challenge"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少 challenge"),
            allowedCredIdsB64Url = allowed,
        )
    }
}
