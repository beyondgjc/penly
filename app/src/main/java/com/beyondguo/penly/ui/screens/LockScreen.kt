package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.beyondguo.penly.bio.BioManager
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.ConfirmDialog
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenGreen
import com.beyondguo.penly.ui.theme.PenWarn
import kotlinx.coroutines.launch

/**
 * 锁屏（对应小程序 components/lock-gate）：
 * - default 模式：一键「轻触进入」（内置默认主密码派生）
 * - custom 模式：主密码输入 + 指纹解锁（BiometricPrompt + Keystore 副本）
 * - 支持忘记密码 → 重置（清空全部数据）
 */
@Composable
fun LockScreen(repo: VaultRepository, onVaultChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var meta by remember { mutableStateOf<VaultMeta?>(null) }
    var pwd by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { meta = repo.meta() }

    fun bioUnlock() {
        val fa = activity ?: return
        error = ""
        BioManager.unlockWithMaster(
            fa,
            onMaster = { m ->
                scope.launch {
                    if (!repo.unlock(m)) {
                        BioManager.clear(context)
                        error = "本地凭证已失效，请手动解锁"
                    }
                }
            },
            onError = { e -> if (e != "已取消") error = e },
        )
    }

    val bioReady = activity != null &&
        BioManager.canAuthenticate(context) &&
        BioManager.hasCachedMaster(context)

    // 锁屏出现且已开启指纹解锁时，自动拉起生物验证（免点击）；每次进锁屏只自动拉起一次。
    // 必须等本应用窗口真正拿到焦点：冷启动动画期间 Activity 已 RESUMED 但桌面仍在最上层，
    // 窗口无焦点时弹认证会被系统判定为后台认证而立即取消（弹窗显示感叹号）。
    var autoPrompted by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    var windowFocused by remember { mutableStateOf(view.hasWindowFocus()) }
    androidx.compose.runtime.DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            windowFocused = hasFocus
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
    LaunchedEffect(meta, windowFocused) {
        val m = meta
        if (m == null || autoPrompted || !bioReady || !windowFocused) return@LaunchedEffect
        autoPrompted = true
        kotlinx.coroutines.delay(200) // 等待窗口完成首帧渲染
        bioUnlock()
    }

    fun unlockWith(m: String) {
        busy = true
        scope.launch {
            val ok = repo.unlock(m)
            busy = false
            if (!ok) error = "主密码错误"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = PenGreen, modifier = Modifier.size(76.dp))
        Spacer(Modifier.height(14.dp))
        Text("印迹", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(28.dp))

        if (meta?.pwdMode == VaultMeta.MODE_DEFAULT) {
            Text(
                "默认保护模式 · 未设主密码",
                style = MaterialTheme.typography.bodySmall,
                color = PenWarn,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        repo.unlockDefault()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "解锁中..." else "轻触进入") }
        } else {
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it; error = "" },
                label = { Text("主密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { unlockWith(pwd) },
                enabled = !busy && pwd.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "解锁中..." else "解锁") }
        }

        // 指纹解锁：custom 模式解出主密码副本；default 模式解出内置默认主密码（等同带验证的轻触进入）
        if (bioReady) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { bioUnlock() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("指纹解锁")
            }
        }

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(36.dp))
        TextButton(onClick = { showReset = true }) { Text("忘记主密码？重置印迹", color = PenDanger) }
    }

    if (showReset) {
        ConfirmDialog(
            title = "重置印迹",
            text = "将删除本机所有密码数据，且无法恢复。确定要继续吗？",
            confirmText = "重置",
            danger = true,
            onConfirm = {
                showReset = false
                scope.launch {
                    BioManager.clear(context)
                    repo.resetVault()
                    onVaultChanged()
                }
            },
            onDismiss = { showReset = false },
        )
    }
}
