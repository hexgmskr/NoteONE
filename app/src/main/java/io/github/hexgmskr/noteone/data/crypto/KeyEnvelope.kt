package io.github.hexgmskr.noteone.data.crypto

import java.util.Base64

/**
 * 密码信封：用主密码派生的密钥把一段字节包起来的结果。
 *
 * 两个使用者共用这一种文件格式（2026-10-01 起）：
 *  - **恢复密钥（副本B）**：包的是 32 字节 DEK，标识行 `noteone-recovery-key`；
 *  - **加密导出**：包的是导出的 JSON，标识行 `noteone-export`。
 * 首行的**用途标识**让解析端能对"拿错文件"说人话（"这是导出文件，不是恢复密钥"），
 * 而不是让用户去猜一个"解密失败"。
 *
 * 文件格式是简单的 `key=value` 文本（UTF-8），**不是 JSON**——
 * org.json 是 Android 内置的，但在 JVM 单测里是空壳（一调用就抛），
 * 而这个格式的解析必须能在电脑上测。值全部来自固定字母表（数字 / Base64），
 * 不需要任何转义，所以文本格式在这里没有「分隔符被内容弄坏」的风险。
 *
 * 生成出来长这样（用户会看到这个文件，所以留了一行中文说明）：
 * ```
 * noteone-recovery-key
 * # 这是 NoteONE 的恢复密钥（副本B）文件，恢复数据时配合主密码使用，请离线保管。
 * version=1
 * kdf=PBKDF2-HMAC-SHA256
 * iterations=600000
 * salt=<Base64>
 * cipher=AES-256-GCM
 * nonce=<Base64>
 * payload=<Base64>
 * ```
 *
 * **解析从严**：未知字段、重复字段、缺字段、版本不认识、长度不对，一律拒绝。
 * 这是恢复与备份路径上的文件——猜错比报错危险得多。
 *
 * **为什么没有 AAD**：信封里的每个字段要么参与派生密钥（kdf/iterations/salt），
 * 要么参与解密（cipher/nonce/payload），改任何一个都会让 GCM 校验失败，
 * 天然防篡改。将来若出现"不进密钥也不进密文"的新字段，再考虑把头部绑进 AAD。
 */
