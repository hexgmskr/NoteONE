package io.github.hexgmskr.noteone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.db.DbKey
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import io.github.hexgmskr.noteone.data.db.SqlCipherDatabaseProvider
import io.github.hexgmskr.noteone.data.db.inTransaction
import io.github.hexgmskr.noteone.data.export.Exporter
import io.github.hexgmskr.noteone.data.export.ImportParser
import io.github.hexgmskr.noteone.data.export.Importer
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.data.tag.TagRepository
import java.security.SecureRandom
import java.time.OffsetDateTime
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
 * **导出 → 导入的端到端往返 + 失败原子性**（2026-10-04 补，审计 #4 覆盖缺口）。
 *
 * 为什么值得单独守：这是全 App 唯一的**数据搬运通道**（灾难恢复就靠它），
 * 但此前只有底层工具级证明（ExportJson/PasswordSeal/ImportParser 各自的单测），
 * 从没有一次"真 SQLCipher 库 → 导出密文 → 解密解析 → 导进另一个真库"的完整往返，
 * 也没有对"中途失败必须整批回滚"的证明。
 *
 * 全部走**隔离库名**（红线），真 provider（含 raw key 与探测）、真 driver 模式事务。
 */
@RunWith(AndroidJUnit4::class)
class ExportImportRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val srcName = "export-import-src.db"
    private val dstName = "export-import-dst.db"

    /** 真随机 32 字节，模拟解锁流程解出来的 DEK（两个库共用一把，省事且不损语义）。 */
    private val key = DbKey(ByteArray(32).also(SecureRandom()::nextBytes))

    /** 与生产同一条路：真 provider（raw key 模式 + 探测 + 迁移逻辑）。 */
    private fun open(name: String): NoteOneDatabase =
        SqlCipherDatabaseProvider(context, name).open(key)

    private fun repository(db: NoteOneDatabase) = ItemRepository(
        itemDao = db.itemDao(),
        tagRepository = TagRepository(db.tagDao(), db.itemTagDao()),
        itemTagDao = db.itemTagDao(),
        inTransaction = { block -> db.inTransaction { block() } },
    )

    private fun importer(db: NoteOneDatabase) = Importer(
        itemDao = db.itemDao(),
        tagRepository = TagRepository(db.tagDao(), db.itemTagDao()),
        itemTagDao = db.itemTagDao(),
        inTransaction = { block -> db.inTransaction { block() } },
    )

    @Before
    fun setUp() = cleanUpFiles()

    @After
    fun tearDown() = cleanUpFiles()

    @Test
    fun `export then import into a fresh database reproduces records and is idempotent`() {
        val password = "端到端往返用的长口令-20261004".toCharArray()
        val exportedAt = OffsetDateTime.now()

        // 1) 源库：三条带标签的记录（走生产写路径：repository + driver 模式事务）
        val src = open(srcName)
        val contents = listOf("端到端-alpha", "端到端-beta", "端到端-gamma")
        try {
            runBlocking {
                val repo = repository(src)
                repo.saveWithTags(contents[0], listOf(TagDraft("主题", "端测A"), TagDraft("长度", "短视频")), 1_000)
                repo.saveWithTags(contents[1], listOf(TagDraft("主题", "端测B")), 2_000)
                repo.saveWithTags(contents[2], listOf(TagDraft("主题", "端测A"), TagDraft("手法", "延时")), 3_000)
            }
        } finally {
            src.close()
        }

        // 2) 导出（加密形态，与 VM 同一条调用），并从源库读出对照集
        val srcRead = open(srcName)
        val original = try {
            runBlocking { srcRead.itemDao().findAllWithTags() }
        } finally {
            srcRead.close()
        }
        assertEquals(3, original.size)

        val envelopeText = Exporter.encryptedText(original, exportedAt, password, iterations = 1_000)
        assertFalse("密文里不该出现明文内容", envelopeText.contains(contents[0]))

        // 3) 解密 → 解析 → 导进另一个**真库**
        val json = Exporter.openEncryptedText(envelopeText, password)
        val parsed = ImportParser.parseItems(json)
        assertEquals(3, parsed.size)

        val dst = open(dstName)
        try {
            val report = runBlocking { importer(dst).import(parsed, now = 9_000) }
            assertEquals("应新增三条", 3, report.imported)
            assertEquals(0, report.skipped)

            val restored = runBlocking { dst.itemDao().findAllWithTags() }
            assertEquals("记录数应一致", 3, restored.size)
            assertEquals(
                "正文集合应一致",
                contents.toSet(),
                restored.map { it.item.content }.toSet(),
            )
            assertEquals(
                "标签集合应一致（含维度归属）",
                original.flatMap { it.tags.map { t -> t.namespace to t.value } }.toSet(),
                restored.flatMap { it.tags.map { t -> t.namespace to t.value } }.toSet(),
            )

            // 4) 幂等：同一份再导一次 → 全部跳过、库里不多不少
            val again = runBlocking { importer(dst).import(parsed, now = 10_000) }
            assertEquals("重复导入不应新增", 0, again.imported)
            assertEquals("重复导入应全部跳过", 3, again.skipped)
            assertEquals(3, runBlocking { dst.itemDao().findAll().size })
        } finally {
            dst.close()
        }
    }

    @Test
    fun `a failure mid-import rolls back the whole batch`() {
        val dst = open(dstName)
        try {
            // 预置一条，用来确认"回滚后它没被动过"
            runBlocking {
                repository(dst).saveWithTags("回滚基线", listOf(TagDraft("主题", "基线")), 1_000)
            }
            val tagsBefore = runBlocking { dst.tagDao().findAll().size }
            val linksBefore = runBlocking { dst.itemDao().findAll().size }

            // 注入失败：SQL 级触发器——插到内容为 "boom" 时 ABORT。
            // 用一条**独立的裸连接**建（同 DbKeyModeMigrationTest 的既有做法）：
            // SQLite 的 schema 变更会 bump schema cookie，Room 那条连接下一条语句
            // 就会重新准备、立刻看到它。
            val rawKey = ("x'" + key.bytes.joinToString("") { "%02x".format(it) } + "'")
                .toByteArray(Charsets.US_ASCII)
            SQLCipherDriver(rawKey, null, null)
                .open(context.getDatabasePath(dstName).absolutePath)
                .use { conn ->
                    conn.prepare(
                        """
                        CREATE TRIGGER inject_failure BEFORE INSERT ON item
                        WHEN NEW.content = 'boom'
                        BEGIN SELECT RAISE(ABORT, '注入的失败'); END
                        """.trimIndent(),
                    ).use { stmt -> while (stmt.step()) { /* 跑到结束 */ } }
                }

            // 一批三条，第二条炸——前一条已插入、第三条还没轮到
            val batch = ImportParser.parseItems(
                """
                {"schema_version":1,"exported_at":"2026-10-04T00:00:00Z","items":[
                  {"content":"回滚-第一","created_at":2000,"tags":[{"namespace":"主题","value":"回滚测"}]},
                  {"content":"boom","created_at":3000,"tags":[{"namespace":"主题","value":"回滚测"}]},
                  {"content":"回滚-第三","created_at":4000,"tags":[{"namespace":"主题","value":"回滚测"}]}
                ]}
                """.trimIndent(),
            )
            val thrown = runCatching {
                runBlocking { importer(dst).import(batch, now = 9_000) }
            }.exceptionOrNull()
            assertTrue("注入的失败必须冒出来，实际=$thrown", thrown != null)

            // **整批回滚**：第一/三条都没进去，标签与关联也没留下半截
            val contents = runBlocking { dst.itemDao().findAll().map { it.content } }
            assertEquals("只应剩下回滚基线那一条", listOf("回滚基线"), contents)
            assertEquals("记录数不变", linksBefore, contents.size)
            assertEquals("标签数不变（新建的主题标签必须一并回滚）", tagsBefore, runBlocking { dst.tagDao().findAll().size })
        } finally {
            dst.close()
        }
    }

    private fun cleanUpFiles() {
        listOf(srcName, dstName).forEach { context.deleteTestDatabase(it) }
    }
}
