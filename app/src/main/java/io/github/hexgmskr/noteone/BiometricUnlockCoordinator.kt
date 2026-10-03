package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.HighSecurityModeSource
import io.github.hexgmskr.noteone.data.crypto.KeyWrapper
import io.github.hexgmskr.noteone.data.crypto.KeystoreKeyWrapper
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.db.DbKey
import io.github.hexgmskr.noteone.ui.lock.BiometricUnlockActions
import javax.crypto.Cipher

/**
 * 指纹解锁与高安全模式切换的全部动作（[BiometricUnlockActions] 的实现）。
 *
 * **2026-10-04 从 [AppContainer] 里的匿名对象抽出——为了可测**：高安全模式的
 * 四条闭环（CLAUDE.md 硬约束，"绝不允许死胡同"）此前零自动化覆盖，只靠真机
 * 人工走；而"换钥匙、重包 DEK、原子落盘、失败回滚"恰恰最不能靠人眼守。
 * 抽成类、依赖全部走接口/lambda 之后，JVM 测试可以拿假 [KeyWrapper]
 * （真 Keystore 的 auth-per-use 钥匙在自动化里根本调不了 doFinal）把闭环的
 * 正常与失败分支全走一遍；真实现的行为仍由 KeystoreKeyWrapperTest 守着。
 *
 * 自身不持有会话状态：DEK 的读写、开库、推进状态机都由容器以 lambda 注入，
 * **"会话"的唯一真相仍在 [AppContainer]**。
 */
