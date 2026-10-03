package io.github.hexgmskr.noteone.ui.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接缝 B 的运行时载体：持有 [LockState]，并在解锁条件满足时推进状态。
 *
 * UI 只读 [state]、只调 [completeUnlock] / [onBackground] / [onForeground]，
 * 不自己判断"该不该锁"。指纹验证由系统 BiometricPrompt 在 App 之外完成，
 * 成功后由容器调 [completeUnlock] 报到（曾预留的 requestUnlock/UnlockMethod
 * 已随 2026-10-04 死代码清理删除：生产走不到，留着是陷阱）。
 */
class LockController(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = LOCK_TIMEOUT_MILLIS,
) {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** 退到后台的时刻；null 表示当前不在后台。 */
    private var backgroundedAt: Long? = null

    /**
     * 验证通过后由容器调用（指纹与主密码都走这条）：界面自己的状态表达"忙碌中"，
     * 状态机只需要终态。失败路径**不需要**调用（留在 [LockState.Locked] 即可）。
     */
    fun completeUnlock() {
        _state.value = LockState.Unlocked
    }

    /** 退到后台。记录时刻，供 [onForeground] 判断是否超时。 */
    fun onBackground() {
        if (backgroundedAt == null) {
            backgroundedAt = clock()
        }
    }

    /**
     * 回到前台。
     *
     * 判断逻辑：
     *  - 从未解锁过（冷启动后直接是前台）→ 什么都不做，等锁门页上的验证
     *  - 退后台时长 <= 宽限期 → 保持 [LockState.Unlocked]，不打断
     *  - 退后台时长 >  宽限期 → 置回 [LockState.Locked]，等重新解锁
     */
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null

        if (_state.value != LockState.Unlocked) return

        if (clock() - since > timeoutMillis) {
            _state.value = LockState.Locked
        }
    }

    /**
     * 强制上锁。用于「锁屏」按钮等用户主动动作（MVP 未用，预留）。
     */
    fun lockNow() {
        backgroundedAt = null
        _state.value = LockState.Locked
    }
}
