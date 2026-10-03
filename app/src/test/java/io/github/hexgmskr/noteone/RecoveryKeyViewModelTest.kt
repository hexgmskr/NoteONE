package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import io.github.hexgmskr.noteone.ui.recoverykey.RecoveryKeyMode
import io.github.hexgmskr.noteone.ui.recoverykey.RecoveryKeyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import javax.crypto.Cipher
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 恢复密钥页逻辑的 JVM 单测。
 *
 * 重点在**表单校验与状态流转**：门槛拦不拦得住、成功之后页面该变成什么样、
 * 失败之后一个字节都不许写。密码学本身已被 [RecoveryKeyManagerTest] 覆盖，
 * 这里用同一个真管家（只是迭代数调低）+ 内存落盘。
 *
 * 加解密跑在注入的测试调度器上——所有用例都是"调完即断言"的直路，
 * 只有防重复提交那条要手动推时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryKeyViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var source: InMemoryKeyBlob
    private lateinit var manager: RecoveryKeyManager

    private val password = "a-long-enough-password"
    private val newPassword = "another-long-enough-pass"
    private val tooShort = "short-pw"

    private val biometric = FakeBiometricActions()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        source = InMemoryKeyBlob()
        manager = RecoveryKeyManager(source, databaseExists = { false }, iterations = 1_000)
        biometric.enabled = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RecoveryKeyViewModel(manager, biometric, UnconfinedTestDispatcher(scheduler))

    private fun RecoveryKeyViewModel.fillSetup(pw: String = password, confirm: String = pw) {
        onPasswordChange(pw)
        onConfirmChange(confirm)
    }

    // ---- 初始状态 ----

    @Test
    fun `fresh install starts in setup mode`() {
        val vm = viewModel()

        assertFalse(vm.uiState.value.isSetUp)
        assertEquals(RecoveryKeyMode.SETUP, vm.uiState.value.mode)
    }

    @Test
    fun `already set up device starts in status mode`() {
        manager.setup(password.toCharArray())

        val vm = viewModel()

        assertTrue(vm.uiState.value.isSetUp)
        assertEquals(RecoveryKeyMode.STATUS, vm.uiState.value.mode)
    }

    // ---- 创建 ----

    @Test
    fun `short password is rejected with a readable message and writes nothing`() {
        val vm = viewModel()
        vm.fillSetup(pw = tooShort)

        vm.submit()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue("提示里要有门槛数字：$error", error!!.contains("12"))
        assertNull("被拦下时不许写任何东西", source.text)
    }

    @Test
    fun `mismatched confirmation is rejected`() {
        val vm = viewModel()
        vm.fillSetup(pw = password, confirm = newPassword)

        vm.submit()

        assertTrue(vm.uiState.value.error!!.contains("不一致"))
        assertNull(source.text)
    }

    @Test
    fun `successful setup flips to status, clears fields and prompts export`() {
        val vm = viewModel()
        vm.fillSetup()

        vm.submit()

        val state = vm.uiState.value
        assertTrue(state.isSetUp)
        assertEquals(RecoveryKeyMode.STATUS, state.mode)
        assertEquals("密码不许残留在界面上", "", state.password)
        assertEquals("", state.confirm)
        assertTrue("必须引导用户导出：${state.notice}", state.notice!!.contains("导出"))
        assertNotNull(source.text)
    }

    // ---- 换主密码 ----

    @Test
    fun `change password requires the current password`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.CHANGE_PASSWORD)
        vm.fillSetup(pw = newPassword)

        vm.submit()

        assertTrue(vm.uiState.value.error!!.contains("当前主密码"))
    }

    @Test
    fun `change password with a wrong current password surfaces the error`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.CHANGE_PASSWORD)
        vm.onCurrentPasswordChange("not-the-current-one")
        vm.fillSetup(pw = newPassword)

        vm.submit()

        assertNotNull("密码不对要有提示", vm.uiState.value.error)
        manager.unlock(password.toCharArray())   // 原密码必须仍有效——失败不改动任何东西
    }

    @Test
    fun `successful change password swaps the password`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.CHANGE_PASSWORD)
        vm.onCurrentPasswordChange(password)
        vm.fillSetup(pw = newPassword)

        vm.submit()

        assertEquals(RecoveryKeyMode.STATUS, vm.uiState.value.mode)
        assertEquals("旧密码该失效", true, runCatching { manager.unlock(password.toCharArray()) }.isFailure)
        manager.unlock(newPassword.toCharArray())   // 新密码生效（不抛即通过）
    }

    @Test
    fun `switching mode clears password fields`() {
        val vm = viewModel()
        vm.onPasswordChange("typed-something")

        vm.switchTo(RecoveryKeyMode.RESTORE)

        assertEquals("", vm.uiState.value.password)
    }

    // ---- 从备份恢复 ----

    @Test
    fun `restore without a picked file is rejected`() {
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.RESTORE)
        vm.onPasswordChange(password)

        vm.submit()

        assertTrue(vm.uiState.value.error!!.contains("备份文件"))
    }

    @Test
    fun `restore with a wrong password fails and stays not set up`() {
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.RESTORE)
        vm.onBackupFilePicked(foreignEnvelope(password))
        vm.onPasswordChange(newPassword)   // 错密码

        vm.submit()

        assertNotNull(vm.uiState.value.error)
        assertFalse("失败的恢复不许落到本机", vm.uiState.value.isSetUp)
        assertNull(source.text)
    }

    @Test
    fun `successful restore marks set up and switches to status`() {
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.RESTORE)
        vm.onBackupFilePicked(foreignEnvelope(password))
        vm.onPasswordChange(password)

        vm.submit()

        val state = vm.uiState.value
        assertTrue(state.isSetUp)
        assertEquals(RecoveryKeyMode.STATUS, state.mode)
        assertNotNull(source.text)
        manager.unlock(password.toCharArray())   // 恢复进来的密文必须是可用的
    }

    @Test
    fun `restore does not enforce the new-password policy`() {
        // 备份可能是旧政策下建的（比如 8 位密码）；门槛只约束"新设的密码"
        val oldShortPassword = "oldshort"
        val vm = viewModel()
        vm.switchTo(RecoveryKeyMode.RESTORE)
        vm.onBackupFilePicked(foreignEnvelope(oldShortPassword))
        vm.onPasswordChange(oldShortPassword)

        vm.submit()

        assertTrue("旧政策下的密码必须能恢复：${vm.uiState.value.error}", vm.uiState.value.isSetUp)
    }

    // ---- 防重复提交 ----

    @Test
    fun `busy blocks a second submit while crypto is running`() = runTest(scheduler) {
        val vm = RecoveryKeyViewModel(manager, biometric, cryptoContext = StandardTestDispatcher(scheduler))
        vm.fillSetup()

        vm.submit()
        assertTrue("提交后立刻进入处理中", vm.uiState.value.busy)
        vm.submit()   // 应被 busy 挡下（若穿透会撞上"已设置"护栏并报错）
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.busy)
        assertTrue(state.isSetUp)
        assertNull("第二次提交不该留下任何痕迹：${state.error}", state.error)
    }

    // ---- 指纹解锁（副本A）----

    @Test
    fun `fresh state reports biometrics disabled`() {
        val vm = viewModel()

        assertFalse(vm.uiState.value.biometricEnabled)
    }

    @Test
    fun `enable flow marks biometrics enabled`() {
        manager.setup(password.toCharArray())
        val cipher = FakeBiometricActions.tokenCipher()
        var provisioned: Cipher? = null
        biometric.beginProvision = { cipher }
        biometric.finishProvision = { provisioned = it }
        val vm = viewModel()

        val begin = vm.beginBiometricProvision()
        vm.finishBiometricProvision(begin!!)

        assertEquals("Cipher 必须原样递给 finish", cipher, provisioned)
        assertTrue(vm.uiState.value.biometricEnabled)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `begin provision failure surfaces an error`() {
        manager.setup(password.toCharArray())
        biometric.beginProvision = { throw RuntimeException("没钥匙") }
        val vm = viewModel()

        assertNull(vm.beginBiometricProvision())

        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.biometricEnabled)
    }

    @Test
    fun `finish provision failure surfaces an error and stays disabled`() {
        manager.setup(password.toCharArray())
        biometric.beginProvision = { FakeBiometricActions.tokenCipher() }
        biometric.finishProvision = { throw RuntimeException("验证中途失败") }
        val vm = viewModel()

        vm.finishBiometricProvision(FakeBiometricActions.tokenCipher())

        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.biometricEnabled)
    }

    @Test
    fun `cancel keeps biometrics disabled with a notice`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()

        vm.biometricProvisionCancelled()

        assertFalse(vm.uiState.value.biometricEnabled)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `disable revokes biometrics`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        val vm = viewModel()
        assertTrue(vm.uiState.value.biometricEnabled)

        vm.disableBiometric()

        assertEquals("应调用吊销", 1, biometric.disableCalls)
        assertFalse(vm.uiState.value.biometricEnabled)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `disable while high security is on tells the user it exits too and resets the flag`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        biometric.highSecurity = true
        val vm = viewModel()
        assertTrue("前提：此时高安全模式应显示为开", vm.uiState.value.highSecurity)

        vm.disableBiometric()

        assertFalse("高安全标记应随关闭指纹一起复位", vm.uiState.value.highSecurity)
        val notice = vm.uiState.value.notice ?: ""
        assertTrue(
            "提示要把'高安全模式一并退出'讲明白，不能让它悄悄发生：$notice",
            notice.contains("高安全模式一并退出"),
        )
    }

    // ---- 高安全模式切换（四条闭环的界面侧，2026-10-03）----

    @Test
    fun `init reads the high security flag and invalid-key state`() {
        biometric.enabled = true
        biometric.highSecurity = true
        biometric.keyInvalid = true

        val vm = viewModel()

        assertTrue(vm.uiState.value.highSecurity)
        assertTrue("作废状态要如实呈现（引导重建的入口）", vm.uiState.value.biometricKeyInvalid)
    }

    @Test
    fun `switch first asks for the main password`() {
        biometric.enabled = true
        val vm = viewModel()

        vm.beginHighSecuritySwitch()

        assertTrue(vm.uiState.value.awaitingSwitchPassword)
    }

    @Test
    fun `switch refuses an empty password`() = runTest {
        biometric.enabled = true
        val vm = viewModel()
        vm.beginHighSecuritySwitch()

        vm.submitHighSecuritySwitch()

        assertNotNull("空密码应被拦下", vm.uiState.value.error)
        assertFalse("不该进入验证环节", vm.uiState.value.switchReadyToProve)
    }

    @Test
    fun `switch verifies the recovery key with the entered password`() = runTest {
        biometric.enabled = true
        val vm = viewModel()
        vm.beginHighSecuritySwitch()
        vm.onSwitchPasswordChange(password)

        vm.submitHighSecuritySwitch()

        assertEquals(
            "应把输入的密码交给退路校验（闭环规则①）",
            password,
            biometric.lastSwitchPassword?.concatToString(),
        )
        assertTrue("校验通过后应放行两段指纹", vm.uiState.value.switchReadyToProve)
        assertFalse(vm.uiState.value.awaitingSwitchPassword)
    }

    @Test
    fun `switch stops when the recovery key check fails`() = runTest {
        biometric.enabled = true
        biometric.verifyForSwitch = { throw IllegalStateException("主密码不对") }
        val vm = viewModel()
        vm.beginHighSecuritySwitch()
        vm.onSwitchPasswordChange("wrong-password-xx")

        vm.submitHighSecuritySwitch()

        assertNotNull(vm.uiState.value.error)
        assertFalse("退路验证没过，绝不放行", vm.uiState.value.switchReadyToProve)
    }

    @Test
    fun `successful switch flips the mode and lands a notice`() {
        biometric.enabled = true
        biometric.highSecurity = false
        val vm = viewModel()

        vm.finishModeSwitchVerify(FakeBiometricActions.tokenCipher())

        assertTrue("模式应翻面", vm.uiState.value.highSecurity)
        assertNotNull(vm.uiState.value.notice)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `wrap failure aborts and cleans up the spare key`() {
        biometric.enabled = true
        biometric.finishWrap = { throw IllegalStateException("打包炸了") }
        val vm = viewModel()

        val ok = vm.finishModeSwitchWrap(FakeBiometricActions.tokenCipher())

        assertFalse(ok)
        assertNotNull(vm.uiState.value.error)
        assertEquals("失败要清扫（删掉多建的钥匙）", 1, biometric.cancelSwitchCalls)
    }

    @Test
    fun `cancelling at a fingerprint prompt keeps the setting unchanged`() {
        biometric.enabled = true
        biometric.highSecurity = false
        val vm = viewModel()

        vm.modeSwitchCancelled()

        assertEquals("取消要清扫暂存状态", 1, biometric.cancelSwitchCalls)
        assertFalse("模式不该变", vm.uiState.value.highSecurity)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `re-provision clears the invalid-key mark`() {
        biometric.enabled = true
        biometric.keyInvalid = true
        val vm = viewModel()
        assertTrue(vm.uiState.value.biometricKeyInvalid)

        vm.finishBiometricProvision(FakeBiometricActions.tokenCipher())

        assertFalse("重建副本A 后作废标记必须消失", vm.uiState.value.biometricKeyInvalid)
        assertTrue(vm.uiState.value.biometricEnabled)
    }

    /** 造一份"另一台设备导出的"备份。 */
    private fun foreignEnvelope(pw: String): String =
        RecoveryKey.wrap(RecoveryKey.generateDek(), pw.toCharArray(), iterations = 1_000).serialize()
}
