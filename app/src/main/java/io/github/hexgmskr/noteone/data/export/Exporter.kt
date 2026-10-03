package io.github.hexgmskr.noteone.data.export

import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.PasswordSeal
import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import java.time.OffsetDateTime

/**
 * 导出的两种形态（用户拍板"导出时二选一"）：
 *  - **明文**：JSON 字节直接落盘，任何工具可读；
 *  - **加密**：同一份 JSON 用主密码包进 [KeyEnvelope] 信封（用途标识
 *    [KeyEnvelope.MAGIC_EXPORT]），文件离开 App 后仍是密文。
 *
 * 两种形态共用 [ExportJson] 的产出——加密形态**不重新序列化**，
 * 包住的字节和明文形态逐字节一致，测试钉住这一点。
 *
 * 加密形态的密码由调用方负责先核对（和恢复密钥比一次），
 * 否则打错一个字母就会得到一份"用错密码封起来的备份"——用户以为它是主密码锁的，
 * 灾难恢复时才发现打不开。核对在 [io.github.hexgmskr.noteone.ui.export.ExportViewModel]。
 */
object Exporter {

    /** 明文导出：spec 6.1 的 JSON，UTF-8 字节。 */
    fun plaintextBytes(items: List<ItemWithTags>, exportedAt: OffsetDateTime): ByteArray =
        ExportJson.build(items, exportedAt).toByteArray(Charsets.UTF_8)

    /** 加密导出：信封文本（key=value 格式，可直接落盘）。 */
    fun encryptedText(
        items: List<ItemWithTags>,
        exportedAt: OffsetDateTime,
        password: CharArray,
        iterations: Int = RecoveryKey.DEFAULT_ITERATIONS,
    ): String = PasswordSeal
        .seal(plaintextBytes(items, exportedAt), password, KeyEnvelope.MAGIC_EXPORT, iterations)
        .serialize()

    /**
     * 解开一份加密导出，拿回里面的 JSON 文本（[encryptedText] 的逆操作，导入路径用）。
     *
     * 密码不对 / 文件被改过，由 [PasswordSeal.open] 以 [io.github.hexgmskr.noteone.data.crypto.CryptoException]
     * 报错，话术里点名"导出文件"；拿错别的用途的信封，由 [KeyEnvelope.parse] 点名。
     */
    fun openEncryptedText(text: String, password: CharArray): String =
        PasswordSeal.open(
            KeyEnvelope.parse(text, KeyEnvelope.MAGIC_EXPORT),
            password,
            subject = "导出文件",
        ).toString(Charsets.UTF_8)
}
