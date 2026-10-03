package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.ui.lock.BiometricUnlockActions
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * JVM 单测用的指纹动作替身。
 *
 * 不碰真 Keystore、不弹真窗（那两样都要真机和真手指），只按用例预设的脚本行事。
 * `Cipher` 在电脑 JVM 上是普通类，用例可以拿它当"令牌"传来传去——
 * 替身不关心它是不是真能加解密，只关心它有没有被原样递到该去的地方。
 */
internal class FakeBiometricActions : BiometricUnlockActions {

    var enabled = false

    /** 预设成抛异常即可模拟"没启用/信封坏了/钥匙坏了"。 */
    var beginUnlock: () -> Cipher = { error("本用例没预设 beginUnlock") }
    var finishUnlock: (Cipher) -> Unit = {}
    var beginProvision: () -> Cipher = { error("本用例没预设 beginProvision") }
    var finishProvision: (Cipher) -> Unit = {}
    var disableCalls = 0

    override fun isEnabled(): Boolean = enabled

    override fun beginUnlock(): Cipher = beginUnlock.invoke()

    override fun finishUnlock(cipher: Cipher) = finishUnlock.invoke(cipher)

    override fun beginProvision(): Cipher = beginProvision.invoke()

    override fun finishProvision(cipher: Cipher) = finishProvision.invoke(cipher)

    override fun disable() {
        disableCalls++
        enabled = false
    }

    // ---- 高安全模式 ----

    /** 预设成抛异常即可模拟"副本B 对不上/没解锁"。 */
    var verifyForSwitch: (CharArray) -> Unit = {}
    var beginSwitch: () -> Cipher = { error("本用例没预设 beginModeSwitch") }
    var finishWrap: (Cipher) -> Unit = {}
    var beginVerify: () -> Cipher = { error("本用例没预设 beginModeSwitchVerify") }
    var finishVerify: (Cipher) -> Unit = {}

    var highSecurity = false
    var keyInvalid = false
    var cancelSwitchCalls = 0

    /** 记录 verifyRecoveryKeyForSwitch 收到的密码（断言"用的哪份密码"）。 */
    var lastSwitchPassword: CharArray? = null

    override fun isHighSecurity(): Boolean = highSecurity

    override fun biometricKeyInvalid(): Boolean = keyInvalid

    override fun verifyRecoveryKeyForSwitch(password: CharArray) {
        lastSwitchPassword = password
        verifyForSwitch.invoke(password)
    }

    override fun beginModeSwitch(): Cipher = beginSwitch.invoke()

    override fun finishModeSwitchWrap(cipher: Cipher) = finishWrap.invoke(cipher)

    override fun beginModeSwitchVerify(): Cipher = beginVerify.invoke()

    override fun finishModeSwitchVerify(cipher: Cipher) {
        finishVerify.invoke(cipher)
        // 真实现的提交会把模式翻面；替身照做，用例不必自己再翻
        highSecurity = !highSecurity
    }

    override fun cancelModeSwitch() {
        cancelSwitchCalls++
    }

    companion object {
        /** 一个真的 AES Cipher，仅当"令牌"用。 */
        fun tokenCipher(): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    KeyGenerator.getInstance("AES").apply { init(128) }.generateKey(),
                )
            }
    }
}
