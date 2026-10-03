package io.github.hexgmskr.noteone.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移脚本（docs/spec.md 第 5 节）。
 *
 * **每次表结构变更都必须在这里补一个 Migration，禁止使用
 * `fallbackToDestructiveMigration()`**——那个方法在结构不匹配时会直接清空数据库，
 * 与「数据保存是重点」的核心需求直接冲突。
 *
 * 书写规则：
 *  1. 每个 Migration 只覆盖一个版本跨度（1→2、2→3），
 *     Room 会自动按顺序串联，但不允许跳号
 *  2. SQL 必须能作用在**旧版本的实际结构**上，写完要跑
 *     [io.github.hexgmskr.noteone.MigrationTest] 回归
 *  3. 新表结构见 `app/schemas/` 下对应的 JSON，那是判官
 */
object Migrations {

    /**
     * v1 → v2：给 `item.createdAt` 加索引。
     *
     * 动因：列表查询一律 `ORDER BY createdAt DESC`，v1 无索引时是全表扫描。
     *
     * `CREATE INDEX IF NOT EXISTS` 是刻意的：迁移脚本应可重入，
     * 万一索引已存在（例如用户装过开发中间版本）不会炸。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_item_createdAt` ON `item` (`createdAt`)"
            )
        }
    }

    /**
     * v2 → v3：删除探针用的 `probe_row` 表。
     *
     * 这张表是阶段 0 验证 SQLCipher 时的脚手架，不进产品数据模型。
     * 从 `@Database` 里移除实体属于表结构变更，因此也要走迁移，
     * 不能直接删——那样遇到已存在的 v2 库会因结构不匹配而崩。
     *
     * `DROP TABLE IF EXISTS` 同样是刻意的：可重入。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `probe_row`")
        }
    }

    /** 注册全部迁移。开库时按版本号自动串联。 */
    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
