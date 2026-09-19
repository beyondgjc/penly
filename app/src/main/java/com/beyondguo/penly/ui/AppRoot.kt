package com.beyondguo.penly.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.beyondguo.penly.crypto.Totp
import com.beyondguo.penly.crypto.VaultLockedException
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.penly
import com.beyondguo.penly.ui.screens.AdvancedProtectionScreen
import com.beyondguo.penly.ui.screens.ChangePwdScreen
import com.beyondguo.penly.ui.screens.SecurityScanScreen
import com.beyondguo.penly.ui.screens.DetailScreen
import com.beyondguo.penly.ui.screens.EditScreen
import com.beyondguo.penly.ui.screens.ListScreen
import com.beyondguo.penly.ui.screens.LockScreen
import com.beyondguo.penly.ui.screens.OnboardingScreen
import com.beyondguo.penly.ui.screens.PasskeyListScreen
import com.beyondguo.penly.ui.screens.SettingsScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

private object Routes {
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val PROTECTION = "protection"
    const val SCAN = "scan"
    const val PASSKEYS = "passkeys"

    fun edit(itemId: String = "") = "edit?itemId=$itemId"
}

/**
 * 根状态机：Loading → Onboarding（未初始化）→ Lock（已初始化未解锁）→ Ready（主界面）。
 * 锁定/解锁由 SessionManager 的状态流驱动，无需手动导航。
 */
@Composable
fun AppRoot() {
    val repo = LocalContext.current.penly.repo
    val unlocked by repo.unlocked.collectAsState()

    var initialized by remember { mutableStateOf<Boolean?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey) {
        // v1 单槽位 → v2 双槽位迁移；无 legacy 数据时立即返回，成本可忽略
        repo.migrateIfNeeded()
        initialized = repo.isInitialized()
    }
    val refresh: () -> Unit = { refreshKey++ }

    when {
        initialized == null -> LoadingScreen()
        initialized == false -> OnboardingScreen(repo = repo, onInitialized = refresh)
        !unlocked -> LockScreen(repo = repo, onVaultChanged = refresh)
        else -> ReadyRoot(repo = repo, onVaultChanged = refresh)
    }
}

@Composable
private fun LoadingScreen() {
    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ReadyRoot(repo: VaultRepository, onVaultChanged: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onList = backStackEntry?.destination?.route == Routes.LIST
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 扫码新增（v3.0 项目④）：扫 otpauth 二维码 → 提取密钥/网站名/参数 → 自动建档 → 进详情页看实时码。
    // 非法内容（非 otpauth 二维码）toast 提示且不建档。
    val scanAddLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val p = Totp.parseInput(raw)
                val title = p.displayName().ifBlank { "2FA 密钥" }
                val id = repo.saveEntry(
                    id = null,
                    title = title,
                    category = "",
                    account = "",
                    secret = "",
                    note = "",
                    totpSecret = p.secret,
                    totpDigits = p.digits,
                    totpPeriod = p.period,
                    totpAlgo = p.algo,
                )
                android.widget.Toast.makeText(context, "已扫码添加：$title", android.widget.Toast.LENGTH_SHORT).show()
                navController.navigate("detail/$id")
            } catch (e: VaultLockedException) {
                // 金库在扫码期间被 15s 自动锁兜底锁定（极少见：手动锁定/系统回收）
                android.widget.Toast.makeText(context, "金库已锁定，请解锁印迹后重新扫码", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: IllegalArgumentException) {
                // parseInput 的边界拒绝（digits/period 越界、hotp 链接、非法字符等）——
                // 具体原因透出（区别于「不是有效二维码」的泛化提示），用户可自查二维码来源
                android.widget.Toast.makeText(context, e.message ?: "不是有效的 2FA 二维码", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "不是有效的 2FA 二维码", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        // 内容区自行处理状态栏/导航栏留白（各页 statusBarsPadding/navigationBarsPadding）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (onList) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(end = 8.dp, bottom = 36.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scanAddLauncher.launch(
                                ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("对准网站的 2FA 二维码")
                                    setBeepEnabled(false)
                                },
                            )
                        },
                    ) {
                        Icon(Icons.Filled.QrCode2, contentDescription = "扫码新增")
                    }
                    Spacer(Modifier.height(12.dp))
                    FloatingActionButton(
                        onClick = { navController.navigate(Routes.edit()) },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加")
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIST,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.LIST) {
                ListScreen(
                    repo = repo,
                    onOpen = { navController.navigate("detail/$it") },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    repo = repo,
                    onVaultChanged = onVaultChanged,
                    onOpen = { navController.navigate(it) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PROTECTION) {
                AdvancedProtectionScreen(
                    repo = repo,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SCAN) {
                SecurityScanScreen(
                    repo = repo,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PASSKEYS) {
                PasskeyListScreen(
                    repo = repo,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "detail/{itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            ) { entry ->
                DetailScreen(
                    repo = repo,
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    onEdit = { navController.navigate(Routes.edit(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "edit?itemId={itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                EditScreen(
                    repo = repo,
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    onDone = { deleted ->
                        // 删除后条目已不存在，弹回列表页，避免落在详情页显示"记录不存在"
                        if (deleted) navController.popBackStack(Routes.LIST, inclusive = false)
                        else navController.popBackStack()
                    },
                )
            }
            composable(
                route = "changepwd/{mode}",
                arguments = listOf(navArgument("mode") { type = NavType.StringType }),
            ) { entry ->
                ChangePwdScreen(
                    repo = repo,
                    mode = entry.arguments?.getString("mode") ?: "change",
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
