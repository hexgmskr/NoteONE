package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyBlobStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 落盘层的 JVM 单测——**用真文件**（临时目录），不搞内存替身。
 *
 * 它值得这样测的原因：写入路径有"先写临时文件、再原子改名"的绕法，
 * 内存替身会把这段绕法整个跳过去；而这段绕法存在的意义（断电/崩溃不产生半截密文）
 * 恰恰是恢复路径的命根子。
 */
class KeyBlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(fileName: String = "recovery-key.txt") =
        KeyBlobStore(File(tmp.root, fileName))

    @Test
    fun `read returns null before anything is written`() {
        assertNull(store().read())
    }

    @Test
    fun `write then read round trips`() {
        val s = store()
        // 内容贴近真实信封：多行、中文注释、Base64 的 '=' 补位
        val text = "noteone-recovery-key\n# 备注：中文与 = 号\nsalt=AAECAwQFBgcICQoLDA0ODw==\n"

        s.write(text)

        assertEquals(text, s.read())
    }

    @Test
    fun `rename failure keeps old content and throws instead of overwriting`() {
        // 让 rename 必然失败：把目标位置占成**非空目录**。
        // 旧实现此时会退化成 file.writeText —— 非原子覆盖写（对目录还会抛另一种异常）。
        // 现在的约定：保持旧内容不动、清掉临时文件、抛出说清原因的 IOException。
        val dir = File(tmp.root, "blocked.txt")
        dir.mkdirs()
        File(dir, "occupant").writeText("占位")
        val s = KeyBlobStore(dir)

        val thrown = runCatching { s.write("new-content") }.exceptionOrNull()

        assertTrue("应抛出 IOException（而不是非原子覆盖或别的异常），实际=$thrown",
            thrown is java.io.IOException)
        assertTrue("错误信息应说清是原子改名失败：${thrown?.message}",
            thrown?.message?.contains("原子改名未成功") == true)
        assertTrue("目标（被占位目录）不该被动过", dir.isDirectory && File(dir, "occupant").readText() == "占位")
        assertFalse("临时文件应被清掉", File(tmp.root, "blocked.txt.tmp").exists())
    }

    @Test
    fun `write replaces previous content and leaves no temp file`() {
        val s = store()
        s.write("第一份")

        s.write("第二份")

        assertEquals("第二份", s.read())
        assertFalse(
            "改名之后临时文件不该残留",
            File(tmp.root, "recovery-key.txt.tmp").exists(),
        )
    }

    @Test
    fun `write creates missing parent directories`() {
        val nested = KeyBlobStore(File(tmp.root, "a/b/recovery-key.txt"))

        nested.write("内容")

        assertEquals("内容", nested.read())
    }

    @Test
    fun `clear removes the file`() {
        val s = store()
        s.write("内容")

        s.clear()

        assertNull("清除后必须读不到", s.read())
        assertFalse(File(tmp.root, "recovery-key.txt").exists())
    }

    @Test
    fun `clear on missing file is a no-op`() {
        // 不抛即通过（clear 会被"吊销恢复路径"之类的流程反复调用）
        store().clear()
    }
}
