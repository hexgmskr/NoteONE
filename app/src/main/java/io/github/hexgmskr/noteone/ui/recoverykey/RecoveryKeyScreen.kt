package io.github.hexgmskr.noteone.ui.recoverykey

import android.app.Activity
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
import io.github.hexgmskr.noteone.ui.common.StatusLine
import io.github.hexgmskr.noteone.ui.common.dismissKeyboardOnTap
import io.github.hexgmskr.noteone.ui.lock.promptBiometric

/**
 * 恢复密钥（副本B）页：设置主密码、导出备份、更换密码、从备份恢复。
 *
 * 页面形态由 [RecoveryKeyUiState.isSetUp] 和 mode 决定，四个形态共用一套骨架：
 * 说明文字 → 输入框（按需）→ 主按钮 → 次按钮 → 错误/提示。
 *
 * **界面文案的诚实原则**：这份文件找回的是"钥匙"（DEK），记录数据本身
 * 不包含在内——不写"有了它就能恢复数据"这种话（数据备份是另一件事）。
 *
 * 导出的方向：设置成功后必须让用户看到"导出一份"的引导，且导出走系统文件
 * 选择器（用户自己挑存哪）。此刻是唯一的备份窗口——不导出就等于没有副本B。
 */
@Composable
fun RecoveryKeyScreen(
    viewModel: RecoveryKeyViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = LocalContext.current as? Activity   // 弹指纹要真 Activity

    // 导出：系统文件选择器（用户挑位置）。回调里再取一次密文文本。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult   // 用户取消
        val text = viewModel.exportText()
        val ok = text != null && runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("openOutputStream 返回 null")
        }.isSuccess
        viewModel.onExportFinished(ok)
    }

    // 导入：选备份文件，读出文本交给 ViewModel
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult   // 用户取消
        val content = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (content == null) viewModel.onBackupFileUnreadable()
        else viewModel.onBackupFilePicked(content)
    }

    // 高安全模式的「包 → 验」两段指纹：主密码核对通过后（switchReadyToProve）
    // 由这里接续。两段都必须真实指纹（auth-per-use），所以拆成两次弹窗；
    // 任一段取消 = 整个切换放弃、设置保持原样（清扫在 cancelModeSwitch）。
    LaunchedEffect(state.switchReadyToProve) {
        if (!state.switchReadyToProve) return@LaunchedEffect
        viewModel.onSwitchProveConsumed()

        val act = activity ?: return@LaunchedEffect
        val turningOn = !state.highSecurity
        val title = if (turningOn) "开启高安全模式" else "关闭高安全模式"

        val wrapCipher = viewModel.beginModeSwitch() ?: return@LaunchedEffect
        promptBiometric(
            act,
            wrapCipher,
            title = title,
            subtitle = "验证后打包新钥匙",
            negativeText = "取消",
        ) { authed ->
            if (authed == null) {
                viewModel.modeSwitchCancelled()
                return@promptBiometric
            }
            if (!viewModel.finishModeSwitchWrap(authed)) return@promptBiometric

            val verifyCipher = viewModel.beginModeSwitchVerify() ?: return@promptBiometric
            promptBiometric(
                act,
                verifyCipher,
                title = title,
                subtitle = "再验证一次，用于校验新钥匙",
                negativeText = "取消",
            ) { authed2 ->
                if (authed2 == null) viewModel.modeSwitchCancelled()
                else viewModel.finishModeSwitchVerify(authed2)
            }
        }
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
            Text("恢复密钥", style = MaterialTheme.typography.titleLarge)

            when (state.mode) {
                RecoveryKeyMode.STATUS -> StatusSection(
                    state = state,
                    viewModel = viewModel,
                    onExport = {
                        exportLauncher.launch(EXPORT_FILE_NAME)
                    },
                    onChangePassword = { viewModel.switchTo(RecoveryKeyMode.CHANGE_PASSWORD) },
                    onEnableBiometric = {
                        val cipher = viewModel.beginBiometricProvision()
                        val act = activity
                        if (cipher != null && act != null) {
                            promptBiometric(act, cipher) { authed ->
                                if (authed != null) viewModel.finishBiometricProvision(authed)
                                else viewModel.biometricProvisionCancelled()
                            }
                        }
                    },
                    onDisableBiometric = viewModel::disableBiometric,
                )

                RecoveryKeyMode.SETUP -> SetupSection(
                    state = state,
                    viewModel = viewModel,
                    onRestoreInstead = { viewModel.switchTo(RecoveryKeyMode.RESTORE) },
                )

                RecoveryKeyMode.CHANGE_PASSWORD -> ChangePasswordSection(state, viewModel)

                RecoveryKeyMode.RESTORE -> RestoreSection(
                    state = state,
                    viewModel = viewModel,
                    onPickFile = { importLauncher.launch(arrayOf("*/*")) },
                )
            }

            StatusLine(state.error, state.notice)

        }
    }
}

