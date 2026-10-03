package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import io.github.hexgmskr.noteone.ui.lock.UnlockGateViewModel
import javax.crypto.Cipher
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 锁门逻辑的 JVM 单测。
 *
 * 未知数：两种形态判断得对不对（有没有恢复密钥）、密码错了之后状态干不干净、
 * 验证期间挡不挡重复提交。密码学本身由管家层覆盖，这里用真管家 + 内存落盘。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockGateViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var source: InMemoryKeyBlob
    private lateinit var manager: RecoveryKeyManager

    private val password = "a-long-enough-password"

    private val biometric = FakeBiometricActions()

    /** 模拟容器侧：验证密码，成功时记一笔"已解锁"。 */
    private var unlockedWith: String? = null
    private val attemptUnlock: (CharArray) -> Unit = { chars ->
        manager.unlock(chars)          // 密码不对会抛，正是要测的路径
        unlockedWith = chars.concatToString()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        source = InMemoryKeyBlob()
        manager = RecoveryKeyManager(source, databaseExists = { false }, iterations = 1_000)
        unlockedWith = null
        biometric.enabled = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = UnlockGateViewModel(manager, attemptUnlock, biometric, UnconfinedTestDispatcher(scheduler))

    // ---- 两种形态 ----

    @Test
    fun `not set up is reported as such`() {
        val vm = viewModel()

        assertFalse(vm.uiState.value.isSetUp)
    }

    @Test
    fun `set up device shows the password form`() {
        manager.setup(password.toCharArray())

        val vm = viewModel()

        assertTrue(vm.uiState.value.isSetUp)
    }

    @Test
    fun `refreshSetupState picks up a freshly created recovery key`() {
        val vm = viewModel()
        assertFalse(vm.uiState.value.isSetUp)

        manager.setup(password.toCharArray())
        vm.refreshSetupState()

        assertTrue("从创建页回来应切到密码形态", vm.uiState.value.isSetUp)
    }

    // ---- 验证 ----

    @Test
    fun `empty password is rejected without attempting`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()

        vm.submit()

        assertNotNull(vm.uiState.value.error)
        assertNull("不该拿空密码去解", unlockedWith)
    }

    @Test
    fun `wrong password surfaces the error and keeps the typed text`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()
        vm.onPasswordChange("wrong-password-here")

        vm.submit()

        assertNotNull("密码不对要给提示", vm.uiState.value.error)
        assertEquals("失败时不该清空输入，好让用户改", "wrong-password-here", vm.uiState.value.password)
        assertNull(unlockedWith)
    }

    @Test
    fun `correct password unlocks and clears the field`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()
        vm.onPasswordChange(password)

        vm.submit()

        assertNull(vm.uiState.value.error)
        assertEquals(password, unlockedWith)
        assertEquals("成功后密码不该留在界面上", "", vm.uiState.value.password)
    }

    @Test
    fun `typing clears a previous error`() {
        manager.setup(password.toCharArray())
        val vm = viewModel()
        vm.onPasswordChange("wrong-password-here")
        vm.submit()
        assertNotNull(vm.uiState.value.error)

        vm.onPasswordChange("wrong-password-here2")

        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `missing recovery key surfaces a readable failure`() {
        // 没设置就尝试解锁（理论上界面挡住了，但兜底要有）
        val vm = viewModel()
        vm.onPasswordChange(password)

        vm.submit()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue("提示要能给人看：$error", error!!.isNotBlank() && !error.contains("Exception"))
    }

    // ---- 防重复提交 ----

    @Test
    fun `busy blocks a second submit while verifying`() = runTest(scheduler) {
        manager.setup(password.toCharArray())
        val vm = UnlockGateViewModel(manager, attemptUnlock, biometric, StandardTestDispatcher(scheduler))
        vm.onPasswordChange(password)

        vm.submit()
        assertTrue("提交后立刻进入验证中", vm.uiState.value.busy)
        vm.submit()   // 应被挡下

        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertEquals("只应尝试一次", 1, listOfNotNull(unlockedWith).size)
    }

    @Test
    fun `manager failure with a weird exception still produces a readable message`() {
        manager.setup(password.toCharArray())
        val vm = UnlockGateViewModel(
            manager,
            attemptUnlock = { throw IllegalStateException("炸了") },
            biometric = biometric,
            cryptoContext = UnconfinedTestDispatcher(scheduler),
        )
        vm.onPasswordChange(password)

        vm.submit()

        assertTrue(
            "非预期异常也要转成人话：${vm.uiState.value.error}",
            vm.uiState.value.error!!.contains("解锁失败"),
        )
    }

    // ---- 指纹路径 ----

    @Test
    fun `biometric enabled shows the fingerprint form and auto-prompts once`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        val vm = viewModel()

        assertTrue("已启用指纹应在状态里体现", vm.uiState.value.biometricEnabled)
        assertTrue("进门应自动弹一次", vm.shouldAutoPromptBiometric())

        vm.beginBiometric()

        assertFalse("弹过就不再自动弹（防循环）", vm.shouldAutoPromptBiometric())
    }

    @Test
    fun `beginBiometric failure reveals the password fallback`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        biometric.beginUnlock = { throw RuntimeException("钥匙坏了") }
        val vm = viewModel()

        val cipher = vm.beginBiometric()

        assertNull("取不到 Cipher 就该返回 null", cipher)
        assertTrue("必须亮出主密码退路", vm.uiState.value.showPassword)
        assertNotNull("要给出提示", vm.uiState.value.notice)
    }

    @Test
    fun `finishBiometric passes the cipher through to the actions`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        val cipher = FakeBiometricActions.tokenCipher()
        var seen: Cipher? = null
        biometric.beginUnlock = { cipher }
        biometric.finishUnlock = { seen = it }
        val vm = viewModel()

        vm.finishBiometric(cipher)

        assertEquals("Cipher 必须原样递过去", cipher, seen)
        assertNull(vm.uiState.value.error)
        assertFalse("成功解出后不该亮密码退路", vm.uiState.value.showPassword)
    }

    @Test
    fun `finishBiometric failure reveals the password fallback`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        biometric.finishUnlock = { throw RuntimeException("解不开") }
        val vm = viewModel()
        val cipher = FakeBiometricActions.tokenCipher()

        vm.finishBiometric(cipher)

        assertTrue("指纹解不开必须亮出主密码退路", vm.uiState.value.showPassword)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `biometric dismissal reveals the password fallback`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        val vm = viewModel()

        vm.biometricDismissed()

        assertTrue(vm.uiState.value.showPassword)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `usePasswordInstead switches without attempting biometrics again`() {
        manager.setup(password.toCharArray())
        biometric.enabled = true
        var beginCalls = 0
        biometric.beginUnlock = { beginCalls++; FakeBiometricActions.tokenCipher() }
        val vm = viewModel()

        vm.usePasswordInstead()

        assertTrue(vm.uiState.value.showPassword)
        assertEquals("主动选密码不该再弹指纹", 0, beginCalls)
    }

    @Test
    fun `recovery key exception message is passed through as-is`() {
        // 管家的异常消息本来就是写给用户看的，不要再包一层
        val vm = UnlockGateViewModel(
            manager,
            attemptUnlock = { throw CryptoException("密码错误，或恢复密钥文件已损坏") },
            biometric = biometric,
            cryptoContext = UnconfinedTestDispatcher(scheduler),
        )
        vm.onPasswordChange(password)

        vm.submit()

        assertEquals("密码错误，或恢复密钥文件已损坏", vm.uiState.value.error)
    }
}
