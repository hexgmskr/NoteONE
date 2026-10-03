package io.github.hexgmskr.noteone

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hexgmskr.noteone.data.db.Migrations
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 步骤 4：迁移脚本回归。
 *
 * 唯一未知数是「迁移机制是否真的能把旧数据搬到新结构」。
 * 这条链路只有真机能验（MigrationTestHelper 需要真实的 SQLite 引擎）。
 *
 * 用 [MigrationTestHelper] 从 `assets/schemas/1.json` 构造一个**真实的 v1 库**，
 * 再跑 MIGRATION_1_2，最后拿 v2 的 schema 做结构校验 + 数据校验。
 * 这是唯一能证明迁移正确的方式：手写 SQL 对不对，只有拿真旧库跑一遍才知道。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val helper = MigrationTestHelper(
        instrumentation,
        NoteOneDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesDataAndAddsIndex() {
        // 1. 按 v1 schema 建一个真实的旧库，塞入数据
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO item (id, content, note, createdAt, updatedAt)
                VALUES (1, 'https://example.com/old', NULL, 1000, 1000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO item (id, content, note, createdAt, updatedAt)
                VALUES (2, 'second', 'has note', 2000, 2000)
                """.trimIndent()
            )
            execSQL("INSERT INTO tag (id, namespace, value, normalizedValue) VALUES (1, '主题', 'MV', 'mv')")
            execSQL("INSERT INTO item_tag (itemId, tagId, sortOrder) VALUES (1, 1, 0)")
            close()
        }

        // 2. 跑迁移，拿 v2 schema 校验结构
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        // 3. 数据必须原样还在——这是「数据保存是重点」的底线
        db.query("SELECT COUNT(*) FROM item").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("迁移后记录条数不应变化", 2, c.getInt(0))
        }
        db.query("SELECT content FROM item WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://example.com/old", c.getString(0))
        }
        db.query("SELECT note FROM item WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("has note", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM item_tag").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("关联表不应丢行", 1, c.getInt(0))
        }

        // 4. 新索引必须真的建出来了
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_item_createdAt'"
        ).use { c ->
            assertTrue("v2 的 createdAt 索引必须已创建", c.count > 0)
        }
        db.close()
    }

    @Test
    fun migrate1To2_indexIsUsableForOrdering() {
        helper.createDatabase(TEST_DB, 1).apply {
            for (i in 1..50) {
                execSQL(
                    "INSERT INTO item (id, content, note, createdAt, updatedAt) " +
                        "VALUES ($i, 'c$i', NULL, ${i * 10}, ${i * 10})"
                )
            }
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        // 迁移后仍能按 createdAt 正确倒序（索引不该破坏排序正确性）
        val ids = mutableListOf<Int>()
        db.query("SELECT id FROM item ORDER BY createdAt DESC LIMIT 3").use { c ->
            while (c.moveToNext()) ids.add(c.getInt(0))
        }
        assertEquals(listOf(50, 49, 48), ids)
        db.close()
    }

    @Test
    fun migration1To2_isIdempotent() {
        // CREATE INDEX IF NOT EXISTS 的目的就是可重入：万一索引已存在不该炸
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO item (id, content, note, createdAt, updatedAt) VALUES (1, 'x', NULL, 1, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)
        // 再手工执行一次同一条 SQL，模拟重复迁移
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_createdAt` ON `item` (`createdAt`)")
        db.query("SELECT COUNT(*) FROM item").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3_dropsProbeTableAndKeepsData() {
        // 1. 按 v2 schema 建旧库（v2 里还有 probe_row），塞入业务数据
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO item (id, content, note, createdAt, updatedAt) " +
                    "VALUES (1, 'keep-me', NULL, 1000, 1000)"
            )
            execSQL("INSERT INTO tag (id, namespace, value, normalizedValue) VALUES (1, '主题', 'MV', 'mv')")
            execSQL("INSERT INTO item_tag (itemId, tagId, sortOrder) VALUES (1, 1, 0)")
            // probe_row 是 v3 要删的脚手架表，v2 的 schema 里它存在
            execSQL("INSERT INTO probe_row (id, text) VALUES (1, 'scaffold')")
            close()
        }

        // 2. 跑 2→3，拿 v3 schema 校验结构
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        // 3. 业务数据必须原样还在
        db.query("SELECT content FROM item WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("keep-me", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM tag").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM item_tag").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        // 4. probe_row 必须真的没了
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'probe_row'"
        ).use { c ->
            assertEquals("probe_row 表必须被删除", 0, c.count)
        }
        db.close()
    }

    @Test
    fun migrate1To3_chainedMigrationsKeepData() {
        // 1→2→3 串联跑，验证两条迁移接得上（Room 会自动串联）
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO item (id, content, note, createdAt, updatedAt) " +
                    "VALUES (1, 'chain', NULL, 1000, 1000)"
            )
            execSQL("INSERT INTO probe_row (id, text) VALUES (1, 'x')")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true,
            Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3,
        )

        db.query("SELECT content FROM item WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("chain", c.getString(0))
        }
        // v2 引入的索引要还在
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_item_createdAt'"
        ).use { c ->
            assertTrue("串联迁移后 createdAt 索引应存在", c.count > 0)
        }
        // v3 删掉的表要没了
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'probe_row'"
        ).use { c ->
            assertEquals(0, c.count)
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
