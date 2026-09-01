package com.beyondguo.penly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.penly
import com.beyondguo.penly.ui.screens.ChangePwdScreen
import com.beyondguo.penly.ui.screens.DetailScreen
import com.beyondguo.penly.ui.screens.EditScreen
import com.beyondguo.penly.ui.screens.ListScreen
import com.beyondguo.penly.ui.screens.LockScreen
import com.beyondguo.penly.ui.screens.OnboardingScreen
import com.beyondguo.penly.ui.screens.SettingsScreen

private object Routes {
    const val LIST = "list"
    const val SETTINGS = "settings"

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
    LaunchedEffect(refreshKey) { initialized = repo.isInitialized() }
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

    Scaffold(
        // 内容区自行处理状态栏/导航栏留白（各页 statusBarsPadding/navigationBarsPadding）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (onList) {
                FloatingActionButton(
                    onClick = { navController.navigate(Routes.edit()) },
                    modifier = Modifier.padding(end = 8.dp, bottom = 36.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加")
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
