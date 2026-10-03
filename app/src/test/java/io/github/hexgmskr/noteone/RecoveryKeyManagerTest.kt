package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥管家的 JVM 单测。
 *
 * 未知数是**生命周期护栏**：首次设置只能发生一次、换密码不许换掉 DEK、
 * 恢复导入不许在"已设置"时乱覆盖、任何失败路径都不许留下半个状态。
 * 这些护栏的作用是防"把用户的老数据锁在门外"，比功能本身更值得测。
 *
 * 迭代数注入低值跑快；"生产默认值确实是 60 万"由单独一条用例把关。
 * 真文件读写由 [KeyBlobStoreTest] 直接测真实现，这里用内存版。
 */
class RecoveryKeyManagerTest {

    /** 内存版落盘。 */

    private val source = InMemoryKeyBlob()
    private val manager = RecoveryKeyManager(source, databaseExists = { false }, iterations = 1_000)

    private val password = "a-long-enough-password".toCharArray()
    private val newPassword = "another-long-enough-pass".toCharArray()

    // ---- 首次设置 ----

    @Test
    fun `setup refuses when a database file already exists`() {
        // 库在、副本B 不在 = "钥匙丢了但门还在"：这时生成新 DEK 会把旧库永久锁死
        // （2026-10-04 审计的 A2）。护栏必须拦下，并且给出可走的恢复路径。
        val guarded = RecoveryKeyManager(source, databaseExists = { true }, iterations = 1_000)

        val refused = runCatching { guarded.setup(password) }

        assertTrue("库文件已存在时必须拒绝创建新密钥", refused.isFailure)
        val msg = refused.exceptionOrNull()?.message ?: ""
        assertTrue("拒绝理由要说明白并指向恢复路径：$msg",
            msg.contains("从备份恢复") && msg.contains("再也打不开"))
        assertNull("被拒后不该落下任何信封", source.read())
    }

    @Test
    fun `isSetUp is false before setup and true after`() {
        assertFalse(manager.isSetUp())

        manager.setup(password)

        assertTrue(manager.isSetUp())
    }

    @Test
    fun `setup stores the same envelope it returns`() {
        val returned = manager.setup(password)

        assertEquals("返回的文本就是落盘的文本（此刻是唯一备份窗口）", returned, manager.exportText())
        assertNotNull("落盘内容必须是可解析的信封", KeyEnvelope.parse(returned, KeyEnvelope.MAGIC_RECOVERY).payload)
    }

    @Test
    fun `unlock after setup returns a usable dek`() {
        manager.setup(password)

        val first = manager.unlock(password)
        val second = manager.unlock(password)

        assertEquals(32, first.bytes.size)
        assertArrayEquals("同一份密文每次解出的必须是同一把 DEK", first.bytes, second.bytes)
    }

    @Test
    fun `setup refuses to run twice`() {
        manager.setup(password)
        val stored = manager.exportText()

        assertThrows(IllegalStateException::class.java) { manager.setup(newPassword) }

        assertEquals("被拒绝的第二次设置不得改动已落盘的密文", stored, manager.exportText())
        manager.unlock(password)   // 老密码必须仍然有效
    }

    @Test
    fun `setup enforces the password policy and writes nothing when it fails`() {
        val tooShort = "short-pw".toCharArray()   // 8 位，低于 12 位门槛

        assertThrows(IllegalArgumentException::class.java) { manager.setup(tooShort) }

        assertFalse("被策略拒绝时不许留下任何状态", manager.isSetUp())
    }

    @Test
    fun `unlock before setup fails with a clear message`() {
        val thrown = assertThrows(CryptoException::class.java) { manager.unlock(password) }

        assertTrue(thrown.message!!.contains("还没有"))
    }

    @Test
    fun `unlock with a wrong password fails`() {
        manager.setup(password)

        assertThrows(CryptoException::class.java) { manager.unlock(newPassword) }
    }

    // ---- 换密码 ----

    @Test
    fun `changePassword keeps the same dek and swaps the password`() {
        manager.setup(password)

        val before = manager.unlock(password).bytes
        manager.changePassword(password, newPassword)
        val after = manager.unlock(newPassword).bytes

        assertArrayEquals("换密码只换包着 DEK 的锁，DEK 本身必须分毫不动", before, after)
        assertThrows("旧密码该失效了", CryptoException::class.java) { manager.unlock(password) }
    }

    @Test
    fun `changePassword enforces policy and leaves the old password working`() {
        manager.setup(password)

        assertThrows(IllegalArgumentException::class.java) {
            manager.changePassword(password, "short".toCharArray())
        }

        manager.unlock(password)   // 被拒绝后，原密文必须原封不动
    }

    // ---- 换机恢复 ----

    @Test
    fun `importFrom stores a foreign envelope when not set up`() {
        // 模拟"另一台设备导出的密文"
        val foreignDek = RecoveryKey.generateDek()
        val foreignText = RecoveryKey.wrap(foreignDek, password, iterations = 1_000).serialize()

        val recovered = manager.importFrom(foreignText, password)

        assertArrayEquals("恢复出来的必须是密文里那把 DEK", foreignDek.bytes, recovered.bytes)
        assertEquals("密文应原样落盘留存", foreignText, manager.exportText())
        assertArrayEquals(manager.unlock(password).bytes, recovered.bytes)
    }

    @Test
    fun `importFrom refuses when this device is already set up`() {
        manager.setup(password)
        val stored = manager.exportText()
        val foreignText = RecoveryKey.wrap(RecoveryKey.generateDek(), password, iterations = 1_000).serialize()

        assertThrows(IllegalStateException::class.java) { manager.importFrom(foreignText, password) }

        assertEquals("本机密文不得被外来文件覆盖", stored, manager.exportText())
    }

    @Test
    fun `importFrom with a wrong password writes nothing`() {
        val foreignText = RecoveryKey.wrap(RecoveryKey.generateDek(), password, iterations = 1_000).serialize()

        assertThrows(CryptoException::class.java) {
            manager.importFrom(foreignText, newPassword)
        }

        assertFalse("失败的导入不许留下半个状态", manager.isSetUp())
    }

    // ---- 生产默认值 ----

    @Test
    fun `default iterations come from RecoveryKey`() {
        // 这条故意用真默认值（慢）：确保"生产走 60 万"不是靠注释保证的
        val production = RecoveryKeyManager(InMemoryKeyBlob(), databaseExists = { false })

        val envelope = KeyEnvelope.parse(production.setup(password), KeyEnvelope.MAGIC_RECOVERY)

        assertEquals(RecoveryKey.DEFAULT_ITERATIONS, envelope.iterations)
    }
}
