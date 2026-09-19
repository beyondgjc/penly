package com.beyondguo.penly.passkey

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry

/**
 * Passkey 凭据提供器（v5.0-②，Android 14+）——双阶段协议的第一阶段（begin）。
 *
 * 安全设计（关键不变量）：**begin 阶段零数据访问**。锁定态下系统会调本 Service
 * 查询可用凭据，此时绝不解锁金库、绝不扫描条目（跨槽位扫描会向胁迫者泄露真库
 * 存在）——只返回泛化条目 + 指向 [PasskeyActivity] 的 PendingIntent；
 * rpId 级过滤发生在第二阶段（Activity 内，金库已解锁）。
 * rpName 从系统传入的 requestJson 解析，那是调用方（RP）自己知道的信息，
 * 不泄露任何金库内容。
 *
 * API 备注（以 credentials-1.3.0 AAR 为准，2026-09-19 javap 核实）：
 * - create 请求的载荷类是 [BeginCreatePublicKeyCredentialRequest]（含 requestJson），
 *   基类 BeginCreateCredentialRequest 无 options 列表，直接 as? 判型；
 * - get 侧选项是 BeginGetPublicKeyCredentialOption（含 requestJson）；
 * - clear 回调泛型是 Void?，onResult(null)。
 */
@RequiresApi(34)
class PasskeyProviderService : CredentialProviderService() {

    companion object {
        private const val EXTRA_MODE = "penly.passkey.mode"
        private const val MODE_CREATE = "create"
        private const val MODE_GET = "get"

        /**
         * 拉起第二阶段 Activity 的 PendingIntent。
         * 系统会把完整请求附加到该 intent 上（因此必须 FLAG_MUTABLE，官方文档明确
         * 要求），PendingIntentHandler 才能在 Activity 侧恢复请求。
         */
        private fun pendingIntent(context: android.content.Context, mode: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PasskeyActivity::class.java).putExtra(EXTRA_MODE, mode)
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, androidx.credentials.exceptions.CreateCredentialException>,
    ) {
        val pkRequest = request as? BeginCreatePublicKeyCredentialRequest
        if (pkRequest == null) {
            callback.onError(CreateCredentialUnknownException("印迹只支持 Passkey（public-key）凭据"))
            return
        }
        val rpName = try {
            WebAuthnJson.parseCreate(pkRequest.requestJson).rpName
        } catch (_: IllegalArgumentException) {
            ""
        }
        val entry = CreateEntry(
            accountName = "印迹",
            pendingIntent = pendingIntent(applicationContext, MODE_CREATE, 1001),
            description = if (rpName.isBlank()) "将 Passkey 保存到印迹保险库"
            else "将「$rpName」的 Passkey 保存到印迹保险库",
        )
        callback.onResult(BeginCreateCredentialResponse(listOf(entry)))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, androidx.credentials.exceptions.GetCredentialException>,
    ) {
        val options = request.beginGetCredentialOptions
            .filterIsInstance<BeginGetPublicKeyCredentialOption>()
        android.util.Log.d("PenlyPasskey", "onBeginGet: total=${request.beginGetCredentialOptions.size} pkOptions=${options.size} classes=${request.beginGetCredentialOptions.map { it.javaClass.name }}")
        if (options.isEmpty()) {
            callback.onError(GetCredentialUnknownException("印迹只支持 Passkey（public-key）凭据"))
            return
        }
        // 每个请求选项一个泛化条目；不在此阶段过滤 rpId（零数据访问不变量）
        val entries = options.mapIndexed { i, option ->
            PublicKeyCredentialEntry(
                context = applicationContext,
                username = "印迹 · Passkey",
                pendingIntent = pendingIntent(applicationContext, MODE_GET, 2000 + i),
                beginGetPublicKeyCredentialOption = option,
            )
        }
        callback.onResult(BeginGetCredentialResponse(entries))
    }

    /** 系统要求清凭据状态：无本地会话状态可清（锁定语义在金库侧），直接成功 */
    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, androidx.credentials.exceptions.ClearCredentialException>,
    ) {
        callback.onResult(null)
    }
}
