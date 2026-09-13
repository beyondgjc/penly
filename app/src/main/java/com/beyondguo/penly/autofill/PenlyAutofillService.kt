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
 *  - 会话已锁定：onFailure(intentSender) 拉起解锁浮层，验证后在浮层内保存
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
                // 未命中：不弹填充提示，但保存链路必须保留——系统只把保存请求发给
                // 认领过表单的服务，含密码字段的登录表单回占位数据集锚定保存
                // （提交后弹「保存到印迹」）；无密码字段的表单（搜索框等）静默不参与。
                if (form.passwordId == null) {
                    callback.onSuccess(null)
                    return
                }
                callback.onSuccess(
                    AutofillResponseBuilder.buildSaveAnchorResponse(applicationContext, form),
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
        val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure
            ?: run { callback.onFailure("无法解析表单"); return }
        val form = FormParser.parse(structure)
        if (form == null || (form.usernameValue.isNullOrBlank() && form.passwordValue.isNullOrBlank())) {
            callback.onFailure("无可保存的内容")
            return
        }
        val repo = applicationContext.penly.repo
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
        // 金库已锁：无法加密入库。诚实提示（用户解锁印迹后重新提交表单即可保存）。
        // V1 不做"锁内解锁保存"——避免为保存场景再引入一层浮层（填充路径已有解锁浮层）。
        callback.onFailure("金库已锁定：请打开印迹解锁后，重新提交登录表单即可保存")
    }

    companion object {
        private const val REQUEST_CODE_AUTH = 1001
        private const val REQUEST_CODE_SAVE = 1002

        /** 设置页判断/跳转用：本服务的启用状态查询走 [AutofillManager] */
        fun serviceComponentName(context: Context): String =
            "${context.packageName}/com.beyondguo.penly.autofill.PenlyAutofillService"
    }
}
