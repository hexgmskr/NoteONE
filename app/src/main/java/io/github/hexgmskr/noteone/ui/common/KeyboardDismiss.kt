package io.github.hexgmskr.noteone.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 点空白处收起键盘（2026-10-04 收敛：此前编辑/导出/恢复密钥三屏各抄一份）。
 *
 * Compose 的输入框不会自己丢焦点，不处理的话键盘一直挡着下半屏，
 * 按钮都点不到。按钮/输入框自身会吃掉点击，落到这里的只有空白区域。
 *
 * **两个都要调**：`clearFocus` 只管焦点，`hide` 只管键盘，单调一个在部分机型上
 * 收不干净（MIUI 实测只 clearFocus 不收）。挂在容器最外层、`padding` 之后，
 * 让整屏空白都算数。
 */
@Composable
internal fun Modifier.dismissKeyboardOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return pointerInput(Unit) {
        detectTapGestures(onTap = {
            focusManager.clearFocus()
            keyboard?.hide()
        })
    }
}
