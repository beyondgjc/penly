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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.beyondguo.penly.bio.BioManager
import com.beyondguo.penly.crypto.CryptoEngine
import com.beyondguo.penly.data.AppPrefs
import com.beyondguo.penly.data.ImportType
import com.beyondguo.penly.data.VaultMeta
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.ConfirmDialog
import com.beyondguo.penly.ui.components.SectionTitle
import com.beyondguo.penly.ui.components.SettingCard
import com.beyondguo.penly.ui.components.SettingRow
import com.beyondguo.penly.util.ClipboardGuard
import com.beyondguo.penly.util.MiuiBgUi
import com.beyondguo.penly.util.UpdateChecker
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
    // 导出（契约 v2）：custom 模式需重输主密码（Argon2id 派生要明文，会话只存派生密钥）
    var exportPwdDialog by remember { mutableStateOf(false) }
    // 剪贴板自动清除开关：初值读 AppPrefs 内存缓存（Application.onCreate 已订阅 DataStore）
    var clipboardClearOn by remember { mutableStateOf(AppPrefs.clipboardAutoClear) }
    // 系统自动填充启用状态（v3.0 项目⑤）：以系统真实状态为唯一事实源。
    // 从系统授权页返回只触发 Activity onResume——Composition 不重建，LaunchedEffect(Unit)
    // 不会重跑（曾因此导致"授权后返回开关不刷新"），必须挂生命周期 ON_RESUME；
    // 首次进入时 Activity 已 RESUMED 收不到回调，初值仍由 remember 现查兜底。
    val autofillManager = context.getSystemService(android.view.autofill.AutofillManager::class.java)
    var autofillEnabled by remember { mutableStateOf(autofillManager?.hasEnabledAutofillServices() == true) }
    var autofillCompatDialog by remember { mutableStateOf(false) }
    // MIUI「后台弹出界面」两步引导（v4.0）：其他应用唤起 autofill 时印迹需在后台
    // 弹窗，MIUI 有独立拦截开关（op 10008），未允许时弹窗被静默吞掉。仅 MIUI 有此
    // 开关；权限状态不做本地检测（各版本 ROM 对该 op 的语义不一致，用户拍板去掉）
    // ——纯引导：第一步系统授权页返回且确认授权成功后，自动进入第二步跳权限页。
    val showBgUiGuide = remember { MiuiBgUi.isMiui() }
    var awaitBgUiStep by remember { mutableStateOf(false) }
    // 检查更新（v4.0）：当前版本号以 PackageManager 为准（此前 UI 硬编码 2.0.0 与实际 build 脱节）
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var newRelease by remember { mutableStateOf<UpdateChecker.GhRelease?>(null) }
    DisposableEffect(activity?.lifecycle, autofillManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autofillEnabled = autofillManager?.hasEnabledAutofillServices() == true
                if (awaitBgUiStep) {
                    awaitBgUiStep = false // 消费一次：无论授权与否都不重复引导
                    if (autofillEnabled && showBgUiGuide && activity != null) {
                        MiuiBgUi.openPermissionPage(activity)
                        // 此处位于 showToast 局部函数定义之前，直接走 android.widget.Toast
                        android.widget.Toast.makeText(
                            context,
                            "第二步：请允许「后台弹出界面」，否则填充解锁界面无法弹出",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

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

    // 导出（契约 v2）：default 模式用内置主密码直接导；custom 模式传入用户重输的主密码，
    // 仓库层会先 verifyCurrentVaultPassword 校验，防止输错密码导出解不开的备份
    fun doExport(masterPassword: String?) {
        scope.launch {
            try {
                val json = repo.exportJson(masterPassword)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                val name = "penly_backup_$stamp.json"
                exportSavedPath = com.beyondguo.penly.util.writeToDownloads(context, name, json)
            } catch (e: Exception) {
                showToast(e.message ?: "导出失败")
            }
        }
    }

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
                // 剪贴板自动清除（v3.0 项目②）：复制的账号/密码 60 秒后自动清空，
                // 压缩"复制完密码→剪贴板长期残留"这一横向泄露面
                SettingRow(
                    title = "剪贴板自动清除",
                    subtitle = "复制的账号/密码 60 秒后自动清空剪贴板",
                    trailing = {
                        Switch(
                            checked = clipboardClearOn,
                            onCheckedChange = { on ->
                                clipboardClearOn = on
                                if (!on) ClipboardGuard.cancelPending()
                                scope.launch { AppPrefs.setClipboardAutoClear(context, on) }
                            },
                        )
                    },
                )
                // 系统自动填充（v3.0 项目⑤）：开关形态。
                // 开 = 跳系统授权页（系统安全要求：绑定必须人工在系统弹窗确认，App 无法静默自绑）
                // 关 = disableAutofillServices() 立即解绑（系统 API，无需二次确认）
                SettingRow(
                    title = "系统自动填充",
                    subtitle = when {
                        autofillEnabled -> "已启用：登录页自动填充账号密码"
                        showBgUiGuide -> "未启用：两步开启——系统授权 + 后台弹出界面"
                        else -> "未启用：打开后需在系统弹窗中确认"
                    },
                    trailing = {
                        Switch(
                            checked = autofillEnabled,
                            onCheckedChange = { want ->
                                if (want) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                                                android.net.Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }.onSuccess {
                                        // 第一步（系统授权）已发起：授权页返回确认后自动进第二步
                                        awaitBgUiStep = showBgUiGuide
                                    }.onFailure { showToast("请到 系统设置 → 密码与账户 → 自动填充服务 手动选择印迹") }
                                } else {
                                    autofillManager?.disableAutofillServices()
                                    autofillEnabled = false
                                }
                            },
                        )
                    },
                )
                // 入口刻意中性、无状态标记、无强调样式：
                // 任何"已开启"提示都会让旁人一眼看出存在第二套数据
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
                        when {
                            meta == null -> showToast("请先初始化印迹")
                            // default 模式：内置主密码可导，无需输入
                            isDefault -> doExport(null)
                            // custom 模式：Argon2id 需要明文主密码，先弹窗重输
                            else -> exportPwdDialog = true
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
                // 兼容范围说明：管理用户预期——填充/保存依赖系统 autofill 协议，
                // 自绘输入框（游戏/自建账号页）与 Compose、Flutter 界面收不到系统请求
                SettingRow(title = "自动填充兼容范围", onClick = { autofillCompatDialog = true })
                // 检查更新（v4.0）：GitHub Releases 为版本事实源——API 可达则语义化比对，
                // 不可达（私有仓库/无网/限流）直接跳发布页由用户自行判断；
                // 下载始终走系统浏览器（不在应用内下载 APK，规避未知来源安装权限）。
                // 版本展示与检查动作合一（市面惯例）：右侧 trailing 为当前版本，点击即检查。
                SettingRow(
                    title = "检查更新",
                    subtitle = if (checkingUpdate) "检查中..." else "检查新版本",
                    trailing = { Text("v$versionName", color = PenText3) },
                    onClick = {
                        if (!checkingUpdate && activity != null) {
                            scope.launch {
                                checkingUpdate = true
                                val rel = try {
                                    UpdateChecker.fetchLatest()
                                } catch (_: Exception) {
                                    null
                                }
                                checkingUpdate = false
                                when {
                                    rel == null -> {
                                        UpdateChecker.openInBrowser(activity)
                                        showToast("已打开发布页，请自行比对最新版本")
                                    }
                                    UpdateChecker.isNewer(rel.tagName, versionName) -> newRelease = rel
                                    else -> showToast("已是最新版本（v$versionName）")
                                }
                            }
                        }
                    },
                )
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

    // ---- 自动填充兼容范围说明：管理预期（协议层边界，非印迹实现缺陷） ----
    if (autofillCompatDialog) {
        AlertDialog(
            onDismissRequest = { autofillCompatDialog = false },
            title = { Text("自动填充兼容范围") },
            text = {
                Text(
                    "支持：使用系统标准输入框的 App 与浏览器/网页登录。" +
                        "聚焦登录框时印迹会提示填充（指纹验证后直接填入）；" +
                        "没存过的登录页保持安静，提交后系统会提示保存到印迹。\n\n" +
                        "不支持：自绘输入框的 App（如部分游戏与自建账号页），" +
                        "以及 Compose、Flutter 等未接入系统协议的界面——" +
                        "系统不会发起填充请求，印迹无法感知此类页面。\n\n" +
                        "遇到不支持的页面，请打开印迹手动添加记录。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { autofillCompatDialog = false }) { Text("我知道了") }
            },
        )
    }

    // ---- 检查更新：发现新版 → 确认后跳浏览器前往发布页下载 ----
    newRelease?.let { rel ->
        AlertDialog(
            onDismissRequest = { newRelease = null },
            title = { Text("发现新版本 ${rel.tagName}") },
            text = {
                Text(
                    "当前版本 v$versionName" +
                        (rel.name?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "") +
                        "\n\n将打开浏览器前往发布页下载。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    activity?.let {
                        UpdateChecker.openInBrowser(
                            it,
                            rel.htmlUrl.ifBlank { "https://github.com/beyondgjc/penly/releases/latest" },
                        )
                    }
                    newRelease = null
                }) { Text("前往下载") }
            },
            dismissButton = {
                TextButton(onClick = { newRelease = null }) { Text("暂不") }
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
                            // 只认当前会话金库的密码：指纹永远只打开当前正在看的这份——
                            // 主库会话输应急密码会被拒（应急密码开的是另一槽位），
                            // 影子会话输应急密码则正常放行（缓存的就是自己世界的密码）。
                            // 旧版第一道 verifyPassword（任一槽位匹配即过）是本检查的
                            // 逻辑子集，纯冗余，已删；更早的 isPrimary() 门禁有两处错
                            //（真主人被误伤——假报已开启却不落盘、开关弹回；跨库场景
                            // 实际判定不到），一并移除。
                            if (!repo.verifyCurrentVaultPassword(bioMaster)) {
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

    // ---- 导入确认：先验证备份密码（解得开才导入），再执行覆盖 ----
    if (pendingImport != null) {
        var importPwd by rememberSaveable { mutableStateOf("") }
        var importError by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!importing) pendingImport = null },
            title = { Text("导入数据") },
            text = {
                Column {
                    Text(
                        "将用备份文件覆盖本机当前数据，此操作不可撤销。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "先验证备份密码，确保导入的数据可读；导入完成后请用该备份对应的密码解锁。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPwd,
                        onValueChange = { importPwd = it; importError = "" },
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !importing,
                    )
                    if (importError.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(importError, color = PenDanger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importing,
                    onClick = {
                        val text = pendingImport ?: return@TextButton
                        scope.launch {
                            importing = true
                            val err = repo.verifyBackupPassword(text, importPwd)
                            if (err != null) {
                                importing = false
                                importError = err
                                return@launch
                            }
                            try {
                                val r = repo.importJson(text, importPwd)
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
                ) { Text(if (importing) "验证中..." else "导入") }
            },
            dismissButton = {
                TextButton(onClick = { if (!importing) pendingImport = null }) { Text("取消") }
            },
        )
    }

    // ---- 导出密码确认（custom 模式）：重输主密码，仓库层先验证再导出 ----
    if (exportPwdDialog) {
        var exportPwd by rememberSaveable { mutableStateOf("") }
        var exportError by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { exportPwdDialog = false },
            title = { Text("输入主密码") },
            text = {
                Column {
                    Text(
                        "导出前请输入当前主密码验证，避免输错密码导出一份自己都解不开的备份。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportPwd,
                        onValueChange = { exportPwd = it; exportError = "" },
                        label = { Text("主密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (exportError.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(exportError, color = PenDanger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (exportPwd.isEmpty()) {
                            exportError = "请输入主密码"
                            return@TextButton
                        }
                        exportPwdDialog = false
                        doExport(exportPwd)
                    },
                ) { Text("导出") }
            },
            dismissButton = { TextButton(onClick = { exportPwdDialog = false }) { Text("取消") } },
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
