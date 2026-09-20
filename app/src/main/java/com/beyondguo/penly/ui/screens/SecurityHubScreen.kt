package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.SectionTitle
import com.beyondguo.penly.ui.components.SettingCard
import com.beyondguo.penly.ui.components.SettingRow

/**
 * 安全中心（二级页）：收纳低频安全功能入口，设置页「安全」组只留高频开关。
 *
 * 红线①：「立即锁定」永远留在设置页，不进本页——应急动作不许多一跳。
 * 红线②：「高级保护」保持刻意中性——无状态标记、无强调样式，
 *         任何"已开启"提示都会让旁人一眼看出存在第二套数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityHubScreen(repo: VaultRepository, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全中心") },
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
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle("安全功能")
            SettingCard {
                // 高级保护：入口刻意中性（类注释红线②），与原设置页形态保持一致
                SettingRow(
                    title = "高级保护",
                    subtitle = "另一把钥匙，打开另一份数据",
                    onClick = { onOpen("protection") },
                )
                // 首个联网功能：入口处即点明"联网"，不隐藏
                SettingRow(
                    title = "安全体检",
                    subtitle = "检查密码是否出现在泄露库中（联网）",
                    onClick = { onOpen("scan") },
                )
                // Passkey 保险库（v5.0-②）：注册/签名在系统凭据流程内，此处只做查看与清理
                SettingRow(
                    title = "Passkey 保险库",
                    subtitle = "管理保存的通行密钥（Android 14+）",
                    onClick = { onOpen("passkeys") },
                )
                // 遗产交接（v5.0 #43/#44）：Shamir 分片设置与恢复包生成
                SettingRow(
                    title = "遗产交接",
                    subtitle = "为信任的人准备恢复路径（2-of-3 分片）",
                    onClick = { onOpen("heirsetup") },
                )
            }
            SectionTitle("硬件级保护")
            EnvelopeSection(repo)
            Spacer(Modifier.height(24.dp))
        }
    }
}
