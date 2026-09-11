package com.beyondguo.penly.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beyondguo.penly.crypto.Totp
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.ConfirmDialog
import com.beyondguo.penly.ui.components.MonogramAvatar
import com.beyondguo.penly.ui.theme.PenDanger
import com.beyondguo.penly.ui.theme.PenDangerSoft
import com.beyondguo.penly.ui.theme.PenLine
import com.beyondguo.penly.ui.theme.PenText1
import com.beyondguo.penly.ui.theme.PenText3
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * 新增/编辑（扁平风 v2，参考 MIUI 密码管理编辑页）：
 * ✕ / 居中标题 / ✓ 顶栏；头部头像 + 名称内联；标签左、输入右的扁平行；
 * ✕ 关闭、✓ 保存；编辑态底部红色删除。
 */
/** [onDone] 参数 deleted：是否因删除而结束（删除时导航需弹回列表页而非详情页） */
@Composable
fun EditScreen(repo: VaultRepository, itemId: String, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = itemId.isNotBlank()

    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var totp by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 扫码录入 2FA 密钥：zxing 取景 Activity 返回的通常是 otpauth:// 链接或裸密钥，
    // 两种形态都交给 save 时的 normalizeSecretInput 统一处理
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { totp = it; error = "" }
    }

    LaunchedEffect(itemId) {
        if (isEdit && !prefilled) {
            repo.item(itemId)?.let { v ->
                try {
                    val e = repo.decryptItem(v)
                    name = e.title
                    category = e.category
                    account = e.account
                    secret = e.secret
                    note = e.note
                    totp = e.totp
                    prefilled = true
                } catch (_: Exception) {
                }
            }
        }
    }

    fun save() {
        if (name.isBlank() && account.isBlank() && secret.isBlank()) {
            error = "至少填写 名称 / 账号 / 密码 一项"
            return
        }
        // 2FA 密钥规范化：支持 base32 串或 otpauth:// 链接；非法则拦截保存
        val totpNorm = if (totp.isBlank()) "" else try {
            Totp.normalizeSecretInput(totp)
        } catch (e: Exception) {
            error = "2FA 密钥格式不正确（应为 base32 串或 otpauth 链接）"
            return
        }
        busy = true
        scope.launch {
            try {
                repo.saveEntry(
                    id = itemId.ifBlank { null },
                    title = name,
                    category = category,
                    account = account,
                    secret = secret,
                    note = note,
                    totpSecret = totpNorm,
                )
                android.widget.Toast.makeText(context, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                onDone(false)
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
                busy = false
            }
        }
    }

    // 保存中吞掉系统返回：避免页面销毁取消保存协程导致条目丢失（与 ✕ 守卫同理）
    androidx.activity.compose.BackHandler(enabled = busy) { }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        // 顶栏：✕ / 居中标题 / ✓
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 保存中禁止关闭：页面销毁会取消 rememberCoroutineScope，
            // 保存协程在挂起点被 kill 会导致条目静默丢失 / 语义索引缺漏
            IconButton(onClick = { if (!busy) onDone(false) }) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
            Text(
                if (isEdit) "编辑记录" else "新增记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (!busy) save() }, enabled = !busy) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "保存",
                    tint = if (busy) PenText1.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 头像 + 名称
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonogramAvatar(name.ifBlank { "密" }, 52.dp)
            Spacer(Modifier.width(16.dp))
            BasicTextField(
                value = name,
                onValueChange = { name = it; error = "" },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = PenText1,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                        if (name.isEmpty()) {
                            Text(
                                "名称",
                                style = MaterialTheme.typography.titleMedium,
                                color = PenText3,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))

        val rowFocus = remember { FocusRequester() }

        FlatInputRow(
            label = "账号",
            value = account,
            onValueChange = { account = it; error = "" },
            placeholder = "请输入账号",
            focusRequester = rowFocus,
        )
        FlatDivider()
        FlatInputRow(
            label = "密码",
            value = secret,
            onValueChange = { secret = it; error = "" },
            placeholder = "请输入密码",
            focusRequester = rowFocus,
        )
        FlatDivider()
        FlatInputRow(
            label = "2FA 密钥",
            value = totp,
            onValueChange = { totp = it; error = "" },
            placeholder = "粘贴或点右侧扫码",
            focusRequester = rowFocus,
            trailing = {
                IconButton(
                    onClick = {
                        scanLauncher.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("对准网站的 2FA 二维码")
                                setBeepEnabled(false)
                                // zxing 默认锁横屏（老条码扫描器惯性）；放开后跟随竖屏，
                                // 与应用整体交互一致（用户反馈：扫码页横屏体验差）
                                setOrientationLocked(false)
                            },
                        )
                    },
                ) {
                    Icon(
                        Icons.Filled.QrCodeScanner,
                        contentDescription = "扫码",
                        tint = PenText3,
                    )
                }
            },
        )
        FlatDivider()
        FlatInputRow(
            label = "分类",
            value = category,
            onValueChange = { category = it },
            placeholder = "默认",
            focusRequester = rowFocus,
        )
        FlatDivider()

        // 备注：多行
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                "备注",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = PenText1,
            )
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = PenText1),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                        if (note.isEmpty()) {
                            Text("补充说明…", style = MaterialTheme.typography.bodyLarge, color = PenText3)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error.isNotEmpty()) {
            Text(
                error,
                color = PenDanger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        if (isEdit) {
            Button(
                onClick = { confirmDelete = true },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PenDangerSoft,
                    contentColor = PenDanger,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(50.dp),
            ) { Text("删除记录") }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除确认",
            text = "确定删除这条加密记录？此操作不可恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    repo.deleteItem(itemId)
                    onDone(true)
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 标签左、输入右的扁平编辑行（输入框收窄贴右，光标天然在右端） */
@Composable
private fun FlatInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { focusRequester.requestFocus() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = PenText1,
        )
        Spacer(Modifier.width(16.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                // 占位词垫在字段后面，右端留出光标宽度，避免重叠
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PenText3,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = PenText1,
                    textAlign = TextAlign.End,
                ),
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .focusRequester(focusRequester),
            )        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun FlatDivider() {
    Spacer(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(PenLine),
    )
}
