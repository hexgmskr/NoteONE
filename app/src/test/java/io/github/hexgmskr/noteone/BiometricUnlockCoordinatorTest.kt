package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.HighSecurityModeSource
import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import io.github.hexgmskr.noteone.data.crypto.KeyWrapper
import io.github.hexgmskr.noteone.data.crypto.KeystoreKeyWrapper
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.db.DbKey
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **高安全模式四条闭环的编排测试**（2026-10-04 补，审计的 #1 测试缺口）。
 *
 * CLAUDE.md 对高安全模式的要求是"绝不允许死胡同"：开启前验副本B、作废即引导重建、
 * 校验过才原子替换、失败要回滚。此前这条链零自动化覆盖，只靠真机人工走。
 *
 * 用**假 KeyWrapper**：真 Keystore 的 auth-per-use 钥匙必须有真指纹，自动化里
 * 连 doFinal 都调不了（现有真机用例只能测负例）。真实现的行为由
 * KeystoreKeyWrapperTest 的真机用例守着；这里测的是**编排**——顺序、校验、
 * 回滚、清理这些"最不能靠人眼守"的部分。
 */
class BiometricUnlockCoordinatorTest {

    // ---- 夹具 ----

    /** 可注入失败的偏好口：setEnabled 返回 false 模拟"写不进去"。 */
    private class FakeStore(
        var enabled: Boolean = false,
        var writeSucceeds: Boolean = true,
    ) : HighSecurityModeSource {
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean): Boolean {
            if (!writeSucceeds) return false
            this.enabled = enabled
            return true
        }
    }

    /** 假钥匙封装：把"模式、信封、作废"这些可观察量记下来，不做真密码学。 */
    private class FakeWrapper : KeyWrapper {
        var provisioned = false
        var unusable = false
        var envelope: String? = null
        /** finishUnwrap 返回的 DEK——测试用它模拟"解出来一致/不一致"。 */
        var unwrapDek: DbKey = DbKey(ByteArray(32) { 1 })
        val ensured = mutableListOf<KeystoreKeyWrapper.KeyMode>()
        val deleted = mutableListOf<KeystoreKeyWrapper.KeyMode>()
        var revoked = false

        /** 假实现不真加密，但 Cipher 参数得上真对象（编排层只传递它，不会用它做运算）。 */
        private fun dummyCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

        override fun isProvisioned(): Boolean = provisioned
        override fun keyUnusable(mode: KeystoreKeyWrapper.KeyMode): Boolean = unusable
        override fun ensureKeyExists(mode: KeystoreKeyWrapper.KeyMode) { ensured += mode }
        override fun deleteKey(mode: KeystoreKeyWrapper.KeyMode) { deleted += mode }
        override fun beginEncrypt(mode: KeystoreKeyWrapper.KeyMode): Cipher = dummyCipher()
        override fun beginDecrypt(mode: KeystoreKeyWrapper.KeyMode): Cipher = dummyCipher()
        override fun beginDecrypt(mode: KeystoreKeyWrapper.KeyMode, envelopeText: String): Cipher = dummyCipher()
        override fun currentEnvelopeText(): String? = envelope
        override fun finishProvision(dek: DbKey, cipher: Cipher) {
            envelope = "env:${dek.bytes.joinToString(",")}"
            provisioned = true
        }
        override fun finishUnwrap(cipher: Cipher): DbKey = unwrapDek
        override fun finishUnwrap(cipher: Cipher, envelopeText: String): DbKey = unwrapDek
        override fun wrapEnvelopeText(dek: DbKey, cipher: Cipher): String = "env:${dek.bytes.joinToString(",")}"
        override fun writeEnvelopeText(text: String) { envelope = text }
        override fun revoke() {
            revoked = true
            provisioned = false
            envelope = null
        }
    }

    private val password = "correct horse battery staple".toCharArray()
    private val sessionDek = DbKey(ByteArray(32) { 7 })

    private val wrapper = FakeWrapper()
    private val store = FakeStore()
    private val manager = RecoveryKeyManager(InMemoryKeyBlob(), databaseExists = { false }, iterations = 1_000)

    private var session: DbKey? = sessionDek
    private var opened = 0
    private var unlocked = 0

    private fun coordinator() = BiometricUnlockCoordinator(
        keyWrapper = wrapper,
        highSecurityModeStore = store,
        recoveryKeyManager = manager,
        sessionKey = { session },
        setSessionKey = { session = it },
        openDatabase = { opened++ },
        onUnlocked = { unlocked++ },
    )

    // ---- 闭环① 前置验证 ----

    @Test
    fun `verifyRecoveryKeyForSwitch accepts the password whose key equals the session DEK`() {
        manager.setup(password)
        session = manager.unlock(password)      // 会话就是这把——一致，应放行
        coordinator().verifyRecoveryKeyForSwitch(password)
    }

    @Test
    fun `verifyRecoveryKeyForSwitch rejects a recovery key that decrypts to a different DEK`() {
        manager.setup(password)
        session = DbKey(ByteArray(32) { 99 })   // 会话是另一把——不一致，必须拒绝

        val thrown = runCatching { coordinator().verifyRecoveryKeyForSwitch(password) }.exceptionOrNull()

        assertTrue("必须是 CryptoException（可展示的话术），实际=$thrown", thrown is CryptoException)
    }

    @Test
    fun `verifyRecoveryKeyForSwitch refuses when there is no session`() {
        manager.setup(password)
        session = null
        assertTrue(runCatching { coordinator().verifyRecoveryKeyForSwitch(password) }.isFailure)
    }

    // ---- 闭环③ 校验过才原子替换（正常路径）----

    @Test
    fun `full switch to high security replaces envelope, flips preference and swaps keys`() {
        wrapper.provisioned = true
        wrapper.envelope = "env:old"
        wrapper.unwrapDek = sessionDek            // 校验会通过（与会话一致）
        val c = coordinator()

        val cipher1 = c.beginModeSwitch()        // ① 建新钥匙（_hs）并取加密用 Cipher
        assertTrue("应 ensure 出高安全钥匙", wrapper.ensured.contains(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY))

        c.finishModeSwitchWrap(cipher1)          // ② 包出新信封（只在内存）
        c.finishModeSwitchVerify(c.beginModeSwitchVerify())   // ③ 校验（一致）

        assertEquals("磁盘信封应换成新的", "env:${sessionDek.bytes.joinToString(",")}", wrapper.envelope)
        assertTrue("偏好应切到高安全", store.enabled)
        assertTrue("旧模式的钥匙应清掉", wrapper.deleted.contains(KeystoreKeyWrapper.KeyMode.NORMAL))
    }

    // ---- 闭环③ 的失败分支：校验不过 = 什么都没发生 ----

    @Test
    fun `verification mismatch aborts and writes nothing`() {
        wrapper.provisioned = true
        wrapper.envelope = "env:old"
        wrapper.unwrapDek = DbKey(ByteArray(32) { 123 })   // 解出来与会话不一致
        val c = coordinator()

        c.finishModeSwitchWrap(c.beginModeSwitch())
        val thrown = runCatching { c.finishModeSwitchVerify(c.beginModeSwitchVerify()) }.exceptionOrNull()

        assertTrue("校验不过要抛 CryptoException，实际=$thrown", thrown is CryptoException)
        assertEquals("磁盘信封不能被动过", "env:old", wrapper.envelope)
        assertFalse("偏好不能被动过", store.enabled)
    }

    // ---- 闭环③ 的失败分支：偏好写失败 → 回滚旧信封 ----

    @Test
    fun `preference write failure rolls back the old envelope`() {
        wrapper.provisioned = true
        wrapper.envelope = "env:old"
        wrapper.unwrapDek = sessionDek
        store.writeSucceeds = false               // 让偏好写失败
        val c = coordinator()

        c.finishModeSwitchWrap(c.beginModeSwitch())
        val thrown = runCatching { c.finishModeSwitchVerify(c.beginModeSwitchVerify()) }.exceptionOrNull()

        assertTrue(thrown is CryptoException)
        assertTrue("错误话术要说清已回滚：${thrown?.message}", thrown?.message?.contains("已回滚") == true)
        assertEquals("旧信封必须被写回", "env:old", wrapper.envelope)
        assertFalse("偏好保持原样", store.enabled)
    }

    // ---- 取消：清掉多建的那把钥匙，别的都不动 ----

    @Test
    fun `cancel deletes the pending key and leaves the envelope alone`() {
        wrapper.provisioned = true
        wrapper.envelope = "env:old"
        val c = coordinator()

        c.beginModeSwitch()
        c.cancelModeSwitch()

        assertTrue("待用的新钥匙要清掉", wrapper.deleted.contains(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY))
        assertEquals("信封不能被动", "env:old", wrapper.envelope)
    }

    // ---- 解锁/关闭/失效判断的接线 ----

    @Test
    fun `finishUnlock stores the session, opens the database and advances the lock`() {
        wrapper.unwrapDek = DbKey(ByteArray(32) { 42 })
        session = null

        coordinator().finishUnlock(Cipher.getInstance("AES/GCM/NoPadding"))

        assertTrue("会话 DEK 应就位", session!!.bytes.contentEquals(ByteArray(32) { 42 }))
        assertEquals("应打开数据库", 1, opened)
        assertEquals("应推进状态机", 1, unlocked)
    }

    @Test
    fun `disable revokes and resets the high security preference`() {
        wrapper.provisioned = true
        wrapper.envelope = "env:old"
        store.enabled = true

        coordinator().disable()

        assertTrue("副本A 应被吊销", wrapper.revoked)
        assertFalse("高安全偏好应一并复位（审计 A3）", store.enabled)
        assertNull(wrapper.envelope)
    }

    @Test
    fun `biometricKeyInvalid needs both an envelope and an unusable key`() {
        val c = coordinator()

        wrapper.provisioned = false; wrapper.unusable = true
        assertFalse("没启用就不算失效", c.biometricKeyInvalid())

        wrapper.provisioned = true; wrapper.unusable = false
        assertFalse("钥匙可用就不算失效", c.biometricKeyInvalid())

        wrapper.provisioned = true; wrapper.unusable = true
        assertTrue("信封在而钥匙不可用 = 需要重新启用", c.biometricKeyInvalid())
    }
}
