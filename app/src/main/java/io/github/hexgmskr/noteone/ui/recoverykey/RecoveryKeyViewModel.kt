package io.github.hexgmskr.noteone.ui.recoverykey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.ui.lock.BiometricUnlockActions
import javax.crypto.Cipher
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 恢复密钥页当前在做哪件事。 */
enum class RecoveryKeyMode {
    /** 已有密文：状态页（导出 / 换密码）。 */
    STATUS,

    /** 还没有密文：创建新的。 */
    SETUP,

    /** 还没有密文：从备份文件恢复（换机/重装场景）。 */
    RESTORE,

    /** 已有密文：更换主密码。 */
    CHANGE_PASSWORD,
}

/**
 * 恢复密钥页的状态。
 *
 * 页面形态由 [isSetUp] 和 [mode] 决定，输入类字段就这三个（按模式用其中一部分）：
 * 新密码 / 确认 / 当前密码。恢复模式额外带一份选中的备份文件内容。
 */
data class RecoveryKeyUiState(
    val isSetUp: Boolean = false,
    val mode: RecoveryKeyMode = RecoveryKeyMode.SETUP,
    val password: String = "",
    val confirm: String = "",
    /** 仅「更换主密码」模式使用。 */
    val currentPassword: String = "",
    /** 已选中待恢复的备份文件内容（由页面读文件后交进来）。 */
    val importedText: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** 派生密钥是秒级的：处理中必须挡住重复提交并显示"处理中"。 */
    val busy: Boolean = false,
    /** 本机是否已启用指纹解锁（副本A 存在）。 */
    val biometricEnabled: Boolean = false,
    /**
     * 副本A 的**硬件钥匙已被系统作废**（信封在、钥匙没了）——
     * 高安全模式下录入新指纹后的预期结果。界面据此把"已启用"改口成
     * "已失效，可重新启用"（闭环规则②的引导面）。
     */
    val biometricKeyInvalid: Boolean = false,
    /** 当前是否处于高安全模式。 */
    val highSecurity: Boolean = false,
    /** 切换高安全模式：等待用户输入主密码（界面据此展开密码框）。 */
    val awaitingSwitchPassword: Boolean = false,
    val switchPassword: String = "",
    /** 主密码核对通过，界面该发起「包 → 验」两段指纹验证。消费后复位。 */
    val switchReadyToProve: Boolean = false,
)

/**
 * 恢复密钥页的逻辑。
 *
 * 所有密码学操作都可能在真机上耗秒级（PBKDF2 60 万次），所以一律丢到
 * [cryptoContext] 上跑，绝不允许占主线程——否则界面冻住，用户以为崩了。
 *
 * 校验只做"能立刻指出的错"（长度、两次不一致、没选文件）；
 * 密码对不对、文件坏没坏，交给管家层的异常，信息原样展示给用户。
 */
