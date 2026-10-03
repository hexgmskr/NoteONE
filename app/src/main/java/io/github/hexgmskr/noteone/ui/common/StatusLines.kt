package io.github.hexgmskr.noteone.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 行内错误 / 提示行（2026-10-04 收敛：本 App 无 Snackbar，各屏的行内提示
 * 此前是逐字抄的 `Text(...)`，共八个站点）。样式统一为 bodySmall——
 * 错误用 error 色、提示默认 primary 色；间距差异走 modifier。
 */

/** 错误行：`message` 为 null 时不占位。 */
@Composable
internal fun ErrorLine(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/**
 * 错误 + 提示两行（锁定页/恢复密钥页/导出页此前各有一个私有 `StatusLine` 包装，
 * 2026-10-04 上收）。[noticeColor] 默认 primary，锁定页传 onSurfaceVariant。
 */
@Composable
internal fun StatusLine(
    error: String?,
    notice: String?,
    noticeColor: Color = MaterialTheme.colorScheme.primary,
) {
    ErrorLine(error)
    NoticeLine(notice, color = noticeColor)
}

/** 提示行：`message` 为 null 时不占位。[color] 默认 primary（锁定页用 onSurfaceVariant）。 */
@Composable
internal fun NoticeLine(
    message: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (message == null) return
    Text(
        text = message,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}
