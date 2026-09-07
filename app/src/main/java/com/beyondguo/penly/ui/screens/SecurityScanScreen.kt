package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.data.ItemSecurityState
import com.beyondguo.penly.data.SecurityReport
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.security.SecurityReportStore
import com.beyondguo.penly.security.SecurityScanner
import com.beyondguo.penly.ui.components.SectionTitle
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenGreen
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.ui.theme.PenWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 安全体检（泄露哨兵）页。
 *
 * 合规要点（《方案》§5）：
 * - 联网校验默认关闭，需用户显式开启开关才发请求
 * - 仅发送密码哈希前 5 位（k-匿名），完整哈希/明文不出端
 * - 本地检测（弱密码/复用）始终可用，不依赖网络
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScanScreen(
    repo: VaultRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reportStore = remember { SecurityReportStore(context) }

    var report by remember { mutableStateOf<SecurityReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var networkEnabled by remember { mutableStateOf(false) }
    var idToTitle by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var toast by remember { mutableStateOf("") }

    fun showToast(m: String) { toast = m }

    LaunchedEffect(Unit) {
        networkEnabled = reportStore.isNetworkEnabled()
        report = reportStore.load()
        idToTitle = repo.items().associate { it.id to it.title }
    }
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
            toast = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全体检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("密码泄露体检", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "检查密码是否出现在公开泄露库中。联网校验采用 k-匿名：只把密码哈希的前 5 位" +
                            "发给 HaveIBeenPwned，密码本身与完整哈希均不出本机。本地检测（弱密码 / 重复使用的密码）无需联网。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PenText3,
                    )
                }
            }

            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许联网查询泄露", style = MaterialTheme.typography.bodyLarge)
                        Text("默认关闭；开启后才会向服务端查询", style = MaterialTheme.typography.bodySmall, color = PenText3)
                    }
                    Switch(
                        checked = networkEnabled,
                        onCheckedChange = { on ->
                            scope.launch { reportStore.setNetworkEnabled(on); networkEnabled = on }
                        },
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        scanning = true
                        progressDone = 0
                        progressTotal = 0
                        try {
                            val r = SecurityScanner.scan(repo, networkEnabled) { d, t ->
                                progressDone = d
                                progressTotal = t
                            }
                            reportStore.save(r)
                            report = r
                            idToTitle = repo.items().associate { it.id to it.title }
                        } catch (e: Exception) {
                            showToast("体检失败：${e.message}")
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) { Text(if (scanning) "体检中…" else "开始体检") }

            if (scanning) {
                val frac = if (progressTotal > 0) progressDone.toFloat() / progressTotal else 0f
                LinearProgressIndicator(
                    progress = frac.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                if (networkEnabled && progressTotal > 0) {
                    Text(
                        "查询中 $progressDone / $progressTotal",
                        style = MaterialTheme.typography.bodySmall,
                        color = PenText3,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }

            report?.let { r ->
                val failed = r.states.count { it.breached == -1 }
                Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("体检结果 · 共 ${r.totalCount} 条", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat("已泄露", r.breachedCount, if (r.breachedCount > 0) PenDanger else PenText3)
                            Stat("重复使用", r.reusedCount, if (r.reusedCount > 0) PenWarn else PenText3)
                            Stat("弱密码", r.weakCount, if (r.weakCount > 0) PenWarn else PenText3)
                        }
                        if (failed > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "⚠ 有 $failed 个密码联网查询失败，泄露项可能不完整；请检查网络后重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = PenWarn,
                            )
                        }
                    }
                }

                val flagged = r.states.filter { it.breached > 0 || it.reused || it.weak }
                if (flagged.isNotEmpty()) {
                    SectionTitle("需要关注（${flagged.size}）")
                    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            flagged.forEach { st -> RiskRow(title = idToTitle[st.itemId] ?: "记录", state = st) }
                        }
                    }
                } else {
                    Text("✅ 未发现明显风险", color = PenGreen, modifier = Modifier.padding(8.dp))
                }

                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "上次体检：${formatTime(r.scannedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PenText3,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch { reportStore.clear(); report = null; showToast("已清除体检记录") }
                        },
                    ) { Text("清除记录", color = PenText3) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "印迹 · 端到端加密，密钥不出本机",
                style = MaterialTheme.typography.bodySmall,
                color = PenText3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Stat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        Text("$count", style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = PenText3)
    }
}

@Composable
private fun RiskRow(title: String, state: ItemSecurityState) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            val tags = buildList {
                if (state.breached > 0) add("泄露 ${state.breached} 次")
                if (state.reused) add("重复使用")
                if (state.weak) add("弱密码")
            }
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = PenDanger)
        }
    }
}

private fun formatTime(ts: Long): String =
    if (ts <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
