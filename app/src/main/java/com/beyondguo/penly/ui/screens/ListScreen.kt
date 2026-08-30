package com.beyondguo.penly.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.penly.data.VaultItem
import com.beyondguo.penly.data.VaultRepository
import com.beyondguo.penly.ui.components.MonogramAvatar
import com.beyondguo.penly.ui.theme.PenBgSoft
import com.beyondguo.penly.ui.theme.PenText3
import com.beyondguo.penly.ui.theme.PenText4

/**
 * 密码箱列表（扁平风 v2，参考 MIUI 密码管理）：
 * 大标题 + 圆角搜索框 + 按首字符分组（字母/汉字）+ 字母头像行。
 */
@Composable
fun ListScreen(repo: VaultRepository, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var accounts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var activeCat by rememberSaveable { mutableStateOf("全部") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = repo.items()
        items = list
        // 列表已解锁可见，解密账号做副标题展示与账号搜索
        accounts = list.associate { it.id to repo.decryptAccount(it) }
        loaded = true
    }

    val cats = remember(items) {
        (listOf("全部") + items.map { it.category.ifBlank { "默认" } }.distinct())
    }
    val kw = keyword.trim().lowercase()
    val filtered = items.filter {
        val catOk = activeCat == "全部" || (it.category.ifBlank { "默认" }) == activeCat
        val kwOk = kw.isEmpty() ||
            it.title.lowercase().contains(kw) ||
            it.category.lowercase().contains(kw) ||
            (accounts[it.id] ?: "").lowercase().contains(kw)
        catOk && kwOk
    }
    val groups = remember(filtered) {
        filtered.sortedBy { it.title }.groupBy {
            val c = it.title.trim().firstOrNull()
            when {
                c == null -> "#"
                c.isLetterOrDigit() && c.code < 128 -> c.uppercaseChar().toString()
                else -> c.toString()
            }
        }
    }

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
                "密码箱",
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
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
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
                LazyColumn(
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
                            VaultRow(item = item, account = accounts[item.id] ?: "", onClick = { onOpen(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultRow(item: VaultItem, account: String, onClick: () -> Unit) {
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
            Text(
                item.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
