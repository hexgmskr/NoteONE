package io.github.hexgmskr.noteone

import android.content.Context
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.HighSecurityModeStore
import io.github.hexgmskr.noteone.data.crypto.KeyBlobStore
import io.github.hexgmskr.noteone.data.crypto.KeystoreKeyWrapper
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.db.DatabaseProvider
import io.github.hexgmskr.noteone.data.db.DbKey
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import io.github.hexgmskr.noteone.data.db.SqlCipherDatabaseProvider
import io.github.hexgmskr.noteone.data.db.inTransaction
import io.github.hexgmskr.noteone.data.export.Importer
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagRepository
import io.github.hexgmskr.noteone.ui.lock.BiometricUnlockActions
import io.github.hexgmskr.noteone.ui.lock.LockController
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderStore
import java.io.File
import javax.crypto.Cipher

/**
 * 手工依赖容器。**不引 Hilt/Koin**——本项目已踩两次依赖版本冲突
 * （KSP/AGP、kotlinx-serialization），每加一个依赖多一个冲突面，
 * 而手工容器在这里完全够用。
 *
 * 数据库在解锁后才打开（接缝 B 的语义）：加密阶段 DEK 要等生物验证通过才能拿到，
 * 所以不能在进程启动时就开库。明文阶段这个时序看不出来，但形状必须正确。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 接缝 A 的实现。阶段 3-2 起为 SQLCipher 版（整库加密）——
     * 换实现那次只改了这一个类名，别的层一行没动。
     */
    val databaseProvider: DatabaseProvider = SqlCipherDatabaseProvider(appContext)

    /** 入口状态机。指纹/密码验证完后都调 completeUnlock 报到（见 LockController）。 */
    val lockController = LockController()

    /** 标签展示顺序偏好。放 SharedPreferences，不进核心表（CLAUDE.md）。 */
    val tagOrderStore = TagOrderStore(appContext)

    /**
     * 高安全模式偏好：副本A 用哪个别名的钥匙（[KeystoreKeyWrapper.KeyMode]）。
     * 放 SharedPreferences——它决定"用哪把钥匙"，不是用户数据；
     * 与副本A 信封文件同在 App 私有存储，uninstall 一起消失，不会单边残留。
     */
    val highSecurityModeStore = HighSecurityModeStore(appContext)

    /**
     * 恢复密钥（副本B）的管家：设置主密码、解锁取 DEK、导出与恢复都走它。
     *
     * 密文落在 App 私有目录的一个文件里（不是 SharedPreferences——
     * 密钥材料不该放在会被系统按自己时机重写的存储里；而且它就是要给用户导出的那段文本）。
     * **DEK 本身不落盘**，落盘的只有被主密码包住的密文。
     */
    val recoveryKeyManager = RecoveryKeyManager(
        KeyBlobStore(File(appContext.filesDir, RECOVERY_KEY_FILE_NAME)),
        databaseExists = { databaseProvider.exists() },
    )

    /**
     * 副本A（Keystore 硬件钥匙）的封装：日常指纹解锁走它。
     * 与副本B 一样是"小文本文件 + 原子写入"，只是文件不同、锁不同。
     */
    private val keystoreKeyWrapper = KeystoreKeyWrapper(
        KeyBlobStore(File(appContext.filesDir, KEYSTORE_WRAP_FILE_NAME)),
    )

    /**
     * 当前会话的数据加密密钥（DEK）。
     *
     * **只活在内存里**：解锁成功时写入，宽限期耗尽/清会话时丢弃。
     * 磁盘上任何时候都不存在明文 DEK——只有被主密码（和将来被 Keystore）包住的密文。
     */
    @Volatile
    private var sessionKey: DbKey? = null

    /** 解锁后持有的数据库实例；未解锁时为 null。 */
    @Volatile
    private var database: NoteOneDatabase? = null

    /**
     * 用主密码尝试解锁：解出 DEK → 存入会话 → 推进状态机。
     *
     * 密码不对 / 还没设置恢复密钥时抛 [io.github.hexgmskr.noteone.data.crypto.CryptoException]
     * （消息可直接给用户看）。**必须在后台线程调用**——PBKDF2 是秒级的。
     */
    fun unlockWithPassword(password: CharArray) {
        val dek = recoveryKeyManager.unlock(password)   // 失败会抛，由调用方转成提示
        sessionKey = dek
        openDatabaseForUnlock()
        lockController.completeUnlock()
    }

    /**
     * 解锁时就把库打开（2026-10-04 审计 A4）：
     *
     * 库损坏 / 恢复来的钥匙不是本机那把时，`openDatabase()` 会抛。以前它发生在
     * 解锁**之后**的组合期（列表构建时）——直接崩溃，没有任何话术；而"数据打不开"
     * 恰恰是最需要告诉用户"检查恢复密钥、导出文件是最后退路"的一刻。
     * 现在把它放在解锁路径里：失败 = 解锁失败，报错走锁门页既有的提示通道。
     */
    private fun openDatabaseForUnlock() {
        try {
            openDatabase()
        } catch (e: Exception) {
            sessionKey = null
            throw CryptoException(
                "数据打不开：${e.message ?: "未知原因"}。" +
                    "请确认你恢复的是本机自己的恢复密钥（别的机器/旧主人的备份都打不开）。" +
                    "实在打不开时，之前导出的文件是重建数据的最后一条路。",
                e,
            )
        }
    }

    /**
     * 打开（或复用）数据库。**只能在解锁后调用**——没有会话密钥就直接报错，
     * 而不是偷偷开一个没有钥匙的库。
     */
    @Synchronized
    fun openDatabase(): NoteOneDatabase {
        val key = sessionKey ?: error("还没有解锁，不能开库")
        return database ?: databaseProvider.open(key).also { database = it }
    }

    /**
     * 清会话：关库 + 丢弃 DEK。宽限期耗尽（回前台发现已锁定）时调用。
     *
     * 宽限期内**不**清——那正是 60 秒存在的意义（切出去复制再切回来不用重验证）。
     */
    @Synchronized
    fun clearSession() {
        closeDatabase()
        sessionKey = null
    }

    /**
     * 标签写入口。**只在解锁后调用**，且这里不代替调用方开库——
     * 传进来的 [db] 必须已经是 [openDatabase] 的产物，
     * 避免出现「还没解锁就把库打开」的暗道。
     */
    fun tagRepository(db: NoteOneDatabase): TagRepository =
        TagRepository(db.tagDao(), db.itemTagDao())

    /**
     * 记录写入口。事务在这里接 [io.github.hexgmskr.noteone.data.db.inTransaction]——
     * 「一条记录 + 它的标签」必须一起成或一起败（见 [ItemRepository]）。
     *
     * 注意**不能**用 `RoomDatabase.withTransaction`：driver 模式下它要 openHelper，
     * 会直接抛异常（真机上以"保存失败"暴露过一次，详见 Transactions.kt 的注释）。
     */
    fun itemRepository(db: NoteOneDatabase): ItemRepository = ItemRepository(
        itemDao = db.itemDao(),
        tagRepository = tagRepository(db),
        itemTagDao = db.itemTagDao(),
        inTransaction = { block -> db.inTransaction { block() } },
    )

    /** 合并导入的入口（导出/导入页用）。与 [itemRepository] 同一套事务接线。 */
    fun importer(db: NoteOneDatabase): Importer = Importer(
        itemDao = db.itemDao(),
        tagRepository = tagRepository(db),
        itemTagDao = db.itemTagDao(),
        inTransaction = { block -> db.inTransaction { block() } },
    )

    @Synchronized
    fun closeDatabase() {
        database?.close()
        database = null
    }

    /**
     * 指纹解锁的全部动作（见 [BiometricUnlockActions]）。
     * 界面只认这个接口——BiometricPrompt 本身由界面侧跑（它要真 Activity）。
     */
    /**
     * 指纹解锁的全部动作（见 [BiometricUnlockActions]）。
     * 界面只认这个接口——BiometricPrompt 本身由界面侧跑（它要真 Activity）。
     * 实现体在 [BiometricUnlockCoordinator]（2026-10-04 移出，为可测性，见那边的注释）。
     */
    val biometricUnlock: BiometricUnlockActions = BiometricUnlockCoordinator(
        keyWrapper = keystoreKeyWrapper,
        highSecurityModeStore = highSecurityModeStore,
        recoveryKeyManager = recoveryKeyManager,
        sessionKey = { sessionKey },
        setSessionKey = { sessionKey = it },
        openDatabase = { openDatabaseForUnlock() },
        onUnlocked = { lockController.completeUnlock() },
    )

    private companion object {
        /** 副本B密文的文件名（App 私有 files 目录下，会给用户导出）。 */
        const val RECOVERY_KEY_FILE_NAME = "recovery-key.txt"

        /** 副本A信封的文件名（App 私有、不出导、不离开本机）。 */
        const val KEYSTORE_WRAP_FILE_NAME = "keystore-wrap.txt"
    }
}
