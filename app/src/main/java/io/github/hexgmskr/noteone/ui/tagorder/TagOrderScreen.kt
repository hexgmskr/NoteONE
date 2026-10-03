package io.github.hexgmskr.noteone.ui.tagorder

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.ui.common.CARD_CORNER
import io.github.hexgmskr.noteone.ui.common.ErrorLine
import io.github.hexgmskr.noteone.ui.common.NoticeLine
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 标签管理页（原"标签顺序调整页"，2026-10-03 并入改名/合并）。
 *
 * 低频设置，不做拖拽——上下箭头够用，也少一个手势依赖
 * （本项目两次踩过依赖版本冲突，能不引就不引）。
 *
 * 两层顺序分两屏调：进来先调**维度间**，点某个维度进去调它的**维度内**。
 * 一次只调一层，避免两组箭头挤在一屏看不清在挪哪个。
 * 值这一层每个标签还带「改名/合并」入口——**同义词的收束就该在看得见
 * 这一维度全部值的地方做**。
 *
 * **落库语义：边改边存**（每次上下移动立刻写入顺序偏好，见
 * [TagOrderViewModel.moveNamespace]）。因此两种离开方式都安全：
 *  - 完成 / 侧滑返回 / 系统返回 = 留着改好的顺序走人；
 *  - 取消 = 恢复进来时的顺序（唯一的撤销手段）。
 * 早先"只在点完成时落库"的写法会让侧滑返回静默丢改动，两级行为也不一致。
 */
@Composable
fun TagOrderScreen(
    viewModel: TagOrderViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    // 内层（某维度的值顺序）开着时，把系统返回截下来退到维度层。
    //
    // **不能让它冒泡到 Activity**：那边只认「子屏 → 上一层屏」，会把标签管理
    // 整屏弹掉、直接落回主页（2026-10-03 用户反馈）。这里用**嵌套**的
    // PredictiveBackHandler——系统按"后注册者优先"分发，本屏注册得比
    // Activity 晚，开着即生效、关掉就自然让位，不给调用方加接口。
    PredictiveBackHandler(enabled = state.isEditingValues) { progress ->
        // 本层不做跟手预览：两层共用同一屏的标题和底部按钮，拖动时不挪动
        // 反而更稳（切换动画在下面 AnimatedContent 里）。只等它的结局——
        // 走完 = 提交，退层；被取消则 collect 抛 CancellationException，
        // 后面的退层不会执行。
        progress.collect { }
        viewModel.doneEditingValues()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("标签管理", style = MaterialTheme.typography.titleLarge)

        // 两层之间横向滑动切换：进层从右滑入、退层往右滑走。
        // 手势提交、系统返回键、点「← 回到维度顺序」三条路都只是改
        // [TagOrderUiState.editingValues]，动画由这里统一给——
        // 所以退层不是瞬变（2026-10-03 反馈：移开的页面不该直接消失）。
        AnimatedContent(
            targetState = state.editingValues,
            transitionSpec = {
                // 目标非空 = 进层：新内容从右进、旧的往左退；退层整个反过来
                val dir = if (targetState != null) 1 else -1
                val spec = tween<IntOffset>(LEVEL_SLIDE_MS)
                // 交叉时两边各淡一半，避免内容互压时看着"打架"
                (slideInHorizontally(spec) { full -> dir * full } +
                    fadeIn(tween(LEVEL_SLIDE_MS / 2))) togetherWith
                    (slideOutHorizontally(spec) { full -> -dir * full } +
                        fadeOut(tween(LEVEL_SLIDE_MS / 2)))
            },
            // 不裁的话，滑动中的内容会画到标题和按钮区域上
            modifier = Modifier.clipToBounds(),
            label = "tagLevel",
        ) { editingNamespace ->
            // 出场（旧）内容用上一份数据渲染：退层时 editingValues 已被清空，
            // 所以值层的数据靠参数带进来，不现读 state
            if (editingNamespace != null) {
                ValueOrderSection(
                    namespace = editingNamespace,
                    values = state.values,
                    viewModel = viewModel,
                )
            } else {
                NamespaceOrderSection(state = state, viewModel = viewModel)
            }
        }

                ErrorLine(state.error)
        NoticeLine(state.notice)

        HorizontalDivider()

        // 本页语义：**边改边存**（每次上下移动立刻落库），所以"完成"只是
        // 留着改好的顺序走人；"取消"是唯一的撤销——恢复进来时的样子。
        // 侧滑返回 / 系统返回键都算"完成"（改动已经存了），不会静默丢东西。
        // 顺序与对话框一致：取消在左（文字按钮）、主操作在右（实心）。
        // 2026-10-03 统一：此前是「完成 | 取消」，主操作靠左，与对话框相反。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 取消也是"有框"的按钮（灰实心），与保存类按钮同形不同色——
            // 2026-10-03 用户二轮定的两样式规则，见 EditScreen 同处注释。
            FilledTonalButton(
                onClick = {
                    viewModel.revert()
                    onBack()
                },
            ) {
                Text("取消")
            }
            Button(onClick = {
                viewModel.persist()
                onBack()
            }) {
                Text("完成")
            }
        }
    }

    if (state.managing != null) {
        TagManageDialog(state = state, viewModel = viewModel)
    }
}

