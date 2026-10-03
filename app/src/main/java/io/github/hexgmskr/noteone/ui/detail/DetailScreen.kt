package io.github.hexgmskr.noteone.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import io.github.hexgmskr.noteone.data.tag.TagMatcher
import io.github.hexgmskr.noteone.ui.common.CARD_CORNER
import io.github.hexgmskr.noteone.ui.common.ErrorLine
import io.github.hexgmskr.noteone.ui.common.TagChipModel
import io.github.hexgmskr.noteone.ui.common.TagRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记录详情：全文 + 标签 + 创建时间。
 *
 * 顶栏按用户拍板的形态：返回 ｜ 复制 ｜ ⋮（编辑 / 删除）。
 * 复制是这一页存在的主要理由——记录最终要被"取用"（如粘回浏览器打开）；
 * 正文额外裹 [SelectionContainer]，想只选一段时也能手动长按选。
 *
 * **正文的呈现（2026-10-03 用户拍板的方向）**：内容不直接"刻"在页面背景上，
 * 而是装进一张独立卡片——像牌匾：有边界、有底、和周围元素分开。
 * 与列表卡片同一套 Card 语言（同一份默认配色），全 App 的"一块内容"长得一样。
 * 标签复用编辑页的色块 chip（[TagRow]，[TagChipModel.onClick] 传 null 即纯展示），
 * 顺序走用户的 [io.github.hexgmskr.noteone.ui.tagorder.TagOrdering]，与列表同口径。
 *
 * 按钮全用文字（复制 / ⋮ 与全局的 ↑ ↓ 同类），项目零图标依赖。
 * 布局间距集中在文件末尾的「可调布局参数」，手调只动那里。
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    /** 「已复制」提示的计时器。用递增 tick 而不是布尔——连续点按会重置计时。 */
    var copiedTick by remember { mutableIntStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(copiedTick) {
        if (copiedTick > 0) {
            delay(1500)
            copiedTick = 0
        }
    }

    // 删除完成：先消费信号（防重复触发），再交给导航回列表
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            viewModel.onDeletedConsumed()
            onDeleted()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DETAIL_PAD_H),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.weight(1f))
            if (copiedTick > 0) {
                Text(
                    text = "已复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(
                onClick = {
                    val text = state.content
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(android.content.ClipData.newPlainText("NoteONE", text)),
                        )
                    }
                    copiedTick++
                },
            ) {
                Text("复制")
            }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menuOpen = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
        }

        ErrorLine(state.error, modifier = Modifier.padding(vertical = 4.dp))

        when {
            state.isLoading -> Unit

            state.notFound -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "记录不存在（可能已被删除）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 牌匾：正文独立成块，与页面背景分开
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = DETAIL_CARD_TOP),
                    shape = RoundedCornerShape(CARD_CORNER),
                ) {
                    SelectionContainer {
                        Text(
                            text = state.content,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(DETAIL_CARD_PAD),
                        )
                    }
                }

                // 标签：色块 chip，按维度分组、走用户自定义顺序（与列表同一口径）
                if (state.tags.isNotEmpty()) {
                    TagRow(
                        groups = TagMatcher
                            .groupByNamespace(state.tags, state.ordering.comparator)
                            .map { group ->
                                group.tags.map { tag ->
                                    TagChipModel(label = "#${tag.value}", selected = true)
                                }
                            },
                        modifier = Modifier.padding(top = DETAIL_TAGS_TOP),
                    )
                }

                Text(
                    text = "创建于 ${formatCreatedAt(state.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = DETAIL_META_TOP, bottom = DETAIL_BOTTOM_PAD),
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这条记录？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.delete()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 创建时间显示成「2026-10-03 02:06」（本地时区）。 */
private fun formatCreatedAt(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

// =====================================================================
// 可调布局参数（手调区）
//
// 想让详情页更松/更紧，改这里就够了。改完 `./gradlew :app:assembleDebug`
// 重新装即可看到效果。
// =====================================================================

/** 页面左右留白。 */
private val DETAIL_PAD_H = 16.dp

/** 正文卡片与顶栏的距离。 */
private val DETAIL_CARD_TOP = 4.dp

/** 正文卡片的内边距（牌匾的"框"有多厚）。 */
private val DETAIL_CARD_PAD = 16.dp

/** 正文卡片的圆角。 */

/** 标签区与正文卡片的距离。 */
private val DETAIL_TAGS_TOP = 20.dp

/** 创建时间与上方内容的距离。 */
private val DETAIL_META_TOP = 20.dp

/** 页面底部留白（滚到底时最后一行不贴边）。 */
private val DETAIL_BOTTOM_PAD = 24.dp
