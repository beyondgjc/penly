package com.beyondguo.penly

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 仅 debug 构建：Autofill 保存链路探针页面（N7 保存弹窗排查）。
 *
 * **自驱型**：从 shell `am start` 拉起后无需任何交互——聚焦账号框触发
 * onFillRequest（印迹锁定+未命中 → save-only 响应），2s 后自动填入账密，
 * 再 1s 后 finish（字段不可见，满足 FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE），
 * 系统保存判定随之发生。主机侧抓 verbose logcat + dumpsys autofill 归因。
 *
 * 关键时间点均打 Log（tag=PenlySaveProbe），与系统日志对齐成完整时间轴。
 */
class AutofillProbeActivity : Activity() {

    private lateinit var userEdit: EditText
    private lateinit var pwdEdit: EditText
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userEdit = EditText(this).apply {
            hint = "账号"
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        pwdEdit = EditText(this).apply {
            hint = "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        loginButton = Button(this).apply {
            text = "登录"
            setOnClickListener { submit() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            addView(userEdit)
            addView(pwdEdit)
            addView(loginButton)
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d(TAG, "probe resumed, focus username")
        userEdit.requestFocus()
        // 自驱时序：留 2s 给 fill request + save-only 响应往返
        userEdit.postDelayed({
            android.util.Log.d(TAG, "probe: auto-filling credentials")
            userEdit.setText("13800000000")
            pwdEdit.setText("Probe12345")
            userEdit.postDelayed({
                android.util.Log.d(TAG, "probe: submitting (finish)")
                submit()
            }, 1000)
        }, 2000)
    }

    private fun submit() {
        if (!isFinishing) finish() // 字段不可见 → 触发保存判定
    }

    companion object {
        private const val TAG = "PenlySaveProbe"
    }
}
