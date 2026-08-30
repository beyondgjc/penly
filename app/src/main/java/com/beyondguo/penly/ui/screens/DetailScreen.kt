package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.penly.data.PlainEntry
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.MonogramAvatar
import com.beyondguo.penly.ui.theme.PenLine
import com.beyondguo.penly.ui.theme.PenText1
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.util.copySensitive
import com.beyondguo.penly.util.formatTime

/**
 * 详情页（扁平风 v2，参考 MIUI 密码管理）：
 * 头像 + 名称 + 更新时间；标签左、值右的扁平行；点行复制（toast），密码默认掩码可切换。
 */
@Composable
fun DetailScreen(
    repo: VaultRepository,
    itemId: String,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf<PlainEntry?>(null) }
    var missing by remember { mutableStateOf(false) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        val v = repo.item(itemId)
        if (v == null) {
            missing = true
        } else {
            entry = try {
                repo.decryptItem(v)
            } catch (_: Exception) {
                null
            }
        }
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
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onEdit(itemId) }) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
        }

        val e = entry
        when {
            e != null -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding(),
                ) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "密码详情",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = PenText1,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonogramAvatar(e.title.ifBlank { "?" }, 56.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                e.title.ifBlank { "（无标题）" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "上次修改：${formatTime(e.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PenText3,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (e.category.isBlank()) "默认" else e.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(10.dp))

                    // 账号行：点行复制
                    ValueRow(
                        label = "账号",
                        value = e.account,
                        modifier = Modifier.clickable {
                            if (e.account.isNotEmpty()) {
                                copySensitive(context, "账号", e.account)
                                android.widget.Toast.makeText(context, "账号已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    HorizontalLine()

                    // 密码行：点行复制并揭示明文（与参考一致，无显式开关）
                    ValueRow(
                        label = "密码",
                        value = if (secretVisible) e.secret else "••••••••••",
                        emptyText = if (e.secret.isEmpty()) "（空）" else null,
                        modifier = Modifier.clickable {
                            if (e.secret.isNotEmpty()) {
                                secretVisible = true
                                copySensitive(context, "密码", e.secret)
                                android.widget.Toast.makeText(context, "密码已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )

                    if (e.note.isNotEmpty()) {
                        HorizontalLine()
                        Spacer(Modifier.height(14.dp))
                        Text("备注", style = MaterialTheme.typography.bodyMedium, color = PenText3)
                        Spacer(Modifier.height(6.dp))
                        Text(e.note, style = MaterialTheme.typography.bodyLarge, color = PenText1)
                    }
                }
            }
            missing -> Text(
                "记录不存在",
                style = MaterialTheme.typography.bodyMedium,
                color = PenText3,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

/** 标签左、值右的扁平信息行 */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = PenText1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            when {
                value.isNotEmpty() -> value
                emptyText != null -> emptyText
                else -> "（空）"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isEmpty()) PenText3 else Color.Unspecified,
            modifier = Modifier.weight(2f, fill = false),
        )
    }
}

@Composable
private fun HorizontalLine() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PenLine),
    )
}
