package io.github.hexgmskr.noteone.ui.lock

import javax.crypto.Cipher

/**
 * 指纹解锁在 App 侧的全部动作（副本A 的生命周期 + 用它解锁）。
 *
 * 抽成接口只有一个目的：**界面 ViewModel 能在 JVM 单测里跑**——
 * 它们不碰 Android 的 BiometricPrompt（那需要真 Activity 和真手指），
 * 只跟这个接口打交道。
 *
 * 为什么方法都成对：Keystore 钥匙是 auth-per-use 的，**加解密都必须先过生物验证**，
 * 而 BiometricPrompt 的用法是"先把 Cipher 放进 CryptoObject → 弹窗 → 验证通过后
 * 拿回同一个 Cipher 继续用"。所以每件事拆成 begin（取待授权 Cipher）+ finish（授权后用）。
 * 具体实现见 [io.github.hexgmskr.noteone.AppContainer.biometricUnlock]。
 */
interface BiometricUnlockActions {

    /** 本机是否已启用指纹解锁（磁盘上有副本A）。 */
    fun isEnabled(): Boolean

    /**
     * 取一个**待授权**的解密 Cipher（塞给 BiometricPrompt 的 CryptoObject）。
     * 没有副本A、或钥匙/信封坏了时抛异常，消息可展示。
     */
    fun beginUnlock(): Cipher

    /** 验证通过后：解出 DEK → 写入会话 → 推进状态机（进入已解锁）。 */
    fun finishUnlock(cipher: Cipher)

    /** 取一个**待授权**的加密 Cipher（启用指纹解锁时用）。 */
    fun beginProvision(): Cipher

    /** 验证通过后：把当前会话的 DEK 包进副本A 并落盘。未解锁时抛异常。 */
    fun finishProvision(cipher: Cipher)

    /** 关闭指纹解锁：吊销副本A（删信封 + 删 Keystore 条目）。副本B 不受影响。 */
    fun disable()

    // ---- 高安全模式（2026-10-03）----

    /** 当前是否处于高安全模式（副本A 用"录入新指纹即作废"的钥匙）。 */
    fun isHighSecurity(): Boolean

    /**
     * 副本A 的钥匙是否已被系统作废：信封还在、钥匙没了
     * ——**通常就是高安全模式生效了**（用户录入了新指纹）。
     * 界面据此给出"用主密码进门后重新启用指纹"的引导（CLAUDE.md 闭环规则②）。
     */
    fun biometricKeyInvalid(): Boolean

    /**
     * 切换前的强制前置（闭环规则①）：核对主密码，且副本B 能解出
     * **与当前会话一致**的 DEK——退路必须先验证过，才允许动副本A。
     * 失败抛异常，消息可展示。
     */
    fun verifyRecoveryKeyForSwitch(password: CharArray)

    /**
     * 切换模式四步（界面在中间穿插两次指纹弹窗）：
     * ① [beginModeSwitch]   建好目标模式的钥匙，取待授权加密 Cipher
     * ② [finishModeSwitchWrap]   授权后包住 DEK，新信封**暂存内存不落盘**
     * ③ [beginModeSwitchVerify]  取待授权解密 Cipher，校验暂存的新信封
     * ④ [finishModeSwitchVerify] 授权后解开比对 → 通过才原子落盘 + 换偏好 + 清理旧钥匙
     *
     * 任一步取消/失败都不动现有的副本A（[cancelModeSwitch] 负责清扫）。
     */
    fun beginModeSwitch(): Cipher
    fun finishModeSwitchWrap(cipher: Cipher)
    fun beginModeSwitchVerify(): Cipher
    fun finishModeSwitchVerify(cipher: Cipher)

    /** 取消切换：丢弃暂存信封、删掉多建的钥匙。设置保持原样。 */
    fun cancelModeSwitch()
}