@Composable
private fun NamespaceOrderSection(
    state: TagOrderUiState,
    viewModel: TagOrderViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        Text("调整维度顺序", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "先显示哪个维度。点一行进去调它内部的顺序。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.namespaces.forEachIndexed { index, namespace ->
            // key：换位时让 Compose 认出"还是这一行"，换位动画才挂得住
            key(namespace) {
            OrderRow(
                label = namespace,
                index = index,
                isFirst = index == 0,
                isLast = index == state.namespaces.lastIndex,
                onUp = { viewModel.moveNamespaceUp(index) },
                onDown = { viewModel.moveNamespaceDown(index) },
                // 维度名本身可点（它长得像个链接），但**整行也是热点**：
                // 两个字的维度名命中区太小，只点字上很容易点空（2026-10-03 反馈）。
                // 值那一层同理，两层行为一致。
                onLabelClick = { viewModel.editValuesOf(namespace) },
                onRowClick = { viewModel.editValuesOf(namespace) },
            )
            }
        }
    }
}

/**
 * 值层（某维度内的顺序 + 改名/合并/删除）。
 *
 * [namespace] 与 [values] 由调用方带进来而不是现读 state：退层动画期间
 * `state.editingValues` 已经清空，出场内容要能拿**上一份**数据把自己画出来。
 */
