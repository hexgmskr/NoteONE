package io.github.hexgmskr.noteone.data.db

import android.content.Context
import androidx.room.Room
import java.io.File
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * 数据加密密钥（DEK）。
 *
 * 全库只加密一次，也只用这一把密钥。日常解锁路径（Keystore 硬件密钥）
 * 与恢复路径（主密码派生密钥）是两把不同的钥匙，锁的是同一个信封。
 * 见 docs/spec.md 第 4 节。
 */
class DbKey(val bytes: ByteArray)

/**
 * 接缝 A：全项目唯一知道「怎么打开数据库」的地方。
 *
 * 阶段 0–2 是明文实现，阶段 3-2 起换成 [SqlCipherDatabaseProvider]（整库加密）——
 * **这次替换只动了本文件**：Entity / DAO / Repository / ViewModel / UI 一行没改，
 * 这就是「加密后置不等于返工」的兑现。
 *
 * 明文实现已随切换删除：加密的 App 里留着一个能开明文库的类，
 * 是一个随时可能被误用的安全隐患。
 */
interface DatabaseProvider {
    fun open(key: DbKey): NoteOneDatabase

    /**
     * 库文件是否已存在（不打开、不需要钥匙）。
     *
     * 给"创建恢复密钥"当护栏用：库在 = 本机有一扇用老 DEK 锁着的门，
     * 此时生成新 DEK 会把那扇门永久锁死（见 [io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager.setup]）。
     */
    fun exists(): Boolean
}

/**
 * 加密实现（阶段 3-2 起）：整库 SQLCipher 加密，钥匙是解锁流程解出来的 DEK。
 *
 * **库名沿用 `noteone.db`**：换加密时就地重建，不做搬迁（阶段 2→3 的既定安排，
 * 明文测试数据直接清掉）。代价是升级到这一版时必须手动删掉旧的明文库文件，
 * 否则 SQLCipher 打不开它——一次性的开发期动作，不写代码处理。
 *
 * **raw key 模式（2026-10-03 改）**：DEK 是 32 字节全熵随机数，直接当 AES 钥匙用
 * （`x'<64位hex>'` 形式，SQLCipher 见此前缀跳过自家 KDF）。此前把它当"口令"传，
 * SQLCipher 会照规矩再跑 25.6 万次 PBKDF2——**真机实测每次开库白烧 999ms**
 * （raw key 同口径 31ms）。KDF 是为"弱口令抗爆破"存在的，对全熵密钥不增加
 * 一个比特的保护，纯是每次解锁的固定开销。
 *
 * 旧库文件是口令模式加密的，[resolveKeyBytes] 负责一次性 rekey 迁移；
 * DEK 本身不变，故副本A（指纹信封）/副本B（主密码信封）/导出文件全部照旧有效。
 */
