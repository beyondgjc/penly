package com.beyondguo.penly.passkey

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.beyondguo.penly.bio.BioManager
import com.beyondguo.penly.crypto.Aead
import com.beyondguo.penly.data.UnlockResult
import com.beyondguo.penly.penly
import com.beyondguo.penly.ui.theme.PenlyTheme
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.launch

/**
 * Passkey 流程第二阶段（v5.0-②）：系统凭据选择器选中印迹条目 → 本 Activity 被拉起
 * → 指纹/主密码解锁（与 AutofillAuthActivity 同款两段式门禁）→ 执行实际的
 * 创建/断言 → PendingIntentHandler 回写响应 → setResult 交还系统。
 *
 * 请求来源（双阶段协议）：系统在拉起本 Activity 时把完整请求并入 intent extras，
 * 经 [PendingIntentHandler.retrieveProviderCreateCredentialRequest] /
 * [PendingIntentHandler.retrieveProviderGetCredentialRequest] 恢复——这正是
 * Service 侧 PendingIntent 必须 FLAG_MUTABLE 的原因。
 *
 * 响应回传口径：
 * - 成功 → setCreateCredentialResponse / setGetCredentialResponse + RESULT_OK；
 * - 失败/无匹配 → setCreateCredentialException / setGetCredentialException + RESULT_OK
 *   （系统会把异常投递给调用方 RP）；用户主动取消 → RESULT_CANCELED。
 *
 * 第一期语义（2026-09-19 拍板 passkey-only）：
 * - 注册：软件密钥对（P-256），私钥进金库 passkey 字段加密存储（随备份走）；
 *   userHandle 采用 RP 下发的 user.id 原样回传；
 * - 断言：按 rpId 过滤，多个匹配时取第一条（多账号选择器留 P1）；
 *   allowCredentials 非空时按 credentialId 字节精确匹配；签名计数先自增再写入。
 *
 * API 备注（credentials-1.3.0，javap 核实）：create 侧请求是
 * [CreatePublicKeyCredentialRequest]；get 侧选项是 [GetPublicKeyCredentialOption]；
 * get 响应统一用 [PublicKeyCredential]（携带 authenticationResponseJson）。
 */
class PasskeyActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = penly.repo

        val createReq = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val getReq = if (createReq == null) {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } else null
        if (createReq == null && getReq == null) {
            // 非凭据流程拉起（不应发生）——直接结束，避免卡在透明浮层
            finish()
            return
        }
        val isCreate = createReq != null

        setContent {
            PenlyTheme {
                var requiresPwd by remember { mutableStateOf<Boolean?>(null) }
                var showUi by remember { mutableStateOf(false) }
                var pwd by remember { mutableStateOf("") }
                var error by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                fun respondError(msg: String) {
                    val out = Intent()
                    if (isCreate) {
                        PendingIntentHandler.setCreateCredentialException(
                            out, CreateCredentialUnknownException(msg),
                        )
                    } else {
                        PendingIntentHandler.setGetCredentialException(
                            out, GetCredentialUnknownException(msg),
                        )
                    }
                    setResult(RESULT_OK, out)
                    finish()
                }

                fun doCreate() {
                    scope.launch {
                        try {
                            val pkReq = createReq?.callingRequest as? CreatePublicKeyCredentialRequest
                                ?: return@launch respondError("不支持的凭据类型")
                            val info = WebAuthnJson.parseCreate(pkReq.requestJson)
                            val kp = WebAuthn.generateKeyPair()
                            val credId = ByteArray(32).also { SecureRandom().nextBytes(it) }
                            repo.savePasskey(
                                rpId = info.rpId,
                                rpName = info.rpName,
                                userName = info.userName,
                                userHandleB64 = Aead.b64(
                                    Base64.getUrlDecoder().decode(info.userIdB64Url),
                                ),
                                credIdB64 = Aead.b64(credId),
                                privPkcs8B64 = Aead.b64(kp.private.encoded),
                            )
                            val cose = WebAuthn.cosePublicKey(kp.public)
                            val authData = WebAuthn.registrationAuthData(info.rpId, credId, cose)
                            val cdj = WebAuthn.clientDataJson(
                                WebAuthn.TYPE_CREATE,
                                Base64.getUrlDecoder().decode(info.challengeB64Url),
                                "https://" + info.rpId,
                            )
                            val attObj = WebAuthn.attestationObject(authData)
                            val out = Intent()
                            PendingIntentHandler.setCreateCredentialResponse(
                                out,
                                CreatePublicKeyCredentialResponse(
                                    WebAuthn.registrationJson(credId, authData, kp.public.encoded, attObj, cdj),
                                ),
                            )
                            setResult(RESULT_OK, out)
                            finish()
                        } catch (e: Exception) {
                            respondError("创建 Passkey 失败：${e.message ?: "未知错误"}")
                        }
                    }
                }

                fun doGet() {
                    scope.launch {
                        try {
                            val pkReq = getReq?.credentialOptions
                                ?.filterIsInstance<GetPublicKeyCredentialOption>()
                                ?.firstOrNull()
                                ?: return@launch respondError("不支持的凭据类型")
                            val info = WebAuthnJson.parseGet(pkReq.requestJson)
                            val candidates = repo.passkeysForRpId(info.rpId)
                            val allowed = info.allowedCredIdsB64Url.map {
                                Base64.getUrlDecoder().decode(it)
                            }
                            // allowCredentials 为空 = 不过滤；否则按 credentialId 字节精确匹配
                            val pick = if (allowed.isEmpty()) {
                                candidates.firstOrNull()
                            } else {
                                candidates.firstOrNull { p ->
                                    val cid = Base64.getDecoder().decode(p.credIdB64)
                                    allowed.any { a -> cid.contentEquals(a) }
                                }
                            }
                            if (pick == null) {
                                return@launch respondError("金库中没有 ${info.rpId} 的 Passkey")
                            }
                            val privB64 = repo.passkeyPrivB64(pick.id)
                                ?: return@launch respondError("Passkey 私钥不可用")
                            val newCount = pick.signCount + 1
                            val authData = WebAuthn.assertionAuthData(info.rpId, newCount)
                            val cdj = WebAuthn.clientDataJson(
                                WebAuthn.TYPE_GET,
                                Base64.getUrlDecoder().decode(info.challengeB64Url),
                                "https://" + info.rpId,
                            )
                            // Chrome 会把响应 clientDataJSON 覆盖为浏览器自生成版本，
                            // 其 SHA256 经 option.clientDataHash 传入 —— 有则直接对哈希签名，无则回退自构版本
                            val cdHash = pkReq.clientDataHash
                            val sig = if (cdHash != null) {
                                WebAuthn.signAssertionHash(Aead.unb64(privB64), authData, cdHash)
                            } else {
                                WebAuthn.signAssertion(Aead.unb64(privB64), authData, cdj)
                            }
                            repo.bumpSignCount(pick.id, newCount)
                            val out = Intent()
                            // 1.3.0 只有 2 参版（3 参重载是 androidx-main 新增，带 @Deprecated）
                            PendingIntentHandler.setGetCredentialResponse(
                                out,
                                GetCredentialResponse(
                                    PublicKeyCredential(
                                        WebAuthn.assertionJson(
                                            Base64.getDecoder().decode(pick.credIdB64),
                                            Base64.getDecoder().decode(pick.userHandleB64),
                                            authData, cdj, sig,
                                        ),
                                    ),
                                ),
                            )
                            setResult(RESULT_OK, out)
                            finish()
                        } catch (e: Exception) {
                            respondError("Passkey 签名失败：${e.message ?: "未知错误"}")
                        }
                    }
                }

                fun unlockAndProceed(m: String?) {
                    if (busy) return
                    busy = true
                    error = ""
                    scope.launch {
                        val result = if (requiresPwd == false) repo.unlockDefault() else {
                            m?.let { repo.unlock(it) } ?: UnlockResult.WrongPassword
                        }
                        if (result == UnlockResult.KeyUnavailable) {
                            busy = false
                            error = "设备保护密钥已失效，请打开印迹按提示恢复"
                            showUi = true
                            return@launch
                        }
                        if (result != UnlockResult.Success) {
                            busy = false
                            error = "解锁失败，请重试"
                            showUi = true
                            return@launch
                        }
                        if (isCreate) doCreate() else doGet()
                    }
                }

                fun tryBiometric() {
                    error = ""
                    BioManager.unlockWithMaster(
                        this@PasskeyActivity,
                        onMaster = { m -> unlockAndProceed(m) },
                        onError = { e ->
                            if (e != "已取消") error = e
                            showUi = true
                        },
                    )
                }

                LaunchedEffect(Unit) {
                    requiresPwd = repo.requiresPassword()
                    if (requiresPwd == false) {
                        unlockAndProceed(null)
                    } else {
                        val bioReady = BioManager.canAuthenticate(this@PasskeyActivity) &&
                            BioManager.hasCachedMaster(this@PasskeyActivity)
                        if (bioReady) tryBiometric() else showUi = true
                    }
                }

                PasskeyGate(
                    showUi = showUi,
                    isCreate = isCreate,
                    requiresPwd = requiresPwd,
                    bioReady = BioManager.canAuthenticate(this@PasskeyActivity) &&
                        BioManager.hasCachedMaster(this@PasskeyActivity),
                    pwd = pwd,
                    onPwdChange = { pwd = it; error = "" },
                    busy = busy,
                    error = error,
                    onBiometric = { tryBiometric() },
                    onUnlock = { unlockAndProceed(pwd) },
                    onCancel = { finish() },
                    onScrimTap = { finish() },
                )
            }
        }
    }
}

/** 浮层 UI（与 AutofillAuthActivity 的两段式同款：先静默指纹，失败/无生物凭据再浮卡片） */
@Composable
private fun PasskeyGate(
    showUi: Boolean,
    isCreate: Boolean,
    requiresPwd: Boolean?,
    bioReady: Boolean,
    pwd: String,
    onPwdChange: (String) -> Unit,
    busy: Boolean,
    error: String,
    onBiometric: () -> Unit,
    onUnlock: () -> Unit,
    onCancel: () -> Unit,
    onScrimTap: () -> Unit,
) {
    val scrimClick = remember { MutableInteractionSource() }
    val cardClick = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(if (showUi) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(scrimClick, indication = null) { onScrimTap() },
        contentAlignment = Alignment.Center,
    ) {
        if (showUi) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clickable(cardClick, indication = null) { /* 消费点击，防穿透关闭 */ },
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (isCreate) "印迹 · 创建 Passkey" else "印迹 · Passkey 验证",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isCreate) "验证通过后，将为本站点生成 Passkey 并存入金库（私钥随备份走）"
                        else "验证通过后，印迹将为本站点签署 Passkey 登录断言",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))

                    when (requiresPwd) {
                        null -> Text("正在读取金库状态…")
                        else -> {
                            if (bioReady) {
                                Button(
                                    onClick = onBiometric,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("指纹解锁并继续") }
                                Spacer(Modifier.height(10.dp))
                            }
                            if (requiresPwd == true) {
                                OutlinedTextField(
                                    value = pwd,
                                    onValueChange = onPwdChange,
                                    label = { Text("主密码") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = onUnlock,
                                    enabled = !busy && pwd.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (busy) "解锁中…" else "解锁并继续") }
                            }
                            if (error.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(error, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onCancel) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
}