@Composable
private fun ValueOrderSection(
    namespace: String,
    values: List<String>,
    viewModel: TagOrderViewModel,
) {
    /** 待确认删除的标签（null = 没开确认框）。 */
    var pendingDelete by remember { mutableStateOf<Tag?>(null) }

    /** 当前滑开的那一行（tag id）。同时只留一行开着，与 Telegram 一致。 */
    var openRowId by remember { mutableStateOf<Long?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        Text(
            text = "调整「$namespace」内的顺序",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "先显示哪个值。点标签改名/合并；把行向右滑开可以删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        values.forEachIndexed { index, value ->
            // 同名标签理论上不会出现（(namespace, normalizedValue) 唯一索引），
            // 但 values 是展示值列表，稳妥起见按值取第一条
            val tag = viewModel.findTag(namespace, value)
            // **key 直挂 Column 的直接子节点，且块内只放一个 composable 调用**
            // （[ValueRow]）——两条缺一，重排时状态就不跟行走（2026-10-03 真机
            // 插桩实测：key 被 if/else 包着 → 每次都重建；key 块内直接写 if/else
            // 或外置 lambda → 运行时按位置复用。两种都表现为 `lastIndex` 恒等于
            // `index`，换位动画与"滑开状态跟着行走"全部失效）。
            key(tag?.id ?: "v$index") {
                ValueRow(
                    tag = tag,
                    value = value,
                    index = index,
                    isLast = index == values.lastIndex,
                    openRowId = openRowId,
                    onRevealed = { openRowId = it },
                    onClosed = { if (openRowId == it) openRowId = null },
                    onDelete = { pendingDelete = it },
                    viewModel = viewModel,
                )
            }
        }

        TextButton(onClick = viewModel::doneEditingValues) {
            Text("← 回到维度顺序")
        }
    }

    pendingDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${doomed.value}」？") },
            text = {
                Text("它会从所有挂过它的记录上移除（记录本身保留）。删除后无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteTag(doomed)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 值层的一行（滑开删除容器 + 行本体）。
 *
 * **独立成 composable 是为了换位动画**：`key()` 块的直接内容是**单个 composable
 * 调用**时，运行时才会把它当"可迁移组"按 key 查找迁移；若 key 块里直接写
 * if/else 或外置 lambda，真机实测它只按位置复用——remember 不跟着行走，
 * 换位动画永不触发（2026-10-03 插桩定位，见 handoff）。
 */
@Composable
private fun ValueRow(
    tag: Tag?,
    value: String,
    index: Int,
    isLast: Boolean,
    openRowId: Long?,
    onRevealed: (Long) -> Unit,
    onClosed: (Long) -> Unit,
    onDelete: (Tag) -> Unit,
    viewModel: TagOrderViewModel,
) {
    val row: @Composable () -> Unit = {
        OrderRow(
            label = "#$value",
            index = index,
            isFirst = index == 0,
            isLast = isLast,
            onUp = { viewModel.moveValueUp(index) },
            onDown = { viewModel.moveValueDown(index) },
            onLabelClick = null,
            onManage = tag?.let { { viewModel.startManaging(it) } },
            // 点整行 = 改名/合并（比小按钮好点中）
            onRowClick = tag?.let { { viewModel.startManaging(it) } },
        )
    }
    if (tag == null) {
        row()
    } else {
        SwipeToRevealDelete(
            rowId = tag.id,
            openRowId = openRowId,
            onRevealed = onRevealed,
            onClosed = onClosed,
            onDelete = { onDelete(tag) },
            content = row,
        )
    }
}

/** 滑开状态的锚点：关着 / 露着删除按钮。 */
private enum class RevealState { Closed, Open }

/**
 * Telegram 式的"滑开露出删除"容器。
 *
 * 整行向右拖（用户拍板的方向）：行内容右移，左边露出删除按钮；
 * 松手按位置/速度吸附到开或关。用 [AnchoredDraggable] 而不是 SwipeToDismiss——
 * 要的是"**露出来、等你点**"，不是"滑过头就消失"：删除标签会动所有记录，
 * 得让手指停一下、眼睛确认一下。
 *
 * 同时只留一行开着（[isOpen] 由父级统一持有）：和 Telegram 一样，
 * 滑开新的一行会把上一行关上，屏幕上不会同时躺着一排删除按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeToRevealDelete(
    rowId: Long,
    openRowId: Long?,
    onRevealed: (Long) -> Unit,
    onClosed: (Long) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val scope = rememberCoroutineScope()

    val state = remember(revealPx) {
        AnchoredDraggableState(
            initialValue = RevealState.Closed,
            anchors = DraggableAnchors {
                RevealState.Closed at 0f
                RevealState.Open at revealPx
            },
        )
    }

    // 规则一：自己滑开/关上就上报（父级据此记"谁开着"）。
    LaunchedEffect(state.settledValue) {
        if (state.settledValue == RevealState.Open) onRevealed(rowId) else onClosed(rowId)
    }

    // 规则二：只有父级**明确指定了别的行**开着，才关自己。
    //
    // 注意不能写成"我开着但父级没认领 = 关"——那是个竞态：滑开落定的瞬间
    // 父级的标记还没更新（真机上表现为"一露头就弹回去"）。
    // 判据必须是 openRowId 有值且不是自己；null（没人在开）时什么都不做。
    LaunchedEffect(openRowId) {
        if (openRowId != null && openRowId != rowId && state.settledValue == RevealState.Open) {
            state.animateTo(RevealState.Closed)
        }
    }

    val offset = state.offset.takeIf { !it.isNaN() } ?: 0f

    Box(modifier = Modifier.fillMaxWidth()) {
        // 底层：左侧的删除按钮（露出的部分）
        TextButton(
            onClick = {
                scope.launch { state.animateTo(RevealState.Closed) }
                onDelete()
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(REVEAL_WIDTH),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("删除")
        }

        // 面层：行内容。不透明底色（与页面同色）盖住按钮；拖动 + 跟手偏移。
        //
        // **offset 必须在 anchoredDraggable 之前（更外层）**：命中测试跟着
        // placement 走——写在里面的话，拖动层仍占着未平移的整行范围，
        // 把底下露出的删除按钮全盖住（视觉上按钮在、点它却没反应，真机实测）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.roundToInt(), 0) }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = state,
                        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
                    ),
                )
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}

/**
 * 一行：标签 + 上移/下移（+ 可选的「改名/合并」入口）。
 *
 * [onLabelClick] 为 null 时标签渲染成普通文本（值那一层：它没有下钻）。
 * [onManage] 非 null 时（值这一层）多一个改名/合并按钮。
 * [onRowClick] 给整行加点击，**两层都用**——维度层是「进去调值顺序」，值层是
 * 「改名/合并」。整行可点是为了命中区：标签往往只有两个字，只点字上很容易点空；
 * 行内的 ↑↓ 按钮各自吃掉自己的点击，不受影响。
 */
@Composable
private fun OrderRow(
    label: String,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLabelClick: (() -> Unit)?,
    onManage: (() -> Unit)? = null,
    onRowClick: (() -> Unit)? = null,
) {
    var rowModifier = Modifier
        .fillMaxWidth()
        // 行高固定：换位动画要把"索引差"换算成像素（见 swapShift）
        .height(ROW_HEIGHT)
    if (onRowClick != null) {
        rowModifier = rowModifier.clickable(onClick = onRowClick)
    }

    // 一行 = 一张卡片（与列表页的记录卡片同一套容器色），不再是一排"悬在黑底上的
    // 文字"——2026-10-03 反馈：条目要肉眼可见。
    Surface(
        shape = RoundedCornerShape(CARD_CORNER),
        color = CardDefaults.cardColors().containerColor,
        modifier = rowModifier.swapShift(index),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onLabelClick != null) {
                TextButton(
                    onClick = onLabelClick,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(label)
                }
            } else {
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onManage != null) {
                    TextButton(
                        onClick = onManage,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("改名/合并", style = MaterialTheme.typography.labelSmall)
                    }
                }
                // ↑↓ 用 TextButton 而不是描边按钮：卡片里再套方框太吵，
                // 也和「改名/合并」的轻重一致（2026-10-03 随卡片样式一起改）
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides ROW_TOUCH,
                ) {
                    TextButton(
                        onClick = onUp,
                        enabled = !isFirst,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("↑")
                    }
                    TextButton(
                        onClick = onDown,
                        enabled = !isLast,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("↓")
                    }
                }
            }
        }
    }
}

