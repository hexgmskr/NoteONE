package io.github.hexgmskr.noteone.data.crypto

import io.github.hexgmskr.noteone.data.db.DbKey
import java.security.SecureRandom

/**
 * 副本B / 恢复路径（spec 4.1）：用户主密码 → KDF 派生 → AES-GCM 包住 DEK。
 *
 * 命名按 spec「恢复密钥」的口径；它同时负责 [generateDek]——本模块是密钥管理的起点，
 * DEK 在别处的使用者只认 [DbKey]，不关心它是怎么生成、怎么被包住的。
 *
 * **加解密本身在 [PasswordSeal]**（2026-10-01 提炼，加密导出复用同一套）。
 * 本对象只剩「DEK 这条路径特有的语义」：32 字节长度校验、固定用恢复密钥用途的信封。
 *
 * **为什么是 PBKDF2 而不是 Argon2**（2026-10-01 拍板）：
 *  - 零新依赖：JDK / Android 平台自带（`PBKDF2WithHmacSHA256`）。本项目两次栽在
 *    依赖冲突上（KSP×AGP9、序列化版本分裂），CLAUDE.md 明写「每加一个依赖就多一个冲突面」；
 *    Argon2 在平台里没有实现，必须引第三方（多为带原生 .so 的库，还多一层 16KB 对齐之类的风险）。
 *  - 信封格式里记着 `kdf` 与 `iterations` 字段，将来真要换 Argon2id，
 *    只是用新 KDF 把同一把 DEK 重包一次（32 字节的事），库文件与数据都不用动。
 *  - 抗破解强度的大头在密码本身；配合 [DEFAULT_ITERATIONS] 的迭代数，
 *    PBKDF2 对「手保管的离线密文」这个威胁模型已经够用。
 */
object RecoveryKey {

    /** DEK 长度：32 字节（AES-256）。 */
    const val DEK_BYTES = 32

    /**
     * 默认迭代数。OWASP 对 PBKDF2-HMAC-SHA256 的建议值（60 万）。
     *
     * **真机实测（2026-10-01，小米 14，debug 构建）：一次「包+解」共 5087ms，
     * 即单次约 2.5 秒**（见 RecoveryKeyInstrumentedTest 的计时用例）。
     * 当前接受这个代价：主密码是「设置 / 恢复 / 加密导出」这类低频路径，不是日常入口。
     *
     * 什么时候要重新考虑：3-2 之后到 3-3（Keystore）之前，主密码会短暂地成为
     * 每次冷启动的解锁路径，那时若 2.5s 的等待碍事，就下调这个常数
     * （改一行 + 重新生成副本B即可，老信封自带迭代数、不受影响）。
     * 下调属于动安全参数，**理由必须记进 HANDOFF**。
     */
    const val DEFAULT_ITERATIONS = 600_000

    /** 整个 App 共用一把随机源；SecureRandom 线程安全。 */
    private val random = SecureRandom()

    /** 生成一把全新的 DEK。只在「第一次启用加密」时调用一次。 */
    fun generateDek(): DbKey = DbKey(ByteArray(DEK_BYTES).also(random::nextBytes))

    /** 用主密码把 DEK 包成副本B信封（用途标识固定为 [KeyEnvelope.MAGIC_RECOVERY]）。 */
    fun wrap(
        dek: DbKey,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): KeyEnvelope = PasswordSeal.seal(dek.bytes, password, KeyEnvelope.MAGIC_RECOVERY, iterations)

    /**
     * 用主密码把 DEK 从信封里解出来。
     *
     * 密码不对/文件损坏的提示由 [PasswordSeal.open] 给出（话术里带"恢复密钥文件"）；
     * 这里只补一条 DEK 路径特有的校验：解出来的必须是 32 字节。
     */
    fun unwrap(envelope: KeyEnvelope, password: CharArray): DbKey {
        val plain = PasswordSeal.open(envelope, password, subject = "恢复密钥文件")
        if (plain.size != DEK_BYTES) {
            throw CryptoException("密钥长度异常：${plain.size}（期望 $DEK_BYTES）")
        }
        return DbKey(plain)
    }
}
