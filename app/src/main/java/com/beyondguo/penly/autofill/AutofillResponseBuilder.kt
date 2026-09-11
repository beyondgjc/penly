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
     * 返回 null = 无可建议内容（服务应回复 onSuccess(null) 不参与）。
     */
    suspend fun buildFillResponse(
        repo: VaultRepository,
        context: Context,
        form: ParsedForm,
    ): FillResponse? {
        val all = repo.items()
        if (all.isEmpty()) return null
        // 来源匹配：保存时记录过包名的条目优先；没有匹配来源时退全量（首批用户无来源记录）
        val matched = all.filter { it.appPackage == form.packageName }
        val candidates = matched.ifEmpty { all }

        val builder = FillResponse.Builder()
        var added = 0
        for (item in candidates) {
            val entry = runCatching { repo.decryptItem(item) }.getOrNull() ?: continue
            if (entry.account.isBlank() && entry.secret.isBlank()) continue
            if ((form.usernameId == null || entry.account.isBlank()) &&
                (form.passwordId == null || entry.secret.isBlank())
            ) continue
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
        if (added == 0) return null
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
