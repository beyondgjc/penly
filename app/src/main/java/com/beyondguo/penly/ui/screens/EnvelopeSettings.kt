package com.beyondguo.penly.ui.screens

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.SettingCard
import com.beyondguo.penly.ui.components.SettingRow
import com.beyondguo.penly.ui.components.SectionTitle
import com.beyondguo.penly.ui.theme.PenGreen
import kotlinx.coroutines.launch

/**
 * TEE 信封（v5.0 #30）设置区：硬件级保护的显式启用/关闭。
 *
 * 启用语义（用户必须知情）：
 * - 条目密钥 key32 额外受「主密码派生 KEK（Argon2id 64MiB）+ TEE wrapKey」双层信封保护，
 *   解锁门禁切换为信封解封——离线拖库者需同时突破内存硬 KDF 与硬件安全芯片；
 * - 代价：换机/恢复出厂 → TEE wrapKey 销毁 → 信封永久不可解，唯一出路 = 备份恢复。
 *   因此启用确认里强制用户显式勾选「已完成备份导出」。
 * - 启用后解锁耗时上升（Argon2id 64MiB，约 1-3 秒量级，视机型）。
 */
@Composable
fun EnvelopeSection(repo: VaultRepository) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var showEnable by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var ackExported by remember { mutableStateOf(false) }
    var master by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enabled = repo.envelopeEnabled()
        loaded = true
    }

    fun refresh() {
        scope.launch { enabled = repo.envelopeEnabled() }
    }

    SettingCard {
        SettingRow(
            title = "硬件级保护（TEE 信封）",
            subtitle = if (!loaded) "" else if (enabled) {
                "已启用 · 换机或恢复出厂后需用备份恢复"
            } else {
                "未启用 · 主密码 + 安全芯片双重防护"
            },
            onClick = if (busy || !loaded) null else {
                { if (enabled) showDisable = true else showEnable = true }
            },
        )
    }

    // ---- 启用确认：风险告知 + 备份勾选 + 主密码 ----
    if (showEnable) {
        AlertDialog(
            onDismissRequest = { if (!busy) showEnable = false },
            title = { Text("启用硬件级保护") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "启用后，解锁需要主密码并通过设备安全芯片（TEE）验证，" +
                            "离线窃取数据文件将几乎无法破解。" +
                            "解锁耗时会有所增加（约 1-3 秒）。\n\n" +
                            "代价：换机或恢复出厂后，芯片密钥销毁，本机数据无法解锁，" +
                            "只能通过备份文件恢复。请确认你已完成一次备份导出。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ackExported, onCheckedChange = { ackExported = it })
                        Text("我已导出备份，知晓换机后需用备份恢复", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = master,
                        onValueChange = { master = it; err = "" },
                        label = { Text("输入主密码确认") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (err.isNotEmpty()) {
                        Spacer(Modifier.size(6.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && ackExported && master.isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val e = repo.enableEnvelope(master)
                            busy = false
                            if (e == null) {
                                showEnable = false
                                master = ""
                                ackExported = false
                                android.widget.Toast.makeText(
                                    context,
                                    "硬件级保护已启用：本机数据现受安全芯片防护",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                refresh()
                            } else {
                                err = e
                            }
                        }
                    },
                ) { Text(if (busy) "启用中..." else "启用") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showEnable = false }) { Text("取消") }
            },
        )
    }

    // ---- 关闭确认：门禁回落 PBKDF2，条目数据零影响 ----
    if (showDisable) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDisable = false },
            title = { Text("关闭硬件级保护") },
            text = {
                Text(
                    "关闭后解锁仅依赖主密码（PBKDF2 派生校验），不再需要安全芯片，" +
                        "离线防护强度下降。条目数据本身不受影响。\n\n确定要关闭吗？",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val e = repo.disableEnvelope()
                            busy = false
                            if (e == null) {
                                showDisable = false
                                android.widget.Toast.makeText(context, "已关闭硬件级保护", android.widget.Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                err = e
                            }
                        }
                    },
                ) { Text(if (busy) "处理中..." else "关闭") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showDisable = false }) { Text("取消") }
            },
        )
    }
}
