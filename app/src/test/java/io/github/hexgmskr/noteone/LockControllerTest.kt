package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.ui.lock.LockController
import io.github.hexgmskr.noteone.ui.lock.LockState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 入口状态机（接缝 B）的 JVM 单测。
 *
 * **2026-10-04 随死代码清理更新**：第三态 `Unlocking` 与弹出式验证器接口
 * （`UnlockMethod` / `requestUnlock`）已删除——生产里从来只有两态，指纹验证
 * 由系统 BiometricPrompt 在 App 之外完成，成功后走 `completeUnlock()` 报到；
 * 留着"调了就崩的预留 API"只会给后来人下套。文档同步改回两态。
 *
 * 唯一未知数仍是宽限期语义（spec 7.1）：冷启动必验；短切后台不验；
 * 退后台超 60 秒才重新验。纯逻辑、无 Android 依赖，故走 JVM 单测。
 */
class LockControllerTest {

    /** 可控时钟，避免真实 sleep。 */
    private class FakeClock(var now: Long = 0L)

    private fun controller(
        clock: FakeClock = FakeClock(),
        timeout: Long = 60_000L,
    ): Pair<LockController, FakeClock> =
        LockController({ clock.now }, timeout) to clock

    // ---- 冷启动 ----

    @Test
    fun `cold start is locked`() {
        val (c, _) = controller()
        assertEquals(LockState.Locked, c.state.value)
    }

    // ---- 宽限期（核心语义）----

    @Test
    fun `brief background then foreground does NOT relock`() {
        // 这条是 spec 7.1 的核心：切出去复制网址再回来，不打断
        val (c, clock) = controller()
        c.completeUnlock()

        c.onBackground()
        clock.now += 30_000          // 出去 30 秒
        c.onForeground()

        assertEquals("30 秒内回来不应重新上锁", LockState.Unlocked, c.state.value)
    }

    @Test
    fun `timeout background then foreground DOES relock`() {
        val (c, clock) = controller()
        c.completeUnlock()

        c.onBackground()
        clock.now += 60_001          // 超过 60 秒
        c.onForeground()

        assertEquals("超过宽限期回来必须重新上锁", LockState.Locked, c.state.value)
    }

    @Test
    fun `exactly at timeout boundary does not relock`() {
        // 边界值：恰好 60 秒不算超时（严格大于才超）
        val (c, clock) = controller()
        c.completeUnlock()

        c.onBackground()
        clock.now += 60_000
        c.onForeground()

        assertEquals("恰好 60 秒应视为未超时", LockState.Unlocked, c.state.value)
    }

    @Test
    fun `multiple brief backgrounds accumulate per episode not cumulatively`() {
        // 每次退后台是独立计时，不是累计
        val (c, clock) = controller()
        c.completeUnlock()

        repeat(10) {
            c.onBackground()
            clock.now += 30_000
            c.onForeground()
            assertEquals("第 ${it + 1} 次短切回来仍应保持解锁", LockState.Unlocked, c.state.value)
        }
    }

    @Test
    fun `custom timeout is honored`() {
        val (c, clock) = controller(timeout = 5_000L)
        c.completeUnlock()

        c.onBackground()
        clock.now += 4_000
        c.onForeground()
        assertEquals("4 秒 < 5 秒超时，应保持解锁", LockState.Unlocked, c.state.value)

        c.onBackground()
        clock.now += 6_000
        c.onForeground()
        assertEquals("6 秒 > 5 秒超时，应重新上锁", LockState.Locked, c.state.value)
    }

    // ---- 报到路径（指纹/主密码都走它）----

    @Test
    fun `completeUnlock enters Unlocked`() {
        val (c, _) = controller()
        c.completeUnlock()
        assertEquals(LockState.Unlocked, c.state.value)
    }

    @Test
    fun `after timeout relock, completeUnlock works again`() {
        val (c, clock) = controller()
        c.completeUnlock()

        c.onBackground()
        clock.now += 120_000
        c.onForeground()
        assertEquals("超时后应重新上锁", LockState.Locked, c.state.value)

        c.completeUnlock()
        assertEquals("重新验证成功后应再次放行", LockState.Unlocked, c.state.value)
    }

    // ---- 异常路径 ----

    @Test
    fun `foreground without prior background is a no-op`() {
        val (c, _) = controller()
        c.completeUnlock()
        c.onForeground()              // 从未退过后台
        assertEquals(LockState.Unlocked, c.state.value)
    }

    @Test
    fun `foreground while already locked does not auto unlock`() {
        val (c, clock) = controller()
        // 冷启动未解锁就退后台再回来
        c.onBackground()
        clock.now += 1_000
        c.onForeground()
        assertEquals("不该自动放行", LockState.Locked, c.state.value)
    }

    @Test
    fun `lockNow forces locked and resets background timer`() {
        val (c, clock) = controller()
        c.completeUnlock()
        c.onBackground()

        c.lockNow()
        assertEquals(LockState.Locked, c.state.value)

        // 上锁后再回前台，不应因旧的后台计时而行为异常
        clock.now += 120_000
        c.onForeground()
        assertEquals(LockState.Locked, c.state.value)
    }
}
