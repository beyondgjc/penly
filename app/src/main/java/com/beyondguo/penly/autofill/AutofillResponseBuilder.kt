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
    const val KEY_MATCHED_IDS = "matchedIds"

    fun clientState(form: ParsedForm, matchedIds: List<String>): Bundle = Bundle().apply {
        form.usernameId?.let { putParcelable(KEY_USERNAME_ID, it) }
        form.passwordId?.let { putParcelable(KEY_PASSWORD_ID, it) }
        putString(KEY_PACKAGE_NAME, form.packageName)
        putString(KEY_WEB_DOMAIN, form.webDomain)
        putStringArrayList(KEY_MATCHED_IDS, ArrayList(matchedIds))
    }

    fun formFromClientState(state: Bundle): ParsedForm? {
        val usernameId: AutofillId? = state.getParcelable(KEY_USERNAME_ID)
        val passwordId: AutofillId? = state.getParcelable(KEY_PASSWORD_ID)
        val pkg = state.getString(KEY_PACKAGE_NAME) ?: ""
        val matchedIds = state.getStringArrayList(KEY_MATCHED_IDS)?.toList()
        if (usernameId == null && passwordId == null) return null
        return ParsedForm(usernameId, passwordId, null, null, pkg, state.getString(KEY_WEB_DOMAIN), matchedIds)
    }

    fun presentation(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    /**
     * 构建"已解锁"填充响应：匹配条目（来源包名优先，无来源则全量）逐条出数据集。
     *
     * @param matchedIds 非空 = 解锁浮层路径：只填充锁定态匹配索引命中的条目
     *   （用户预期：存过才提示，指纹验证后直接填充匹配项）；null = 会话已解锁的直连路径，
     *   按来源包名匹配、无匹配退全量。
     *
     * **关键语义：即使没有可填充的数据集，也返回带 SaveInfo 的响应**——
     * 系统只把「保存」请求发给认领过表单的服务。返回 null 仅限结构异常。
     */
    suspend fun buildFillResponse(
        repo: VaultRepository,
        context: Context,
        form: ParsedForm,
        matchedIds: List<String>? = null,
    ): FillResponse? {
        val all = repo.items()
        val candidates: List<com.beyondguo.penly.data.VaultItem> = when {
            matchedIds != null -> all.filter { it.id in matchedIds } // 锁定态已按包名匹配过
            else -> all.filter { it.appPackage == form.packageName }.ifEmpty { all }
        }
        android.util.Log.d(
            "PenlyAutofill",
            "buildFillResponse: items=${all.size} candidates=${candidates.size} " +
                "user=${form.usernameId != null} pwd=${form.passwordId != null} pkg=${form.packageName}",
        )

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
        return buildFillResponse(repo, context, form, form.matchedIds)
    }

    /**
     * 锁定态未命中的「保存锚定」响应（N6 语义修正）。
     *
     * 没存过该应用的凭据 → **不弹填充提示**（没有可填充的数据集），但保存链路必须保留：
     * 系统只把「保存」请求发给认领过表单的服务，因此仍需返回响应——
     * 空值占位数据集 + SaveInfo，用户手输账密提交后系统即弹「保存到印迹」。
     * 仅含密码字段的登录表单调用此函数；无密码字段的表单由服务侧静默不参与。
     */
    fun buildSaveAnchorResponse(context: Context, form: ParsedForm): FillResponse {
        val views = presentation(context, "印迹", "登录后自动提示保存")
        val dataset = Dataset.Builder()
        form.usernameId?.let { dataset.setValue(it, AutofillValue.forText(""), views) }
        form.passwordId?.let { dataset.setValue(it, AutofillValue.forText(""), views) }
        return FillResponse.Builder()
            .addDataset(dataset.build())
            .setSaveInfo(buildSaveInfo(form))
            .build()
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