class BiometricUnlockCoordinator(
    private val keyWrapper: KeyWrapper,
    private val highSecurityModeStore: HighSecurityModeSource,
    private val recoveryKeyManager: RecoveryKeyManager,
    private val sessionKey: () -> DbKey?,
    private val setSessionKey: (DbKey) -> Unit,
    /** 解锁时打开数据库（容器提供，含失败话术，见 AppContainer.openDatabaseForUnlock）。 */
    private val openDatabase: () -> Unit,
    /** 推进状态机到 Unlocked（容器提供 lockController::completeUnlock）。 */
    private val onUnlocked: () -> Unit,
) : BiometricUnlockActions {

    /** 当前副本A 该用哪个模式的钥匙（由偏好决定；切换流程负责让它与信封一致）。 */
    private fun currentKeyMode(): KeystoreKeyWrapper.KeyMode =
        if (highSecurityModeStore.isEnabled()) {
            KeystoreKeyWrapper.KeyMode.HIGH_SECURITY
        } else {
            KeystoreKeyWrapper.KeyMode.NORMAL
        }

    override fun isEnabled(): Boolean = keyWrapper.isProvisioned()

    override fun beginUnlock(): Cipher = keyWrapper.beginDecrypt(currentKeyMode())

    override fun finishUnlock(cipher: Cipher) {
        val dek = keyWrapper.finishUnwrap(cipher)
        setSessionKey(dek)
        openDatabase()
        onUnlocked()
    }

    override fun beginProvision(): Cipher {
        // 钥匙必须**先生成**：Cipher 要拿它 init，顺序反了会以
        // "Keystore 里没有钥匙" 的形态在真机上暴露（已踩过一次）
        val mode = currentKeyMode()
        keyWrapper.ensureKeyExists(mode)
        return keyWrapper.beginEncrypt(mode)
    }

    override fun finishProvision(cipher: Cipher) {
        // 会话里有 DEK 才谈得上"包住它"——启用指纹解锁必须发生在解锁之后
        val dek = sessionKey() ?: error("还没有解锁，不能启用指纹解锁")
        keyWrapper.finishProvision(dek, cipher)
    }

    override fun disable() {
        // 关指纹 = 副本A 全部吊销。**高安全模式偏好一并复位**（2026-10-04 审计）：
        // 那个开关只作用于"指纹钥匙"的模式；偏好留着 true 的话，"重新启用指纹"
        // 会按残留偏好悄悄建回高安全钥匙、并绕过闭环①的"当场验证副本B"——
        // 用户无感地回到高安全模式。复位后想再进，必须重新走一遍开启流程。
        keyWrapper.revoke()
        highSecurityModeStore.setEnabled(false)
    }

    // ---- 高安全模式（切换流程跨两次指纹弹窗，暂存状态放这里）----
    //
    // 语义：**提交之前不动任何现有东西**——新钥匙建在另一个别名上（两个模式的
    // 别名永不重合），新信封只在内存里。任一步取消/失败都等于没发生，
    // 现有副本A 原封不动。

    private var pendingMode: KeystoreKeyWrapper.KeyMode? = null
    private var pendingEnvelope: String? = null

    override fun isHighSecurity(): Boolean = highSecurityModeStore.isEnabled()

    override fun biometricKeyInvalid(): Boolean =
        keyWrapper.isProvisioned() && keyWrapper.keyUnusable(currentKeyMode())

    override fun verifyRecoveryKeyForSwitch(password: CharArray) {
        val session = sessionKey() ?: error("还没有解锁，不能切换高安全模式")
        // 旧政策下的短密码也要接受（门槛只约束"新设的密码"）——这里不校验长度
        val fromBackup = recoveryKeyManager.unlock(password)
        if (!fromBackup.bytes.contentEquals(session.bytes)) {
            throw CryptoException("这份恢复密钥解出的钥匙和当前数据的钥匙不一致，不能继续")
        }
    }

    override fun beginModeSwitch(): Cipher {
        check(pendingMode == null) { "已有一次模式切换在进行中" }
        // 有副本A 才谈得上"替换"；没有时界面不该走到这里
        check(keyWrapper.isProvisioned()) { "先启用指纹解锁，再切换高安全模式" }

        val target = if (isHighSecurity()) {
            KeystoreKeyWrapper.KeyMode.NORMAL
        } else {
            KeystoreKeyWrapper.KeyMode.HIGH_SECURITY
        }
        keyWrapper.ensureKeyExists(target)
        pendingMode = target
        return keyWrapper.beginEncrypt(target)
    }

    override fun finishModeSwitchWrap(cipher: Cipher) {
        val dek = sessionKey() ?: error("还没有解锁，不能切换高安全模式")
        pendingEnvelope = keyWrapper.wrapEnvelopeText(dek, cipher)
    }

    override fun beginModeSwitchVerify(): Cipher {
        val target = pendingMode ?: error("没有正在进行的模式切换")
        val text = pendingEnvelope ?: error("没有待校验的新信封")
        return keyWrapper.beginDecrypt(target, text)
    }

    override fun finishModeSwitchVerify(cipher: Cipher) {
        val target = pendingMode ?: error("没有正在进行的模式切换")
        val text = pendingEnvelope ?: error("没有待校验的新信封")
        val session = sessionKey() ?: error("还没有解锁，不能切换高安全模式")
        val oldMode = currentKeyMode()

        // ① 校验（闭环规则③的"校验过"）：解出来必须与当前会话同一把 DEK
        val unwrapped = keyWrapper.finishUnwrap(cipher, text)
        if (!unwrapped.bytes.contentEquals(session.bytes)) {
            throw CryptoException("新信封校验不通过（解出的钥匙与当前不一致），已放弃切换")
        }

        // ② 原子落盘新信封（KeyBlobStore 临时文件 + 改名）。旧信封留底，供回滚
        val oldEnvelope = keyWrapper.currentEnvelopeText()
        keyWrapper.writeEnvelopeText(text)

        // ③ 写偏好；写不进去就把旧信封写回去——宁可回到"没发生"，
        //    也不留下"偏好与信封对不上、指纹解锁失灵"的哑状态
        if (!highSecurityModeStore.setEnabled(target == KeystoreKeyWrapper.KeyMode.HIGH_SECURITY)) {
            oldEnvelope?.let { keyWrapper.writeEnvelopeText(it) }
            pendingMode = null
            pendingEnvelope = null
            throw CryptoException("模式设置写入失败，已回滚，请重试")
        }

        // ④ 清理不再使用的旧钥匙（失败无害：留着一把没人用的钥匙）
        if (oldMode != target) keyWrapper.deleteKey(oldMode)

        pendingMode = null
        pendingEnvelope = null
    }

    override fun cancelModeSwitch() {
        // 取消时把"多建的那把钥匙"清掉；目标别名永远不是当前在用的那把
        pendingMode?.let { keyWrapper.deleteKey(it) }
        pendingMode = null
        pendingEnvelope = null
    }
}
