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
        // N9：认证 intentSender 由系统在 client（被填充 app）Activity 里
        // startIntentSenderForResult 启动——**不能加 FLAG_ACTIVITY_NEW_TASK**：
        // 加了会被 affinity 匹配进印迹自己的任务并把印迹切到前台，透明浮层
        // 透出的是印迹主页而非客户端登录页（实测截图实证）。不加 flag 时
        // 浮层自然落到 client 任务栈顶，视觉=验证卡片悬浮在客户端上。
        val intent = android.content.Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_CLIENT_STATE, AutofillResponseBuilder.clientState(form, matches.map { it.itemId }))
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
        // 金库已锁：无主密钥无法加密入库。N8（锁定态保存）终态：
        // onSuccess(intentSender) 官方通道直接拉浮层（AutofillAuthActivity SAVE 模式），
        // 验证指纹/主密码后表单账密直接入库。前提：MIUI「后台弹出界面」权限
        // （MIUIOP 10008）开启——被拒时静默吞掉无回调（曾用通知兜底，用户拍板移除，
        // 依赖引导用户开权限，见 docs/autofill.md 2.4/4.4）。
        // 表单值经浮层 Intent 内存传递，用后即弃不落盘。
        android.util.Log.d("PenlyAutofill", "onSaveRequest: locked -> direct-launch save overlay")
        val intent = android.content.Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_MODE, AutofillAuthActivity.MODE_SAVE)
            putExtra(AutofillAuthActivity.EXTRA_SAVE_TITLE, form.packageName)
            putExtra(AutofillAuthActivity.EXTRA_SAVE_ACCOUNT, form.usernameValue ?: "")
            putExtra(AutofillAuthActivity.EXTRA_SAVE_SECRET, form.passwordValue ?: "")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val sender = android.app.PendingIntent.getActivity(
            this, REQUEST_CODE_SAVE, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        callback.onSuccess(sender)
    }

    companion object {
        // 1001→1003（N9）：PendingIntent record 按 component+requestCode 匹配复用，
        // FLAG_UPDATE_CURRENT 官方语义只承诺替换 extras，**Intent 的 flags 不被更新**
        // ——v3.0 初版 AUTH intent 带 NEW_TASK 创建了 record，后来源码删掉 NEW_TASK
        // 后 record 仍残留旧 flags：系统 START 日志实证 flg=0x10000000 → NEW_TASK
        // 启动 → client 立即收到 RESULT_CANCELED（data=null），setResult 的真响应
        // 永远没人消费（指纹后字段不回填）。换 requestCode 逃逸旧 record。
        private const val REQUEST_CODE_AUTH = 1003
        private const val REQUEST_CODE_SAVE = 1002

        /** 设置页判断/跳转用：本服务的启用状态查询走 [AutofillManager] */
        fun serviceComponentName(context: Context): String =
            "${context.packageName}/com.beyondguo.penly.autofill.PenlyAutofillService"
    }
}
