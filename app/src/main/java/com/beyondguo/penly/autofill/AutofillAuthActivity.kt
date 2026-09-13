package com.beyondguo.penly.autofill

import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.beyondguo.penly.bio.BioManager
import com.beyondguo.penly.penly
import com.beyondguo.penly.ui.theme.PenlyTheme
import kotlinx.coroutines.launch

/**
 * Autofill 解锁浮层（v3.0 项目⑤）：
 *
 * 金库锁定时收到填充请求 → 服务回复认证数据集 → 用户点卡片 → 系统拉起本浮层
 * → 指纹/主密码解锁 → 构建真正的 FillResponse 通过 setResult 回传系统 → 字段被填充。
 *
 * 必须继承 FragmentActivity（BiometricPrompt 要求），解锁逻辑与 LockScreen 同源
 * （requiresPassword 判定 + unlockDefault/unlock + BioManager 缓存副本）。
 * V1 仅处理 FILL 模式；保存路径锁定时由服务 onFailure 提示用户先解锁。
 */
class AutofillAuthActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CLIENT_STATE = "clientState"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clientState = intent?.getBundleExtra(EXTRA_CLIENT_STATE)
        val repo = penly.repo

        setContent {
            PenlyTheme {
                var requiresPwd by remember { mutableStateOf<Boolean?>(null) }
                var pwd by remember { mutableStateOf("") }
                var error by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                fun respondFill() {
                    scope.launch {
                        // buildFillResponse 现在恒返回带 SaveInfo 的响应（无数据集也认领表单，
                        // 保证之后手输的账密能触发保存提示）
                        val response = AutofillResponseBuilder.buildFillResponseForClientState(
                            repo, applicationContext, clientState ?: Bundle(),
                        )
                        if (response == null) {
                            error = "金库中没有可填充的条目"
                            return@launch
                        }
                        android.util.Log.d("PenlyAutofill", "auth unlock: responding with fill response")
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
                        )
                        finish()
                    }
                }

                fun unlockAndRespond(m: String?) {
                    if (busy) return
                    busy = true
                    error = ""
                    scope.launch {
                        val ok = if (requiresPwd == false) repo.unlockDefault() else {
                            m?.let { repo.unlock(it) } == true
                        }
                        if (!ok) {
                            busy = false
                            error = "解锁失败，请重试"
                            return@launch
                        }
                        respondFill()
                    }
                }

                LaunchedEffect(Unit) {
                    requiresPwd = repo.requiresPassword()
                    // default 模式：等同主界面「轻触进入」，无需输入
                    if (requiresPwd == false) unlockAndRespond(null)
                }

                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("印迹 · 自动填充验证", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "验证通过后才能使用金库数据填充登录表单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))

                    when (requiresPwd) {
                        null -> Text("正在读取金库状态…")
                        else -> {
                            val bioReady = BioManager.canAuthenticate(this@AutofillAuthActivity) &&
                                BioManager.hasCachedMaster(this@AutofillAuthActivity)
                            if (bioReady) {
                                Button(
                                    onClick = {
                                        error = ""
                                        BioManager.unlockWithMaster(
                                            this@AutofillAuthActivity,
                                            onMaster = { m -> unlockAndRespond(m) },
                                            onError = { e -> if (e != "已取消") error = e },
                                        )
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("指纹解锁并继续") }
                                Spacer(Modifier.height(10.dp))
                            }
                            if (requiresPwd == true) {
                                OutlinedTextField(
                                    value = pwd,
                                    onValueChange = { pwd = it; error = "" },
                                    label = { Text("主密码") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { unlockAndRespond(pwd) },
                                    enabled = !busy && pwd.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (busy) "解锁中…" else "解锁并继续") }
                            }
                            if (error.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(error, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { finish() }) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
}
