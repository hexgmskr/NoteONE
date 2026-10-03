package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.db.DbKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副本B（恢复密钥）的 JVM 单测。
 *
 * 未知数是「同一把 DEK 能不能包了再解回来」与「解不开的情况是不是都挡得住」：
 * 错密码、密文被改、参数被改，都必须失败，**不能解出一把错的钥匙**。
 *
 * 本模块刻意不碰任何 Android API，就是为了能在这里（电脑上）全测完。
 * 测试统一用低迭代数（[fastWrap]）跑，安全参数本身另有用例把关。
 */
class RecoveryKeyTest {

    private val password = "correct horse battery staple".toCharArray()

    /** 测试专用低迭代数：PBKDF2 在这里只是"慢"，安全性由 [RecoveryKey.DEFAULT_ITERATIONS] 把关。 */
    private companion object {
        const val FAST_ITERATIONS = 1_000

        /** 改一个字节的副本，只用于测篡改。 */
        fun flipOneByte(bytes: ByteArray): ByteArray =
            bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
    }

    private fun fastWrap(dek: DbKey) =
        RecoveryKey.wrap(dek, password, iterations = FAST_ITERATIONS)

    /** 基于一个正常信封，替换其中某几个字段，用来构造"被改过"的信封。 */
    private fun envelopeWith(
        base: KeyEnvelope,
        salt: ByteArray = base.salt,
        iterations: Int = base.iterations,
        nonce: ByteArray = base.nonce,
        payload: ByteArray = base.payload,
    ) = KeyEnvelope(base.magic, base.version, base.kdf, iterations, salt, base.cipher, nonce, payload)

    // ---- DEK 生成 ----

    @Test
    fun `generateDek produces 32 random bytes`() {
        val a = RecoveryKey.generateDek()
        val b = RecoveryKey.generateDek()

        assertEquals(32, a.bytes.size)
        assertNotEquals("两次生成不该相同（随机源要真的随机）", a.bytes.toList(), b.bytes.toList())
    }

    // ---- 包装与解包 ----

    @Test
    fun `wrap then unwrap returns the same dek`() {
        val dek = RecoveryKey.generateDek()

        val recovered = RecoveryKey.unwrap(fastWrap(dek), password)

        assertArrayEquals(dek.bytes, recovered.bytes)
    }

    @Test
    fun `same dek and password wrap to different ciphertext each time`() {
        val dek = RecoveryKey.generateDek()

        val first = fastWrap(dek)
        val second = fastWrap(dek)

        assertNotEquals("盐必须每次新取", first.salt.toList(), second.salt.toList())
        assertNotEquals("nonce 必须每次新取", first.nonce.toList(), second.nonce.toList())
        assertNotEquals("密文因此必然不同", first.payload.toList(), second.payload.toList())
    }

    @Test
    fun `unwrap uses the iterations recorded in the envelope`() {
        // 用 1000 次包、且解包也成功，就证明解包用的是信封里记的数——
        // 若错用当前默认值（60 万），派生出的钥匙不同，必然解不开。
        val envelope = fastWrap(RecoveryKey.generateDek())
        assertEquals(FAST_ITERATIONS, envelope.iterations)

        RecoveryKey.unwrap(envelope, password)   // 不抛即通过
    }

    @Test
    fun `default iterations meet the owasp floor`() {
        assertTrue(
            "PBKDF2-HMAC-SHA256 的默认迭代数不得低于 OWASP 建议的 60 万；" +
                "调低必须先想清楚并记录理由（HANDOFF）",
            RecoveryKey.DEFAULT_ITERATIONS >= 600_000,
        )
    }

    // ---- 失败路径：全部必须挡住 ----

    @Test
    fun `wrong password is rejected`() {
        val envelope = fastWrap(RecoveryKey.generateDek())

        val thrown = assertThrows(CryptoException::class.java) {
            RecoveryKey.unwrap(envelope, "wrong password".toCharArray())
        }
        assertNotNull("提示要能给人看", thrown.message)
    }

    @Test
    fun `tampered payload is rejected`() {
        val base = fastWrap(RecoveryKey.generateDek())
        val broken = envelopeWith(base, payload = flipOneByte(base.payload))

        assertThrows("被篡改的密文不该解开（GCM 认证标签必须拦住）", CryptoException::class.java) {
            RecoveryKey.unwrap(broken, password)
        }
    }

    @Test
    fun `tampered salt is rejected`() {
        val base = fastWrap(RecoveryKey.generateDek())
        val broken = envelopeWith(base, salt = flipOneByte(base.salt))

        assertThrows("盐被改 ⇒ 派生出的钥匙不同 ⇒ 不该解开", CryptoException::class.java) {
            RecoveryKey.unwrap(broken, password)
        }
    }

    @Test
    fun `tampered iterations is rejected`() {
        val base = fastWrap(RecoveryKey.generateDek())
        val broken = envelopeWith(base, iterations = base.iterations + 1)

        assertThrows("迭代数被改 ⇒ 派生出的钥匙不同 ⇒ 不该解开", CryptoException::class.java) {
            RecoveryKey.unwrap(broken, password)
        }
    }

    @Test
    fun `tampered nonce is rejected`() {
        val base = fastWrap(RecoveryKey.generateDek())
        val broken = envelopeWith(base, nonce = flipOneByte(base.nonce))

        assertThrows("nonce 被改 ⇒ GCM 认证失败", CryptoException::class.java) {
            RecoveryKey.unwrap(broken, password)
        }
    }
}
