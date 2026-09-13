package com.beyondguo.penly.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillManager
import com.beyondguo.penly.autofill.AutofillResponseBuilder.buildFillResponse
import com.beyondguo.penly.penly
import kotlinx.coroutines.runBlocking

/**
 * 系统自动填充服务（v3.0 项目⑤）：印迹成为系统级密码管理器的入口。
 *
 * onFillRequest：系统检测到登录表单 → 回复数据集。
 *  - 会话已解锁：直接解密金库出数据集（解锁路径）
 *  - 会话已锁定（15s 自动锁后的常态）：先免解锁匹配——命中回「认证数据集」，
 *    系统拉起 [AutofillAuthActivity]，指纹/主密码验证后回传真正的 FillResponse；
 *    未命中不弹填充提示，含密码字段的表单回「保存锚定占位」（提交后触发保存）
 *
 * onSaveRequest：用户在系统保存弹窗点「保存」→ 取表单提交值入库。
 *  - 会话已解锁：直接 saveEntry（记录来源包名）
 *  - 会话已锁定：onSuccess(intentSender) 拉起解锁浮层，验证后在浮层内保存
 *
 * onSavedRequest 不做包名校验白名单——Android 系统已保证请求来自真实前台应用。
 */
class PenlyAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure
            ?: run { callback.onSuccess(null); return }
        val form = FormParser.parse(structure)
        if (form == null || (form.usernameId == null && form.passwordId == null)) {
            callback.onSuccess(null) // 不是登录表单，不参与
            return
        }
        val repo = applicationContext.penly.repo
        val response: FillResponse? = if (repo.unlocked.value) {
            runCatching { runBlocking { buildFillResponse(repo, applicationContext, form) } }.getOrNull()
        } else {
            // 锁定路径（N6 重构·匹配前置）：**先免解锁匹配，再决定是否提示**。
            // 读双槽位明文匹配索引（appPackage/标题为设计内明文），命中才弹验证卡片。
            val matches = runCatching { runBlocking { repo.autofillMatchIndex(form.packageName) } }
                .getOrNull().orEmpty()
            if (matches.isEmpty()) {
                // 未命中：聚焦阶段完全静默（无卡片），仅回 save-only 响应
                // （只含 SaveInfo，零数据集）认领表单——用户手输提交后系统
                // 即弹「保存到印迹」。无密码字段的表单（搜索框等）不参与。
                if (form.passwordId == null) {
                    android.util.Log.d("PenlyAutofill", "onFillRequest: no match, no pwd field -> null (silent)")
                    callback.onSuccess(null)
                    return
                }
                android.util.Log.d("PenlyAutofill", "onFillRequest: no match, pwd field -> save-only anchor")
                callback.onSuccess(
                    AutofillResponseBuilder.buildSaveAnchorResponse(form),
                )
                return
            }
            buildAuthDataset(form, matches)
        }
        callback.onSuccess(response)
    }

    /**
     * 锁定路径：命中匹配索引时回「认证数据集」，卡片展示命中条目标题；
     * 用户点按 → 解锁浮层验证 → 回传只含命中条目的 FillResponse（指纹后直接填充）。
     */
    private fun buildAuthDataset(form: ParsedForm, matches: List<com.beyondguo.penly.data.VaultRepository.AutofillMatch>): FillResponse {
        val intent = android.content.Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_CLIENT_STATE, AutofillResponseBuilder.clientState(form, matches.map { it.itemId }))
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val sender = android.app.PendingIntent.getActivity(
            this, REQUEST_CODE_AUTH, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        val title = if (matches.size == 1) matches[0].title else "印迹 · ${matches.size} 条匹配"
        val targetId = form.passwordId ?: form.usernameId!!
        val dataset = Dataset.Builder()
            .setValue(
                targetId,
                android.view.autofill.AutofillValue.forText(""),
                AutofillResponseBuilder.presentation(this, title, "点按验证指纹后填充"),
            )
            .setAuthentication(sender)
            .build()
        return FillResponse.Builder().addDataset(dataset).build()
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback,
    ) {
        val repo = applicationContext.penly.repo
        android.util.Log.d("PenlyAutofill", "onSaveRequest: received (unlocked=${repo.unlocked.value})")
        val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure
            ?: run { callback.onFailure("无法解析表单"); return }
        val form = FormParser.parse(structure)
        if (form == null || (form.usernameValue.isNullOrBlank() && form.passwordValue.isNullOrBlank())) {
            callback.onFailure("无可保存的内容")
            return
        }
        if (repo.unlocked.value) {
            val ok = runCatching {
                runBlocking {
                    repo.saveEntry(
                        id = null,
                        title = form.packageName,
                        category = "",
                        account = form.usernameValue ?: "",
                        secret = form.passwordValue ?: "",
                        note = "",
                        appPackage = form.packageName,
                    )
                }
            }.isSuccess
            if (ok) callback.onSuccess() else callback.onFailure("保存失败")
            return
        }
        // 金库已锁：无主密钥无法加密入库。N8（锁定态保存）：发 heads-up 通知，
        // 用户点按后拉起解锁浮层（AutofillAuthActivity SAVE 模式），验证后表单账密
        // 直接入库。表单值经浮层 Intent 内存传递，用后即弃不落盘；通知文案不含密文。
        //
        // 启动通道演进（2026-09-13 实测）：
        // ① 官方 onSuccess(intentSender)：MIUI 静默拦截——保存场景下被填充 activity
        //    已 finish，intent 失去前台启动上下文（ActivityTaskManager 有 START 记录
        //    但 activity 永不创建窗口；填充路径能弹是因点卡片时 client activity 活着）
        // ② 服务进程直接 startActivity：同样被后台启动限制拦截
        // ③ 通知通道：用户点按通知 = 真实交互，任何 ROM 放行；通知留存可补点。
        android.util.Log.d("PenlyAutofill", "onSaveRequest: locked -> notify for save")
        notifySavePending(form)
        callback.onSuccess()
    }

    /**
     * 锁定态保存（N8）：发高优先级通知，点按拉起 SAVE 模式解锁浮层。
     * 通知留存通知栏，用户可稍后补点；点按属真实用户交互，不受后台启动限制。
     * 文案只含来源包名（非敏感），账密密文经 Intent 传给浮层、不进通知。
     */
    private fun notifySavePending(form: ParsedForm) {
        val intent = android.content.Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_MODE, AutofillAuthActivity.MODE_SAVE)
            putExtra(AutofillAuthActivity.EXTRA_SAVE_TITLE, form.packageName)
            putExtra(AutofillAuthActivity.EXTRA_SAVE_ACCOUNT, form.usernameValue ?: "")
            putExtra(AutofillAuthActivity.EXTRA_SAVE_SECRET, form.passwordValue ?: "")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            this, REQUEST_CODE_SAVE, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            CHANNEL_SAVE_PENDING, "保存待验证",
            android.app.NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "锁定状态下从系统保存弹窗转来的待保存账密" }
        manager.createNotificationChannel(channel)
        val notification = android.app.Notification.Builder(this, CHANNEL_SAVE_PENDING)
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setContentTitle("保存到印迹")
            .setContentText("点按验证并保存 ${form.packageName} 的登录账密")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFY_ID_SAVE_PENDING, notification)
    }

    companion object {
        private const val REQUEST_CODE_AUTH = 1001
        private const val REQUEST_CODE_SAVE = 1002
        private const val CHANNEL_SAVE_PENDING = "autofill_save_pending"
        private const val NOTIFY_ID_SAVE_PENDING = 2001

        /** 设置页判断/跳转用：本服务的启用状态查询走 [AutofillManager] */
        fun serviceComponentName(context: Context): String =
            "${context.packageName}/com.beyondguo.penly.autofill.PenlyAutofillService"
    }
}
