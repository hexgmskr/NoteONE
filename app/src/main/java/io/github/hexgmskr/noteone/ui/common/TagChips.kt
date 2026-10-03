package io.github.hexgmskr.noteone.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 紧凑标签 chip 与标签排布，**编辑页标签面板 / 列表页筛选条共用**。
 *
 * 抽到公共位置的原因：同一批标签在两处必须**长得一样**。各写一份的话，
 * 改了字号或内边距只改一边，同一个标签在两个屏幕里看着像两个 App 的东西，
 * 而且很难察觉。观感参数统一放在文件末尾的「可调布局参数」块，一处改两处生效。
 */

/**
 * 一个 chip 的展示数据。
 *
 * [onClick] 为 null = **纯展示 chip**（详情页用）：不可点、无涟漪——
 * 可点却点了没反应比不可点更像坏了。
 */
internal data class TagChipModel(
    val label: String,
    val selected: Boolean,
    val onClick: (() -> Unit)? = null,
)

/**
 * 平铺列表 → 按维度分组的 chip（[TagRow] 的 `groups` 入参）。
 *
 * 分组顺序 = 输入的**首次出现序**，组内保持输入序——所以**调用方要先把列表
 * 按展示顺序排好**（各面板都走 [TagOrdering.comparator]，见
 * ItemListViewModel / EditViewModel）。2026-10-04 收敛：编辑页已选面板与
 * 列表页筛选面板此前各写一份 `groupBy → values → map`，规则容易漂。
 *
 * @param namespaceOf 取维度名（草稿的维度名是原始输入，调用方自行 trim）
 * @param chipOf 单个元素 → chip
 */
internal inline fun <T> List<T>.toChipGroups(
    namespaceOf: (T) -> String,
    chipOf: (T) -> TagChipModel,
): List<List<TagChipModel>> =
    groupBy(namespaceOf).values.map { group -> group.map(chipOf) }.toList()

/**
 * 标签连续排布。
 *
 * 不按维度强制分行——一个维度一行会让面板纵向拉得很长，把「添加标签」「保存」
 * 挤出首屏。改为单个 FlowRow 连续排布，**维度间用小空隙暗示分组**（组内紧、
 * 组间松），维度归属照样看得出来，但占用的行数是按内容自然折行，不是按维度数。
 *
 * @param groups 按维度分好组的 chip，组间会插一个更宽的间隙
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagRow(
    groups: List<List<TagChipModel>>,
    modifier: Modifier = Modifier,
) {
    // Material3 的可点组件（这里的 Surface(onClick)）会把布局盒撑到「最小触控尺寸」，
    // 默认 48dp。chip 视觉只有约 20dp 高：按默认值，每行凭空多出 28dp 上下留白——
    // 横向因为 chip 本来就宽几乎不膨胀，纵向却膨胀一倍多，于是「左右正常、行距失调」。
    //
    // 现在把盒子压到 CHIP_MIN_TOUCH，行间观感间距做到组内左右间距的 1.8 倍
    // （2026-10-01 用户实机两轮调整后的定值）。算术见 CHIP_MIN_TOUCH / CHIP_ROW_GAP。
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides CHIP_MIN_TOUCH,
    ) {
        FlowRow(
            modifier = modifier,
            // 横向不用 horizontalArrangement：`spacedBy` 会给**每对相邻子项**
            // 都插一份间距——想用「插一个宽 Spacer」加宽组间时，Spacer 两侧各被
            // 插一次 CHIP_GAP，实际组间变成 2×CHIP_GAP + Spacer 宽，精确倍数
            // 无从谈起（2026-10-01 实机量出来才发现：想给 3.2dp 结果给了 11.3dp）。
            // 改为**尾随 Spacer**手工摆：每个 chip 后跟一段宽度精确的空白；
            // 落在行尾的那段不可见，也不会造成「换行后行首缩进」。
            verticalArrangement = Arrangement.spacedBy(CHIP_ROW_GAP),
        ) {
            groups.forEachIndexed { gi, group ->
                group.forEachIndexed { ci, chip ->
                    TagChip(chip)
                    val lastOfGroup = ci == group.lastIndex
                    val veryLast = lastOfGroup && gi == groups.lastIndex
                    if (!veryLast) {
                        Spacer(
                            modifier = Modifier.width(
                                if (lastOfGroup) GROUP_GAP else CHIP_GAP,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 紧凑标签 chip。
 *
 * 用自绘 Surface 而非 FilterChip——后者默认 32dp 高、带勾选图标、留白也大，
 * 在本项目「值本来就短」的场景里纯属占地。字号取 labelMedium（12sp）是下限，
 * 再小中文就难认了；padding 收紧到上下 2dp。
 *
 * [TagChipModel.selected] 语义按上下文分两种：编辑页里是「已选草稿 / 已选中」，
 * 筛选条里是「当前筛选条件」，视觉都是选中态，点一下的后果由调用方决定。
 */