class KeyEnvelope(
    /**
     * 用途标识（文件首行）。写进文件是刻意的：解析端拿 [parse] 的 `expectedMagic`
     * 一比对，就能区分"恢复密钥"与"加密导出"，报出指向正确的话。
     */
    val magic: String,
    val version: Int,
    /**
     * KDF 参数。**解包时用信封里记录的这份，不用代码里的当前默认值**——
     * 将来调大默认迭代数，老信封仍然要能开。
     */
    val kdf: String,
    val iterations: Int,
    val salt: ByteArray,
    val cipher: String,
    val nonce: ByteArray,
    /** 被 AES-GCM 包住的原始字节（密文 + 认证标签）。 */
    val payload: ByteArray,
) {

    /** 序列化成可保存、可导出的文本。字段顺序固定：确定性输出，方便测试与肉眼比对。 */
    fun serialize(): String = listOf(
        magic,
        commentFor(magic),
        "version=$version",
        "kdf=$kdf",
        "iterations=$iterations",
        "salt=${encode(salt)}",
        "cipher=$cipher",
        "nonce=${encode(nonce)}",
        "payload=${encode(payload)}",
    ).joinToString(separator = "\n", postfix = "\n")

    companion object {
        /** 恢复密钥（副本B）文件的标识行。**改它等于让所有老备份文件认不出来**。 */
        const val MAGIC_RECOVERY = "noteone-recovery-key"

        /** 加密导出文件的标识行。 */
        const val MAGIC_EXPORT = "noteone-export"

        /** 当前格式版本。格式变化时递增，老解析器遇到新版本要明确拒绝。 */
        const val VERSION = 1

        const val KDF_NAME = "PBKDF2-HMAC-SHA256"
        const val CIPHER_NAME = "AES-256-GCM"
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12

        /**
         * 迭代数上限。防一个改过的/损坏的文件塞个天文数字进来把 App 卡死。
         * 正常值见 [RecoveryKey.DEFAULT_ITERATIONS]，离这个上限很远。
         */
        const val MAX_ITERATIONS = 5_000_000

        /** 已知用途标识 → 给用户看的名称。报错话术与文件注释都由它派生。 */
        private val PURPOSES = mapOf(
            MAGIC_RECOVERY to "恢复密钥",
            MAGIC_EXPORT to "记录导出",
        )

        /** 解析文本。任何一处不合规都抛 [CryptoException]，带一句能给人看的原因。 */
        fun parse(text: String, expectedMagic: String): KeyEnvelope {
            // 空行与 # 开头的注释行跳过
            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            val first = lines.firstOrNull()
            if (first != expectedMagic) {
                // 手上这份是**另一种** NoteOne 文件时，直接点名，别让用户去猜
                val actual = PURPOSES[first]
                throw CryptoException(
                    if (actual != null) {
                        "这是「${actual}」文件，不是「${purposeOf(expectedMagic)}」文件"
                    } else {
                        "这不像是 NoteONE 的${purposeOf(expectedMagic)}文件（缺少标识行）"
                    }
                )
            }

            val fields = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val sep = line.indexOf('=')
                if (sep <= 0) throw CryptoException("文件内容损坏：无法解析「$line」")
                val key = line.take(sep).trim()
                val value = line.substring(sep + 1).trim()
                if (value.isEmpty()) throw CryptoException("字段「$key」的值为空")
                if (fields.put(key, value) != null) throw CryptoException("字段「$key」出现重复")
            }

            val version = intField(fields, "version", "版本号")
            if (version != VERSION) {
                throw CryptoException("这封信封的版本是 $version，本 App 只认识 $VERSION（可能需要更新 App）")
            }

            val kdf = fields.remove("kdf") ?: throw CryptoException("缺少字段「kdf」")
            if (kdf != KDF_NAME) throw CryptoException("不认识的 KDF：$kdf")

            val iterations = intField(fields, "iterations", "迭代数")
            if (iterations !in 1..MAX_ITERATIONS) {
                throw CryptoException("迭代数超出合理范围：$iterations")
            }

            val salt = bytesField(fields, "salt")
            val nonce = bytesField(fields, "nonce")
            if (salt.size != SALT_BYTES) throw CryptoException("salt 长度异常：${salt.size}")
            if (nonce.size != NONCE_BYTES) throw CryptoException("nonce 长度异常：${nonce.size}")

            val cipher = fields.remove("cipher") ?: throw CryptoException("缺少字段「cipher」")
            if (cipher != CIPHER_NAME) throw CryptoException("不认识的加密算法：$cipher")

            val payload = bytesField(fields, "payload")
            if (payload.isEmpty()) throw CryptoException("payload 为空")

            if (fields.isNotEmpty()) {
                throw CryptoException("有无法识别的字段：${fields.keys.joinToString("、")}")
            }

            return KeyEnvelope(
                magic = first,
                version = version,
                kdf = kdf,
                iterations = iterations,
                salt = salt,
                cipher = cipher,
                nonce = nonce,
                payload = payload,
            )
        }

        private fun purposeOf(magic: String): String = PURPOSES[magic] ?: "密文"

        /** 每个用途配一句写进文件的中文说明。用户会看到这个文件。 */
        private fun commentFor(magic: String): String = when (magic) {
            MAGIC_RECOVERY ->
                "# 这是 NoteONE 的恢复密钥（副本B）文件，恢复数据时配合主密码使用，请离线保管。"
            MAGIC_EXPORT ->
                "# 这是 NoteONE 的记录导出文件（已用主密码加密）。把它导入 App 或用配套工具解开，可得到 JSON 数据。"
            else -> "# NoteONE 文件"
        }

        private fun intField(fields: MutableMap<String, String>, key: String, label: String): Int {
            val raw = fields.remove(key) ?: throw CryptoException("缺少字段「$key」")
            return raw.toIntOrNull() ?: throw CryptoException("$label 不是整数：$raw")
        }

        private fun bytesField(fields: MutableMap<String, String>, key: String): ByteArray {
            val raw = fields.remove(key) ?: throw CryptoException("缺少字段「$key」")
            return try {
                Base64.getDecoder().decode(raw)
            } catch (e: IllegalArgumentException) {
                throw CryptoException("字段「$key」不是合法的 Base64")
            }
        }

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
