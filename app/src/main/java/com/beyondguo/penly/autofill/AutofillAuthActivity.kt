package com.beyondguo.penly.autofill

import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.beyondguo.penly.bio.BioManager
import com.beyondguo.penly.penly
import com.beyondguo.penly.util.AppNameResolver
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
 *
 * 视觉形态（N9 两段式）：本 Activity 使用透明主题（Theme.Penly.Translucent）。
 * 第一段：浮层出现时**不渲染任何卡片**（scrim 全透明，BiometricPrompt 自带 dim），
 * 直接自动弹指纹——视觉上只有系统指纹框悬浮在客户端上，无跳转感；
 * 第二段：指纹失败/取消（或设备无生物凭据）后才浮出 scrim + 居中卡片，
 * 允许重试指纹/输主密码/取消。点击卡片外空区 = 取消。
 *
 * 两种模式：
 * - FILL（默认）：解锁后构建 FillResponse 通过 setResult 回传系统 → 字段被填充。
 * - SAVE（N8，锁定态保存）：onSaveRequest 时金库锁定 → 服务 onSuccess(intentSender)
 *   拉起本浮层并经 Intent 暂存表单值（内存传递、用后即弃、不落盘不打日志），
 *   验证解锁后直接把表单账密入库，无需用户重新提交表单。
 */
class AutofillAuthActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CLIENT_STATE = "clientState"
        const val EXTRA_MODE = "mode"
        const val MODE_FILL = "fill"
        const val MODE_SAVE = "save"
        const val EXTRA_SAVE_TITLE = "saveTitle"
        const val EXTRA_SAVE_ACCOUNT = "saveAccount"
        const val EXTRA_SAVE_SECRET = "saveSecret"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clientState = intent?.getBundleExtra(EXTRA_CLIENT_STATE)
        val isSave = intent?.getStringExtra(EXTRA_MODE) == MODE_SAVE
        val saveTitle = intent?.getStringExtra(EXTRA_SAVE_TITLE).orEmpty()
        val saveAccount = intent?.getStringExtra(EXTRA_SAVE_ACCOUNT).orEmpty()
        val saveSecret = intent?.getStringExtra(EXTRA_SAVE_SECRET).orEmpty()
        val repo = penly.repo

        setContent {
            PenlyTheme {
                var requiresPwd by remember { mutableStateOf<Boolean?>(null) }
                var showUi by remember { mutableStateOf(false) }
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
                            showUi = true
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

                fun unlockAndProceed(m: String?) {
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
                            showUi = true
                            return@launch
                        }
                        if (!isSave) {
                            // 可见反馈：默认模式下浮层 <300ms 即自动完成并关闭，没有这条提示
                            // 用户会以为"点了没反应"（N5）。空金库时本次点按的价值=认领表单，
                            // 手输账密提交后才会触发保存提示。
                            android.widget.Toast.makeText(
                                applicationContext,
                                "印迹已解锁：输入账密并登录后，将提示保存到印迹",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            respondFill()
                        } else {
                            // SAVE 模式：解锁后主密钥可用 → 表单账密直接入库（N8）。
                            val saved = try {
                                repo.saveEntry(
                                    id = null,
                                    // v4.0：标题用应用显示名（解不出回包名兜底）；appPackage 仍存包名
                                    title = AppNameResolver.label(applicationContext, saveTitle) ?: saveTitle,
                                    category = "",
                                    account = saveAccount,
                                    secret = saveSecret,
                                    note = "",
                                    appPackage = saveTitle,
                                )
                                true
                            } catch (e: Exception) {
                                android.util.Log.e("PenlyAutofill", "save from autofill overlay failed", e)
                                false
                            }
                            android.widget.Toast.makeText(
                                applicationContext,
                                if (saved) "已保存到印迹" else "保存失败，请打开印迹重试",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            finish()
                        }
                    }
                }

                // N9 两段式：浮层刚出现时只弹指纹（卡片不渲染）；
                // onError（失败/取消）或解锁失败才浮出卡片兜底。
                fun tryBiometric() {
                    error = ""
                    BioManager.unlockWithMaster(
                        this@AutofillAuthActivity,
                        onMaster = { m -> unlockAndProceed(m) },
                        onError = { e ->
                            if (e != "已取消") error = e
                            showUi = true
                        },
                    )
                }

                LaunchedEffect(Unit) {
                    requiresPwd = repo.requiresPassword()
                    if (requiresPwd == false) {
                        // default 模式：等同主界面「轻触进入」，无需输入
                        unlockAndProceed(null)
                    } else {
                        val bioReady = BioManager.canAuthenticate(this@AutofillAuthActivity) &&
                            BioManager.hasCachedMaster(this@AutofillAuthActivity)
                        if (bioReady) {
                            tryBiometric()
                        } else {
                            // 无生物凭据：只能主密码，直接浮出卡片
                            showUi = true
                        }
                    }
                }

                // 浮层外壳：指纹阶段 scrim 全透明（BiometricPrompt 自带 dim），卡片
                // 浮出后才压暗。点击空区 = 取消；卡片自身消费点击防穿透。
                val scrimClick = remember { MutableInteractionSource() }
                val cardClick = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (showUi) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
                        .clickable(scrimClick, indication = null) { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (showUi) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            tonalElevation = 6.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth()
                                .clickable(cardClick, indication = null) { /* 消费点击，防穿透关闭 */ },
                        ) {
                            Column(
                                Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    if (isSave) "印迹 · 保存验证" else "印迹 · 自动填充验证",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (isSave) "验证通过后，表单中的账密将保存到印迹"
                                    else "验证通过后才能使用金库数据填充登录表单",
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
                                                onClick = { tryBiometric() },
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
                                                onClick = { unlockAndProceed(pwd) },
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
        }
    }
}
