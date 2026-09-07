package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.SettingCard
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenText3
import kotlinx.coroutines.launch

/**
 * 高级保护（影子保险库的唯一入口）。
 *
 * ## 为什么界面没有任何"已开启 / 未开启"状态
 *
 * 这个页面**刻意不告诉用户他有没有设置过应急密码**：
 * - 主按钮文案恒为「重设应急密码」，无论设置与否
 * - 不显示"当前状态""上次修改时间"之类任何状态字段
 * - 不提供"验证应急密码""找回应急密码"
 *
 * 理由：一旦界面上出现状态，胁迫者只要看一眼这个页面就知道"存在第二套数据"，
 * 不可证伪性当场归零。同理，存储层面也不保留是否设置过的标记
 * （见 [com.beyondguo.penly.data.Slot] 的双槽位 + 占位数据设计）。
 *
 * 该页面在设置页中的入口同样是中性命名、无状态、无强调样式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedProtectionScreen(
    repo: VaultRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pwd by rememberSaveable { mutableStateOf("") }
    var pwd2 by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (pwd != pwd2) {
            error = "两次输入的密码不一致"
            return
        }
        busy = true
        error = ""
        scope.launch {
            val err = repo.setDuressPassword(pwd)
            busy = false
            if (err != null) {
                error = err
            } else {
                pwd = ""
                pwd2 = ""
                android.widget.Toast.makeText(context, "已更新", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高级保护") },
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingCard {
                Column(Modifier.padding(16.dp)) {
                    Text("应急密码", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "应急密码是另一把钥匙。用它解锁时，打开的是另一份数据：结构与你日常看到的一致，内容则是自动生成的占位数据。\n\n" +
                            "两把钥匙相互独立，无法互相推导；真库内容不会出现在那份数据里。\n\n" +
                            "· 应急密码与主密码请务必设为不同\n" +
                            "· 忘记应急密码无法找回，只能在此重设\n" +
                            "· 那份数据会随主库条目数量变化自动更新",
                        style = MaterialTheme.typography.bodySmall,
                        color = PenText3,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SettingCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it; error = "" },
                        label = { Text("应急密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pwd2,
                        onValueChange = { pwd2 = it; error = "" },
                        label = { Text("确认应急密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { submit() },
                        enabled = !busy && pwd.isNotEmpty() && pwd2.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (busy) "处理中..." else "重设应急密码") }
                    if (error.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "为保障不可区分性，本页面不显示是否已设置，也不支持验证或找回。",
                style = MaterialTheme.typography.bodySmall,
                color = PenText3,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
