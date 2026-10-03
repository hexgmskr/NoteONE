package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.PasswordSeal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用密码信封原语（[PasswordSeal]）的 JVM 单测。
 *
 * 恢复密钥与加密导出共用这套加解密。这里测「包进-解出」的通用性质；
 * DEK 路径特有的东西（32 字节长度校验等）在 `RecoveryKeyTest`。
 */
class PasswordSealTest {

    private val password = "correct horse battery staple".toCharArray()

    /** 低迭代数：单测不陪 PBKDF2 跑 60 万次。默认值的验证由 RecoveryKeyTest 把关。 */
    private val iterations = 100

    @Test
    fun `seal then open round trips arbitrary bytes`() {
        // 长度远超 DEK 的 32 字节、覆盖各种字节值——这正是导出要包的东西
        val plain = ByteArray(10_000) { (it % 251).toByte() }

        val envelope = PasswordSeal.seal(plain, password, KeyEnvelope.MAGIC_EXPORT, iterations)

        assertArrayEquals(plain, PasswordSeal.open(envelope, password))
    }

    @Test
    fun `seal then open round trips utf8 json text`() {
        val json = """{"items":[{"content":"中文🙂 \"引号\" \\ 反斜杠"}]}"""
        val plain = json.toByteArray(Charsets.UTF_8)

        val envelope = PasswordSeal.seal(plain, password, KeyEnvelope.MAGIC_EXPORT, iterations)

        assertEquals(json, PasswordSeal.open(envelope, password).toString(Charsets.UTF_8))
    }

    @Test
    fun `wrong password is rejected with a message naming the file`() {
        val envelope = PasswordSeal.seal(ByteArray(64), password, KeyEnvelope.MAGIC_EXPORT, iterations)

        val thrown = assertThrows(CryptoException::class.java) {
            PasswordSeal.open(envelope, "wrong password".toCharArray(), subject = "导出文件")
        }
        assertTrue("报错要指出是哪个文件", thrown.message!!.contains("导出文件"))
    }

    @Test
    fun `tampered payload is rejected`() {
        val envelope = PasswordSeal.seal(ByteArray(64), password, KeyEnvelope.MAGIC_EXPORT, iterations)
        envelope.payload[0] = (envelope.payload[0] + 1).toByte()

        assertThrows(CryptoException::class.java) { PasswordSeal.open(envelope, password) }
    }

    @Test
    fun `same content sealed twice produces different ciphertext`() {
        // 每次都要取新 salt/nonce：密文重复会泄露"两次导出的内容相同"这件事
        val a = PasswordSeal.seal("same".toByteArray(), password, KeyEnvelope.MAGIC_EXPORT, iterations)
        val b = PasswordSeal.seal("same".toByteArray(), password, KeyEnvelope.MAGIC_EXPORT, iterations)

        assertFalse("salt 必须每次不同", a.salt.contentEquals(b.salt))
        assertFalse("nonce 必须每次不同", a.nonce.contentEquals(b.nonce))
        assertFalse("密文必须每次不同", a.payload.contentEquals(b.payload))
    }

    @Test
    fun `seal records the magic and parameters it was given`() {
        val envelope = PasswordSeal.seal(ByteArray(8), password, KeyEnvelope.MAGIC_EXPORT, iterations)

        assertEquals(KeyEnvelope.MAGIC_EXPORT, envelope.magic)
        assertEquals(iterations, envelope.iterations)
        assertEquals(KeyEnvelope.KDF_NAME, envelope.kdf)
        assertEquals(KeyEnvelope.CIPHER_NAME, envelope.cipher)
        assertEquals(KeyEnvelope.VERSION, envelope.version)
    }
}
