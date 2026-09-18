package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.penly.data.VaultItem
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.search.SmartSearcher
import com.beyondguo.penly.ui.components.MonogramAvatar
import com.beyondguo.penly.ui.theme.PenBgSoft
import com.beyondguo.penly.ui.theme.PenGreen
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.ui.theme.PenText4
import com.beyondguo.penly.util.InitialLetter
import kotlinx.coroutines.launch

/** 分组数达到该值才显示右侧索引条——条目少时它反而碍事 */
private const val INDEX_MIN_GROUPS = 6

/** 索引条每个标签的尺寸 */
private val INDEX_LABEL_SIZE = 20.dp

/**
 * 印迹列表（扁平风 v2，参考 MIUI 密码管理）：
 * 大标题 + 圆角搜索框 + 按首字符分组（字母/汉字）+ 字母头像行
 * + 右侧分组快速索引条（分组数达标时出现）。
 */
@Composable
fun ListScreen(repo: VaultRepository, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var accounts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var activeCat by rememberSaveable { mutableStateOf("全部") }
    var loaded by remember { mutableStateOf(false) }
    // 存储格式迁移（v5.0）：解锁后检测到旧格式（CBC）时弹一次性确认框
    var showMigrateDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val list = repo.items()
        items = list
        // 列表已解锁可见，解密账号做副标题展示与账号搜索。
        // 逐条降级：单条解密/验签失败（坏数据）不让整页崩掉（review C1）
        accounts = list.associate {
            it.id to runCatching { repo.decryptAccount(it) }.getOrDefault("")
        }
        loaded = true
        // 格式迁移检测放在列表加载后：弹窗出现时列表已可交互，迁移失败也不阻塞使用
        if (repo.needsFormatMigration()) showMigrateDialog = true
    }

    val cats = remember(items) {
        (listOf("全部") + items.map { it.category.ifBlank { "默认" } }.distinct())
    }
    val kw = keyword.trim().lowercase()

    // 语义检索（端内 AI）：输入防抖 250ms 后调用 smartSearch。
    // - 引擎未就绪时返回纯关键词结果且 semanticUsed=false，追加去重后无任何变化，无害降级
    // - 语义命中**不限定当前分类**（搜索是明确意图）；显示时按字母归入各自分组并带 AI 角标
    var semanticHits by remember { mutableStateOf<List<SmartSearcher.Hit>>(emptyList()) }
    LaunchedEffect(kw) {
        if (kw.isEmpty()) {
            semanticHits = emptyList()
        } else {
            kotlinx.coroutines.delay(250) // 防抖：避免逐键触发 embedding
            val outcome = repo.smartSearch(keyword.trim())
            if (kw == keyword.trim().lowercase()) semanticHits = outcome.hits // 输入已变化则丢弃过期结果
        }
    }

    val filtered = items.filter {
        val catOk = activeCat == "全部" || (it.category.ifBlank { "默认" }) == activeCat
        val kwOk = kw.isEmpty() ||
            it.title.lowercase().contains(kw) ||
            it.category.lowercase().contains(kw) ||
            (accounts[it.id] ?: "").lowercase().contains(kw)
        catOk && kwOk
    }.let { kwHits ->
        // 关键词命中在前，语义补充在后（按相关度），按 itemId 去重
        val kwIds = kwHits.mapTo(HashSet()) { it.id }
        val semanticOnly = semanticHits.mapNotNull { hit -> items.firstOrNull { it.id == hit.itemId } }
            .filter { it.id !in kwIds }
        if (semanticOnly.isEmpty()) kwHits else kwHits + semanticOnly
    }
    // 归并到 A–Z（中文取拼音首字母），索引条因此恒定 ≤27 项；
    // 组内按标题排序，组间按字母序，# 组排最后（与系统通讯录一致）。
    val groups = remember(filtered) {
        filtered
            .groupBy { InitialLetter.of(it.title) }
            .entries
            .sortedWith(compareBy { e -> if (e.key == InitialLetter.OTHER) "ZZ" else e.key })
            .associate { (k, v) -> k to v.sortedBy { it.title } }
    }

    // 每个分组头在 LazyColumn 中的 item 下标，供右侧索引条跳转使用。
    // 结构为 [header, item, item, ..., header, item, ...]，故下标按 1 + 组内条数 递增。
    val headerIndex = remember(groups) {
        val map = LinkedHashMap<String, Int>()
        var idx = 0
        for ((header, list) in groups) {
            map[header] = idx
            idx += 1 + list.size
        }
        map
    }
    /** 当前视口顶部所属的分组，用于索引条高亮 */
    val activeGroup by remember(headerIndex) {
        derivedStateOf {
            var current: String? = null
            for ((label, idx) in headerIndex) {
                if (idx <= listState.firstVisibleItemIndex) current = label else break
            }
            current
        }
    }
    val showIndex = headerIndex.size >= INDEX_MIN_GROUPS
    /** 语义命中的条目 id 集合，行内显示 AI 角标 */
    val semanticHitIds = remember(semanticHits) { semanticHits.map { it.itemId }.toSet() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "印迹",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "我的", tint = PenText3)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索", style = MaterialTheme.typography.bodyLarge, color = PenText3) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = PenText3) },
            singleLine = true,
            shape = CircleShape,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = PenBgSoft,
                focusedContainerColor = PenBgSoft,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        if (cats.size > 1) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(cats) { c ->
                    FilterChip(
                        selected = activeCat == c,
                        onClick = { activeCat = c },
                        label = { Text(c) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            !loaded -> {}
            filtered.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Inventory2,
                        contentDescription = null,
                        tint = PenText4,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (items.isEmpty()) "暂无记录，点击右下角添加" else "无匹配记录",
                        color = PenText3,
                    )
                }
            }
            else -> {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 110.dp),
                    ) {
                        groups.forEach { (header, list) ->
                            item(key = "h_$header") {
                                Text(
                                    header,
                                    fontSize = 13.sp,
                                    color = PenText3,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                            items(list, key = { it.id }) { item ->
                                VaultRow(
                                    item = item,
                                    account = accounts[item.id] ?: "",
                                    semantic = item.id in semanticHitIds,
                                    onClick = { onOpen(item.id) },
                                )
                            }
                        }
                    }
                    if (showIndex) {
                        AlphabetIndexBar(
                            labels = headerIndex.keys.toList(),
                            activeLabel = activeGroup,
                            onSelect = { label ->
                                headerIndex[label]?.let { idx ->
                                    scope.launch { listState.animateScrollToItem(idx) }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
    }

    // ---- 存储格式迁移确认（契约 v2 §5：显式一次性，用户确认后才动手） ----
    if (showMigrateDialog) {
        var migrating by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!migrating) showMigrateDialog = false },
            title = { Text("升级加密格式") },
            text = {
                Text(
                    "本机数据将升级为更强的加密格式（约 1-2 秒），主密码不变。\n\n" +
                        "完成后旧的备份文件仍可导入，但建议重新导出一份新备份。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !migrating,
                    onClick = {
                        scope.launch {
                            migrating = true
                            val err = repo.migrateStorageFormat()
                            migrating = false
                            showMigrateDialog = false
                            if (err == null) {
                                android.widget.Toast.makeText(
                                    context,
                                    "加密格式已升级，建议尽快重新导出一份备份",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    err,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) { Text(if (migrating) "升级中..." else "立即升级") }
            },
            dismissButton = {
                TextButton(enabled = !migrating, onClick = { showMigrateDialog = false }) { Text("稍后") }
            },
        )
    }
}

/**
 * 列表右侧分组快速索引条（条目多时的增强）。
 *
 * 数据来自 [ListScreen] 的 A–Z 分组（中文按拼音首字母归并，见 [InitialLetter]），
 * 因此标签恒定 ≤27 项——条目再多也不会被撑爆。
 *
 * 交互：按下即跳转，竖向拖动可连续切换分组（与系统通讯录一致）。
 * 仅在分组数达到 [INDEX_MIN_GROUPS] 时出现，避免短列表里碍事。
 */
@Composable
private fun AlphabetIndexBar(
    labels: List<String>,
    activeLabel: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight(0.7f) // 只占列表区约七成高并垂直居中：避免整条压在下半屏、贴底或压到 FAB
            .offset(y = (-40).dp) // 整体上移：视觉重心在列表中上部，与通讯录一致（命中换算基于组件自身坐标，不受偏移影响）
            .width(26.dp)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .pointerInput(labels) {
                val heightPx = size.height
                // 每个标签均分整条高度（weight 布局），命中区间 = y / 槽位高
                fun indexAt(y: Float): Int {
                    if (labels.isEmpty()) return 0
                    val slot = heightPx / labels.size
                    return (y / slot).toInt().coerceIn(0, labels.lastIndex)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = indexAt(down.position.y)
                    onSelect(labels[last])
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val i = indexAt(change.position.y)
                        if (i != last) {
                            last = i
                            onSelect(labels[i])
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        labels.forEach { label ->
            val selected = label == activeLabel
            Box(
                modifier = Modifier.weight(1f), // 槽位均分：27 个字母在矮屏上也完整可见，不再截断
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(INDEX_LABEL_SIZE)
                        .clip(CircleShape)
                        .background(if (selected) PenGreen else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Color.White else PenText3,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultRow(item: VaultItem, account: String, onClick: () -> Unit, semantic: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(item.title.ifBlank { "?" }, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (semantic) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "AI",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = PenGreen,
                    )
                }
            }
            if (account.isNotBlank()) {
                Text(
                    account,
                    style = MaterialTheme.typography.bodySmall,
                    color = PenText3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
