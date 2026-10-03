package io.github.hexgmskr.noteone

import android.security.keystore.UserNotAuthenticatedException
import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import io.github.hexgmskr.noteone.data.crypto.KeystoreKeyException
import io.github.hexgmskr.noteone.data.crypto.KeystoreKeyWrapper
import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import java.security.KeyStore
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 副本A（Keystore 硬件钥匙）的真机回归。
 *
 * **能自动测的边界**：钥匙生成、参数是否按要求、**没授权是不是真的用不了**、
 * 吊销是否干净、信封解析是否从严。**包→解全流程测不了**——它必须过真实
 * 指纹/人脸（auth-per-use），由真机手动验证覆盖。
 *
 * 用隔离的 Keystore 条目名（`..._test`）与内存落盘：绝不动 App 的真钥匙。
 */
class KeystoreKeyWrapperTest {

    private class InMemorySource : KeyBlobSource {
        var text: String? = null
        override fun read(): String? = text
        override fun write(text: String) { this.text = text }
        override fun clear() { text = null }
    }

    private val store = InMemorySource()
    private val wrapper = KeystoreKeyWrapper(store, keyAlias = TEST_ALIAS)

    /** 一份格式合法、内容无意义的信封（只为过解析、走到"要用钥匙"那一步）。 */
    private val fakeEnvelope = buildString {
        appendLine(KeystoreKeyWrapper.MAGIC)
        appendLine("version=1")
        appendLine("iv=AAAAAAAAAAAAAAAA")                       // 12 字节的 Base64
        appendLine("payload=${"QUJD".repeat(8)}")               // 任意非空
    }

    @Before
    fun setUp() {
        cleanUp()
    }

    @After
    fun tearDown() {
        cleanUp()
    }

    // ---- 生成与参数 ----

    @Test
    fun `fresh device has no key and no envelope`() {
        assertFalse(wrapper.isProvisioned())
        assertNull(keystoreKey())
    }

    @Test
    fun `ensureKeyExists creates the key and is idempotent`() {
        wrapper.ensureKeyExists()
        assertNotNull("钥匙应已生成", keystoreKey())

        wrapper.ensureKeyExists()   // 再来一次不该炸、也不该换钥匙

        assertNotNull(keystoreKey())
        // 「物理不可导出」不是纸面说法：Keystore 钥匙拿不到编码字节
        assertNull("Keystore 钥匙不该可导出（encoded 必须是 null）", keystoreKey()!!.encoded)
    }

    // ---- 没授权就是用不了（本测试的核心）----
    //
    // 注意断言的位置：**init 不会抛**，没授权也能 init 成功；
    // 异常在真正用钥匙时（doFinal）才抛——所以哨兵钉在 finish* 上。

    @Test
    fun `provision without authentication is rejected at the moment of use`() {
        wrapper.ensureKeyExists()
        val cipher = wrapper.beginEncrypt()   // 这一步允许成功（真机实测）

        assertUnauthenticatedFailure("没通过生物验证就用钥匙（加密）") {
            wrapper.finishProvision(RecoveryKey.generateDek(), cipher)
        }
    }

    @Test
    fun `unwrap without authentication is rejected at the moment of use`() {
        wrapper.ensureKeyExists()
        store.text = fakeEnvelope
        val cipher = wrapper.beginDecrypt()   // 同上，允许成功

        assertUnauthenticatedFailure("没通过生物验证就用钥匙（解密）") {
            wrapper.finishUnwrap(cipher)
        }
    }

    /**
     * 断言"这次用钥匙因为没验证而失败"。
     *
     * 不直接断言异常类型：JCE 会把底层异常包一层（真机实测 `doFinal` 抛的是
     * `IllegalBlockSizeException -> android.security.KeyStoreException`），
     * 所以查**整条异常链**，认两种形态：
     *  - `UserNotAuthenticatedException`（Keystore provider 直出的情况）
     *  - 消息里带 "auth" 的 `KeyStoreException`（硬件层直出的情况）
     * 失败时把链连同消息原样打出来，省得靠猜。
     */
    private fun assertUnauthenticatedFailure(what: String, block: () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull("$what：居然没抛异常（钥匙没被授权保护？）", thrown)

        val chain = generateSequence(thrown) { it.cause }.toList()
        val looksLikeAuthFailure = chain.any { it is UserNotAuthenticatedException } ||
            chain.any {
                it is android.security.KeyStoreException &&
                    it.message?.contains("auth", ignoreCase = true) == true
            }

        assertTrue(
            "$what：异常链应表明「未通过验证」。实际链：" +
                chain.joinToString(" -> ") { "${it.javaClass.name}: ${it.message}" },
            looksLikeAuthFailure,
        )
    }

    // ---- 信封解析从严 ----

    @Test
    fun `beginDecrypt without an envelope says so in plain words`() {
        wrapper.ensureKeyExists()

        val thrown = assertThrows(KeystoreKeyException::class.java) { wrapper.beginDecrypt() }
        assertTrue("提示要能给人看：${thrown.message}", thrown.message!!.contains("指纹"))
    }

    @Test
    fun `malformed envelope is rejected before touching the key`() {
        wrapper.ensureKeyExists()
        store.text = "这不是信封\nfoo=bar\n"

        assertThrows(KeystoreKeyException::class.java) { wrapper.beginDecrypt() }
    }

    @Test
    fun `envelope with unknown field is rejected`() {
        wrapper.ensureKeyExists()
        store.text = fakeEnvelope + "future_field=x\n"

        assertThrows(KeystoreKeyException::class.java) { wrapper.beginDecrypt() }
    }

    // ---- 吊销 ----

