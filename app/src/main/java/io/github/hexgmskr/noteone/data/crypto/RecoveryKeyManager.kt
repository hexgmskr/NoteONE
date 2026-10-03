package io.github.hexgmskr.noteone.data.crypto

import io.github.hexgmskr.noteone.data.db.DbKey

/**
 * 密钥管家：把「主密码 / DEK / 副本B密文」的生命周期收在一处。
 *
 * 状态只有一个布尔：[isSetUp]——磁盘上有没有副本B密文。
 * 没有 = 从没设置过（任何新装设备都从这个状态开始）；有 = 解锁、导出、改密码都基于它。
 *
 * **DEK 不落盘**。磁盘上唯一存在的东西是密码包过的副本B密文；
 * DEK 只在 [unlock] 被调用时从密文里解出来、活在内存里。
 *
 * **破坏性操作有护栏**：[setup] 与 [importFrom] 在"已设置"时直接拒绝——
 * 它们都会改写磁盘上的密文，误调用等于把已有 DEK 换掉（老数据全被锁在门外）。
 * 换密码走 [changePassword]，它会用旧密码先解出 DEK 再重包。
 */
class RecoveryKeyManager(
    private val source: KeyBlobSource,
    /**
     * 「本机是否已存在加密数据库文件」的探针（生产由接缝 A 的 provider 提供）。
     *
     * **刻意没有默认值**：这是防"新 DEK 锁死旧库"的护栏，每个调用点都必须
     * 显式说明自己的世界长什么样，不能靠一个静默的默认值蒙混过去。
     */
    private val databaseExists: () -> Boolean,
    /**
     * 包装 DEK 时用的迭代数。生产代码永远用默认值；单测注入低值，
     * 免得每条用例都等 60 万次 PBKDF2（那是秒级的）。默认值本身另有用例把关。
     */
    private val iterations: Int = RecoveryKey.DEFAULT_ITERATIONS,
) {

    /** 本机是否已经设置过恢复密钥。 */
    fun isSetUp(): Boolean = source.read() != null

    /**
     * 首次设置：生成 DEK → 主密码包成副本B → 落盘。
     * 返回信封文本，调用方应立刻提示用户导出保管（此刻是唯一的备份窗口）。
     */
    fun setup(password: CharArray): String {
        check(!isSetUp()) { "已经设置过恢复密钥；更换主密码请用 changePassword" }
        // **最关键的一条护栏**（2026-10-04 审计）：库文件在、副本B 不在 =
        // "钥匙丢了但门还在"。此时生成新 DEK，那把新钥匙永远开不了旧库
        // （老 DEK 只存在于用户离线备份的恢复密钥里）；而且本机一旦变成"已设置"，
        // 连「从备份恢复」也会被上一条护栏挡住——不可逆，必须拦住。
        check(!databaseExists()) {
            "本机已有一个加密数据库，但它对应的恢复密钥不在（可能文件被清理过）。" +
                "现在创建新密钥会生成一把全新的钥匙，那个库将再也打不开。" +
                "请改用「从备份恢复」导入你离线保管的恢复密钥。" +
                "（确认旧库不要了才考虑清库重来——那是不可逆的手工步骤。）"
        }
        requirePasswordPolicy(password)

        val text = RecoveryKey.wrap(RecoveryKey.generateDek(), password, iterations).serialize()
        source.write(text)
        return text
    }

    /** 用主密码把 DEK 解出来。日常解锁与恢复都走这一条。 */
    fun unlock(password: CharArray): DbKey {
        val text = source.read() ?: throw CryptoException("还没有设置恢复密钥")
        return RecoveryKey.unwrap(KeyEnvelope.parse(text, KeyEnvelope.MAGIC_RECOVERY), password)
    }

    /** 密文文本，给"导出给你自己保管"用。没设置过返回 null。 */
    fun exportText(): String? = source.read()

    /**
     * 换机/重装后的恢复：验证外来的密文 + 密码确实能解出 DEK，然后把它作为本机副本落盘。
     *
     * 只允许在"本机还没设置"时调用（与 [setup] 同一护栏）：本机已有密文时导入
     * 无关文件会把本机 DEK 换掉。密码不对时**什么都不写**——不能留下半个坏状态。
     */
    fun importFrom(text: String, password: CharArray): DbKey {
        check(!isSetUp()) { "本机已设置恢复密钥；直接导入会替换它" }

        val dek = RecoveryKey.unwrap(KeyEnvelope.parse(text, KeyEnvelope.MAGIC_RECOVERY), password)
        source.write(text)
        return dek
    }

    /** 更换主密码：旧密码解出 DEK → 新密码重包 → 覆盖落盘。DEK 本身不变，数据无感。 */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): String {
        requirePasswordPolicy(newPassword)

        val dek = unlock(oldPassword)
        val text = RecoveryKey.wrap(dek, newPassword, iterations).serialize()
        source.write(text)
        return text
    }

    companion object {
        /**
         * 主密码最短长度。2026-10-01 从 8 提到 12：这是零成本换来的最大安全提升
         * （数量级上比调 KDF 参数管用），界面上配合一句"建议用一句只有你知道的话"。
         */
        const val MIN_PASSWORD_LENGTH = 12

        /** 策略检查。UI 层也会挡一道，但核心层不能依赖 UI 层记得挡。 */
        fun requirePasswordPolicy(password: CharArray) {
            require(password.size >= MIN_PASSWORD_LENGTH) {
                "主密码至少 $MIN_PASSWORD_LENGTH 位"
            }
        }
    }
}
