package io.github.hexgmskr.noteone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.db.DbKey
import io.github.hexgmskr.noteone.data.db.SqlCipherDatabaseProvider
import io.github.hexgmskr.noteone.data.db.inTransaction
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.data.tag.TagRepository
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SQLCipher 版接缝 A（阶段 3-2）的真机回归。
 *
 * 走**生产路径**（真 provider、真落盘、真迁移注册），但**用隔离库名**
 * `provider-sqlcipher-test.db`——绝不能用生产名，
 * 否则本测试的清理会删掉用户真库（HANDOFF §4 的教训）。
 *
 * 这里回答的问题比探针更进一步：探针问"这条通路能不能走通"，
 * 这里问"它在接缝里到底是不是按我们要的方式在工作"——
 * 尤其最后一条：**库文件真不是明文**（文件头不是 SQLite 明文魔数）。
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherDatabaseProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = SqlCipherDatabaseProvider(context, TEST_DB_NAME)

    /** 真随机 32 字节，模拟解锁流程解出来的 DEK。 */
    private val key = DbKey(ByteArray(32).also(SecureRandom()::nextBytes))

    /** 另一把钥匙：用于"开错钥匙必须失败"。 */
    private val otherKey = DbKey(ByteArray(32).also(SecureRandom()::nextBytes))

    @Before
    fun setUp() = cleanUp()

    @After
    fun tearDown() = cleanUp()

    // ---- 基本通路 ----

    @Test
    fun writeAndReadBack() {
        val db = provider.open(key)
        try {
            val id = runBlocking {
                db.itemDao().insert(Item(content = "via-seam-encrypted", createdAt = 1, updatedAt = 1))
            }
            val read = runBlocking { db.itemDao().findById(id) }
            assertNotNull("写入后读不回（id=$id）", read)
            assertEquals("via-seam-encrypted", read!!.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun dataSurvivesReopen() {
        val id = run {
            val db = provider.open(key)
            try {
                runBlocking {
                    db.itemDao().insert(Item(content = "persisted-enc", createdAt = 1, updatedAt = 1))
                }
            } finally {
                db.close()
            }
        }

        val db = provider.open(key)
        try {
            assertEquals("persisted-enc", runBlocking { db.itemDao().findById(id) }!!.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun wrongKeyCannotRead() {
        val id = run {
            val db = provider.open(key)
            try {
                runBlocking {
                    db.itemDao().insert(Item(content = "secret", createdAt = 1, updatedAt = 1))
                }
            } finally {
                db.close()
            }
        }

        // raw key 模式后，换错钥匙在**开库那一刻**就被挡住（两种钥匙形式都探测不过），
        // 比"能开但读不出"更早——判据不变：拿不到数据。开+读整体包起来断言失败。
        val stolen = runCatching {
            val db = provider.open(otherKey)
            try {
                runBlocking { db.itemDao().findById(id) }
            } finally {
                db.close()
            }
        }
        assertTrue("换错钥匙居然读到了，说明加密没生效！", stolen.isFailure)
    }

    @Test
    fun schemaVersionOnDiskIsCurrent() {
        // 必须先真读写一次：Room 的 build() 是懒的——不碰库就不会创建文件
        // （踩过一次：只 open+close 的话文件根本不存在，随后直读会"现建"一个空库，
        //  读出来 user_version=0、零张表，看着像加密/迁移出了问题）。
        val db = provider.open(key)
        try {
            runBlocking {
                db.itemDao().insert(Item(content = "version-check", createdAt = 1, updatedAt = 1))
            }
        } finally {
            db.close()
        }

        // driver 模式下 Room 的 openHelper 不可用（会抛"no SupportSQLiteOpenHelper.Factory"），
        // 所以拿 SQLCipher 自己的 API 直读 user_version：
        // 这同时证明落盘的是"带当前 schema 版本的真库"，而不是别的什么文件。
        // **钥匙要用 raw key 形式**（x'<64位hex>'）：库文件现在就是这个模式，
        // 拿口令形式的 key.bytes 开会被拒（file is not a database）。
        val rawKeyBytes = ("x'" + key.bytes.joinToString("") { "%02x".format(it) } + "'")
            .toByteArray(Charsets.US_ASCII)
        val raw = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(TEST_DB_NAME).absolutePath,
            rawKeyBytes,
            null,
            null,
        )
        try {
            assertEquals("库文件的 schema 版本应是当前版本（迁移已注册）", 3, raw.version)
        } finally {
            raw.close()
        }
    }

    // ---- 事务（driver 模式专用口，曾被 Room 传统事务口坑过）----

    @Test
    fun writerTransactionCommits() {
        val db = provider.open(key)
        try {
            val result = runBlocking {
                db.inTransaction {
                    db.itemDao().insert(Item(content = "tx-1", createdAt = 1, updatedAt = 1))
                    db.itemDao().insert(Item(content = "tx-2", createdAt = 2, updatedAt = 2))
                    "done"
                }
            }

            assertEquals("done", result)
            assertEquals(2, runBlocking { db.itemDao().findAll() }.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun writerTransactionRollsBackOnFailure() {
        val db = provider.open(key)
        try {
            runCatching {
                runBlocking {
                    db.inTransaction {
                        db.itemDao().insert(Item(content = "ghost", createdAt = 1, updatedAt = 1))
                        throw IllegalStateException("半路失败")
                    }
                }
            }.onSuccess { throw AssertionError("事务里的异常必须冒出来") }

            assertEquals(
                "事务失败后一条都不该落库",
                0,
                runBlocking { db.itemDao().findAll() }.size,
            )
        } finally {
            db.close()
        }
    }

    /**
     * 编辑/删除的写路径在 driver 模式下真的能跑：更新 + 清关联 + 重挂 +
     * CASCADE 删除，全走 [ItemRepository] 的真实事务接线。
     *
     * 为什么专门钉这条：SQLCipher 用 driver 模式开库，事务走手写口
     * （[inTransaction]）而不是 Room 的 `withTransaction`——"driver 模式 +
     * 手写事务"这个组合历史上被 48 条全绿测试漏放过，最后靠真机点"保存"才暴露
     * （HANDOFF §4）。本用例是第一次在事务里做「DELETE + INSERT」，留条哨兵。
     */
    @Test
    fun repositoryUpdateRewritesTagsAndDeleteCascadesInDriverMode() {
        val db = provider.open(key)
        try {
            val repo = ItemRepository(
                itemDao = db.itemDao(),
                tagRepository = TagRepository(db.tagDao(), db.itemTagDao()),
                itemTagDao = db.itemTagDao(),
                inTransaction = { block -> db.inTransaction { block() } },
            )

            val id = runBlocking {
                repo.saveWithTags(
                    content = "旧内容",
                    drafts = listOf(TagDraft("主题", "萌宠")),
                    now = 100L,
                )
            }

            // 编辑：换内容、换标签
            runBlocking {
                repo.updateWithTags(
                    itemId = id,
                    content = "新内容",
                    drafts = listOf(TagDraft("手法", "延时摄影")),
                    now = 200L,
                )
            }

            val after = runBlocking { db.itemDao().findWithTagsById(id) }!!
            assertEquals("新内容", after.item.content)
            assertEquals("createdAt 不该被编辑动到", 100L, after.item.createdAt)
            assertEquals("updatedAt 应记下本次编辑", 200L, after.item.updatedAt)
            assertEquals("标签应整体重挂", listOf("延时摄影"), after.tags.map { it.value })

            // 删除：item_tag 由外键 CASCADE 清掉
            assertEquals(1L, runBlocking { repo.deleteByIds(listOf(id)) })
            assertEquals(0, runBlocking { db.itemTagDao().tagCountOfItem(id) })
            // 标签不随记录回收（换下来的旧标签也留着）——筛选项里还能再用
            assertNotNull(
                "标签本身不该被误删",
                runBlocking { db.tagDao().findByNormalized("手法", "延时摄影") },
            )
        } finally {
            db.close()
        }
    }

    // ---- 文件层面的证明 ----

    @Test
    fun databaseFileIsNotPlaintextSqlite() {
        // 建库并写一条，确保文件真的落盘了
        val db = provider.open(key)
        try {
            runBlocking {
                db.itemDao().insert(Item(content = "header-check", createdAt = 1, updatedAt = 1))
            }
        } finally {
            db.close()
        }

        val file = context.getDatabasePath(TEST_DB_NAME)
        assertTrue("库文件不存在：$file", file.exists())

        val header = file.inputStream().use { input ->
            ByteArray(PLAINTEXT_SQLITE_MAGIC.size).also { input.read(it) }
        }
        assertFalse(
            "库文件头是明文 SQLite 魔数——整库加密没有生效！",
            header.contentEquals(PLAINTEXT_SQLITE_MAGIC),
        )
    }

    private fun cleanUp() = context.deleteTestDatabase(TEST_DB_NAME)

    private companion object {
        /** 隔离库名。**永远不要改成生产名。** */
        const val TEST_DB_NAME = "provider-sqlcipher-test.db"

        /** 明文 SQLite 文件头的前 16 字节。 */
        val PLAINTEXT_SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
