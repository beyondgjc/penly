package com.beyondguo.penly.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * Autofill 表单解析（v3.0 项目⑤）。
 *
 * 系统把登录页的控件树（AssistStructure）交给服务，这里负责回答两个问题：
 * 1. 哪个输入框是账号、哪个是密码（启发式，优先级见 [classifyField]）
 * 2. 用户实际输入了什么（onSaveRequest 时从节点取提交值）
 *
 * 启发式优先级：系统 autofillHints > inputType 变体 > 控件资源名 > 提示文字。
 * 覆盖 Chrome 与主流 App；长尾应用识别失败属业界常态（Bitwarden 同样存在）。
 */
data class ParsedForm(
    /** 用户名输入框（可能为 null：纯密码表单） */
    val usernameId: AutofillId?,
    /** 密码输入框（可能为 null：纯用户名表单） */
    val passwordId: AutofillId?,
    /** 用户在用户名框输入的值（仅 onSaveRequest 时有值） */
    val usernameValue: String?,
    /** 用户在密码框输入的值（仅 onSaveRequest 时有值） */
    val passwordValue: String?,
    /** 请求来源 App 包名（保存时记录来源，填充时按它匹配） */
    val packageName: String,
    /** 网页域名（浏览器场景，V2 用于域名匹配） */
    val webDomain: String?,
    /** 锁定态匹配索引命中的条目 id（仅解锁浮层路径有值） */
    val matchedIds: List<String>? = null,
)

object FormParser {

    fun parse(structure: AssistStructure): ParsedForm? {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var usernameValue: String? = null
        var passwordValue: String? = null
        var pkg = ""
        var domain: String? = null

        fun walk(node: AssistStructure.ViewNode) {
            if (domain == null) node.webDomain?.let { domain = it }
            node.autofillId?.let { id ->
                val kind = classifyField(node)
                // 注意：?. 防不住这里——AutofillValue 非 text 类型（checkbox/switch/list 等，
                // type=2 toggle）时引用非空但 textValue 会抛 IllegalStateException
                // ("value must be a text value")，必须先 isText 判型再取值。
                val value = node.autofillValue?.takeIf { it.isText }
                    ?.textValue?.toString()
                    ?.takeIf { it.isNotEmpty() }
                when (kind) {
                    FieldKind.USERNAME -> {
                        if (usernameId == null) {
                            usernameId = id
                            usernameValue = value
                        }
                    }
                    FieldKind.PASSWORD -> {
                        if (passwordId == null) {
                            passwordId = id
                            passwordValue = value
                        }
                    }
                    FieldKind.NONE -> {}
                }
            }
            for (i in 0 until node.childCount) {
                node.getChildAt(i)?.let { walk(it) }
            }
        }

        // 来源包名取自 AssistStructure 级的 activityComponent（WindowNode 无此信息）
        structure.activityComponent?.packageName?.let { pkg = it }
        for (w in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(w).rootViewNode?.let { walk(it) }
        }

        if (usernameId == null && passwordId == null) return null
        return ParsedForm(usernameId, passwordId, usernameValue, passwordValue, pkg, domain)
    }

    private enum class FieldKind { NONE, USERNAME, PASSWORD }

    /**
     * 单字段分类。密码识别优先（密码框绝不能被误判成账号框）；
     * 账号识别兜底 keywords 参考了 Bitwarden 公开的规则集。
     */
    private fun classifyField(node: AssistStructure.ViewNode): FieldKind {
        val hints = node.autofillHints?.toList() ?: emptyList()
        if (hints.contains(View.AUTOFILL_HINT_PASSWORD)) return FieldKind.PASSWORD
        if (isPasswordInputType(node.inputType)) return FieldKind.PASSWORD

        val idName = node.idEntry?.lowercase()
        if (idName?.contains("password") == true || idName?.contains("passwd") == true ||
            idName?.contains("pwd") == true
        ) return FieldKind.PASSWORD
        val hintText = (node.hint ?: "").lowercase()
        if (hintText.contains("密码") || hintText.contains("password") || hintText.contains("passcode")) {
            return FieldKind.PASSWORD
        }

        // 到这里还没认出密码框，才允许判成账号框
        if (hints.contains(View.AUTOFILL_HINT_USERNAME) || hints.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS)) {
            return FieldKind.USERNAME
        }
        if (idName != null && USER_ID_KEYWORDS.any { idName.contains(it) }) return FieldKind.USERNAME
        if (hintText.isNotEmpty() && USER_HINT_KEYWORDS.any { hintText.contains(it) }) return FieldKind.USERNAME
        return FieldKind.NONE
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private val USER_ID_KEYWORDS =
        listOf("user", "email", "account", "login", "logon", "phone", "mobile", "nick")
    private val USER_HINT_KEYWORDS =
        listOf("用户名", "账号", "邮箱", "手机", "user", "email", "account", "login", "phone", "mobile")
}
