package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.legacy.HeirManager
import com.beyondguo.penly.ui.components.ConfirmDialog
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenGreen
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.util.writeToDownloads
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 遗产交接设置向导（#43）：
 * 0. 说明页 —— 概念与安全边界；已配置时显示状态 + 重新生成
 * 1. 分片展示 —— 3 份分片文本逐张抄录（任意 2 份可恢复、单份泄露无害）
 * 2. 生成恢复包 —— 标准加密备份（密码 = hex(L)，无人需要记住），写入下载目录
 *
 * 重新生成 = 轮换：新 L + 新分片 + 旧恢复包作废（必须重新分发全部 3 份）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeirSetupScreen(repo: VaultRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var step by remember { mutableIntStateOf(0) }
    var intervalDays by remember { mutableIntStateOf(0) }
    var pkgAt by remember { mutableStateOf(0L) }
    var setup by remember { mutableStateOf<HeirManager.Setup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var copiedIndex by remember { mutableIntStateOf(-1) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val (d, _, p) = repo.heirStatus()
        intervalDays = if (d > 0) d else 30
        pkgAt = p
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("遗产交接") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
        ) {
            Text(
                "为最坏的一天提前准备：让信任的人在你的主密码随你一起消失时，仍能取回金库。",
                style = MaterialTheme.typography.bodySmall,
                color = PenText3,
            )
            Spacer(Modifier.height(18.dp))

        when (step) {
            0 -> StepIntro(
                repo = repo,
                intervalDays = intervalDays,
                pkgAt = pkgAt,
                onIntervalChange = { d ->
                    intervalDays = d
                    scope.launch { repo.setHeirInterval(d) }
                },
                onStart = { step = 1 },
                onRotateAsk = { confirmRotate = true },
                onDisableAsk = { confirmDisable = true },
            )

            1 -> StepShares(
                setup = setup,
                busy = busy,
                error = error,
                copiedIndex = copiedIndex,
                onGenerate = {
                    busy = true
                    error = ""
                    setup = HeirManager.generateSetup()
                    busy = false
                },
                onCopy = { i, t ->
                    clipboard.setText(AnnotatedString(t))
                    copiedIndex = i
                },
                onCopiedDone = { copiedIndex = -1 },
                onNext = { step = 2 },
                onBack = { step = 0 },
            )

            else -> StepPackage(
                repo = repo,
                setup = setup,
                busy = busy,
                error = error,
                savedPath = savedPath,
                onGenerate = {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            val s = setup ?: return@launch
                            val json = repo.exportLegacyPackage(s.legacyKeyHex)
                            val name = "yinji-legacy-" +
                                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
                            val path = writeToDownloads(context, name, json)
                            val now = System.currentTimeMillis()
                            repo.setHeirInterval(intervalDays)
                            repo.markHeirPkg()
                            pkgAt = now
                            savedPath = path
                        } catch (e: Exception) {
                            error = e.message ?: "生成失败"
                        }
                        busy = false
                    }
                },
                onDone = onBack,
                onBack = { step = 1 },
            )
        }
        }
    }

    if (confirmRotate) {
        ConfirmDialog(
            title = "重新生成分片？",
            text = "将产生新的遗产密钥：之前分发的 3 份分片和已导出的恢复包全部作废，" +
                "必须重新抄录并分发全部 3 份分片。确定继续吗？",
            confirmText = "重新生成",
            danger = true,
            onConfirm = {
                confirmRotate = false
                step = 1
            },
            onDismiss = { confirmRotate = false },
        )
    }

    if (confirmDisable) {
        ConfirmDialog(
            title = "关闭遗产交接",
            text = "将清除本机的遗产配置（死信开关、心跳与恢复包记录），不影响金库数据。\n\n" +
                "注意：已导出的恢复包文件不会自动删除，请自行从下载目录删除；" +
                "已分发的分片将无法单独作废（除非随后重新生成轮换）。\n\n确定关闭吗？",
            confirmText = "关闭",
            danger = true,
            onConfirm = {
                confirmDisable = false
                scope.launch {
                    repo.disableHeir()
                    val (d, _, p) = repo.heirStatus()
                    intervalDays = d
                    pkgAt = p
                }
            },
            onDismiss = { confirmDisable = false },
        )
    }
}

