package io.github.hexgmskr.noteone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.dao.ItemTagDao
import io.github.hexgmskr.noteone.data.dao.TagDao
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.entity.Tag

/**
 * Room 数据库。
 *
 * 三表结构见 docs/spec.md 第 3 节：item / tag / item_tag，
 * 标签是多维分面体系，去重靠 tag 表的 `(namespace, normalizedValue)` 联合唯一索引。
 *
 * **禁止 `fallbackToDestructiveMigration()`**——那个方法在结构不匹配时会直接清空数据库，
 * 与「数据保存是重点」的核心需求冲突。表结构变更必须写 [Migrations] 脚本。
 *
 * 版本历史：
 *  - v1：三表初始结构
 *  - v2：item.createdAt 加索引（见 [Migrations.MIGRATION_1_2]）
 *  - v3：删除阶段 0 探针遗留的 probe_row 表（见 [Migrations.MIGRATION_2_3]）
 *
 * exportSchema = true：每个版本的表结构导出到 `app/schemas/`，
 * 交给 MigrationTestHelper 构造旧版本测试库。**这些 JSON 必须入库**。
 */
@Database(
    entities = [
        Item::class,
        Tag::class,
        ItemTag::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class NoteOneDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun tagDao(): TagDao
    abstract fun itemTagDao(): ItemTagDao
}