class RecoveryKeyViewModel(
    private val manager: RecoveryKeyManager,
    /** 指纹解锁的启用/关闭（副本A 的生死）。界面只认这个接口，弹窗由界面侧跑。 */
    private val biometric: BiometricUnlockActions,
    /** 跑密码学用的调度器。参数化是为了单测能注入测试调度器。 */
    private val cryptoContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryKeyUiState())
    val uiState: StateFlow<RecoveryKeyUiState> = _uiState.asStateFlow()

    init {
        val setUp = manager.isSetUp()
        _uiState.update {
            it.copy(
                isSetUp = setUp,
                mode = if (setUp) RecoveryKeyMode.STATUS else RecoveryKeyMode.SETUP,
                biometricEnabled = biometric.isEnabled(),
                biometricKeyInvalid = biometric.biometricKeyInvalid(),
                highSecurity = biometric.isHighSecurity(),
            )
        }
    }

    // ---- 指纹解锁（副本A）----

    /**
     * 取待授权的加密 Cipher，界面拿去弹指纹。取不到则提示并返回 null。
     *
     * 前置：本机已解锁（会话里有 DEK）——本页只能从已解锁的界面进来，
     * 但真到 finish 阶段还会再挡一道（见 AppContainer）。
     */
    fun beginBiometricProvision(): Cipher? = try {
        biometric.beginProvision()
    } catch (e: Throwable) {
        _uiState.update { it.copy(error = "暂时无法启用指纹解锁：${e.message ?: "未知原因"}") }
        null
    }

    /** 指纹验证通过：把 DEK 包进副本A 并落盘。 */
    fun finishBiometricProvision(cipher: Cipher) {
        try {
            biometric.finishProvision(cipher)
            _uiState.update {
                it.copy(
                    biometricEnabled = true,
                    // 刚重建过，作废标记必然过时
                    biometricKeyInvalid = false,
                    error = null,
                    notice = "已启用指纹解锁。以后冷启动刷指纹进门。",
                )
            }
        } catch (e: Throwable) {
            _uiState.update { it.copy(error = "启用失败：${e.message ?: "未知原因"}") }
        }
    }

    /** 用户取消了指纹弹窗。 */
    fun biometricProvisionCancelled() {
        _uiState.update { it.copy(notice = "已取消，指纹解锁未启用") }
    }

    /** 关闭指纹解锁：吊销副本A。主密码路径不受影响。高安全模式一并退出（见下）。 */
    fun disableBiometric() {
        try {
            // 关指纹会连带复位高安全模式偏好（它只作用于指纹钥匙，见 AppContainer.disable）
            // ——先问清楚当时开没开，好把这件事如实写进提示，不让它悄悄发生。
            val wasHighSecurity = biometric.isHighSecurity()
            biometric.disable()
            _uiState.update {
                it.copy(
                    biometricEnabled = false,
                    biometricKeyInvalid = false,
                    highSecurity = false,
                    error = null,
                    notice = if (wasHighSecurity) {
                        "已关闭指纹解锁（高安全模式一并退出——它只作用于指纹钥匙）。" +
                            "以后用主密码进门；想再开高安全模式，重新启用指纹时走一遍开启流程。"
                    } else {
                        "已关闭指纹解锁。以后用主密码进门。"
                    },
                )
            }
        } catch (e: Throwable) {
            _uiState.update { it.copy(error = "关闭失败：${e.message ?: "未知原因"}") }
        }
    }

    // ---- 高安全模式（CLAUDE.md 四条闭环的界面侧）----

    /** 点「开启/关闭高安全模式」：先进入"输主密码"环节（闭环规则①）。 */
    fun beginHighSecuritySwitch() {
        _uiState.update {
            it.copy(awaitingSwitchPassword = true, switchPassword = "", error = null, notice = null)
        }
    }

    fun onSwitchPasswordChange(value: String) =
        _uiState.update { it.copy(switchPassword = value, error = null) }

    /** 放弃切换（密码环节的"取消"）。 */
    fun cancelHighSecuritySwitch() {
        _uiState.update {
            it.copy(awaitingSwitchPassword = false, switchPassword = "", switchReadyToProve = false)
        }
    }

    /**
     * 核对主密码 + 副本B（闭环规则①：退路必须先验证过才能动副本A）。
     * PBKDF2 秒级 → 走 runCrypto；通过后置 [RecoveryKeyUiState.switchReadyToProve]，
     * 由界面接续两段指纹验证。
     */
    fun submitHighSecuritySwitch() {
        val state = _uiState.value
        if (state.busy) return
        if (state.switchPassword.isEmpty()) return showError("请输入主密码（用于当场验证副本B 可解）")

        runCrypto {
            biometric.verifyRecoveryKeyForSwitch(state.switchPassword.toCharArray())
            _uiState.update {
                it.copy(
                    awaitingSwitchPassword = false,
                    switchPassword = "",
                    switchReadyToProve = true,
                    error = null,
                )
            }
        }
    }

    /** 界面消费"可以开始验证了"信号。 */
    fun onSwitchProveConsumed() = _uiState.update { it.copy(switchReadyToProve = false) }

    /** 第 1 段指纹前取待授权加密 Cipher。失败即终止切换。 */
    fun beginModeSwitch(): Cipher? = try {
        biometric.beginModeSwitch()
    } catch (e: Throwable) {
        failSwitch("无法开始切换：${e.message ?: "未知原因"}")
        null
    }

    /** 第 1 段指纹通过：用新钥匙包住 DEK（信封暂存内存）。 */
    fun finishModeSwitchWrap(cipher: Cipher): Boolean = try {
        biometric.finishModeSwitchWrap(cipher)
        true
    } catch (e: Throwable) {
        failSwitch("打包失败：${e.message ?: "未知原因"}")
        false
    }

    /** 第 2 段指纹前取待授权解密 Cipher（校验新信封用）。 */
    fun beginModeSwitchVerify(): Cipher? = try {
        biometric.beginModeSwitchVerify()
    } catch (e: Throwable) {
        failSwitch("无法校验新信封：${e.message ?: "未知原因"}")
        null
    }

    /** 第 2 段指纹通过：校验 → 提交（原子替换 + 换模式）。 */
    fun finishModeSwitchVerify(cipher: Cipher) {
        try {
            biometric.finishModeSwitchVerify(cipher)
            val nowHigh = biometric.isHighSecurity()
            _uiState.update {
                it.copy(
                    highSecurity = nowHigh,
                    error = null,
                    notice = if (nowHigh) {
                        "高安全模式已开启：以后录入新指纹会作废指纹解锁（只能用主密码重新启用）。" +
                            "主密码 + 副本B 是唯一退路，请确认备份在手边。"
                    } else {
                        "已关闭高安全模式：录入新指纹不再影响指纹解锁。"
                    },
                )
            }
        } catch (e: Throwable) {
            failSwitch("切换失败：${e.message ?: "未知原因"}")
        }
    }

    /** 任一段指纹被取消：清扫暂存状态，设置保持原样。 */
    fun modeSwitchCancelled() {
        runCatching { biometric.cancelModeSwitch() }
        _uiState.update { it.copy(notice = "已取消，设置未改变", error = null) }
    }

    /** 切换过程中的失败：先清扫（删掉多建的钥匙），再报错。 */
    private fun failSwitch(message: String) {
        runCatching { biometric.cancelModeSwitch() }
        _uiState.update { it.copy(error = message, notice = null) }
    }

    // ---- 表单输入 ----

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun onConfirmChange(value: String) = _uiState.update { it.copy(confirm = value, error = null) }

    fun onCurrentPasswordChange(value: String) =
        _uiState.update { it.copy(currentPassword = value, error = null) }

    /** 切换模式时清空输入——密码尤其不该跨模式残留。 */
    fun switchTo(mode: RecoveryKeyMode) = _uiState.update {
        it.copy(
            mode = mode,
            password = "",
            confirm = "",
            currentPassword = "",
            importedText = null,
            error = null,
            notice = null,
        )
    }

    /** 页面从系统文件选择器读回备份内容后交进来。 */
    fun onBackupFilePicked(text: String) = _uiState.update {
        it.copy(importedText = text, error = null)
    }

    /** 备份文件读不出来（选了不可读的东西）。 */
    fun onBackupFileUnreadable() = showError("这个文件读不出来，换一个试试")

    /** 导出结果（页面写文件成功与否）。 */
    fun onExportFinished(success: Boolean) = _uiState.update {
        if (success) {
            it.copy(error = null, notice = "已导出。请把它离线保管好——这是找回密钥的唯一途径。")
        } else {
            it.copy(error = "导出没有完成")
        }
    }

    /** 给页面导出用的密文文本。 */
    fun exportText(): String? = manager.exportText()

    // ---- 提交 ----

    fun submit() {
        val state = _uiState.value
        if (state.busy) return   // 防重复提交：派生密钥期间按钮该是禁用的，这里是兜底

        when (state.mode) {
            RecoveryKeyMode.SETUP -> submitSetup(state)
            RecoveryKeyMode.CHANGE_PASSWORD -> submitChangePassword(state)
            RecoveryKeyMode.RESTORE -> submitRestore(state)
            RecoveryKeyMode.STATUS -> Unit
        }
    }

    private fun submitSetup(state: RecoveryKeyUiState) {
        validateNewPassword(state)?.let { return showError(it) }

        runCrypto {
            manager.setup(state.password.toCharArray())
            _uiState.update {
                it.copy(
                    isSetUp = true,
                    mode = RecoveryKeyMode.STATUS,
                    password = "",
                    confirm = "",
                    notice = "已设置。请立刻导出一份备份并离线保管——这是找回密钥的唯一途径。",
                )
            }
        }
    }

    private fun submitChangePassword(state: RecoveryKeyUiState) {
        if (state.currentPassword.isEmpty()) return showError("请输入当前主密码")
        validateNewPassword(state)?.let { return showError(it) }

        runCrypto {
            manager.changePassword(
                state.currentPassword.toCharArray(),
                state.password.toCharArray(),
            )
            _uiState.update {
                it.copy(
                    mode = RecoveryKeyMode.STATUS,
                    password = "",
                    confirm = "",
                    currentPassword = "",
                    notice = "主密码已更换。旧密码从此失效。",
                )
            }
        }
    }

    private fun submitRestore(state: RecoveryKeyUiState) {
        val text = state.importedText ?: return showError("先选择恢复密钥备份文件")
        // 注意：恢复不校验 12 位门槛——备份可能是旧政策下创建的短密码，
        // 门槛只约束"新设的密码"，不约束"已有的密码"。
        if (state.password.isEmpty()) return showError("请输入这份备份的主密码")

        runCrypto {
            manager.importFrom(text, state.password.toCharArray())
            _uiState.update {
                it.copy(
                    isSetUp = true,
                    mode = RecoveryKeyMode.STATUS,
                    password = "",
                    importedText = null,
                    notice = "已从备份恢复密钥。",
                )
            }
        }
    }

    // ---- 内部 ----

    /** 新密码的即时校验。通过返回 null。 */
    private fun validateNewPassword(state: RecoveryKeyUiState): String? {
        if (state.password.length < RecoveryKeyManager.MIN_PASSWORD_LENGTH) {
            return "主密码至少 ${RecoveryKeyManager.MIN_PASSWORD_LENGTH} 位（建议用一句只有你知道的话）"
        }
        if (state.password != state.confirm) return "两次输入不一致"
        return null
    }

    private fun showError(message: String) = _uiState.update { it.copy(error = message, busy = false) }

    /**
     * 统一跑一次密码学操作：置 busy → 后台执行 → 收尾。
     *
     * [block] 成功时更新自己的业务状态；**收尾（清 busy、失败转提示）无论成败都在这里做**——
     * busy 要是只在失败路径上复位，成功后界面会永远停在"处理中…"（有专门用例守着这一点）。
     */
    private fun runCrypto(block: () -> Unit) {
        _uiState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val failure = withContext(cryptoContext) {
                try {
                    block()
                    null
                } catch (e: CancellationException) {
                    throw e                      // 取消不是失败，别吞
                } catch (e: Throwable) {
                    e
                }
            }
            _uiState.update {
                it.copy(busy = false, error = failure?.let { e -> messageOf(e) })
            }
        }
    }

    private fun messageOf(e: Throwable): String = when (e) {
        is CryptoException -> e.message ?: "恢复密钥操作失败"
        is IllegalArgumentException -> e.message ?: "输入不符合要求"
        is IllegalStateException -> e.message ?: "当前状态不允许这个操作"
        else -> "出了点问题：${e.message ?: e.javaClass.simpleName}"
    }
}