    @Test
    fun `revoke deletes both the envelope and the keystore entry`() {
        wrapper.ensureKeyExists()
        store.text = fakeEnvelope

        wrapper.revoke()

        assertFalse(wrapper.isProvisioned())
        assertNull("Keystore 条目也该没了", keystoreKey())
    }

    @Test
    fun `revoke on a clean device is a no-op`() {
        wrapper.revoke()   // 不抛即通过

        assertFalse(wrapper.isProvisioned())
    }

    // ---- 高安全模式：两把钥匙互相独立（2026-10-03）----

    @Test
    fun `high security key lives under a separate alias`() {
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY)

        assertTrue(wrapper.hasKey(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY))
        assertFalse(
            "高安全模式的钥匙不该占用默认别名（否则切换会覆盖在用的那把）",
            wrapper.hasKey(KeystoreKeyWrapper.KeyMode.NORMAL),
        )
    }

    @Test
    fun `deleteKey removes only the named mode`() {
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.NORMAL)
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY)

        wrapper.deleteKey(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY)

        assertFalse(wrapper.hasKey(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY))
        assertTrue("另一把不受影响", wrapper.hasKey(KeystoreKeyWrapper.KeyMode.NORMAL))
    }

    @Test
    fun `revoke also clears the high security key`() {
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.NORMAL)
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY)
        store.text = fakeEnvelope

        wrapper.revoke()

        assertFalse(wrapper.hasKey(KeystoreKeyWrapper.KeyMode.NORMAL))
        assertFalse(
            "高安全模式的钥匙也要清掉，不留没人用的 auth 钥匙",
            wrapper.hasKey(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY),
        )
        assertFalse(wrapper.isProvisioned())
    }

    // ---- 作废探测（2026-10-03 真机踩过：getKey 拿得到、但 init 会抛）----

    @Test
    fun `freshly created keys are not invalidated`() {
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.NORMAL)
        wrapper.ensureKeyExists(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY)

        assertFalse(wrapper.isKeyInvalidated(KeystoreKeyWrapper.KeyMode.NORMAL))
        assertFalse(wrapper.isKeyInvalidated(KeystoreKeyWrapper.KeyMode.HIGH_SECURITY))
        assertFalse(wrapper.keyUnusable(KeystoreKeyWrapper.KeyMode.NORMAL))
    }

    @Test
    fun `keyUnusable is true when the key is missing`() {
        assertTrue("没有钥匙 = 不可用", wrapper.keyUnusable(KeystoreKeyWrapper.KeyMode.NORMAL))
        assertFalse("'缺失'不算'作废'——两者话术不同", wrapper.isKeyInvalidated(KeystoreKeyWrapper.KeyMode.NORMAL))
    }

    // ---- 作废 → 删 → 重造（2026-10-04 补：此前只有负例）----

    @Test
    fun `ensureKeyExists deletes a dead key and recreates it`() {
        // 真作废态造不出来（要现场录入新指纹），用可注入探测模拟"这把已作废"。
        // 这正是 2026-10-03 在真机上翻过车的那条分支：作废的钥匙对象仍在
        // Keystore 里，只看"在不在"就会跳过重建、继续用那把死钥匙——
        // 表现为"重新启用指纹解锁"也失败。
        var invalidated = false
        val w = KeystoreKeyWrapper(store, keyAlias = TEST_ALIAS, invalidatedProbe = { invalidated })

        w.ensureKeyExists(KeystoreKeyWrapper.KeyMode.NORMAL)
        assertTrue("前提：先真造出一把钥匙", w.hasKey(KeystoreKeyWrapper.KeyMode.NORMAL))
        val before = creationDate(KeystoreKeyWrapper.KeyMode.NORMAL)
        assertNotNull("前提：拿得到创建时间（用它当'这一把'的身份证）", before)

        Thread.sleep(30)                                          // 保证两次生成的毫秒时间戳不同
        invalidated = true                                        // 模拟"系统已作废这把"
        w.ensureKeyExists(KeystoreKeyWrapper.KeyMode.NORMAL)      // 应：先删死钥匙、再重造
        invalidated = false                                       // 重造之后不再是作废态

        assertTrue("重造后应有一把钥匙", w.hasKey(KeystoreKeyWrapper.KeyMode.NORMAL))
        val after = creationDate(KeystoreKeyWrapper.KeyMode.NORMAL)
        assertNotNull("重造后应拿得到创建时间", after)
        assertNotEquals("必须是重新生成的一把（创建时间应不同）", before, after)
    }

    /**
     * 别名对应钥匙的创建时间——「这一把」的身份证。
     *
     * 不用证书序列号：AndroidKeyStore 对 AES 钥匙不保证有证书（实测 getCertificate
     * 返回 null）；getKey 每次都返回新对象、getEncoded 又是 null，也没有别的身份可比。
     * getCreationDate 在 API 33 起被标 deprecated，但仍是可用的公开 API。
     */
    @Suppress("DEPRECATION")
    private fun creationDate(mode: KeystoreKeyWrapper.KeyMode): java.util.Date? = runCatching {
        val alias = if (mode == KeystoreKeyWrapper.KeyMode.NORMAL) TEST_ALIAS else "${TEST_ALIAS}_hs"
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCreationDate(alias)
    }.getOrNull()

    // ---- 内部 ----

    private fun keystoreKey(): SecretKey? = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(TEST_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun cleanUp() {
        store.clear()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("${TEST_ALIAS}_hs")
        }
    }

    private companion object {
        /** 隔离条目名：绝不使用生产名 `notes_app_dek_wrapper`。 */
        const val TEST_ALIAS = "notes_app_dek_wrapper_test"
    }
}