class SqlCipherDatabaseProvider(
    private val context: Context,
    /**
     * 库文件名。默认生产名，主代码永远不传第二个参数。
     *
     * **这个口子是给仪器测试用的**：测试跑在真机上，若沿用生产名，
     * 测试自己的清理动作会把用户真库一起删掉（实锤发生过，见 HANDOFF §4）。
     * 要测的是开库配置与迁移注册，这两样与文件名无关，换隔离名字照样测得到。
     */
    private val databaseName: String = DATABASE_NAME,
) : DatabaseProvider {

    override fun exists(): Boolean = context.getDatabasePath(databaseName).exists()

    override fun open(key: DbKey): NoteOneDatabase {
        // 原生库不自动加载（见 [SqlCipher]），开库前必须先加载
        SqlCipher.load()

        val keyBytes = resolveKeyBytes(key)
        return Room.databaseBuilder(context, NoteOneDatabase::class.java, databaseName)
            .setDriver(SQLCipherDriver(keyBytes, null, null))
            .addMigrations(*Migrations.ALL)
            // 明确不加 fallbackToDestructiveMigration()：加密库里是用户唯一的一份数据，
            // 「看不懂就清空」在这里的后果比明文阶段更严重。
            .build()
    }

    /**
     * 决定"喂钥匙"的形式，并在需要时把旧格式的库文件一次性迁移到 raw key 模式。
     *
     * 判定顺序——**不存任何"已迁移"标记，每次靠"能不能开"自己判**，天然自愈：
     * 迁移完成瞬间断电、或从备份放回旧格式的库文件副本，下一次打开都会自动纠正，
     * 不存在"标记丢了就把数据锁死"的窗口。
     *
     *   1. 文件不存在（新装）→ 直接用 raw key 建库；
     *   2. 试 raw key → 能开 = 已迁移（毫秒级，正常路径）；
     *   3. 退回口令模式 → 能开 = 旧格式 → **当场 rekey 迁移**（事务内完成）；
     *   4. 两种形式都开不了 → 钥匙不对或文件损坏，抛错（调用方按"解不开"处理）。
     */
    private fun resolveKeyBytes(key: DbKey): ByteArray {
        val file = context.getDatabasePath(databaseName)
        val rawKey = rawKeyForm(key)

        if (!file.exists()) return rawKey
        if (probeOpens(file, rawKey)) return rawKey

        // 旧格式（口令模式）：**一条连接**做完「探测 + rekey」——口令模式的 KDF
        // 是秒级的，开两条连接会白付两次（≈多等一秒）。
        return runCatching {
            SQLCipherDriver(key.bytes, null, null).open(file.absolutePath).use { conn ->
                // 先真读一次：钥匙不对/文件损坏在这里就抛，不会误触发 rekey
                conn.prepare("SELECT count(*) FROM sqlite_master").use { stmt -> stmt.step() }
                // 能读 = 是本库的旧格式 → 当场改写成 raw key 模式（事务内完成）
                val rawHex = key.bytes.joinToString("") { "%02x".format(it) }
                conn.prepare("PRAGMA rekey = \"x'$rawHex'\"").use { stmt ->
                    while (stmt.step()) { /* 跑到结束 */ }
                }
            }
            rawKey
        }.getOrElse {
            // 迁移失败≠库不能用（rekey 在事务里，失败会回滚）：只要旧格式还能读，
            // 就退回口令模式继续用——下次打开再试迁移，绝不把能用的库判死。
            if (probeOpens(file, key.bytes)) key.bytes
            else throw IllegalStateException("数据库打不开：钥匙不匹配或文件损坏", it)
        }
    }

    /**
     * raw key 的传法：`x'<64 位小写 hex>'`。
     *
     * SQLCipher 见此前缀即"这是原始密钥、别磨"，直接当 AES-256 钥匙用。
     * 用字节数组传（不用 String）——DEK 不该在内存里多留一份不可擦除的拷贝。
     */
    private fun rawKeyForm(key: DbKey): ByteArray {
        require(key.bytes.size == 32) {
            "raw key 模式要求 32 字节密钥（AES-256），实际 ${key.bytes.size} 字节"
        }
        return ("x'" + key.bytes.joinToString("") { "%02x".format(it) } + "'")
            .toByteArray(Charsets.US_ASCII)
    }

    /**
     * 用指定钥匙形式开一条**裸连接**探一下能不能真读出来。
     *
     * 必须真读一次：SQLCipher 打开时不校验钥匙，第一次读盘才发现解不开。
     * 探针连接用完立刻关，不留给 Room 之后的开库添乱。
     */
    private fun probeOpens(file: File, keyBytes: ByteArray): Boolean = runCatching {
        SQLCipherDriver(keyBytes, null, null).open(file.absolutePath).use { conn ->
            conn.prepare("SELECT count(*) FROM sqlite_master").use { stmt -> stmt.step() }
        }
    }.isSuccess

    companion object {
        /** 生产库名。 */
        const val DATABASE_NAME = "noteone.db"
    }
}
