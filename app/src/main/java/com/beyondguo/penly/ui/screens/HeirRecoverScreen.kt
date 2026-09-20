package com.beyondguo.penly.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.data.ImportException
import com.beyondguo.penly.data.UnlockResult
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.legacy.HeirManager
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenGreen
import kotlinx.coroutines.launch

/**
 * 受托人恢复（#44）：拿到 2 份分片 + 遗产恢复包的人在锁屏页进入此界面完成接管。
 *
 * 流程（全程不接触 hex(L) 明文——它在内存里瞬时存在）：
 * ① 输入两份分片 → [HeirManager.recoverKeyHex] 重建 hex(L)；
 * ② 选择恢复包文件 → [VaultRepository.importJson](text, hex(L))（导入后本地解锁密码 = hex(L)）；
 * ③ 引导受托人设置自己的主密码：unlock(hex(L)) → changeMasterPassword(hex(L), 新密码)；
 * ④ 完成后解锁态进入主界面 —— 受托人正式接管金库。
 *
 * 错误语义：分片格式错/编号重复即拒；分片抄错时 combine 出错误 L，
 * 由恢复包内层 GCM 完整性校验 fail-closed（干净报「分片与恢复包不匹配」）。
 */
@Composable
fun HeirRecoverScreen(repo: VaultRepository, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(0) } // 0 输入 1 设新密码 2 完成
    var s1 by rememberSaveable { mutableStateOf("") }
    var s2 by rememberSaveable { mutableStateOf("") }
    var pkgText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var newPwd by rememberSaveable { mutableStateOf("") }
    var newPwd2 by rememberSaveable { mutableStateOf("") }
    // hex(L) 跨阶段内存暂存（不进 rememberSaveable，防进程重建落盘到 saved state）
    var legacyHex by remember { mutableStateOf<String?>(null) }

    val pkgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    }
                    if (text.isNullOrBlank()) error = "恢复包文件为空" else pkgText = text
                } catch (e: Exception) {
                    error = "读取文件失败：${e.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("遗产恢复", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        when (phase) {
            0 -> {
                Text(
                    "请输入死者生前留给你的任意 2 份分片，并选择遗产恢复包文件。" +
                        "分片形如 YJH1<编号>-<内容>。",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.beyondguo.penly.ui.theme.PenText3,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = s1,
                    onValueChange = { s1 = it.trim(); error = "" },
                    label = { Text("分片一") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = s2,
                    onValueChange = { s2 = it.trim(); error = "" },
                    label = { Text("分片二") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { pkgPicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pkgText == null) "选择遗产恢复包文件" else "✅ 已选择恢复包（点击更换）")
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        busy = true
                        error = ""
                        scope.launch {
                            try {
                                val hex = HeirManager.recoverKeyHex(s1, s2)
                                val pkg = pkgText ?: throw ImportException("请先选择恢复包文件")
                                val result = repo.importJson(pkg, hex)
                                legacyHex = hex
                                phase = 1
                                error = ""
                                android.widget.Toast.makeText(
                                    context,
                                    "已恢复 ${result.count} 条记录，请设置你自己的主密码",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: IllegalArgumentException) {
                                error = e.message ?: "分片格式不正确"
                            } catch (e: ImportException) {
                                error = "分片与恢复包不匹配或文件已损坏：${e.message}"
                            } catch (e: Exception) {
                                error = e.message ?: "恢复失败"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && s1.isNotEmpty() && s2.isNotEmpty() && pkgText != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (busy) "恢复中…" else "开始恢复") }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator()
                }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
                }
            }

            1 -> {
                Text(
                    "金库已恢复到本机。当前解锁密码由分片重建、无人可读，" +
                        "请立即设置属于你的主密码完成接管。",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.beyondguo.penly.ui.theme.PenText3,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it; error = "" },
                    label = { Text("新主密码（至少 8 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newPwd2,
                    onValueChange = { newPwd2 = it; error = "" },
                    label = { Text("再次输入新主密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (newPwd != newPwd2) {
                            error = "两次输入不一致"
                            return@Button
                        }
                        busy = true
                        error = ""
                        scope.launch {
                            val hex = legacyHex
                            if (hex == null) {
                                error = "内部状态丢失，请重新恢复"
                                busy = false
                                return@launch
                            }
                            val unlock = repo.unlock(hex)
                            if (unlock != UnlockResult.Success) {
                                error = "金库解锁异常，请重试"
                                busy = false
                                return@launch
                            }
                            val err = repo.changeMasterPassword(hex, newPwd)
                            if (err != null) {
                                error = err
                            } else {
                                legacyHex = null
                                phase = 2
                                onDone()
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && newPwd.isNotEmpty() && newPwd2.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (busy) "设置中…" else "设置主密码并接管") }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> {
                Text("✅ 接管完成", color = PenGreen, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDone) { Text("进入印迹") }
            }
        }
    }
}