@Composable
private fun StepIntro(
    repo: VaultRepository,
    intervalDays: Int,
    pkgAt: Long,
    onIntervalChange: (Int) -> Unit,
    onStart: () -> Unit,
    onRotateAsk: () -> Unit,
    onDisableAsk: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("工作原理", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "① 生成一把 32 字节遗产密钥，拆成 3 份分片——任意 2 份即可重建，" +
                    "单份丢失或泄露都无法恢复任何内容；\n" +
                    "② 生成「遗产恢复包」（全量加密备份，密码由遗产密钥派生，无人需要记住），" +
                    "存到受托人能拿到的地方（云盘 / U 盘）；\n" +
                    "③ 受托人收集任意 2 份分片 + 恢复包，在锁屏页「我是受托人」入口即可恢复。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "安全边界：任何拿到 2 份分片的人都能恢复金库，3 份分片务必放在互不共谋的位置" +
                    "（如自己抽屉 / 信任家人 / 保险柜）。重新生成会使旧分片与旧恢复包全部作废。",
                style = MaterialTheme.typography.bodySmall,
                color = PenDanger,
            )
        }
    }
    Spacer(Modifier.height(14.dp))

    Text("死信开关（本机提醒）", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        "每次解锁印迹视为「报平安」。超过所选天数未解锁，锁屏页将显示醒目的受托人恢复入口" +
            "（纯本地机制，不会向任何人发送通知）。",
        style = MaterialTheme.typography.bodySmall,
        color = PenText3,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30, 60, 90, 180).forEach { d ->
            FilterChip(
                selected = intervalDays == d,
                onClick = { onIntervalChange(d) },
                label = { Text("$d 天") },
            )
        }
    }
    Spacer(Modifier.height(14.dp))

    if (pkgAt > 0) {
        Text(
            "已配置：恢复包生成于 " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(pkgAt)) +
                "。金库内容变化后请重新生成。",
            style = MaterialTheme.typography.bodySmall,
            color = PenText3,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRotateAsk, modifier = Modifier.fillMaxWidth()) { Text("重新生成（轮换密钥）") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDisableAsk, modifier = Modifier.fillMaxWidth()) {
            Text("关闭遗产交接", color = PenDanger)
        }
        Spacer(Modifier.height(8.dp))
    } else {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始配置") }
    }
}

@Composable
private fun StepShares(
    setup: HeirManager.Setup?,
    busy: Boolean,
    error: String,
    copiedIndex: Int,
    onGenerate: () -> Unit,
    onCopy: (Int, String) -> Unit,
    onCopiedDone: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    if (setup == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(32.dp))
            Spacer(Modifier.height(10.dp))
            Button(onClick = onGenerate, enabled = !busy) { Text("生成遗产密钥与 3 份分片") }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("上一步") }
        }
        return
    }
    Text(
        "请将 3 份分片分别抄录/复制到互不共谋的 3 个位置。每份形如 YJH1<编号>-<内容>，一行完整复制。",
        style = MaterialTheme.typography.bodySmall,
        color = PenText3,
    )
    Spacer(Modifier.height(10.dp))
    setup.shareTexts.forEachIndexed { i, t ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${i + 1} 份", style = MaterialTheme.typography.titleSmall, color = PenGreen)
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = {
                        onCopy(i, t)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (copiedIndex == i) "已复制" else "复制")
                    }
                }
                Text(t, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (copiedIndex >= 0) {
        LaunchedEffect(copiedIndex) {
            kotlinx.coroutines.delay(1500)
            onCopiedDone()
        }
    }
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("我已抄录并妥善保存") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onBack) { Text("上一步") }
}

@Composable
private fun StepPackage(
    repo: VaultRepository,
    setup: HeirManager.Setup?,
    busy: Boolean,
    error: String,
    savedPath: String?,
    onGenerate: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (savedPath == null) {
            Text(
                "最后一步：生成「遗产恢复包」并保存。它 = 当前金库的全量加密备份，" +
                    "加密密码由遗产密钥派生（不需要任何人记住）。金库内容更新后可重新生成覆盖。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onGenerate, enabled = !busy && setup != null) {
                Text(if (busy) "生成中…" else "生成恢复包并保存到下载目录")
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(Modifier.size(32.dp))
            }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("上一步") }
        } else {
            Text("✅ 遗产交接已配置完成", color = PenGreen, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                "恢复包已保存到：\n$savedPath\n\n请把恢复包放到受托人能拿到的地方（云盘/U 盘），" +
                    "并告知其：在印迹锁屏页点「我是受托人，帮忙恢复」。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
