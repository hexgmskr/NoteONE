package io.github.hexgmskr.noteone.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.hexgmskr.noteone.data.export.ImportFormat
import io.github.hexgmskr.noteone.ui.common.StatusLine
import io.github.hexgmskr.noteone.ui.common.dismissKeyboardOnTap
import java.time.LocalDate

/**
 * 导出页：把全部记录导出成文件，加密或明文二选一（用户拍板）。
 *
 * 文案的责任跟恢复密钥页一样是**诚实**：恢复密钥找回的是"钥匙"，
 * 这里的导出文件才是"数据"。两样都在手上，才谈得上完整的恢复。
 *
 * 写文件走系统文件选择器（用户自己挑位置），与恢复密钥的导出同一条路——
 * DocumentsUI 在小米上可自动化（对照：OpenDocument 会落到 MIUI 文件管理器，那个不行）。
 */
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 写文件逻辑两种形态共用：把 VM 备好的字节写进用户选的位置。
    val writeToUri: (android.net.Uri?) -> Unit = { uri ->
        if (uri == null) {
            viewModel.onExportCancelled()
        } else {
            val bytes = viewModel.pendingBytes()
            val ok = bytes != null && runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                    ?: error("openOutputStream 返回 null")
            }.isSuccess
            viewModel.onExportFinished(ok)
        }
    }

    // 两个 launcher 只差 MIME 与默认扩展名；内容由 VM 决定，这里不碰数据。
    val encryptedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> writeToUri(uri) }
    val plaintextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> writeToUri(uri) }

    // 选导入文件：与恢复密钥的「从备份恢复」同一条路
    // （OpenDocument 在 MIUI 下落进自带文件管理器，接受人工点选）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult   // 用户取消
        val content = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (content == null) viewModel.onImportFileUnreadable()
        else viewModel.onImportFilePicked(content)
    }

    // 数据备好（加密态可能等几秒）→ 拉起系统文件选择器。一次性事件，拉起即消费。
    LaunchedEffect(state.pendingPicker) {
        when (state.pendingPicker) {
            ExportKind.ENCRYPTED -> {
                encryptedLauncher.launch("noteone-export-${LocalDate.now()}.txt")
                viewModel.onPickerLaunched()
            }

            ExportKind.PLAINTEXT -> {
                plaintextLauncher.launch("noteone-export-${LocalDate.now()}.json")
                viewModel.onPickerLaunched()
            }

            null -> Unit
        }
    }

    if (state.confirmPlaintext) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPlaintextExport,
            title = { Text("导出明文？") },
            text = {
                Text(
                    "明文文件不加密：任何拿到它的人（其它 App、网盘同步、借用手机的人）" +
                        "都能直接读到全部记录。\n\n" +
                        "只是临时给自己看一眼、看完就删，可以走明文；" +
                        "要在手机外长期保管，建议用加密导出。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPlaintextExport) {
                    Text("仍然导出明文")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPlaintextExport) {
                    Text("取消")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PAGE_PADDING)
            .dismissKeyboardOnTap(),
    ) {
        // 顶部固定一行：返回。不随内容滚走、永远够得着；
        // 与详情页同一布局语言（2026-10-03 统一：此前是本页底部一个描边按钮，
        // 和详情/编辑几屏对不上）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("返回") }
            Spacer(Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = PAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            Text("导出 / 导入", style = MaterialTheme.typography.titleLarge)

            Text(
                text = "把全部记录导出成一个文件，交给你自己保管；也可以把导出的文件读回来。" +
                    "它和恢复密钥各管一半：恢复密钥找回「钥匙」，导出文件保管「数据」" +
                    "——完整的恢复需要两样都有。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.busy,
                label = { Text("主密码（加密导出需要）") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Button(onClick = viewModel::requestEncryptedExport, enabled = !state.busy) {
                Text(if (state.busy) "处理中…（加密要几秒）" else "导出（加密）…")
            }
            Text(
                text = "文件离开手机后仍是密文，只有主密码能打开。" +
                    "导出前会先用它核对一次恢复密钥，防止把打错的密码封进备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 与「导出（加密）」同为**执行类**动作，样式必须一致（2026-10-03 用户指出）：
            // 按钮样式按"动作类型"分——执行=实心、挑选/导航=描边或文字；
            // "不推荐明文"这件事由下面那段提示文字说，不靠按钮轻重暗示。
            Button(onClick = viewModel::requestPlaintextExport, enabled = !state.busy) {
                Text("导出明文 JSON…")
            }
            Text(
                text = "不加密，任何工具都能直接读——注意别把它留在会被人碰到的地方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("从导出文件恢复", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "选一个之前导出的文件（加密的或明文都行），把里面的记录合并进来。" +
                    "库里已有的记录不动；完全相同的（内容 + 创建时间）会跳过——" +
                    "同一份文件重复导入也不会出事。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 挑文件 = 非积极动作：灰实心、有框（2026-10-03 用户二轮的两样式
            // 规则——同区域同逻辑只留"积极实心主色 / 非积极实心灰"两种；
            // 描边这一档不再用于动作按钮）。
            FilledTonalButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !state.busy,
            ) {
                Text(if (state.importText == null) "选择导出文件…" else "重新选择…")
            }
            ImportPickedLine(state)

            if (state.importNeedsPassword) {
                OutlinedTextField(
                    value = state.importPassword,
                    onValueChange = viewModel::onImportPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.busy,
                    label = { Text("这份文件的密码（改过密码的话，是当时的旧密码）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }

            if (state.importText != null) {
                Button(onClick = viewModel::startImport, enabled = !state.busy) {
                    Text(if (state.busy) "导入中…（解密要几秒）" else "导入")
                }
            }

            StatusLine(state.error, state.notice)

        }
    }
}

/** 已选文件的状态一行：明文报条数，加密报"等密码"。 */
@Composable
private fun ImportPickedLine(state: ExportUiState) {
    val text = when {
        state.importFormat == ImportFormat.JSON && state.importItemCount != null ->
            "已选择：明文文件，共 ${state.importItemCount} 条记录"

        state.importFormat == ImportFormat.ENCRYPTED ->
            "已选择：加密文件，输入密码后开始导入"

        else -> null
    }
    text?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// =====================================================================
// 可调布局参数（导出页）
// =====================================================================

/** 页面四周留白。 */
private val PAGE_PADDING = 16.dp

/** 各区块之间纵向间距。 */
private val SECTION_GAP = 12.dp