@Composable
internal fun TagChip(chip: TagChipModel) {
    val shape = RoundedCornerShape(CHIP_CORNER)
    val color = if (chip.selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (chip.selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label: @Composable () -> Unit = {
        Text(
            text = chip.label,
            style = CHIP_TEXT_STYLE,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = CHIP_PAD_H, vertical = CHIP_PAD_V),
        )
    }

    val onClick = chip.onClick
    if (onClick == null) {
        // 纯展示：不可点、无涟漪
        Surface(shape = shape, color = color, contentColor = contentColor, content = label)
    } else {
        Surface(
            onClick = onClick,
            shape = shape,
            color = color,
            contentColor = contentColor,
            content = label,
        )
    }
}

// =====================================================================
// 可调布局参数
//
// 想让标签 chip 更密/更松，改这里就够了——**只有这一份**，
// 编辑页标签面板与列表页筛选条同步生效。改完
// `./gradlew :app:assembleDebug` 重新装即可看到效果。
// =====================================================================

/** chip 之间的**横向**间距（组内）。用户实机确认「合适」，别动它。 */
internal val CHIP_GAP = 4.dp

/**
 * chip **行与行之间**的额外纵向间距。
 *
 * 观感行距 = 触控盒比视觉高出的那圈（见 [CHIP_MIN_TOUCH]，上下各 2dp）
 *          + 本值（3.2dp）≈ 7.2dp ＝ [CHIP_GAP] 的 1.8 倍
 * （2026-10-01 用户定：行间要压到组内左右间距的 1.8 倍）。
 * 想微调行距先动这里；动 [CHIP_MIN_TOUCH] 会同时影响可点性。
 * 写成比例而不是 3.2.dp：改 [CHIP_GAP] 时比例不跑偏。
 */
internal val CHIP_ROW_GAP = CHIP_GAP * 0.8f

/**
 * chip 触控盒的最小边长。**改这里不会改变 chip 的视觉尺寸**——
 * 视觉高度由 [CHIP_PAD_V] 决定（约 20dp），这里压的只是外面那圈
 * 「看不见的」点击热区盒子。
 *
 * Material3 默认 48dp（防点不中），对 20dp 高的 chip 太奢：每行白占近半屏。
 * 24dp 是 WCAG 2.2「目标尺寸」的下限——行距因此可以压得很紧，
 * **代价是点按容错变小**：若日后觉得容易点错行，把它调回 28/32
 * （行距会相应变松，可再调 [CHIP_ROW_GAP] 找平衡）。
 * 2026-10-01 用户实机两轮调整后定为 24。
 */
internal val CHIP_MIN_TOUCH = 24.dp

/**
 * 维度分组之间的**总**间距（组内用 [CHIP_GAP]，组间就是本值本身）。
 *
 * 用来**替代「一个维度一行」**：组间留一点空，组内紧挨着，
 * 维度归属照样看得出来，但不强制换行、省纵向空间。
 * = 1.8 × [CHIP_GAP]（2026-10-01 用户定；此前 3 倍仍嫌散），
 * 写成比例是为了和 [CHIP_GAP] 联动。摆放方式见 [TagRow] 的尾随 Spacer 说明。
 */
internal val GROUP_GAP = CHIP_GAP * 1.8f

/**
 * chip 文字样式。`labelMedium` 是 12sp。
 *
 * **不要再小了**——中文笔画密，小于 12sp 可读性掉得很快。
 * 要省空间请调 padding 和间距，不要动字号。
 */
internal val CHIP_TEXT_STYLE @Composable get() = MaterialTheme.typography.labelMedium

/** chip 内文字的左右留白。 */
internal val CHIP_PAD_H = 6.dp

/** chip 内文字的上下留白。这个直接决定 chip 高度。 */
internal val CHIP_PAD_V = 2.dp