/**
 * 换位动画：把"索引一变、行瞬移"补成"滑过去"。
 *
 * 做法：索引变化时先 snap 到**旧位置**对应的偏移，再动画滑回 0——
 * 于是看到的是相邻两行互换，而不是整列重排（2026-10-03 用户要求）。
 * 行高固定（[ROW_HEIGHT]）才能把索引差换算成像素。
 *
 * 偏移读在 layout 期（`offset {}`），逐帧变化不进组合。交换中的那一行
 * 抬到上层，免得被邻行压住一半。
 */
@Composable
private fun Modifier.swapShift(index: Int): Modifier {
    var lastIndex by remember { mutableStateOf(index) }
    var elevated by remember { mutableStateOf(false) }
    val shift = remember { Animatable(0f) }
    val pitchPx = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }

    LaunchedEffect(index) {
        if (lastIndex != index) {
            shift.snapTo((lastIndex - index) * pitchPx)
            elevated = true
            shift.animateTo(0f, tween(SWAP_MS, easing = FastOutSlowInEasing))
            elevated = false
        }
        lastIndex = index
    }

    return this
        .zIndex(if (elevated) 1f else 0f)
        .offset { IntOffset(0, shift.value.roundToInt()) }
}

// =====================================================================
// 可调布局参数（手调区）
// =====================================================================

