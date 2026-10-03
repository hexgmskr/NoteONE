package io.github.hexgmskr.noteone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.db.DatabaseProvider
import io.github.hexgmskr.noteone.data.db.DbKey
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import io.github.hexgmskr.noteone.data.db.SqlCipher
import io.github.hexgmskr.noteone.data.entity.Item
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SQLCipher 整库加密的回归保护。
 *
 * 前身是阶段 0 的探针测试，用来回答「这套工具链能不能用 SQLCipher 开加密 Room 库」。
 * 问题已经回答（能），现在这个测试的角色转为**回归保护**：防止将来改动接缝或
 * 升级依赖时把加密通路弄坏。
 *
 * 三个用例各证一件事：
 *   1. correctKey_canReadWrite        —— 密钥 A 建库写入再读回，基本通路
 *   2. sameKey_reopenRetainsData      —— 关掉再用密钥 A 重开，数据仍在，真落盘
 *   3. wrongKey_mustFailToRead        —— 用密钥 B 重开同一文件，读取必须失败
 *
 * **第三条才是「真的加密了」的证明**：少了它，前两条在「根本没加密」的情况下
 * 同样会通过。这条一旦失守，就是整库加密静默失效，风险最高。
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherEncryptionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "encryption-test.db"

    /** 32 字节密钥（raw key 模式要求 AES-256 的 32 字节；固定值保证可重复）。 */
    private val keyA = DbKey(ByteArray(32) { (it + 1).toByte() })
    private val keyB = DbKey(ByteArray(32) { (it + 101).toByte() })

    /**
     * 走的是接缝 A，验证这条缝确实能承载加密实现。
     * 阶段 3 会把这个实现挪进正式的 DatabaseProvider。
     */
    private inner class SqlCipherDatabaseProvider : DatabaseProvider {
        /** 这个替身不参与"创建恢复密钥"的护栏，按真实情况如实回答即可。 */
        override fun exists(): Boolean = context.getDatabasePath(dbName).exists()

        override fun open(key: DbKey): NoteOneDatabase {
            SqlCipher.load()
            // **与生产一致：raw key 形式**（x'<64位hex>'），SQLCipher 见此前缀跳过 KDF
            val rawKey = ("x'" + key.bytes.joinToString("") { "%02x".format(it) } + "'")
                .toByteArray(Charsets.US_ASCII)
            return Room.databaseBuilder(context, NoteOneDatabase::class.java, dbName)
                .setDriver(SQLCipherDriver(rawKey, null, null))
                .build()
        }
    }

    @Before
    fun setUp() {
        cleanUpFiles()
    }

    @After
    fun tearDown() {
        cleanUpFiles()
    }

    /** 用密钥 A 开库，插入一条 Item，返回 id。用完即关。 */
    private fun insertItem(text: String, at: Long): Long {
        val db = SqlCipherDatabaseProvider().open(keyA)
        try {
            return runBlocking {
                db.itemDao().insert(Item(content = text, createdAt = at, updatedAt = at))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun correctKey_canReadWrite() {
        val db = SqlCipherDatabaseProvider().open(keyA)
        try {
            val id = runBlocking {
                db.itemDao().insert(Item(content = "hello", createdAt = 1000, updatedAt = 1000))
            }
            val readBack = runBlocking { db.itemDao().findById(id) }
            assertEquals("hello", readBack!!.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun sameKey_reopenRetainsData() {
        val id = insertItem("persisted", 2000)

        val db = SqlCipherDatabaseProvider().open(keyA)
        try {
            val readBack = runBlocking { db.itemDao().findById(id) }
            assertEquals("persisted", readBack!!.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun wrongKey_mustFailToRead() {
        val id = insertItem("secret", 3000)

        val db = SqlCipherDatabaseProvider().open(keyB)
        try {
            // 光 open() 不一定立刻报错，真正读盘时才发现解不开
            val stolen = runCatching {
                runBlocking { db.itemDao().findById(id) }
            }
            assertTrue(
                "用错误密钥读取居然成功了，说明整库加密没生效！结果=$stolen",
                stolen.isFailure,
            )
            assertNotNull(stolen.exceptionOrNull())
        } finally {
            db.close()
        }
    }

    private fun cleanUpFiles() = context.deleteTestDatabase(dbName)
}
