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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenGreen
import kotlinx.coroutines.launch

/**
 * 冷启动引导（对应小程序 lock-gate 的 setup 态）：
 * 「快速开始」用内置默认主密码（penly-def-v1，等同明文保护，可随时升级）；
 * 「设置主密码」直接建立强保护。
 */
@Composable
fun OnboardingScreen(repo: VaultRepository, onInitialized: () -> Unit) {
    var settingPwd by rememberSaveable { mutableStateOf(false) }
    var pwd by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun quickStart() {
        busy = true
        scope.launch {
            repo.initVault(CryptoEngine.ANDROID_DEFAULT_MASTER, VaultMeta.MODE_DEFAULT)
            onInitialized()
        }
    }

    fun createMaster() {
        if (pwd.length < CryptoEngine.MASTER_MIN_LEN) {
            error = "主密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位"
            return
        }
        if (pwd != confirm) {
            error = "两次输入不一致"
            return
        }
        busy = true
        scope.launch {
            repo.initVault(pwd, VaultMeta.MODE_CUSTOM)
            onInitialized()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = PenGreen, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(16.dp))
        Text("印迹", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("端到端加密 · 明文不出本机", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("两种开始方式", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "· 快速开始：使用应用内置默认密码保护，无需记忆；\n  随时可在「我的」升级为主密码\n" +
                        "· 设置主密码：强保护；主密码是唯一凭证，\n  遗忘后数据无法找回，只能重置",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        if (!settingPwd) {
            Button(
                onClick = { quickStart() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (busy) "初始化中..." else "快速开始（默认保护）")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { settingPwd = true; error = "" },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("设置主密码") }
        } else {
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it; error = "" },
                label = { Text("主密码（至少 ${CryptoEngine.MASTER_MIN_LEN} 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = "" },
                label = { Text("确认主密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { createMaster() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "创建中..." else "创建并进入") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { settingPwd = false; error = "" }, enabled = !busy) {
                Text("返回")
            }
        }
    }
}
