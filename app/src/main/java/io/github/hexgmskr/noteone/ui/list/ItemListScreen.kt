package io.github.hexgmskr.noteone.ui.list

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.ui.common.CARD_CORNER
import io.github.hexgmskr.noteone.ui.common.ErrorLine
import io.github.hexgmskr.noteone.ui.common.MorphSource
import io.github.hexgmskr.noteone.ui.common.TagChipModel
import io.github.hexgmskr.noteone.ui.common.TagRow
import io.github.hexgmskr.noteone.ui.common.toChipGroups
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import io.github.hexgmskr.noteone.ui.tagorder.toCompactDisplay

/**
 * 主界面：记录列表 + 按标签筛选。
 *
 * 列表项用 [ItemWithTags.sortedTags] 而非 `tags`——后者顺序由 SQLite 决定、
 * 不作保证，直接用会让标签看起来「乱跳」。
 */
@Composable
fun ItemListScreen(
    viewModel: ItemListViewModel,
    /** 各入口的参数是**进场形变的起点**（被点元素自己的位置/颜色/图标）。 */
    onAddClick: (MorphSource?) -> Unit,
    onItemClick: (Long, MorphSource?) -> Unit,
    onTagOrderClick: (MorphSource?) -> Unit,
    onRecoveryKeyClick: (MorphSource?) -> Unit,
    onExportClick: (MorphSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    /** 批量删除的确认框。纯 UI 门槛，不值得进 ViewModel 状态。 */
    var showDeleteConfirm by remember { mutableStateOf(false) }

    /** 列表滚动状态：滚动中要把展开的按钮栈收回去。 */
    val listState = rememberLazyListState()

    // 用 Box + FAB，不用嵌套 Scaffold：MainActivity 已有一个 Scaffold，
    // 再嵌会重复应用系统内边距，表现为整体界面偏移。
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.inSelectionMode) {
                // 多选模式：筛选条整体让位——筛选把已选中的记录藏起来
                // 还能照样删掉，是最容易误删的形态，索性不让它出现。
                SelectionBar(
                    count = state.selectedItemIds.size,
                    onCancel = viewModel::clearSelection,
                    onDelete = { showDeleteConfirm = true },
                )
            }

            // 删除失败之类的行内错误（本项目无 Snackbar，沿用行内提示惯例）
            ErrorLine(state.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            if (state.displayItems.isEmpty()) {
                // 空态：筛选条不进列表（没有东西可滚），固定在顶部——
                // 筛空之后用户还得靠它清筛选，不能藏起来
                if (!state.inSelectionMode) {
                    TagFilterBar(
                        state = state,
                        onTagToggle = viewModel::toggleTag,
                        onClearFilter = viewModel::clearFilter,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                EmptyListHint(
                    isLoading = state.isLoading,
                    // 「筛出来是空」不等于「库里没记录」。只有库里本来有记录
                    // 时才可能是筛空，这时话术必须指向筛选条件——
                    // 否则用户看到「还没有记录」会以为数据丢了。
                    isEmptyByFilter = state.selectedTagIds.isNotEmpty() && state.allItems.isNotEmpty(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 筛选条作为列表第一项、与记录**一起滚**（2026-10-03 用户拍板：
                    // 标签区和条目连成一个页面）。它和第一条记录的间距由
                    // LazyColumn 的 spacedBy 给——因此**恰好等于记录之间的间距**。
                    if (!state.inSelectionMode && state.candidateTags.isNotEmpty()) {
                        item(key = "filter") {
                            TagFilterBar(
                                state = state,
                                onTagToggle = viewModel::toggleTag,
                                onClearFilter = viewModel::clearFilter,
                            )
                        }
                    }

                    items(state.displayItems, key = { it.item.id }) { entry ->
                        ItemCard(
                            entry = entry,
                            ordering = state.ordering,
                            selected = entry.item.id in state.selectedItemIds,
                            onClick = { morph ->
                                // 多选模式下点卡片是切换选中；平时是打开详情
                                if (state.inSelectionMode) {
                                    viewModel.toggleSelection(entry.item.id)
                                } else {
                                    onItemClick(entry.item.id, morph)
                                }
                            },
                            onLongClick = { viewModel.startSelection(entry.item.id) },
                        )
                    }
                }
            }
        }

        // 多选模式下隐藏整个按钮栈：避免"正在挑要删的，手滑建了一条"
        if (!state.inSelectionMode) {
            // 右下角的按钮栈：新建（常驻）+ 标签管理/恢复密钥/导出导入
            // （叠着，按住新建向上划展开——见 ActionFabStack）。
            // 「回顶部」楔形钮也住在它里面：位置由栈的展开进度决定，
            // 必须和新建卡片共用一份位移才能一起升降。
            ActionFabStack(
                onAddClick = onAddClick,
                onTagManageClick = onTagOrderClick,
                onRecoveryKeyClick = onRecoveryKeyClick,
                onExportClick = onExportClick,
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除选中的记录？") },
            text = {
                Text("将删除 ${state.selectedItemIds.size} 条记录，删除后无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSelected()
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

/**
 * 多选模式的上下文栏：取消 ｜ 已选 N 条 ｜ 删除。
 *
 * 替换掉筛选条与设置行（见调用处的注释）；「已选」数字用 `weight` 居中，
 * 左右各一个按钮。
 *
 * **两侧都是裸文字按钮是刻意为之，不是漏改**（2026-10-04 审计，用户拍板）：
 * 本栏是「模式栏」不是「页面动作栏」——取消的语义是**退出多选**（等同返回，
 * 两样式规则本就允许导航用文字）；删除的「执行」发生在确认框里，且全 App 的
 * 删除入口（⋮ 菜单 / 确认框 / 标签滑开）都是文字形态，保持一致。
 * 两样式规则未覆盖「破坏性动作」这一类；将来若想改成实心（红），只动这一个按钮。
 */
@Composable
private fun SelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text("取消", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "已选 ${count} 条",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDelete) {
            Text("删除", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// =====================================================================
// 可调布局参数（手调区）
//
// 列表页本体已无手调参数；右下角那一摞按钮的参数在
// `ActionFabStack.kt` 末尾。
// =====================================================================


/**
 * 筛选条：「全部」+ 全部候选标签。标签**可多选**，
 * 语义（同维度「或」、跨维度「且」）在 [ItemListViewModel.applyFilter] 里。
 *
 * 标签**只显示值不显示维度名**，与列表项、编辑页一致；维度归属靠分组间距暗示。
 * 顺序直接用 ViewModel 排好的 [ListUiState.candidateTags]（走 TagOrdering），
 * 所以用户在「调整标签顺序」里调过的顺序在这里同样生效。
 *
 * 已知取舍：候选多了会往下折行、挤压列表。当前不设上限——标签体系是用户
 * 自己维护的，量级可预期；真到几十个标签再考虑折叠/横滑，先不做推测性设计。
 */
@Composable
private fun TagFilterBar(
    state: ListUiState,
    onTagToggle: (Long) -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 一条标签都没有时整条不显示（只剩「全部」没有意义）
    if (state.candidateTags.isEmpty()) return

    // 「全部」单独成组：它不是一个标签，跟前后的标签留一点组间距区分开。
    // 选中的标签全清掉 = 回到全部，所以它高亮当且仅当一个都没选。
    val allGroup = listOf(
        TagChipModel(
            label = "全部",
            selected = state.selectedTagIds.isEmpty(),
            onClick = onClearFilter,
        ),
    )

    val tagGroups = state.candidateTags.toChipGroups({ it.namespace }) { tag ->
        TagChipModel(
            label = "#${tag.value}",
            selected = tag.id in state.selectedTagIds,
            // 点一下选中，再点一下取消（多选，与编辑页 chip 的切换语义一致）
            onClick = { onTagToggle(tag.id) },
        )
    }

    // 间距交给调用方的布局：在列表里 = LazyColumn 的 contentPadding + spacedBy
    // （所以与第一条记录的间距恰好等于记录之间的间距）；固定在顶部时调用方补 padding
    TagRow(
        groups = listOf(allGroup) + tagGroups,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 列表卡片：**1 行摘要**（全文点进详情看）+ 标签行。
 *
 * 缩略从 3 行收到 1 行是用户拍板的（2026-10-03）：列表要的是"扫一眼找回哪条"，
 * 不是阅读；阅读交给详情页。
 *
 * 点 = 打开详情（多选模式下是切换选中），长按 = 进入多选；
 * 选中态用容器色区分，不引图标依赖。
 *
 * 点击时把自己**当时的屏幕矩形**交上去，作为详情页进场形变的起点
 * （卡片铺开成整页——见 [MorphSource]）。
 */
@Composable
private fun ItemCard(
    entry: ItemWithTags,
    ordering: TagOrdering,
    selected: Boolean,
    onClick: (MorphSource?) -> Unit,
    onLongClick: () -> Unit,
) {
    var boundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        // 读卡片自己的默认容器色，而不是猜一个——形变要从完全相同的颜色起步
        CardDefaults.cardColors().containerColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { boundsInRoot = it.boundsInRoot() }
            .combinedClickable(
                onClick = { onClick(boundsInRoot?.let { MorphSource(it, containerColor, CARD_CORNER) }) },
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.item.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.sortedTags.isNotEmpty()) {
                Text(
                    text = entry.sortedTags.toCompactDisplay(ordering),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyListHint(isLoading: Boolean, isEmptyByFilter: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                isLoading -> "加载中…"
                isEmptyByFilter -> "没有带这个标签的记录"
                else -> "还没有记录"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isLoading) {
            Text(
                text = if (isEmptyByFilter) {
                    "点「全部」看所有记录"
                } else {
                    "新建一条记录后会出现在这里"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
