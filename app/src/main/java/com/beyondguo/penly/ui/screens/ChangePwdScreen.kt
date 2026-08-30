package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenInfo
import kotlinx.coroutines.launch

/**
 * 设置/修改主密码（对应小程序 pages/vault/change-pwd）：
 * mode=set：default → custom 升级（旧密码自动取内置默认主密码）；
 * mode=change：custom → custom，需输入旧密码。全量记录重加密。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePwdScreen(repo: VaultRepository, mode: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSet = mode == "set"

    var oldPwd by rememberSaveable { mutableStateOf("") }
    var newPwd by rememberSaveable { mutableStateOf("") }
    var confirmPwd by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (newPwd.length < CryptoEngine.MASTER_MIN_LEN) {
            error = "新主密码至少 ${CryptoEngine.MASTER_MIN_LEN} 位"
            return
        }
        if (newPwd != confirmPwd) {
            error = "两次输入的新密码不一致"
            return
        }
        busy = true
        scope.launch {
            val err = repo.changeMasterPassword(if (isSet) null else oldPwd, newPwd)
            if (err == null) {
                // 旧主密码的指纹副本已失效，清除（需重新开启指纹解锁）
                com.beyondguo.penly.bio.BioManager.clear(context)
                android.widget.Toast.makeText(context, "主密码已更新", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            } else {
                error = err
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isSet) "设置主密码" else "修改主密码") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isSet)
                        "将为密码箱设置主密码：全部记录会用新密码重新加密，内置默认密码即失效。此后主密码是唯一凭证，遗忘无法找回。"
                    else
                        "修改主密码：所有记录将用新密码重新加密（旧密码用于解密校验），云端/备份文件只会看到密文变化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PenInfo,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            if (!isSet) {
                OutlinedTextField(
                    value = oldPwd,
                    onValueChange = { oldPwd = it; error = "" },
                    label = { Text("旧主密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = newPwd,
                onValueChange = { newPwd = it; error = "" },
                label = { Text("新主密码（至少 ${CryptoEngine.MASTER_MIN_LEN} 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPwd,
                onValueChange = { confirmPwd = it; error = "" },
                label = { Text("确认新主密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = PenDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && (isSet || oldPwd.isNotEmpty()) && newPwd.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "重加密中..." else if (isSet) "设置主密码" else "确认修改") }
        }
    }
}
