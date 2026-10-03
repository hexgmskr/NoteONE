package io.github.hexgmskr.noteone.ui.lock

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.ui.common.StatusLine
import io.github.hexgmskr.noteone.ui.recoverykey.RecoveryKeyScreen
import io.github.hexgmskr.noteone.ui.recoverykey.RecoveryKeyViewModel
import javax.crypto.Cipher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 锁门（宽限期耗尽或冷启动时挡在最前面）。
 *
 * 三种形态：
 *  - **还没设置恢复密钥** → 引导去创建（创建页就是列表页那个同一个页面）
 *  - **已启用指纹** → 进门自动弹一次指纹；旁边始终留着"用主密码"的退路
 *  - **没启用指纹** → 主密码输入（PBKDF2 秒级，期间显示"正在验证…"）
 *
 * 指纹验证本身用**平台自带的 BiometricPrompt**（minSdk 36 完全够），不引
 * androidx.biometric——CLAUDE.md 的依赖纪律；真要换实现，换的就是这一处调用。
 *
 * 指纹失败/取消**绝不封死**：一律亮出主密码输入。用户忘了密码还有副本B 文件，
 * 但指纹只是"方便"，不该成为唯一的门。
 */
@Composable
fun UnlockGate(
    manager: RecoveryKeyManager,
    attemptUnlock: (CharArray) -> Unit,
    biometric: BiometricUnlockActions,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember { UnlockGateViewModel(manager, attemptUnlock, biometric) }
    val state by viewModel.uiState.collectAsState()
    var showRecoveryKey by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

    // 进门的自动弹窗：只在"已启用指纹、没弹过、也没在看密码框"时弹一次。
    // 弹过一次就置位（beginBiometric 里置），取消后不会循环弹——重试靠按钮。
    LaunchedEffect(state.biometricEnabled, state.isSetUp, activity) {
        if (activity != null && viewModel.shouldAutoPromptBiometric()) {
            viewModel.beginBiometric()?.let { cipher ->
                promptBiometric(activity, cipher) { authed ->
                    if (authed != null) viewModel.finishBiometric(authed)
                    else viewModel.biometricDismissed()
                }
            }
        }
    }

    if (showRecoveryKey) {
        val recoveryViewModel = remember { RecoveryKeyViewModel(manager, biometric) }
        RecoveryKeyScreen(
            viewModel = recoveryViewModel,
            onBack = {
                showRecoveryKey = false
                viewModel.refreshSetupState()   // 可能刚创建了恢复密钥/改了指纹设置
            },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GATE_PADDING),
        verticalArrangement = Arrangement.spacedBy(GATE_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NoteONE", style = MaterialTheme.typography.titleLarge)

        when {
            !state.isSetUp -> NotSetUpSection(onGoCreate = { showRecoveryKey = true })

            else -> {

                if (state.biometricEnabled) {
                    Button(
                        onClick = {
                            activity?.let { act ->
                                viewModel.beginBiometric()?.let { cipher ->
                                    promptBiometric(act, cipher) { authed ->
                                        if (authed != null) viewModel.finishBiometric(authed)
                                        else viewModel.biometricDismissed()
                                    }
                                }
                            }
                        },
                        enabled = !state.busy,
                    ) {
                        // 只写"指纹"不写"人脸"：小米的人脸是弱生物识别，
                        // 解不了 Keystore 的 auth-per-use 钥匙（详见 BiometricPromptRunner）
                        Text("用指纹解锁")
                    }
                }

                // 指纹不可用、被取消、或用户主动选择时亮出密码路。
                // 「用主密码」是主操作旁边的**非积极**按钮：灰实心、有框，不抢主色
                // （2026-10-03 用户二轮的两样式规则，见 EditScreen 底部动作栏注释）。
                if (state.showPassword || !state.biometricEnabled) {
                    PasswordUnlockSection(state, viewModel)
                } else {
                    FilledTonalButton(onClick = viewModel::usePasswordInstead, enabled = !state.busy) {
                        Text("用主密码")
                    }
                }
            }
        }

        StatusLine(state.error, state.notice, noticeColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NotSetUpSection(onGoCreate: () -> Unit) {
    Text(
        text = "还没有恢复密钥。它是打开这个数据库的钥匙——没有它，库打不开，" +
            "也没人能帮你找回来。先去创建一份。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onGoCreate) {
        Text("去创建恢复密钥")
    }
}

@Composable
private fun PasswordUnlockSection(state: UnlockGateViewModel.UiState, viewModel: UnlockGateViewModel) {
    Text(
        text = "输入主密码解锁。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("主密码") },
        enabled = !state.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Button(onClick = viewModel::submit, enabled = !state.busy) {
        Text(if (state.busy) "正在验证…（要几秒）" else "解锁")
    }
}

/** [UnlockGate] 的逻辑。 */
class UnlockGateViewModel(
    private val manager: RecoveryKeyManager,
    private val attemptUnlock: (CharArray) -> Unit,
    private val biometric: BiometricUnlockActions,
    /** 验证（PBKDF2）跑在这里；参数化是为了单测注入。 */
    private val cryptoContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {

    data class UiState(
        /** 本机是否已有副本B密文。默认 true 避免"未设置"界面闪现一下。 */
        val isSetUp: Boolean = true,
        val password: String = "",
        val error: String? = null,
        /** 偏提示性的信息（如"指纹取消了，用主密码吧"），与 error 分开呈现。 */
        val notice: String? = null,
        val busy: Boolean = false,
        /** 本机是否已启用指纹解锁（有副本A）。 */
        val biometricEnabled: Boolean = false,
        /** 本次进门是否已弹过指纹（弹过就不再自动弹，防循环）。 */
        val biometricTried: Boolean = false,
        /** 是否亮出主密码输入（用户选择 / 指纹不可用 / 没启用指纹时都为真）。 */
        val showPassword: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshSetupState()
    }

    /** 重新判断"有没有恢复密钥 / 有没有启用指纹"。从创建页回来时调用。 */
    fun refreshSetupState() {
        viewModelScope.launch {
            val setUp = withContext(cryptoContext) { manager.isSetUp() }
            val biometricEnabled = withContext(cryptoContext) { biometric.isEnabled() }
            _uiState.update { it.copy(isSetUp = setUp, biometricEnabled = biometricEnabled) }
        }
    }

    // ---- 指纹路径 ----

    /** 本次进门是否该自动弹一次指纹。 */
    fun shouldAutoPromptBiometric(): Boolean {
        val s = _uiState.value
        return s.isSetUp && s.biometricEnabled && !s.biometricTried && !s.showPassword
    }

    /**
     * 取待授权的解密 Cipher，界面拿去弹指纹。
     * 取不到（没启用/信封坏/钥匙坏）就提示并亮出密码退路，返回 null。
     */
    fun beginBiometric(): Cipher? {
        _uiState.update { it.copy(biometricTried = true, error = null, notice = null) }
        return try {
            biometric.beginUnlock()
        } catch (e: Throwable) {
            // 分两种情形给话术：高安全模式下"钥匙被系统作废"是**预期行为**，
            // 要明确告诉用户发生了什么、以及怎么走回去（CLAUDE.md 闭环规则②：
            // 作废时引导「主密码进门 → 重建副本A」，不允许死胡同）。
            val notice = if (biometric.biometricKeyInvalid()) {
                "指纹钥匙已被系统作废（通常是录入了新指纹）——这是高安全模式的预期行为。" +
                    "用主密码进门后，到「恢复密钥」页重新启用指纹解锁即可。"
            } else {
                "指纹解锁暂时用不了，请用主密码"
            }
            _uiState.update { it.copy(notice = notice, showPassword = true) }
            null
        }
    }

    /** 指纹验证通过：解 DEK → 会话 → 解锁。失败同样不封死，退主密码。 */
    fun finishBiometric(cipher: Cipher) {
        try {
            biometric.finishUnlock(cipher)
        } catch (e: CryptoException) {
            // 钥匙解出来了、但库打不开（损坏/不是本机的库）——把原话放出来，
            // 别被下面的通用话术吞掉（2026-10-04 审计 A4）。
            _uiState.update { it.copy(notice = e.message, showPassword = true) }
        } catch (e: Throwable) {
            _uiState.update {
                it.copy(notice = "指纹解不开（可能钥匙已变），请用主密码", showPassword = true)
            }
        }
    }

    /** 用户取消 / 指纹整体失败。 */
    fun biometricDismissed() {
        _uiState.update { it.copy(notice = "已取消，可以用主密码解锁", showPassword = true) }
    }

    fun usePasswordInstead() {
        _uiState.update { it.copy(showPassword = true, notice = null) }
    }

    // ---- 密码路径（原样保留）----

    fun onPasswordChange(value: String) = _uiState.update {
        it.copy(password = value, error = null)
    }

    fun submit() {
        val state = _uiState.value
        if (state.busy) return
        if (state.password.isEmpty()) {
            _uiState.update { it.copy(error = "请输入主密码") }
            return
        }

        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val failure = withContext(cryptoContext) {
                try {
                    attemptUnlock(state.password.toCharArray())
                    null
                } catch (e: Throwable) {
                    e
                }
            }
            // 成功时状态机已推进（Locked → Unlocked），整个门会被界面换掉；
            // 这里只处理失败与收尾
            _uiState.update {
                it.copy(
                    busy = false,
                    password = if (failure == null) "" else it.password,
                    error = failure?.let { e -> messageOf(e) },
                )
            }
        }
    }

    private fun messageOf(e: Throwable): String = when (e) {
        is CryptoException -> e.message ?: "解锁失败"
        else -> "解锁失败：${e.message ?: e.javaClass.simpleName}"
    }
}

// ---- 可调布局参数（解锁门）----

/** 门页四周留白。 */
private val GATE_PADDING = 24.dp

/** 门页元素间距。 */
private val GATE_GAP = 12.dp
