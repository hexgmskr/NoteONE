package io.github.hexgmskr.noteone.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import io.github.hexgmskr.noteone.data.db.DbKey
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 副本A 路径上的失败。message 是能直接展示给用户的一句话。 */
class KeystoreKeyException(message: String) : Exception(message)

/**
 * 副本A（spec 4.1）：**Keystore 硬件密钥**包住 DEK —— 日常解锁路径。
 *
 * 两把锁的分工：副本B（主密码）是"换手机/救急"的路，副本A 是"每天用"的路。
 * Keystore 密钥**物理不可导出**，手机被拿走也解不出 DEK——前提是锁屏没被攻破。
 *
 * ## 为什么要拆成 begin/finish 两段
 * 钥匙设了 `setUserAuthenticationRequired(true)`（auth-per-use）：**加解密都需要先过
 * 生物验证**。而 BiometricPrompt 的用法是"先把 Cipher 放进 CryptoObject，再弹窗，
 * 验证通过后拿回同一个 Cipher 继续用"——所以本类的形状就是
 * [beginEncrypt]/[beginDecrypt] 先取一个待授权的 Cipher，授权后 [finishProvision]/[finishUnwrap]。
 *
 * **注意授权检查发生在哪一步**（真机实测，别想当然）：`Cipher.init` **不会**抛
 * `UserNotAuthenticatedException`——没授权也能 init 成功；异常在真正用钥匙的那一刻
 * （`doFinal`）才抛。所以"没授权用不了"的哨兵用例断言在 finish* 上，
 * 而且 begin/finish 之间夹着的那个生物验证弹窗，正好落在 init 之后、doFinal 之前。
 *
 * ## 参数按 spec 4.2 锁定
 * 默认模式 [KeyMode.NORMAL]：`setInvalidatedByBiometricEnrollment(false)`——
 * **换/加指纹不该锁死数据**（CLAUDE.md 红线）。
 * 另外显式定了 `setKeySize(256)`：spec 的片段没写这一项，这里取 256 与副本B 的
 * AES-256-GCM 对齐（硬件 AES 下性能差异可忽略）。
 *
 * ## 高安全模式（2026-10-03 落地）
 *
 * **这个参数只在造钥匙那一刻生效，造完改不了**——所以"切换模式"＝换一把新钥匙 +
 * 把同一把 DEK 重包一遍（闭环规则见 CLAUDE.md「加密」一节）。两个模式各占一个
 * Keystore 别名（[KeyMode]），切换时：新别名的钥匙建好 → 新信封包好并**验证可解**
 * → 才原子替换落盘 → 再清理旧别名的钥匙。任何一步失败/取消都不动现有副本A。
 *
 * 哪个别名是"当前"的？**由 [HighSecurityModeSource]（prefs）决定**，
 * 切换流程负责让它和落盘的信封保持一致（先写文件、再写偏好；偏好写失败会把
 * 旧信封写回去，见 `AppContainer` 的切换实现）。
 *
 * ## 无授权自动化测试的边界
 * 全流程（包→解）必须在真实指纹/人脸下走，仪器测试测不了；仪器测试能测的是
 * "钥匙确实生成了""没授权确实用不了"。全流程由真机手动验证覆盖。
 */
/**
 * [KeystoreKeyWrapper] 的行为接口（2026-10-04 为可测性抽出）。
 *
 * 为什么需要它：auth-per-use 钥匙的 `doFinal` **必须有真指纹**，自动化里跑不了
 * ——"高安全模式四条闭环"的编排逻辑此前完全无法被测试（审计 #1 测试缺口）。
 * 抽出口子后，测试可以拿假实现把闭环的正常与失败分支全走一遍；真实现
 * （真 Keystore）的行为仍由 KeystoreKeyWrapperTest 的真机用例守着。
 *
 * 签名里出现 [KeystoreKeyWrapper.KeyMode] 是有意的：那两个模式是 Keystore
 * 钥匙的属性，不搬出来，免得为一次抽取动 60+ 处引用。
 * 默认参数值只写在接口上（Kotlin 规则：override 不许带默认值，调用方继承）。
 */
