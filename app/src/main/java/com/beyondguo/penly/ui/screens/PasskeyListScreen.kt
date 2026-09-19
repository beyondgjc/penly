package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.MonogramAvatar
import com.beyondguo.penly.ui.theme.PenLine
import com.beyondguo.penly.ui.theme.PenText1
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.util.formatTime
import kotlinx.coroutines.launch

/**
 * Passkey 保险库管理页（v5.0-② #48）：
 * 按 rpId 分组展示本机全部 Passkey（用户名 / 凭据 ID 摘要 / 签名计数），支持删除。
 *
 * Passkey 的注册与签名发生在系统凭据流程里（浏览器/RP 通过 Credential Manager 调起
 * PasskeyActivity），本页只做查看与清理。删除某条 Passkey 后，该站点的下次登录
 * 需要重新注册（RP 端原凭据将失效）。
 *
 * 展示口径：rpId / credId / userHandle / signCount 是明文字段，直接展示；
 * 私钥加密存储，永不显示、不可导出（与「槽位绝不写日志」同级的纪律）。
 */
@Composable
fun PasskeyListScreen(
    repo: VaultRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var groups by remember {
        mutableStateOf<List<Pair<String, List<VaultRepository.PasskeyInfo>>>>(emptyList())
    }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<VaultRepository.PasskeyInfo?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        val all = try {
            repo.listPasskeys()
        } catch (_: Exception) {
            // 解锁竞态（15s 自动锁兜底）：中性感，按空列表处理
            emptyList()
        }
        groups = all
            .sortedBy { it.createdAt }
            .groupBy { it.rpId }
            .map { (k, v) -> k to v }
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "Passkey 保险库",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PenText1,
            )
        }

        when {
            loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            groups.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有 Passkey\n\n在支持通行密钥的网站上注册时，\n选择「印迹」即可保存到这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PenText3,
                )
            }

            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                groups.forEach { (rpId, items) ->
                    item(key = "header-$rpId") {
                        Text(
                            rpId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(items.size, key = { i -> items[i].id }) { idx ->
                        val p = items[idx]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { /* 预留：详情展开 */ }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MonogramAvatar(p.userName.ifBlank { rpId.ifBlank { "?" } }, 44.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.userName.ifBlank { "（无用户名）" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = PenText1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "签名 ${p.signCount} 次 · ${formatTime(p.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PenText3,
                                )
                            }
                            IconButton(onClick = { pendingDelete = p }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除",
                                    tint = PenText3,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(PenLine),
                        )
                    }
                }
                item {
                    Text(
                        "删除后，对应站点下次登录需要重新注册通行密钥",
                        style = MaterialTheme.typography.bodySmall,
                        color = PenText3,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 Passkey") },
            text = { Text("删除「${p.userName.ifBlank { p.rpId }}」（${rpDisplay(p)}）的通行密钥？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = p.id
                    pendingDelete = null
                    scope.launch {
                        try {
                            repo.deleteItem(id)
                        } catch (_: Exception) {
                        }
                        refreshKey++
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun rpDisplay(p: VaultRepository.PasskeyInfo): String = p.rpId.ifBlank { "未知站点" }