/** 滑开后露出的删除按钮宽度。 */
private val REVEAL_WIDTH = 96.dp

/**
 * 改名 / 合并对话框（标签管理）。
 *
 * 两件事在一个框里，因为它们是同一类动作：**这个标签的身份要不要变**——
 * 改成新名字（记录不动），或者并进同维度的另一个标签（引用转移、本标签消失）。
 *
 * 合并要**二次确认**：点候选只是选中，再点「确认合并」才真做——
 * 合并会删掉一个标签，没有撤销，和删记录同一个级别的动作。
 */
@Composable
private fun TagManageDialog(
    state: TagOrderUiState,
    viewModel: TagOrderViewModel,
) {
    val tag = state.managing ?: return

    AlertDialog(
        onDismissRequest = viewModel::cancelManaging,
        title = { Text("改名 / 合并「${tag.value}」") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 错误必须**贴着顶部**：放在内容末尾的话，候选一多就被挤出
                // 弹窗可视区（真机实测：点了"保存新名字"看着毫无反应）
                ErrorLine(state.error)

                val pending = state.pendingMerge
                if (pending != null) {
                    // 二次确认页
                    Text(
                        text = "把「${tag.value}」合并到「${pending.value}」？" +
                            "引用它的记录会改挂到「${pending.value}」，本标签被删除。这一步无法撤销。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = "改名：只换名字，引用它的记录不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.renameInput,
                        onValueChange = viewModel::onRenameInputChange,
                        label = { Text("新名字") },
                        singleLine = true,
                    )
                    TextButton(onClick = viewModel::submitRename) {
                        Text("保存新名字")
                    }

                    if (state.mergeCandidates.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = "合并到同维度的其他标签（引用转移、本标签删除）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.mergeCandidates.forEach { target ->
                            TextButton(onClick = { viewModel.askMergeInto(target) }) {
                                Text("#${target.value}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val pending = state.pendingMerge
            if (pending != null) {
                TextButton(onClick = viewModel::confirmMerge) {
                    Text("确认合并")
                }
                TextButton(onClick = viewModel::cancelPendingMerge) {
                    Text("返回")
                }
            } else {
                TextButton(onClick = viewModel::cancelManaging) {
                    Text("关闭")
                }
            }
        },
    )
}

/** 维度层 ⇄ 值层切换动画的时长。太慢像卡住，太快看不出是"退了一层"。 */
private const val LEVEL_SLIDE_MS = 260

// ---- 行（条目卡片）的观感参数 ----

/** 行高。**固定值**：换位动画靠它把索引差换算成像素。 */
private val ROW_HEIGHT = 48.dp

/** 行间距。与行高一起构成"步距"，换位位移按它算。 */
private val ROW_GAP = 8.dp

/** 行卡片圆角（与列表页记录卡片同一档）。 */

/** ↑↓ 按钮的触控盒（卡片里塞不下 48dp 的默认触控盒，压到 40dp）。 */
private val ROW_TOUCH = 40.dp

/** 换位动画时长。太长会挡着下一次点击，太短看不出"换"。 */
private const val SWAP_MS = 220
