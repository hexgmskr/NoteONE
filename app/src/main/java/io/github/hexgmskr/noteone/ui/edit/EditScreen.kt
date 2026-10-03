package io.github.hexgmskr.noteone.ui.edit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.ui.common.CHIP_CORNER
import io.github.hexgmskr.noteone.ui.common.ErrorLine
import io.github.hexgmskr.noteone.ui.common.TagChipModel
import io.github.hexgmskr.noteone.ui.common.TagRow
import io.github.hexgmskr.noteone.ui.common.dismissKeyboardOnTap
import io.github.hexgmskr.noteone.ui.common.toChipGroups
import kotlin.math.roundToInt

/**
 * 新建 / 编辑记录页（同一套 UI，靠 [EditViewModel.isEditing] 区分标题与保存语义）。
 *
 * 「粘贴」是**用户主动点击**触发的前台剪贴板读取，不做后台自动监听——
 * Android 10+ 起后台读剪贴板受限，且自动读会打扰用户（spec 7.3）。
 */
@Composable
fun EditScreen(
    viewModel: EditViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    // 保存成功后返回列表。用 LaunchedEffect 确保只触发一次
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.onSavedConsumed()
            onSaved()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .dismissKeyboardOnTap()
            .verticalScroll(rememberScrollState())
            .padding(PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        Text(
            text = if (viewModel.isEditing) "编辑记录" else "新建记录",
            style = MaterialTheme.typography.titleLarge,
        )

        // 「粘贴」并入文本框，不单独占一行。
        //
        // 单独一行的粘贴按钮「显示效果大于功能重要程度」——它只是输入的一个快捷
        // 方式，却把下面的标签区和保存按钮整体往下挤。
        //
        // 用 trailingIcon 而不是叠在框上：Material 会为它预留内边距，文字永远
        // 不会压到按钮；自己叠一层则要手算留白，文本一长就撞上。
        // 代价是它落在右上而非右下（多行框的 trailingIcon 位置由 Material 决定），
        // 权衡下来「不占行 + 无碰撞」比「精确到右下角」更值。
        // ---- 内容框的行数随动（4 行 ⇄ 2 行）----
        //
        // 「新建标签」展开时把竖向空间让给它，内容框收成 2 行（2026-10-01 用户定）。
        // 这里只负责给切换加动画。做法一句话：
        //
        //   **动画期间把框按插值高度重新量一遍**——Material 的框高 = 行数×行高 +
        //   固定留白，所以只要知道"4 行时多高"这一个真值，任意一帧的高度就是
        //   `4 行高度 − 2 个行高 × 进度`。
        //
        // **可见框高**全程连续：每一帧都来自下面的插值约束，动画结束时不需要
        // "交班"给另一种测量方式——早先按"动画中压高度、静止时放开"写的版本，
        // 正是在交班那一帧出现了闪跳（2026-10-03 反馈：拉伸到位后消失又出现）。
        //
        // **框内文本布局并不连续**（2026-10-04 审计如实记）：minLines/maxLines
        // 跟着状态布尔瞬切，与边框插值是**两套口径**——原注释写"没有开关"与事实
        // 不符。真机复核过观感可接受：内容多为短文本，内外不同步不可见；且插值
        // 两端恰好等于两个形态的自然高度，落点不跳。属于**已接受的取舍**，不修代码。
        val editDensity = LocalDensity.current
        val contentLineHeightPx = with(editDensity) {
            MaterialTheme.typography.bodyLarge.lineHeight
                .takeOrElse { MaterialTheme.typography.bodyLarge.fontSize * 1.5f }
                .toPx()
        }

        // 行数随动的进度：与「新建标签」区同一个时长，两半同步。
        // 刻意不 `by` 取值——真正的读取发生在 measure 期（见 layout 修饰符），
        // 逐帧变化不会让这一屏（含标签面板）跟着重组。
        val contentLineProgress = animateFloatAsState(
            targetValue = if (state.isTagCreatorExpanded) 1f else 0f,
            animationSpec = tween(PANEL_ANIM_MS),
            label = "contentLines",
        )

        /** 折叠态（4 行）的自然高度（px）。静止时量一次就够，另一半用行高推。 */
        var fourLineHeight by remember { mutableIntStateOf(0) }

        OutlinedTextField(
            value = state.content,
            onValueChange = viewModel::onContentChange,
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // 进度与高度都在 measure 里读/算：逐帧变化只失效布局，不进组合
                    val p = contentLineProgress.value
                    val fourLine = fourLineHeight
                    val placeable = if (fourLine <= 0) {
                        // 基准还没量到：先按原样量一次，量完由 onSizeChanged 记下
                        measurable.measure(constraints)
                    } else {
                        val h = (fourLine - 2f * contentLineHeightPx * p)
                            .roundToInt()
                            .coerceIn(1, constraints.maxHeight)
                        measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    }
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .onSizeChanged { size ->
                    // 只在第一次量到基准时记录；此后高度都是压出来的，读数不可信
                    if (fourLineHeight == 0) fourLineHeight = size.height
                },
            // 固定行数 + 内部滚动（2026-10-01 用户定）。原来只有 minLines=4，
            // 是「至少四行」：内容一多就长高，把下面全往下挤。现在 minLines 与
            // maxLines 取同值（行数钉死），超出在框内滚动，框永不变高。
            // 随动：展开「新建标签」时收成 2 行，把竖向空间让给那个区域（否则
            // 整页过长、打字时够不着）；折叠回去恢复 4 行（否则整页太空）。
            minLines = if (state.isTagCreatorExpanded) 2 else 4,
            maxLines = if (state.isTagCreatorExpanded) 2 else 4,
            label = { Text("内容（链接或文本）") },
            placeholder = { Text("粘贴或输入要归档的内容") },
            trailingIcon = {
                // 前台读剪贴板：只在用户点击时读，符合 Android 限制
                TextButton(
                    onClick = { viewModel.onPaste(clipboardManager.getText()?.text) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("粘贴", style = MaterialTheme.typography.labelLarge)
                }
            },
        )

        TagPanel(state = state, viewModel = viewModel)

        ErrorLine(state.error)

        // 底部动作栏：取消在左、主操作在右。
        // **取消也是"有框"的按钮**（灰实心 = FilledTonalButton），只是不抢主色——
        // 2026-10-03 用户二轮定：同区域同逻辑下只留两种样式，
        // 积极 = Button（主色实心）、非积极 = FilledTonalButton（灰实心）。
        // 裸文字按钮只留给纯导航/行内小动作（返回、粘贴、↑↓……），不当动作按钮用。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onBack) {
                Text("取消")
            }
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
            ) {
                Text(if (state.isSaving) "保存中…" else "保存")
            }
        }
    }
}