interface KeyWrapper {
    fun isProvisioned(): Boolean
    fun keyUnusable(mode: KeystoreKeyWrapper.KeyMode): Boolean
    fun ensureKeyExists(mode: KeystoreKeyWrapper.KeyMode = KeystoreKeyWrapper.KeyMode.NORMAL)
    fun deleteKey(mode: KeystoreKeyWrapper.KeyMode)
    fun beginEncrypt(mode: KeystoreKeyWrapper.KeyMode = KeystoreKeyWrapper.KeyMode.NORMAL): javax.crypto.Cipher
    fun beginDecrypt(mode: KeystoreKeyWrapper.KeyMode = KeystoreKeyWrapper.KeyMode.NORMAL): javax.crypto.Cipher
    fun beginDecrypt(mode: KeystoreKeyWrapper.KeyMode, envelopeText: String): javax.crypto.Cipher
    fun currentEnvelopeText(): String?
    fun finishProvision(dek: io.github.hexgmskr.noteone.data.db.DbKey, cipher: javax.crypto.Cipher)
    fun finishUnwrap(cipher: javax.crypto.Cipher): io.github.hexgmskr.noteone.data.db.DbKey
    fun finishUnwrap(cipher: javax.crypto.Cipher, envelopeText: String): io.github.hexgmskr.noteone.data.db.DbKey
    fun wrapEnvelopeText(dek: io.github.hexgmskr.noteone.data.db.DbKey, cipher: javax.crypto.Cipher): String
    fun writeEnvelopeText(text: String)
    fun revoke()
}