// ---- 各形态 ----

@Composable
private fun StatusSection(
    state: RecoveryKeyUiState,
    viewModel: RecoveryKeyViewModel,
    onExport: () -> Unit,
    onChangePassword: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
) {
    Text(
        text = "已设置。这份备份文件 + 你的主密码，是将来找回密钥的唯一途径。" +
            "它找回的是「钥匙」；记录数据本身不在文件里。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onExport, enabled = !state.busy) {
        Text("导出一份备份文件…")
    }
    FilledTonalButton(onClick = onChangePassword, enabled = !state.busy) {
        Text("更换主密码")
    }

    HorizontalDivider()
    Text("指纹解锁", style = MaterialTheme.typography.titleSmall)
    if (state.biometricEnabled && state.biometricKeyInvalid) {
        // 闭环规则②的引导面：高安全模式下录入新指纹 → 硬件钥匙被系统作废。
        // 不是错误，是预期行为；说清楚发生了什么、怎么走回去。
        Text(
            text = "已失效：硬件钥匙被系统作废了（通常是录入了新指纹）。" +
                "主密码不受影响；点下面的按钮重新启用即可恢复指纹进门。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        FilledTonalButton(onClick = onEnableBiometric, enabled = !state.busy) {
            Text("重新启用指纹解锁")
        }
    } else if (state.biometricEnabled) {
        Text(
            text = "已启用：冷启动刷指纹进门，主密码随时可以兜底。" +
                "关闭它不影响主密码路径。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onDisableBiometric, enabled = !state.busy) {
            Text("关闭指纹解锁")
        }
    } else {
        Text(
            // 只写"指纹"不写"人脸"：小米的人脸是弱生物识别，解不了 Keystore 的
            // auth-per-use 钥匙（详见 BiometricPromptRunner 的注释）
            text = "启用后，冷启动用指纹进门，不必输主密码。" +
                "它靠这台手机的硬件钥匙，换机或钥匙失效时仍走主密码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 指纹解锁是个开关：开、关是同一个角色，样式必须一致
        // （2026-10-03 统一：此前"启用"是实心按钮、"关闭"是文字按钮）
        FilledTonalButton(onClick = onEnableBiometric, enabled = !state.busy) {
            Text("启用指纹解锁")
        }
    }

    HighSecuritySection(state = state, viewModel = viewModel)
}

/**
 * 高安全模式区（CLAUDE.md「加密」一节四条闭环的界面侧）。
 *
 * 界面只负责：解释、收主密码、按顺序跑两段指纹、展示结果；
 * "验证退路 → 打包 → 校验 → 原子替换"的语义全在
 * [io.github.hexgmskr.noteone.ui.lock.BiometricUnlockActions] 的实现里。
 */
@Composable
private fun HighSecuritySection(
    state: RecoveryKeyUiState,
    viewModel: RecoveryKeyViewModel,
) {
    HorizontalDivider()
    Text("高安全模式", style = MaterialTheme.typography.titleSmall)

    if (!state.biometricEnabled) {
        Text(
            text = "先启用指纹解锁，才能使用高安全模式（它作用于指纹用的那把硬件钥匙）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = if (state.highSecurity) {
            "已开启：录入新指纹会使指纹解锁失效，需要主密码重新启用——" +
                "防止别人拿到已解锁的手机后偷录指纹进门。"
        } else {
            "开启后：录入新指纹会使指纹解锁失效。经常换/加指纹的人不必开；" +
                "在意「手机被别人拿去偷录指纹」的风险时再开。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.highSecurity) {
        // 闭环规则④：显著警告——副本B 是从"钥匙作废"走回来的唯一退路
        Text(
            text = "⚠ 主密码 + 副本B 是唯一退路：请确认备份文件离线在手、密码你记得住。" +
                "两者都丢了，指纹一变数据就永远打不开。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (state.awaitingSwitchPassword) {
        Text(
            text = "切换前先当场验证副本B 能解开（退路验证过，才允许动指纹钥匙）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PasswordField(
            value = state.switchPassword,
            onValueChange = viewModel::onSwitchPasswordChange,
            label = "主密码",
            enabled = !state.busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 顺序与全 App 一致：取消在左（灰实心）、主操作在右（主色实心）——
            // 2026-10-04 审计发现此处此前反着排（主操作在左）。
            FilledTonalButton(onClick = viewModel::cancelHighSecuritySwitch, enabled = !state.busy) {
                Text("取消")
            }
            Button(onClick = viewModel::submitHighSecuritySwitch, enabled = !state.busy) {
                Text(if (state.busy) "验证中…（要几秒）" else "验证并继续")
            }
        }
    } else {
        FilledTonalButton(onClick = viewModel::beginHighSecuritySwitch, enabled = !state.busy) {
            Text(if (state.highSecurity) "关闭高安全模式" else "开启高安全模式")
        }
    }
}

@Composable
private fun SetupSection(
    state: RecoveryKeyUiState,
    viewModel: RecoveryKeyViewModel,
    onRestoreInstead: () -> Unit,
) {
    Text(
        text = "给这份密钥设一个主密码。密码 + 导出的备份文件是唯一的找回途径——" +
            "忘了密码或丢了文件，谁也救不回来。建议用一句只有你知道的长句子。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PasswordField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = "主密码（至少 12 位）",
        enabled = !state.busy,
    )
    PasswordField(
        value = state.confirm,
        onValueChange = viewModel::onConfirmChange,
        label = "再输一遍",
        enabled = !state.busy,
    )
    Button(onClick = viewModel::submit, enabled = !state.busy) {
        Text(if (state.busy) "处理中…（派生密钥要几秒）" else "创建")
    }
    HorizontalDivider()
    FilledTonalButton(onClick = onRestoreInstead, enabled = !state.busy) {
        Text("已有恢复密钥文件？从备份恢复")
    }
}

@Composable
private fun ChangePasswordSection(state: RecoveryKeyUiState, viewModel: RecoveryKeyViewModel) {
    PasswordField(
        value = state.currentPassword,
        onValueChange = viewModel::onCurrentPasswordChange,
        label = "当前主密码",
        enabled = !state.busy,
    )
    PasswordField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = "新主密码（至少 12 位）",
        enabled = !state.busy,
    )
    PasswordField(
        value = state.confirm,
        onValueChange = viewModel::onConfirmChange,
        label = "再输一遍新密码",
        enabled = !state.busy,
    )
    Button(onClick = viewModel::submit, enabled = !state.busy) {
        Text(if (state.busy) "处理中…" else "更换")
    }
    Text(
        text = "更换后旧密码立即失效；导出的旧备份文件仍对应旧密码。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 回到状态页的路。没有它就只能靠底部"返回"退出整个页面——那不合理
    FilledTonalButton(
        onClick = { viewModel.switchTo(RecoveryKeyMode.STATUS) },
        enabled = !state.busy,
    ) {
        Text("取消")
    }
}

@Composable
private fun RestoreSection(
    state: RecoveryKeyUiState,
    viewModel: RecoveryKeyViewModel,
    onPickFile: () -> Unit,
) {
    Text(
        text = "选一份此前导出的恢复密钥文件，输入它当时用的主密码。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 挑文件 = 非积极动作（同「选择导出文件」）：灰实心、有框。
    // 此前用描边，全 app 收成两样式后描边这一档不再用于动作按钮（2026-10-03 二轮）。
    FilledTonalButton(onClick = onPickFile, enabled = !state.busy) {
        Text(if (state.importedText == null) "选择备份文件…" else "重新选择…")
    }
    state.importedText?.let { text ->
        Text(
            text = "已选择备份文件（${text.length} 字符）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PasswordField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = "这份备份的主密码",
        enabled = !state.busy,
    )
    Button(onClick = viewModel::submit, enabled = !state.busy) {
        Text(if (state.busy) "处理中…" else "恢复")
    }
    // 回到"创建新的"那条路（恢复是从那里进来的）
    FilledTonalButton(
        onClick = { viewModel.switchTo(RecoveryKeyMode.SETUP) },
        enabled = !state.busy,
    ) {
        Text("取消")
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

// =====================================================================
// 可调布局参数（恢复密钥页）
//
// 想手调观感改这里。chip 等共用样式不在这里，见 ui/common/TagChips.kt。
// =====================================================================

/** 页面四周留白。 */
private val PAGE_PADDING = 16.dp

/** 各区块之间纵向间距。 */
private val SECTION_GAP = 12.dp

/** 导出时的默认文件名（用户可在系统选择器里改）。 */
private const val EXPORT_FILE_NAME = "noteone-recovery-key.txt"
