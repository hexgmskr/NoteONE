package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.PasswordSeal
import io.github.hexgmskr.noteone.data.export.ExportJson
import io.github.hexgmskr.noteone.data.export.Exporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出两种形态（[Exporter]）的 JVM 单测。
 *
 * 加密形态的密码核对在 ViewModel 层（要真管家），这里测的是
 * 「包出来的东西能不能解回去、能不能藏住内容」这件密码学事实。
 */
class ExporterTest {

    private val password = "这是一个测试用的长密码".toCharArray()

    /** 低迭代数：单测不陪 PBKDF2 跑 60 万次（默认值本身由 RecoveryKeyTest 把关）。 */
    private val fastIterations = 100

    private val items = listOf(
        testItem(
            id = 1,
            content = "https://example.com/secret",
            createdAt = 1758000000000,
            tags = arrayOf("主题" to "萌宠"),
        ),
    )

    @Test
    fun `plaintext bytes are exactly the json utf8`() {
        val bytes = Exporter.plaintextBytes(items, TEST_EXPORTED_AT)

        assertEquals(ExportJson.build(items, TEST_EXPORTED_AT), bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `encrypted export is an export envelope and round trips to the same bytes`() {
        val text = Exporter.encryptedText(items, TEST_EXPORTED_AT, password, fastIterations)

        assertTrue("首行是导出用途的标识", text.startsWith(KeyEnvelope.MAGIC_EXPORT + "\n"))
        assertFalse("明文内容不得出现在文件文本里", text.contains("example.com"))

        val opened = PasswordSeal.open(
            KeyEnvelope.parse(text, KeyEnvelope.MAGIC_EXPORT),
            password,
            subject = "导出文件",
        )
        // 加密形态包住的字节必须和明文形态**逐字节一致**——两种导出一份数据
        assertArrayEquals(Exporter.plaintextBytes(items, TEST_EXPORTED_AT), opened)
    }

    @Test
    fun `encrypted export records the iterations it was given`() {
        val text = Exporter.encryptedText(items, TEST_EXPORTED_AT, password, fastIterations)

        assertEquals(
            fastIterations,
            KeyEnvelope.parse(text, KeyEnvelope.MAGIC_EXPORT).iterations,
        )
    }

    @Test
    fun `wrong password fails with a message naming the export file`() {
        val text = Exporter.encryptedText(items, TEST_EXPORTED_AT, password, fastIterations)

        val thrown = assertThrows(CryptoException::class.java) {
            PasswordSeal.open(
                KeyEnvelope.parse(text, KeyEnvelope.MAGIC_EXPORT),
                "错误的密码".toCharArray(),
                subject = "导出文件",
            )
        }
        assertTrue(thrown.message!!.contains("导出文件"))
    }

    @Test
    fun `empty export is still a valid envelope`() {
        val text = Exporter.encryptedText(emptyList(), TEST_EXPORTED_AT, password, fastIterations)

        val opened = PasswordSeal.open(
            KeyEnvelope.parse(text, KeyEnvelope.MAGIC_EXPORT),
            password,
        )
        assertTrue(opened.toString(Charsets.UTF_8).contains("\"items\": []"))
    }
}
