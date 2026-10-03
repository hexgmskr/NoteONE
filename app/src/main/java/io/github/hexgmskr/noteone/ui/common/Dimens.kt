package io.github.hexgmskr.noteone.ui.common

import androidx.compose.ui.unit.dp

/**
 * 全局共享尺寸（2026-10-04 收敛）。
 *
 * 此前 `12.dp` 的卡片圆角在详情页（DETAIL_CARD_CORNER）与标签顺序页
 * （ROW_CORNER）各写了一份私有常量——数值相同、名字不同，改一处忘一处的
 * 典型形态。CHIP_CORNER 也一并收在这里（原在 TagChips.kt，同包、引用处零改动）。
 */

/** 卡片 / 可点行 / 面板的统一圆角。 */
internal val CARD_CORNER = 12.dp

/** 标签小片的圆角（比卡片更小，视觉上从属）。 */
internal val CHIP_CORNER = 6.dp