/**
 * 标签面板（spec 7.3），三层：
 *
 *  1. **选已有** —— 按维度隐式分组陈列，点 chip 选中/取消
 *  2. **模糊匹配** —— 值输入框下面列出归一化匹配到的已有标签，点一下选中
 *  3. **新建** —— 维度 + 值两个输入框，常驻「+ 添加标签」入口
 *
 * **不显示维度名**（见 [io.github.hexgmskr.noteone.data.entity.toCompactDisplay]）：
 * 维度归属靠分组间距暗示。维度名只在第三层的输入框里露出——那正是
 * 「定义分类体系」的场合。
 */
@Composable
private fun TagPanel(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    // 间距不用 Column 的统一 spacedBy——面板里「chip 行 / 分隔线」之间要紧凑（PANEL_GAP），
    // 「新建标签」表单内部要透气（PANEL_FORM_GAP），一个值伺候不了两处。
    Column {
        Text("标签", style = MaterialTheme.typography.titleMedium)

        // 已选草稿——连续排布，维度间用小空隙暗示分组，点一下移除
        if (state.tags.isNotEmpty()) {
            Spacer(Modifier.height(PANEL_GAP))
            TagRow(
                groups = state.sortedTags.toChipGroups({ it.namespace.trim() }) { draft ->
                    TagChipModel(label = "#${draft.value}", selected = true) {
                        viewModel.removeTag(draft)
                    }
                },
            )
        }

        // 曾经的「点一下标签可移除」提示已删（2026-10-01）：标签用法熟悉后
        // 它零信息量，而竖向空间在键盘弹起时最吃紧——新建区的两栏不能被顶出去。
        Spacer(Modifier.height(PANEL_GAP))
        HorizontalDivider()
        Spacer(Modifier.height(PANEL_GAP))

        // 第一层：已有标签，按维度隐式分组
        //
        // 空态提示只在「既没有已有标签、也还没加草稿」时出现。
        // 用户刚建好第一条草稿时不该再跟一句「还没有标签」——
        // 眼前就摆着 chip，那句话只会让人怀疑刚才那下是不是没生效。
        if (state.existingTags.isEmpty() && state.tags.isEmpty()) {
            Text(
                text = "还没有标签。下面填好「维度 + 值」就能建第一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TagRow(
                groups = state.existingByNamespace.map { group ->
                    group.tags.map { tag ->
                        TagChipModel(
                            label = "#${tag.value}",
                            selected = state.isSelected(tag),
                            onClick = { viewModel.toggleExisting(tag) },
                        )
                    }
                },
            )
        }

        // 「新建标签」的两个状态：入口按钮 ⇄ 整段表单。
        //
        // 用 AnimatedContent 而不是 AnimatedVisibility：两者高度差很大，硬切会让
        // 整页跳；AnimatedContent 把容器高度平滑过渡、内容交叉淡入，而且**退出的
        // 那一支动画完就从树里消失**，不会在外层 spacing 里留下零高度的空位。
        //
        // 每个分支都包一层 Column：AnimatedContent/AnimatedVisibility 的内容按
        // **一个**布局单元对待，直接摆一串并列子节点会全部叠在同一位置
        // （2026-10-03 真机踩过：分隔线、输入框、按钮叠成一坨）。
        AnimatedContent(
            targetState = state.isTagCreatorExpanded,
            transitionSpec = {
                fadeIn(tween(PANEL_ANIM_MS)) togetherWith fadeOut(tween(PANEL_ANIM_MS))
            },
            label = "tagCreator",
        ) { expanded ->
            Column(modifier = Modifier.fillMaxWidth()) {
                if (expanded) {
                    Spacer(Modifier.height(PANEL_GAP))
                    HorizontalDivider()
                    Spacer(Modifier.height(PANEL_TITLE_TOP_GAP))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("新建标签", style = MaterialTheme.typography.titleSmall)
                        // 「收起」是这一行“小标题”的一部分：文字仍是 20dp，但 M3 的
                        // TextButton 默认套 40dp+ 的盒子，把整行撑高、上下间距显大。
                        // heightIn + 去掉纵向内边距压到 24dp——只砍空白。
                        TextButton(
                            onClick = viewModel::collapseTagCreator,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.heightIn(max = PANEL_COLLAPSE_BTN_HEIGHT),
                        ) {
                            Text("收起", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // 这行是**下面输入框组的标题**：下间距贴住（2dp），上间距与分隔线拉开（8dp）。
                    Spacer(Modifier.height(PANEL_TITLE_BOTTOM_GAP))
                    OutlinedTextField(
                        value = state.namespaceInput,
                        onValueChange = viewModel::onNamespaceChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("维度（如「长度」「主题」「手法」）") },
                        placeholder = { Text("这条标签属于哪个维度") },
                    )

                    Spacer(Modifier.height(PANEL_FORM_GAP))
                    OutlinedTextField(
                        value = state.valueInput,
                        onValueChange = viewModel::onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("值") },
                        placeholder = { Text("输入时会提示已有标签") },
                    )

                    // 第二层：模糊匹配提示
                    if (state.suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(PANEL_FORM_GAP))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "已有这些，点一下直接选中：",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TagRow(
                                groups = listOf(
                                    state.suggestions.map { tag ->
                                        TagChipModel(
                                            label = "#${tag.value}",
                                            selected = state.isSelected(tag),
                                            onClick = { viewModel.toggleExisting(tag) },
                                        )
                                    },
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(PANEL_FORM_GAP))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { viewModel.addTagFromInputs() }) {
                            Text("+ 添加标签")
                        }
                    }
                } else {
                    Spacer(Modifier.height(PANEL_GAP))
                    // 「新建标签」的入口放在备选区末尾，视觉上比标签轻。
                    //
                    // 刻意不用和标签一样的实心 chip：那样它会被误当成一个标签去点，
                    // 而它的语义是「展开下方的创建区」，不是「选中某个标签」。
                    // 用描边 + 加号，弱化视觉权重，但留够点击热区。
                    //
                    // 和 chip 同一个问题：默认会被 M3 的触控盒撑高，压到统一的小尺寸。
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides PANEL_SMALL_TOUCH,
                    ) {
                        Surface(
                            onClick = viewModel::expandTagCreator,
                            shape = RoundedCornerShape(CHIP_CORNER),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Text(
                                text = "+ 新建",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 可调布局参数（编辑页）
//
// 想手调编辑页观感，改这里就够了，不用翻到 Composable 里找魔法数字。
// 改完 `./gradlew :app:assembleDebug` 重新装即可看到效果。
//
// 标签 chip 的观感参数（间距/圆角/内边距/字号/触控盒）不在这里——
// 那些是编辑页与列表页筛选条共用的，见 `ui/common/TagChips.kt`。
// =====================================================================

/** 页面四周留白。调大整体更松，调小更挤。 */
private val PAGE_PADDING = 16.dp

/** 标题与内容框之间、各区块之间的纵向间距。 */
private val SECTION_GAP = 12.dp

/**
 * 标签面板内的紧凑纵向间距：标题 / 标签行 / 分隔线 / 新建入口之间。
 *
 * chip 的触控盒在视觉之外还有一圈留白（见 TagChips.CHIP_MIN_TOUCH），
 * 观感上的「行距」是「这个值 + 盒子那圈留白」，所以它比 [SECTION_GAP] 小得多。
 * 调大更松，调小更挤；调到 0.dp 也不会贴死，盒子那圈留白还在。
 */
private val PANEL_GAP = 4.dp

/**
 * 「新建标签」区展开/收起的动画时长。
 *
 * 两个状态（入口按钮 ⇄ 整个表单）用同一时长，纵向伸缩才是同步的——
 * 一个快一个慢会让首尾互相等待，看着像卡了一下。
 */
private const val PANEL_ANIM_MS = 200

/** 「新建标签」表单内部：输入框 / 匹配提示 / 按钮之间。 */
private val PANEL_FORM_GAP = 8.dp

/** 「+ 新建」入口的触控盒高度（来龙去脉见 TagChips.CHIP_MIN_TOUCH 注释）。 */
private val PANEL_SMALL_TOUCH = 36.dp

/** 「新建标签」标题行与上方分隔线的间距——标题归属下面一组，上方要拉开。 */
private val PANEL_TITLE_TOP_GAP = 8.dp

/** 标题行与其下辖表单的间距——「标题贴住内容」，比上间距窄得多。 */
private val PANEL_TITLE_BOTTOM_GAP = 2.dp

/**
 * 「收起」按钮的高度上限。TextButton 默认套 40dp+ 的盒子，把标题行整体撑高；
 * 24dp 只砍空白，文字（20dp）不受影响。
 */
private val PANEL_COLLAPSE_BTN_HEIGHT = 24.dp
