package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.penly.crypto.Totp
import com.beyondguo.penly.data.PlainEntry
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.MonogramAvatar
import com.beyondguo.penly.ui.theme.PenGreen
import com.beyondguo.penly.ui.theme.PenLine
import com.beyondguo.penly.ui.theme.PenText1
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.util.copySensitive
import com.beyondguo.penly.util.formatTime
import kotlinx.coroutines.delay

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
                    // 空密码不套掩码，让 emptyText 的「（空）」透出（bug：空密码行永远显示星星）
                    ValueRow(
                        label = "密码",
                        value = if (e.secret.isEmpty()) "" else if (secretVisible) e.secret else "••••••••••",
                        emptyText = if (e.secret.isEmpty()) "（空）" else null,
                        modifier = Modifier.clickable {
                            if (e.secret.isNotEmpty()) {
                                secretVisible = true
                                copySensitive(context, "密码", e.secret)
                                android.widget.Toast.makeText(context, "密码已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )

                    // 2FA 验证码卡（v3.0 项目④）：条目有 TOTP 密钥才渲染
                    if (e.totp.isNotBlank()) {
                        HorizontalLine()
                        TotpCodeCard(secretInput = e.totp, digits = e.totpDigits, period = e.totpPeriod, algo = e.totpAlgo)
                    }

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

/**
 * 2FA 验证码卡（v3.0 项目④）：实时 6 位码（3+3 分组）+ 30s 圆环倒计时。
 * 点击复制走 [copySensitive] —— 自动接剪贴板 60s 清除（项目②）。
 * 密钥非法（解码失败）时整卡不渲染，避免展示坏数据。
 */
@Composable
private fun TotpCodeCard(secretInput: String, digits: Int, period: Int, algo: String) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val context = LocalContext.current
    val timeSec = nowMs / 1000
    // digits/period/algo 来自条目存储（otpauth 参数），空/0 = 回落默认（6 位 / 30 秒 / SHA1）；
    // 此处 secretInput 已是规范化 base32，不能再用 parseInput —— 那会把链接参数丢掉重置为默认值
    val effDigits = digits.takeIf { it in 1..9 } ?: Totp.DEFAULT_DIGITS
    val effPeriod = period.takeIf { it in 1..3600 } ?: Totp.DEFAULT_PERIOD
    val effAlgo = algo.takeIf { it in Totp.ALGORITHMS } ?: Totp.DEFAULT_ALGO
    val secretBytes = remember(secretInput) {
        runCatching { Totp.base32Decode(secretInput) }.getOrNull()
    }
    val code = secretBytes?.let {
        runCatching { Totp.generate(it, timeSec, effDigits, effPeriod, effAlgo) }.getOrNull()
    }
    if (code == null) return
    val remaining = (effPeriod - timeSec % effPeriod).toInt()
    val progress = remaining / effPeriod.toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                copySensitive(context, "验证码", code)
                android.widget.Toast.makeText(context, "验证码已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "2FA 验证码",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = PenText1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                ),
                color = PenText1,
            )
        }
        Canvas(Modifier.size(34.dp)) {
            drawArc(
                color = PenLine,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 6f),
            )
            drawArc(
                color = PenGreen,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 6f, cap = StrokeCap.Round),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("${remaining}s", style = MaterialTheme.typography.bodySmall, color = PenText3)
    }
}