class KeystoreKeyWrapper(
    private val store: KeyBlobSource,
    /**
     * Keystore 条目名的前缀。默认生产名；测试注入隔离名，免得动到 App 的真钥匙。
     * 高安全模式的别名在前缀后加 `_hs`（见 [aliasOf]）。
     */
    private val keyAlias: String = KEY_ALIAS,
    /**
     * 「钥匙是否已作废」的探测口。默认走真实探测（见文件顶部的
     * [probeKeyInvalidatedByInit]）。参数化的唯一用途是**测试**：真作废态需要
     * 现场录入新指纹，自动化造不出来（见 ensureKeyExists 的注释）；注入假探测后，
     * 仪器测试可以在真 Keystore 上把"作废 → 先删 → 重造"这条分支走通——
     * 它曾在真机上翻过车（重建时继续用死钥匙）。
     */
    private val invalidatedProbe: (java.security.Key) -> Boolean = Companion::probeKeyInvalidatedByInit,
) : KeyWrapper {

    /** 副本A 的两把钥匙：模式不同，别名不同，参数不同。 */
    enum class KeyMode {
        /** 默认：录入新指纹**不**使钥匙失效（spec 4.2 / CLAUDE.md 红线）。 */
        NORMAL,

        /** 高安全（用户可选开启）：录入新指纹即作废这把钥匙。 */
        HIGH_SECURITY,
    }

    /** 模式对应的 Keystore 别名。 */
    fun aliasOf(mode: KeyMode): String =
        if (mode == KeyMode.NORMAL) keyAlias else "${keyAlias}_hs"

    /** 本机是否已经启用了副本A（磁盘上有信封）。 */
    override fun isProvisioned(): Boolean = store.read() != null

    /** 指定模式的钥匙在不在 Keystore 里。 */
    fun hasKey(mode: KeyMode): Boolean = loadKeyOrNull(mode) != null

    /**
     * 指定模式的钥匙是否**已被系统作废**（高安全模式下录入新指纹）。
     *
     * **不能只看 `getKey()` 返不返回 null**（2026-10-03 真机实测）：
     * 作废后钥匙对象**仍在** Keystore 里，`getKey` 照样拿得到；
     * 但它已永久不可用——任何 `Cipher.init` 会抛
     * `KeyPermanentlyInvalidatedException`。所以探测方式就是"拿它 init 一次"。
     *
     * **init 不需要指纹**（授权检查在 doFinal，阶段 3-3 真机实测过），
     * 因此这个探测可以随时调，不会弹窗。
     */
    fun isKeyInvalidated(mode: KeyMode): Boolean {
        val key = loadKeyOrNull(mode) ?: return false   // 钥匙不存在 ≠ 作废
        return invalidatedProbe(key)
    }

    /**
     * 指定模式的钥匙是否**不可用**（不存在、或被系统作废）——
     * 界面的"副本A 已失效"判断用它：信封在 + 钥匙不可用 = 需要重新启用。
     */
    override fun keyUnusable(mode: KeyMode): Boolean {
        val key = loadKeyOrNull(mode) ?: return true
        return invalidatedProbe(key)
    }

    /**
     * 确保指定模式的钥匙可用；已有**且未作废**则什么都不做（幂等）。
     *
     * **作废的钥匙必须删掉重造**（2026-10-03 修）：被作废的钥匙对象还在
     * Keystore 里，若只看"在不在"就跳过，重建时会继续用那把死钥匙，
     * 表现为"重新启用指纹解锁"也失败（真机上就是这么暴露的）。
     *
     * 生成不需要用户授权（只有"用"才要），所以这一步可以在任何时机调。
     */
    override fun ensureKeyExists(mode: KeyMode) {
        val existing = loadKeyOrNull(mode)
        if (existing != null && !invalidatedProbe(existing)) return
        if (existing != null) deleteKey(mode)   // 死钥匙占着别名，先清掉

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                aliasOf(mode),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(true)
                // 默认模式：换/加指纹不使钥匙失效（CLAUDE.md 红线，spec 4.2 已锁定）。
                // 高安全模式：反过来——录入新指纹即作废（用户显式选择，参数只在造钥匙时生效）。
                .setInvalidatedByBiometricEnrollment(mode == KeyMode.HIGH_SECURITY)
                .build(),
        )
        generator.generateKey()
    }

    /** 删掉指定模式的钥匙（切换完成后清理旧钥匙、或取消时清理多建的钥匙）。 */
    override fun deleteKey(mode: KeyMode) {
        runCatching { keystore().deleteEntry(aliasOf(mode)) }
    }

    /**
     * 取一个**待授权**的加密 Cipher，塞给 BiometricPrompt 的 CryptoObject。
     * 授权通过后把它交给 [finishProvision]。
     */
    override fun beginEncrypt(mode: KeyMode): Cipher =
        Cipher.getInstance(TRANSFORM).apply {
            initOrExplain { init(Cipher.ENCRYPT_MODE, requireKey(mode)) }
        }

    /**
     * 取一个**待授权**的解密 Cipher。IV 取自信封。
     * 授权通过后把它交给 [finishUnwrap]。
     */
    override fun beginDecrypt(mode: KeyMode): Cipher =
        beginDecrypt(
            mode,
            store.read() ?: throw KeystoreKeyException("还没有启用指纹解锁（本机没有副本A）"),
        )

    /**
     * 待授权解密 Cipher（指定信封文本）。
     *
     * 给切换流程的"校验还没落盘的新信封"用——IV 必须来自那份**新**信封，
     * 不能读磁盘上的旧的。
     */
    override fun beginDecrypt(mode: KeyMode, envelopeText: String): Cipher {
        val envelope = parseEnvelope(envelopeText)
        return Cipher.getInstance(TRANSFORM).apply {
            initOrExplain {
                init(Cipher.DECRYPT_MODE, requireKey(mode), GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
            }
        }
    }

    /** 磁盘上当前的信封文本（切换流程留底回滚用）。 */
    override fun currentEnvelopeText(): String? = store.read()

    /** 授权后的落盘：用已授权的 [cipher] 包住 DEK，写成信封并原子落盘。 */
    override fun finishProvision(dek: DbKey, cipher: Cipher) {
        store.write(wrapEnvelopeText(dek, cipher))
    }

    /** 授权后的解包：用已授权的 [cipher] 解出 DEK（信封取磁盘上的）。 */
    override fun finishUnwrap(cipher: Cipher): DbKey =
        finishUnwrap(
            cipher,
            store.read() ?: throw KeystoreKeyException("还没有启用指纹解锁（本机没有副本A）"),
        )

    /**
     * 授权后的解包（指定信封文本）。
     *
     * 切换高安全模式时用它**校验还没落盘的新信封**——"新包好、校验过，才替换旧的"
     * （CLAUDE.md 闭环规则③）。
     */
    override fun finishUnwrap(cipher: Cipher, envelopeText: String): DbKey {
        val envelope = parseEnvelope(envelopeText)

        val plain = try {
            cipher.doFinal(envelope.payload)
        } catch (e: AEADBadTagException) {
            // 指纹验证已经过了还解不开 ⇒ 密文损坏、或钥匙被系统换掉了（如恢复出厂、锁屏重设）
            throw KeystoreKeyException("硬件钥匙解不开这份密文（可能需要用主密码恢复）")
        }
        if (plain.size != RecoveryKey.DEK_BYTES) {
            throw KeystoreKeyException("解出的密钥长度异常：${plain.size}")
        }
        return DbKey(plain)
    }

    /** 授权后的落盘（指定信封文本暂不落盘，供切换流程暂存校验用）。 */
    override fun wrapEnvelopeText(dek: DbKey, cipher: Cipher): String {
        val payload = cipher.doFinal(dek.bytes)
        val iv = cipher.iv ?: throw KeystoreKeyException("Cipher 没有 IV（不该发生）")
        return serialize(iv, payload)
    }

    /** 把一份信封文本落盘（切换流程校验通过后的提交步骤）。 */
    override fun writeEnvelopeText(text: String) {
        store.write(text)
    }

    /**
     * 吊销副本A：删信封 + 删**两个模式**的 Keystore 条目。
     *
     * 副本B 不受影响（吊销一条路不动另一条，spec 4.1）。
     */
    override fun revoke() {
        store.clear()
        KeyMode.entries.forEach { runCatching { keystore().deleteEntry(aliasOf(it)) } }
    }

    // ---- 内部 ----

    /**
     * 执行 Cipher.init，把"钥匙被系统作废"翻译成用户能懂的话。
     *
     * 不翻译的话，界面上会出现系统的英文消息（实测表现："指纹暂时用不了"
     * 背后其实是一句看不懂的系统异常）。
     */
    private inline fun Cipher.initOrExplain(block: () -> Unit) {
        try {
            block()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystoreKeyException("指纹钥匙已被系统作废（通常是录入了新指纹）；用主密码进门后重新启用即可")
        }
    }

    private fun requireKey(mode: KeyMode): SecretKey =
        loadKeyOrNull(mode) ?: throw KeystoreKeyException("Keystore 里没有副本A 的钥匙（先 ensureKeyExists）")

    private fun loadKeyOrNull(mode: KeyMode): SecretKey? =
        runCatching { keystore().getKey(aliasOf(mode), null) as? SecretKey }.getOrNull()

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** 解析出来的信封只需要 IV 和密文两块。 */
    private class Envelope(val iv: ByteArray, val payload: ByteArray)

    /**
     * 文本格式（与副本B 同风格：key=value、无转义需求、解析从严）。
     *
     * 副本A 不出导、不经手用户，但仍然用文本：出问题时能直接 cat 出来看，
     * 排查成本低一截。
     */
    private fun serialize(iv: ByteArray, payload: ByteArray): String = listOf(
        MAGIC,
        COMMENT,
        "version=$VERSION",
        "iv=${encode(iv)}",
        "payload=${encode(payload)}",
    ).joinToString(separator = "\n", postfix = "\n")

    private fun parseEnvelope(text: String): Envelope {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (lines.firstOrNull() != MAGIC) {
            throw KeystoreKeyException("这不是副本A 的信封文件")
        }

        val fields = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val sep = line.indexOf('=')
            if (sep <= 0) throw KeystoreKeyException("信封内容损坏：无法解析「$line」")
            val key = line.take(sep).trim()
            val value = line.substring(sep + 1).trim()
            if (value.isEmpty()) throw KeystoreKeyException("字段「$key」的值为空")
            if (fields.put(key, value) != null) throw KeystoreKeyException("字段「$key」出现重复")
        }

        val version = fields.remove("version")?.toIntOrNull()
            ?: throw KeystoreKeyException("信封缺少或写坏了版本号")
        if (version != VERSION) throw KeystoreKeyException("不认识的副本A 版本：$version")

        val iv = decode(fields.remove("iv") ?: throw KeystoreKeyException("信封缺少「iv」"))
        if (iv.size != IV_BYTES) throw KeystoreKeyException("iv 长度异常：${iv.size}")

        val payload = decode(fields.remove("payload") ?: throw KeystoreKeyException("信封缺少「payload」"))
        if (payload.isEmpty()) throw KeystoreKeyException("payload 为空")

        if (fields.isNotEmpty()) {
            throw KeystoreKeyException("有无法识别的字段：${fields.keys.joinToString("、")}")
        }
        return Envelope(iv, payload)
    }

    private fun decode(raw: String): ByteArray = try {
        Base64.getDecoder().decode(raw)
    } catch (e: IllegalArgumentException) {
        throw KeystoreKeyException("信封里有不合法的 Base64")
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** Keystore 条目名（spec 4.2 锁定）。 */
        const val KEY_ALIAS = "notes_app_dek_wrapper"

        /**
         * 「这把钥匙是否已被系统作废」的默认探测：拿它 init 一次。
         *
         * 授权检查在 doFinal（阶段 3-3 真机实测），所以 init 不需要指纹；
         * 而被作废的钥匙**在 init 就会**抛 [KeyPermanentlyInvalidatedException]。
         * 其余失败一律不当作废——宁可漏报（走通用话术），不误报。
         */
        fun probeKeyInvalidatedByInit(key: java.security.Key): Boolean = try {
            Cipher.getInstance(TRANSFORM).init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, ByteArray(IV_BYTES)),
            )
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            true
        } catch (e: Exception) {
            false
        }

        const val MAGIC = "noteone-keystore-wrap"
        const val VERSION = 1

        private const val COMMENT =
            "# 这是 NoteONE 的副本A（硬件钥匙信封），与你的指纹/人脸绑定。换机或换钥匙后用它解不开数据时，用主密码（副本B）恢复。"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}
