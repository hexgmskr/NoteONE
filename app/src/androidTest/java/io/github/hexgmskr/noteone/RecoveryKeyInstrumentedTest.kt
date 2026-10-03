package io.github.hexgmskr.noteone

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 恢复密钥模块在**真机**上的行为验证。
 *
 * JVM 单测证明的是电脑上的密码学实现；安卓用的是另一套加密提供方
 * （Conscrypt），算法名相同不代表行为相同——"能在手机上跑"只有真机能证。
 *
 * 顺带量一个必须知道的数字：**60 万次 PBKDF2 在小米 14 上要多久**。
 * 这个常数直接决定每次冷启动解锁的等待时间（3-2 之后主密码是解锁路径的一部分），
 * 结果用 `adb logcat -s RecoveryKeyBench` 查看。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKeyInstrumentedTest {

    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun wrapAndUnwrapWorksOnAndroid() {
        val dek = RecoveryKey.generateDek()

        val envelope = RecoveryKey.wrap(dek, password, iterations = 1_000)   // 低迭代，只为快
        val recovered = RecoveryKey.unwrap(envelope, password)

        assertArrayEquals("真机上包→解必须还原同一把 DEK", dek.bytes, recovered.bytes)
    }

    @Test
    fun wrongPasswordIsRejectedOnAndroid() {
        val envelope = RecoveryKey.wrap(RecoveryKey.generateDek(), password, iterations = 1_000)

        assertThrows(CryptoException::class.java) {
            RecoveryKey.unwrap(envelope, "not the password".toCharArray())
        }
    }

    @Test
    fun envelopeTextRoundTripsOnAndroid() {
        val envelope = RecoveryKey.wrap(RecoveryKey.generateDek(), password, iterations = 1_000)

        val parsed = KeyEnvelope.parse(envelope.serialize(), KeyEnvelope.MAGIC_RECOVERY)

        assertEquals(envelope.iterations, parsed.iterations)
        assertArrayEquals(envelope.salt, parsed.salt)
        assertArrayEquals(envelope.payload, parsed.payload)

        // 解析回来的信封必须还能解开
        RecoveryKey.unwrap(parsed, password)
    }

    @Test
    fun defaultIterationsTimingOnThisDevice() {
        val dek = RecoveryKey.generateDek()

        val elapsedMs = measure {
            val envelope = RecoveryKey.wrap(dek, password)     // 用默认迭代数
            RecoveryKey.unwrap(envelope, password)
        }
        Log.i(TAG, "默认迭代数(${RecoveryKey.DEFAULT_ITERATIONS}) 一次包+解耗时 ${elapsedMs}ms")

        // 上界只防"慢到不可用"；实际值看日志，用来决定要不要调迭代数
        assertTrue("解锁链路太慢（${elapsedMs}ms），需要下调迭代数或换方案", elapsedMs < 10_000)
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val TAG = "RecoveryKeyBench"
    }
}
