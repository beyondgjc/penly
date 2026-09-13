package com.beyondguo.penly.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillContext
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.beyondguo.penly.R
import com.beyondguo.penly.data.VaultRepository

/**
 * Autofill 数据集/响应构建（v3.0 项目⑤）。
 * 服务与解锁浮层（[com.beyondguo.penly.autofill.AutofillAuthActivity]）共用——
 * 锁定路径回复"认证数据集"，浮层验证通过后用同一套函数重建真正的 FillResponse 回传系统。
 */
object AutofillResponseBuilder {

    /** clientState 键：跨认证回传的字段坐标与来源信息 */
    const val KEY_USERNAME_ID = "usernameId"
    const val KEY_PASSWORD_ID = "passwordId"
    const val KEY_PACKAGE_NAME = "packageName"
    const val KEY_WEB_DOMAIN = "webDomain"

    fun clientState(form: ParsedForm): Bundle = Bundle().apply {
        form.usernameId?.let { putParcelable(KEY_USERNAME_ID, it) }
        form.passwordId?.let { putParcelable(KEY_PASSWORD_ID, it) }
        putString(KEY_PACKAGE_NAME, form.packageName)
        putString(KEY_WEB_DOMAIN, form.webDomain)
    }

    fun formFromClientState(state: Bundle): ParsedForm? {
        val usernameId: AutofillId? = state.getParcelable(KEY_USERNAME_ID)
        val passwordId: AutofillId? = state.getParcelable(KEY_PASSWORD_ID)
        val pkg = state.getString(KEY_PACKAGE_NAME) ?: ""
        if (usernameId == null && passwordId == null) return null
        return ParsedForm(usernameId, passwordId, null, null, pkg, state.getString(KEY_WEB_DOMAIN))
    }

    fun presentation(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    /**
     * 构建"已解锁"填充响应：匹配条目（来源包名优先，无来源则全量）逐条出数据集。
     *
     * **关键语义：即使没有可填充的数据集，也必须返回带 SaveInfo 的响应**——
     * 系统只把「保存」请求发给认领过表单的服务（用户点过印迹卡片的表单）。
     * 若此处返回 null，表单未认领，用户手输的账密在提交时永远不会触发保存提示
     * （N3 教训：GitHub 第一页只有账号框、条目账号为空 → 数据集为空 → null → 保存失效）。
     * 返回 null 仅限：结构里根本没有账号/密码字段（非登录表单，由服务层判定）。
     */
    suspend fun buildFillResponse(
        repo: VaultRepository,
        context: Context,
        form: ParsedForm,
    ): FillResponse? {
        val all = repo.items()
        // 来源匹配：保存时记录过包名的条目优先；没有匹配来源时退全量（首批用户无来源记录）
        val matched = all.filter { it.appPackage == form.packageName }
        val candidates = matched.ifEmpty { all }

        val builder = FillResponse.Builder()
        var added = 0
        for (item in candidates) {
            val entry = runCatching { repo.decryptItem(item) }.getOrNull() ?: continue
            // 数据集级过滤：该条目至少能填一个"当前表单存在"的字段
            val contributes = (form.usernameId != null && entry.account.isNotBlank()) ||
                (form.passwordId != null && entry.secret.isNotBlank())
            if (!contributes) continue
            val views = presentation(context, item.title.ifBlank { "印迹" }, entry.account.ifBlank { item.title })
            val dataset = Dataset.Builder()
            form.usernameId?.let {
                dataset.setValue(it, AutofillValue.forText(entry.account), views)
            }
            form.passwordId?.let {
                dataset.setValue(it, AutofillValue.forText(entry.secret), views)
            }
            builder.addDataset(dataset.build())
            added++
        }
        android.util.Log.d(
            "PenlyAutofill",
            "buildFillResponse: items=${all.size} matched=${matched.size} added=$added " +
                "user=${form.usernameId != null} pwd=${form.passwordId != null} pkg=${form.packageName}",
        )
        if (added == 0) {
            // 无匹配条目：放一个空值占位数据集锚定保存跟踪。
            // 纯 SaveInfo 无数据集的响应在部分框架实现上不会触发保存 UI（N3 后续）；
            // 占位数据集让框架把本表单锚定到印迹，用户手输提交后即可触发保存。
            val views = presentation(context, "印迹", "手动输入后提交即可保存到印迹")
            val dataset = Dataset.Builder()
            form.usernameId?.let { dataset.setValue(it, AutofillValue.forText(""), views) }
            form.passwordId?.let { dataset.setValue(it, AutofillValue.forText(""), views) }
            builder.addDataset(dataset.build())
        }
        return builder
            .setSaveInfo(buildSaveInfo(form))
            .build()
    }

    /** 解锁浮层验证通过后的回传响应：与上面同一套数据集，目标字段来自 clientState */
    suspend fun buildFillResponseForClientState(
        repo: VaultRepository,
        context: Context,
        state: Bundle,
    ): FillResponse? {
        val form = formFromClientState(state) ?: return null
        return buildFillResponse(repo, context, form)
    }

    private fun buildSaveInfo(form: ParsedForm): SaveInfo {
        val hasPassword = form.passwordId != null
        val type =
            (if (hasPassword) SaveInfo.SAVE_DATA_TYPE_PASSWORD else 0) or
            (if (form.usernameId != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
        val ids = listOfNotNull(form.usernameId, form.passwordId).toTypedArray()
        return SaveInfo.Builder(type, ids)
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }
}
