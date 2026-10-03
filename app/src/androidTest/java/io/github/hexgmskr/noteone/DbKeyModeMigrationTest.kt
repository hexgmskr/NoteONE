package io.github.hexgmskr.noteone

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.db.DbKey
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import io.github.hexgmskr.noteone.data.db.SqlCipher
import io.github.hexgmskr.noteone.data.db.SqlCipherDatabaseProvider
import io.github.hexgmskr.noteone.data.entity.Item
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEK 喂法迁移的回归保护（2026-10-03）。
 *
 * 背景：旧实现把 32 字节 DEK 当"口令"交给 SQLCipher，库每次开库都多跑一遍
 * 25.6 万次 PBKDF2——真机实测**每次解锁白烧约 1 秒**（口令模式 999ms vs
 * raw key 31ms）。新实现改用 raw key（`x'<64位hex>'`）并在开库时一次性把
 * 旧格式文件 rekey 迁移（见 [SqlCipherDatabaseProvider.resolveKeyBytes]）。
 *
 * 本测试按**生产路径**验证迁移：先用"旧实现"的写法造一个口令模式库（= 迁移前
 * 生产库的样子），再交给真正的 Provider 打开，断言自动迁移、数据完好、
 * 旧口令失效、文件仍非明文。
 *
 * **只碰隔离库名**（红线：androidTest 永远不许用 noteone.db）。
 */
@RunWith(AndroidJUnit4::class)
class DbKeyModeMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 模拟 DEK：32 字节。固定值保证可重复（真 DEK 随机，迁移逻辑与值无关）。 */
    private val dekBytes: ByteArray = ByteArray(32) { (it * 7 + 11).toByte() }

    @Before
    fun setUp() = cleanUpFiles()

    @After
    fun tearDown() = cleanUpFiles()

    /** 按"旧实现"（口令模式）造库——迁移前生产库就是这个样子。 */
    private fun createLegacyPassphraseDb() {
        SqlCipher.load()
        val db = Room.databaseBuilder(context, NoteOneDatabase::class.java, DB_NAME)
            .setDriver(SQLCipherDriver(dekBytes, null, null))
            .build()
        try {
            runBlocking {
                db.itemDao().insert(Item(content = "迁移乘客", createdAt = 1000, updatedAt = 1000))
            }
        } finally {
            db.close()
        }
    }

    /** 开库 + 第一条真实查询（Room.build() 是懒的，真正的开库动作在第一次读写）。 */
    private fun openAndFirstRead(db: NoteOneDatabase): List<Item> =
        runBlocking { db.itemDao().findAll() }

    @Test
    fun legacyPassphraseDb_isAutoMigratedByProvider_andDataSurvives() {
        createLegacyPassphraseDb()

        // 旧格式的"每次解锁代价"：关掉重开 + 首查（这就是那个 ~1 秒）
        val t0 = SystemClock.uptimeMillis()
        val legacy = Room.databaseBuilder(context, NoteOneDatabase::class.java, DB_NAME)
            .setDriver(SQLCipherDriver(dekBytes, null, null))
            .build()
        try {
            openAndFirstRead(legacy)
        } finally {
            legacy.close()
        }
        val passphraseMs = SystemClock.uptimeMillis() - t0

        // 生产 Provider 打开：内部 raw 探测失败 → 口令能开 → 自动 rekey → 走 raw
        val provider = SqlCipherDatabaseProvider(context, DB_NAME)
        val migrated = provider.open(DbKey(dekBytes))
        val rows = try {
            openAndFirstRead(migrated)
        } finally {
            migrated.close()
        }
        assertEquals("迁移后记录数变了——数据没活过迁移！", 1, rows.size)
        assertEquals("迁移乘客", rows.first().content)

        // 迁移后再开一次（走 raw 快路径）并计时——两组数字只记日志，不做时间断言
        // （设备负载会让时间断言变假红）；功能正确性由上面的断言保证。
        val t1 = SystemClock.uptimeMillis()
        val fast = provider.open(DbKey(dekBytes))
        try {
            openAndFirstRead(fast)
        } finally {
            fast.close()
        }
        val rawMs = SystemClock.uptimeMillis() - t1
        Log.d(TAG, "OPENCOST passphraseMs=$passphraseMs rawKeyMs=$rawMs")
    }

    @Test
    fun afterMigration_oldPassphraseAndPlaintextBothRefused() {
        createLegacyPassphraseDb()

        // 触发迁移
        val provider = SqlCipherDatabaseProvider(context, DB_NAME)
        provider.open(DbKey(dekBytes)).close()

        // 1) 旧口令（= 把 DEK 当口令用）再也打不开——文件真的换了钥匙
        val withOldPassphrase = runCatching {
            val db = Room.databaseBuilder(context, NoteOneDatabase::class.java, DB_NAME)
                .setDriver(SQLCipherDriver(dekBytes, null, null))
                .build()
            try {
                openAndFirstRead(db)
            } finally {
                db.close()
            }
        }
        assertTrue(
            "迁移后旧口令居然还能打开——迁移没生效或加密失效！$withOldPassphrase",
            withOldPassphrase.isFailure,
        )

        // 2) 文件头仍不是明文 SQLite 魔数——整库加密仍在
        val header = RandomAccessFile(context.getDatabasePath(DB_NAME), "r").use { input ->
            ByteArray(PLAINTEXT_SQLITE_MAGIC.size).also { input.read(it) }
        }
        assertFalse(
            "迁移后库文件头是明文 SQLite 魔数——整库加密没有生效！",
            header.contentEquals(PLAINTEXT_SQLITE_MAGIC),
        )
    }

    @Test
    fun `a corrupted or foreign file is refused with a clear error and left untouched`() {
        // 垃圾字节冒充库文件：既不是本机 raw key 的库、也不是口令模式的合法库。
        // 这是 A4 那条"解锁失败而不是崩溃"的直接触发器——必须抛**带话术**的错，
        // 而且绝不能改动那个文件（用户还有靠导出文件恢复的余地）。
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4096) { 0x5A })
        val before = file.readBytes()

        val provider = SqlCipherDatabaseProvider(context, DB_NAME)
        val thrown = runCatching { provider.open(DbKey(dekBytes)) }.exceptionOrNull()

        assertTrue("必须抛错（而不是静默开出一个空库），实际=$thrown", thrown != null)
        assertTrue(
            "错误话术要点明「钥匙不匹配或文件损坏」：${thrown?.message}",
            thrown?.message?.contains("钥匙不匹配或文件损坏") == true,
        )
        assertTrue("文件一个字节都不能被动过", file.readBytes().contentEquals(before))
    }

    private fun cleanUpFiles() = context.deleteTestDatabase(DB_NAME)

    private companion object {
        const val TAG = "DbKeyMigration"

        /** **隔离库名**——红线：测试永远不许用生产名 noteone.db。 */
        const val DB_NAME = "dbkey-migration-test.db"

        /** 明文 SQLite 文件头的前 16 字节。 */
        val PLAINTEXT_SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
