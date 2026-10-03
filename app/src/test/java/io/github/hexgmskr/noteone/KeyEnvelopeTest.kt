package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码信封**文件格式**的 JVM 单测（副本B 与加密导出共用的格式）。
 *
 * 这个文件是用户要亲手保管的东西：可能被复制、被网盘同步搞出乱码、被误编辑。
 * 解析从严是对的策略——宁可明确报错，不要猜。
 * 这里逐条验证"篡改/损坏必须被拒绝"，以及"自己写出去的东西自己能读回来"。
 */
class KeyEnvelopeTest {

    /** 一个各字段都合法的信封（内容无所谓，只测格式）。 */
    private fun validEnvelope() = KeyEnvelope(
        magic = KeyEnvelope.MAGIC_RECOVERY,
        version = KeyEnvelope.VERSION,
        kdf = KeyEnvelope.KDF_NAME,
        iterations = 1_000,
        salt = ByteArray(KeyEnvelope.SALT_BYTES) { it.toByte() },
        cipher = KeyEnvelope.CIPHER_NAME,
        nonce = ByteArray(KeyEnvelope.NONCE_BYTES) { it.toByte() },
        payload = ByteArray(48) { it.toByte() },
    )

    private fun validText(): String = validEnvelope().serialize()

    /** 本类其余用例测的都是恢复密钥用途；导出用途的用例在文件末尾。 */
    private fun parseRecovery(text: String) = KeyEnvelope.parse(text, KeyEnvelope.MAGIC_RECOVERY)

    private fun dropLine(text: String, key: String): String =
        text.lines().filterNot { it.startsWith("$key=") }.joinToString("\n") + "\n"

    private fun setValue(text: String, key: String, value: String): String =
        text.lines().joinToString("\n") { line ->
            if (line.startsWith("$key=")) "$key=$value" else line
        } + "\n"

    // ---- 正常路径 ----

    @Test
    fun `serialize then parse round trips all fields`() {
        val original = validEnvelope()

        val parsed = parseRecovery(original.serialize())

        assertEquals(original.version, parsed.version)
        assertEquals(original.kdf, parsed.kdf)
        assertEquals(original.iterations, parsed.iterations)
        assertEquals(original.cipher, parsed.cipher)
        assertArrayEquals(original.salt, parsed.salt)
        assertArrayEquals(original.nonce, parsed.nonce)
        assertArrayEquals(original.payload, parsed.payload)
    }

    @Test
    fun `serialize is deterministic and starts with the magic line`() {
        val text = validText()

        assertEquals("同内容两次序列化必须逐字节一致", text, validText())
        assertTrue("首行必须是文件标识", text.startsWith(KeyEnvelope.MAGIC_RECOVERY + "\n"))
    }

    @Test
    fun `parse tolerates blank lines and comments`() {
        // 用户可能在里面加备注、或复制粘贴时带进空行
        val text = validText().replace(
            KeyEnvelope.MAGIC_RECOVERY + "\n",
            KeyEnvelope.MAGIC_RECOVERY + "\n\n# 我自己加的备注：这把是给新手机的\n",
        )

        val parsed = parseRecovery(text)

        assertEquals(validEnvelope().iterations, parsed.iterations)
    }

    // ---- 失败路径：全部必须拒绝 ----

    @Test
    fun `rejects a file that is not a recovery key`() {
        assertThrows(CryptoException::class.java) {
            parseRecovery("这是一份普通文本\nhello=world\n")
        }
    }

    @Test
    fun `rejects missing fields`() {
        val thrown = assertThrows(CryptoException::class.java) {
            parseRecovery(dropLine(validText(), "salt"))
        }
        assertTrue("报错要指向缺的字段", thrown.message!!.contains("salt"))
    }

    @Test
    fun `rejects duplicate fields`() {
        val text = validText() + "version=1\n"

        assertThrows(CryptoException::class.java) { parseRecovery(text) }
    }

    @Test
    fun `rejects unknown fields`() {
        // 也可能是"未来版本写的文件被老 App 读到"——同样要明确拒绝，不许猜
        val text = validText() + "future_field=whatever\n"

        assertThrows(CryptoException::class.java) { parseRecovery(text) }
    }

    @Test
    fun `rejects malformed base64`() {
        val text = validText().replace("salt=", "salt=!!!not-base64!!!")

        assertThrows(CryptoException::class.java) { parseRecovery(text) }
    }

    @Test
    fun `rejects a version this app does not know`() {
        val text = validText().replace("version=1", "version=2")

        val thrown = assertThrows(CryptoException::class.java) { parseRecovery(text) }
        assertTrue("要提示可能是 App 需要更新", thrown.message!!.contains("版本"))
    }

    @Test
    fun `rejects absurd iteration counts`() {
        val text = validText().replace("iterations=1000", "iterations=999999999")

        assertThrows(CryptoException::class.java) { parseRecovery(text) }
    }

    @Test
    fun `rejects wrong salt length`() {
        // 合法的 Base64，但解出来只有 8 字节
        val text = validText().replace("salt=", "salt=AAAAAAAAAAA=")

        assertThrows(CryptoException::class.java) { parseRecovery(text) }
    }

    @Test
    fun `rejects empty values`() {
        val text = setValue(validText(), "payload", "")

        val thrown = assertThrows(CryptoException::class.java) { parseRecovery(text) }
        assertTrue("报错要指向空的字段", thrown.message!!.contains("payload"))
    }

    // ---- 用途标识（2026-10-01：信封被加密导出复用后新增）----

    @Test
    fun `export envelope serializes with the export magic and round trips`() {
        val original = KeyEnvelope(
            magic = KeyEnvelope.MAGIC_EXPORT,
            version = KeyEnvelope.VERSION,
            kdf = KeyEnvelope.KDF_NAME,
            iterations = 1_000,
            salt = ByteArray(KeyEnvelope.SALT_BYTES) { it.toByte() },
            cipher = KeyEnvelope.CIPHER_NAME,
            nonce = ByteArray(KeyEnvelope.NONCE_BYTES) { it.toByte() },
            payload = ByteArray(48) { it.toByte() },
        )

        val text = original.serialize()
        assertTrue("首行是导出用途的标识", text.startsWith(KeyEnvelope.MAGIC_EXPORT + "\n"))

        val parsed = KeyEnvelope.parse(text, KeyEnvelope.MAGIC_EXPORT)
        assertEquals(KeyEnvelope.MAGIC_EXPORT, parsed.magic)
        assertArrayEquals(original.payload, parsed.payload)
    }

    @Test
    fun `parse names the other purpose when handed the wrong kind of file`() {
        // 用户把恢复密钥文件选进"导出"、或反过来——报错要直接点名是哪一种，
        // 而不是让用户对着"解密失败"猜（这是标识行存在的意义）
        val thrown1 = assertThrows(CryptoException::class.java) {
            KeyEnvelope.parse(validText(), KeyEnvelope.MAGIC_EXPORT)
        }
        assertTrue("要指出手上是恢复密钥文件", thrown1.message!!.contains("恢复密钥"))

        val exportFile = validText()
            .replaceFirst(KeyEnvelope.MAGIC_RECOVERY, KeyEnvelope.MAGIC_EXPORT)
        val thrown2 = assertThrows(CryptoException::class.java) {
            KeyEnvelope.parse(exportFile, KeyEnvelope.MAGIC_RECOVERY)
        }
        assertTrue("要指出手上是记录导出文件", thrown2.message!!.contains("记录导出"))
    }
}
