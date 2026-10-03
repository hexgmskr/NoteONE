package io.github.hexgmskr.noteone.data.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码学路径（恢复密钥 / 加密导出）上的失败。message 是能直接展示给用户的一句话。
 *
 * 2026-10-01 从 `RecoveryKeyException` 改名而来：同一条路径现在有两个使用者，
 * 名字带 RecoveryKey 会让导出那边的人以为catch错了东西。
 */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 「主密码 → PBKDF2 派生 → AES-GCM 加密任意字节 → [KeyEnvelope] 信封」的通用原语。
 *
 * 两个使用者：
 *  - **恢复密钥（副本B）**：包的是 32 字节 DEK，见 [RecoveryKey]；
 *  - **加密导出**：包的是导出的 JSON 字节，见 `data/export/Exporter`。
 *
 * 2026-10-01 从 [RecoveryKey] 里提炼出来（当时导出功能要加密的是任意长度的 JSON，
 * 而原来那套写死了「只包 32 字节 DEK」）。**为什么用 PBKDF2、迭代数为什么取 60 万**：
 * 决策记录随 [RecoveryKey] 保留，这里不重复；两处共用同一份实现，
 * 「两种文件同一套加密参数」是提炼的主要目的。
 */
object PasswordSeal {

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /** 整个 App 共用一把随机源；SecureRandom 线程安全。 */
    private val random = SecureRandom()

    /**
     * 把 [plain] 用主密码封进信封。每次调用都取全新的 salt 与 nonce——
     * 同样的内容、同样的密码，封两次得到的密文必须不同。
     *
     * @param magic 信封的用途标识（[KeyEnvelope.MAGIC_RECOVERY] / [KeyEnvelope.MAGIC_EXPORT]）。
     *   用途写进文件首行，解析端能对"拿错文件"给出明确的话，而不是解密失败。
     */
    fun seal(plain: ByteArray, password: CharArray, magic: String, iterations: Int): KeyEnvelope {
        require(iterations > 0) { "迭代数必须为正：$iterations" }

        val salt = ByteArray(KeyEnvelope.SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(KeyEnvelope.NONCE_BYTES).also(random::nextBytes)

        val payload = withDerivedKey(password, salt, iterations) { key ->
            Cipher.getInstance(CIPHER_TRANSFORM).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            }.doFinal(plain)
        }

        return KeyEnvelope(
            magic = magic,
            version = KeyEnvelope.VERSION,
            kdf = KeyEnvelope.KDF_NAME,
            iterations = iterations,
            salt = salt,
            cipher = KeyEnvelope.CIPHER_NAME,
            nonce = nonce,
            payload = payload,
        )
    }

    /**
     * 用主密码把信封解开，拿回原始字节。
     *
     * 密码错误与密文被篡改在密码学上不可区分（都表现为 GCM 校验失败），
     * 所以合并成同一句提示——这也是对的：两种情况用户能做的事一样（检查密码、检查文件）。
     * 迭代数与盐都取自信封自身，不用当前默认值，老信封才不会被将来的参数调整影响。
     *
     * @param subject 出错提示里对"这个文件"的称呼（"恢复密钥文件" / "导出文件"），
     *   让用户看到的句子指向他手上真正拿着的那份东西。
     */
    fun open(envelope: KeyEnvelope, password: CharArray, subject: String = "文件"): ByteArray =
        withDerivedKey(password, envelope.salt, envelope.iterations) { key ->
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.nonce))
            }
            try {
                cipher.doFinal(envelope.payload)
            } catch (e: AEADBadTagException) {
                throw CryptoException("密码错误，或${subject}已损坏")
            }
        }

    /**
     * 派生 → 用完即清零。
     *
     * 派生密钥和主密码的副本都只在这一次调用里活着：Kotlin/Java 的 String 没法清零，
     * 但 PBEKeySpec 与派生出的字节数组可以，清了能让「内存里的敏感材料」少一份。
     */
    private inline fun <T> withDerivedKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        block: (SecretKeySpec) -> T,
    ): T {
        val spec = PBEKeySpec(password, salt, iterations, AES_KEY_BITS)
        val derived = try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            block(SecretKeySpec(derived, "AES"))
        } finally {
            derived.fill(0)
        }
    }
}
