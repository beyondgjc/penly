package com.beyondguo.penly.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.beyondguo.penly.bio.BioManager
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.ImportType
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.ConfirmDialog
import com.beyondguo.penly.ui.components.SectionTitle
import com.beyondguo.penly.ui.components.SettingCard
import com.beyondguo.penly.ui.components.SettingRow
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenGreen
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.ui.theme.PenWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 我的（对应小程序 pages/mine）：
 * 模式状态、立即锁定、指纹解锁开关、修改主密码、导出/导入、重置、加密说明。
 * 纯本地版无「存储位置/云备份」概念，导入导出即跨端迁移手段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: VaultRepository,
    onVaultChanged: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var meta by remember { mutableStateOf<VaultMeta?>(null) }
    var bioOn by remember { mutableStateOf(false) }
    var bioSupported by remember { mutableStateOf(false) }
    var bioDialog by remember { mutableStateOf(false) }
    var bioMaster by rememberSaveable { mutableStateOf("") }
    var bioError by rememberSaveable { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    var exportSavedPath by remember { mutableStateOf<String?>(null) }

    fun showToast(msg: String) {
        toast = msg
    }

    LaunchedEffect(Unit) {
        meta = repo.meta()
        bioSupported = activity != null && BioManager.isBiometricAvailable(context)
        bioOn = BioManager.hasCachedMaster(context)
    }

    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
            toast = ""
        }
    }

    // 导入：标准 Activity Result API 文件选择。
    // biometric 1.2.0-beta01 起 fragment 已 ≥1.3.0（移除了 requestCode 低 16 位校验），
    // 默认 registry 的随机 requestCode 不再与 FragmentActivity 冲突，无需任何自定义适配
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { inStream ->
                        inStream.bufferedReader().readText()
                    }
                    if (text.isNullOrBlank()) showToast("文件为空") else pendingImport = text
                } catch (e: Exception) {
                    showToast("读取文件失败：${e.message}")
                }
            }
        }
    }

    val isDefault = meta?.pwdMode == VaultMeta.MODE_DEFAULT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的") },
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
            // ---- 模式状态 ----
            SettingCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, contentDescription = null, tint = PenGreen)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isDefault) "默认保护（未设主密码）" else "主密码保护",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (isDefault)
                                "当前使用内置默认密码，数据未受强保护，建议设置主密码"
                            else
                                "端到端加密 · 主密码是唯一凭证",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDefault) PenWarn else PenText3,
                        )
                    }
                }
                if (isDefault) {
                    Button(
                        onClick = { onOpen("changepwd/set") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) { Text("设置主密码（推荐）") }
                }
            }

            SectionTitle("安全")
            SettingCard {
                SettingRow(
                    title = "立即锁定",
                    subtitle = "清除内存密钥，回到锁屏",
                    onClick = { repo.lock() },
                )
                if (bioSupported) {
                    SettingRow(
                        title = "指纹解锁",
                        subtitle = if (isDefault)
                            "默认保护下指纹仅作为进入验证；建议设置主密码后使用"
                        else
                            "主密码副本经 Keystore 加密，仅生物验证通过后使用",
                        trailing = {
                            Switch(
                                checked = bioOn,
                                onCheckedChange = { enable ->
                                    val fa = activity ?: return@Switch
                                    if (enable) {
                                        // 未录入指纹/面容时 Keystore 不允许创建每次验证密钥，先给出明确引导
                                        if (!BioManager.canAuthenticate(context)) {
                                            showToast("请先在系统设置录入指纹/面容，再回来开启")
                                            return@Switch
                                        }
                                        if (isDefault) {
                                            // 默认模式：无需输入主密码（内置常量），直接生物验证后缓存
                                            BioManager.saveMaster(
                                                fa,
                                                CryptoEngine.ANDROID_DEFAULT_MASTER,
                                                onDone = {
                                                    bioOn = true
                                                    showToast("已开启指纹解锁")
                                                },
                                                onError = { showToast(it) },
                                            )
                                        } else {
                                            bioMaster = ""
                                            bioError = ""
                                            bioDialog = true
                                        }
                                    } else {
                                        BioManager.clear(context)
                                        bioOn = false
                                        showToast("已关闭指纹解锁")
                                    }
                                },
                            )
                        },
                    )
                }
            }

            SectionTitle("数据")
            SettingCard {
                SettingRow(
                    title = "修改主密码",
                    subtitle = if (isDefault) "从默认保护升级为主密码" else "全部记录将重新加密",
                    onClick = { onOpen(if (isDefault) "changepwd/set" else "changepwd/change") },
                )
                SettingRow(
                    title = "导出数据",
                    subtitle = "保存加密备份 JSON 到手机「下载」目录",
                    onClick = {
                        if (meta == null) {
                            showToast("请先初始化印迹")
                        } else {
                            scope.launch {
                                try {
                                    val json = repo.exportJson()
                                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                                    val name = "penly_backup_$stamp.json"
                                    val where = com.beyondguo.penly.util.writeToDownloads(context, name, json)
                                    exportSavedPath = where
                                } catch (e: Exception) {
                                    showToast(e.message ?: "导出失败")
                                }
                            }
                        }
                    },
                )
                SettingRow(
                    title = "导入数据",
                    subtitle = "从备份文件覆盖本机数据",
                    onClick = { importLauncher.launch("*/*") },
                )
                SettingRow(
                    title = "重置印迹",
                    subtitle = "删除本机全部数据，不可恢复",
                    danger = true,
                    onClick = { confirmReset = true },
                )
            }

            SectionTitle("关于")
            SettingCard {
                SettingRow(title = "加密说明", onClick = { showAbout = true })
                SettingRow(title = "版本", trailing = { Text("1.0.0", color = PenText3) })
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "印迹 · 端到端加密，密钥不出本机",
                style = MaterialTheme.typography.bodySmall,
                color = PenText3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )
        }
    }

    // ---- 导出成功：弹窗展示保存位置，需用户确认后关闭 ----
    if (exportSavedPath != null) {
        AlertDialog(
            onDismissRequest = { exportSavedPath = null },
            title = { Text("导出成功") },
            text = {
                Text(
                    "加密备份已保存到：\n\n$exportSavedPath\n\n" +
                        "可在手机「文件管理 → 下载」中找到；" +
                        "未设主密码的备份仅本应用可解锁，设置主密码后的备份可与微信小程序互导。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { exportSavedPath = null }) { Text("我知道了") }
            },
        )
    }

    // ---- 指纹开启对话框：先验一次主密码，再走生物验证加密保存副本 ----
    if (bioDialog && activity != null) {
        AlertDialog(
            onDismissRequest = { if (!importing) bioDialog = false },
            title = { Text("开启指纹解锁") },
            text = {
                Column {
                    Text(
                        "需先验证一次主密码；验证通过后主密码副本会以 Keystore 硬件加密保存在本设备，仅在生物验证通过后使用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bioMaster,
                        onValueChange = { bioMaster = it; bioError = "" },
                        label = { Text("主密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (bioError.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(bioError, color = PenDanger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = bioMaster.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            if (!repo.unlock(bioMaster)) {
                                bioError = "主密码错误"
                                return@launch
                            }
                            BioManager.saveMaster(
                                activity,
                                bioMaster,
                                onDone = {
                                    bioOn = true
                                    bioDialog = false
                                    showToast("已开启指纹解锁")
                                },
                                onError = { bioError = it },
                            )
                        }
                    },
                ) { Text("验证并开启") }
            },
            dismissButton = { TextButton(onClick = { bioDialog = false }) { Text("取消") } },
        )
    }

    // ---- 导入确认 ----
    if (pendingImport != null) {
        ConfirmDialog(
            title = "导入数据",
            text = "将用备份文件覆盖本机当前数据，此操作不可撤销。确定导入吗？",
            confirmText = "导入",
            danger = true,
            onConfirm = {
                importing = true
                scope.launch {
                    try {
                        val r = repo.importJson(pendingImport!!)
                        pendingImport = null
                        importing = false
                        when (r.type) {
                            ImportType.WIPED -> showToast("备份为空，已清空本机数据")
                            ImportType.RESTORED ->
                                showToast("导入 ${r.count} 条，请用该备份的主密码解锁")
                            ImportType.REENCRYPTED ->
                                showToast("导入 ${r.count} 条，已转入默认保护，建议设置主密码")
                        }
                        onVaultChanged()
                    } catch (e: Exception) {
                        importing = false
                        showToast(e.message ?: "导入失败")
                    }
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "重置印迹",
            text = "将删除本机所有密码数据（含指纹解锁凭证），且无法恢复。确定要继续吗？",
            confirmText = "重置",
            danger = true,
            onConfirm = {
                confirmReset = false
                scope.launch {
                    BioManager.clear(context)
                    repo.resetVault()
                    onVaultChanged()
                }
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 · 加密说明") },
            text = {
                Text(
                    "印迹采用端到端加密：主密码经 PBKDF2（10 万次迭代）派生 AES-256 密钥，" +
                        "账号/密码/备注在本机加密后才存储，密钥只驻留内存，应用切后台即自动锁定。\n\n" +
                        "「默认保护」使用应用内置默认密码，等同未加密，仅作快速体验；设置主密码后即升级为强保护。\n\n" +
                        "数据迁移通过导出的加密备份文件完成：设置主密码后的备份可与微信小程序互相导入；" +
                        "未设主密码的备份仅本应用可解锁。\n\n" +
                        "无论何种模式，忘记主密码都无法恢复已加密数据，重置会清空全部内容。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("我知道了") } },
        )
    }
}
