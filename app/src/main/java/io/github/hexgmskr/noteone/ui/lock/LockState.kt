package io.github.hexgmskr.noteone.ui.lock

/**
 * 接缝 B：入口状态机。**两态**（2026-10-04 清理后如实收拢）：
 *
 *   Locked ⇄ Unlocked
 *   （超时 / 冷启动回到 Locked）
 *
 * UI 从第一天起就按「数据要等解锁后才有」来写，不假定数据库随时可用——
 * 这是「加密后置不等于返工」的另一半保证。
 *
 * **为什么没有"验证中"第三态**：指纹验证由系统 BiometricPrompt 在 App 之外
 * 完成（弹窗期间界面停在锁门页），成功/失败各自落回 Unlocked/Locked 两个终态，
 * App 自身不存在可停留的中间态。曾预留的弹出式验证器接口（UnlockMethod）与
 * 第三态一起删除——留一个调了就崩的空接口，不如没有。
 */
sealed interface LockState {
    /** 未解锁。此状态下不得读取任何业务数据。 */
    data object Locked : LockState

    /** 已解锁，可正常使用。 */
    data object Unlocked : LockState
}

// 历史注记：明文阶段的 PlaintextUnlockMethod（无条件放行）随 3-2b 删除；
// 预留的弹出式验证器接口 UnlockMethod 随 2026-10-04 死代码清理删除
// （生产从不经过它）。解锁一律走 LockController.completeUnlock() 报到。

/**
 * 解锁后多久（毫秒）退到后台再回来需要重新验证。
 *
 * 从「退后台」那一刻起算。短于这个时长切回来不打断体验
 * （切出去复制网址、复制一段文本是高频动作）；超过则重新验证。
 */
const val LOCK_TIMEOUT_MILLIS = 60_000L
