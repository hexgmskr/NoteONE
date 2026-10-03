package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.dao.ItemTagDao
import io.github.hexgmskr.noteone.data.dao.TagDao
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag

/**
 * JVM 测试用的假 DAO。
 *
 * 只模仿读写行为，不做归一化/去重——那是 [io.github.hexgmskr.noteone.data.tag.TagRepository]
 * 的语义，真要验证去重得看它对 DAO 的调用方式，所以这里按「按 normalizedValue 精确查」
 * 的契约如实实现 [TagDao.findByNormalized]，不多也不少。
 *
 * 内存存放、非线程安全：JVM 单测是顺序跑的。
 */

internal class FakeItemDao : ItemDao {
    /** 记录表本体：insert/update/delete/deleteByIds/findById/findAll 都操作它。 */
    val inserted = mutableListOf<Item>()
    var shouldFail = false

    /**
     * 「记录 + 标签」的读结果，由测试直接摆好（列表页筛选测试用）。
     *
     * 假 DAO 不做 `@Relation` 关联查询——那要真 Room 库；这里如实返回
     * 测试摆好的结果，与真 DAO 的契约（返回全量、按 createdAt 倒序由调用方保证）一致。
     *
     * **与 [inserted] 的关系**：测试要摆数据就两个都摆（读路径从这里取、写路径断言看
     * [inserted]）；而写操作（update/delete/deleteByIds）会**同时维护两个视图**，
     * 模拟真库"写完之后再读是新的"。insert 不自动进这里——关联标签摆不出来。
     */
    var itemsWithTags: List<ItemWithTags> = emptyList()

    override suspend fun insert(item: Item): Long {
        if (shouldFail) throw RuntimeException("db exploded")
        inserted += item.copy(id = inserted.size + 1L)
        return inserted.size.toLong()
    }

    override suspend fun update(item: Item) {
        if (shouldFail) throw RuntimeException("db exploded")
        val index = inserted.indexOfFirst { it.id == item.id }
        if (index >= 0) inserted[index] = item
        itemsWithTags = itemsWithTags.map {
            if (it.item.id == item.id) it.copy(item = item) else it
        }
    }

    override suspend fun delete(item: Item) {
        inserted.removeAll { it.id == item.id }
        itemsWithTags = itemsWithTags.filterNot { it.item.id == item.id }
    }

    override suspend fun deleteByIds(ids: List<Long>): Int {
        if (shouldFail) throw RuntimeException("db exploded")
        val before = inserted.size
        inserted.removeAll { it.id in ids }
        itemsWithTags = itemsWithTags.filterNot { it.item.id in ids }
        return before - inserted.size
    }

    /** 先查记录表；查不到时回退到关联读的摆设（编辑载入路径用它兜底）。 */
    override suspend fun findById(id: Long): Item? =
        inserted.find { it.id == id } ?: itemsWithTags.find { it.item.id == id }?.item

    override suspend fun findAll(): List<Item> = inserted

    override suspend fun findAllWithTags(): List<ItemWithTags> = itemsWithTags
    override suspend fun findByTag(tagId: Long): List<ItemWithTags> = emptyList()
    override suspend fun findWithTagsById(id: Long): ItemWithTags? =
        itemsWithTags.find { it.item.id == id }
}

internal class FakeTagDao : TagDao {
    val inserted = mutableListOf<Tag>()
    var shouldFail = false

    override suspend fun insert(tag: Tag): Long {
        if (shouldFail) throw RuntimeException("db exploded")
        val id = inserted.size + 1L
        inserted += tag.copy(id = id)
        return id
    }

    override suspend fun update(tag: Tag) {
        if (shouldFail) throw RuntimeException("db exploded")
        val index = inserted.indexOfFirst { it.id == tag.id }
        if (index >= 0) inserted[index] = tag
    }

    override suspend fun delete(tag: Tag) {
        inserted.removeAll { it.id == tag.id }
    }

    override suspend fun deleteByIds(ids: List<Long>): Int {
        if (shouldFail) throw RuntimeException("db exploded")
        val before = inserted.size
        inserted.removeAll { it.id in ids }
        return before - inserted.size
    }

    override suspend fun findById(id: Long): Tag? = inserted.find { it.id == id }

    /** 契约如实实现：按 (namespace, normalizedValue) 精确匹配。 */
    override suspend fun findByNormalized(namespace: String, normalizedValue: String): Tag? =
        inserted.find { it.namespace == namespace && it.normalizedValue == normalizedValue }

    override suspend fun findByNamespace(namespace: String): List<Tag> =
        inserted.filter { it.namespace == namespace }

    override suspend fun allNamespaces(): List<String> = inserted.map { it.namespace }.distinct()
    override suspend fun findAll(): List<Tag> = inserted
}

internal class FakeItemTagDao : ItemTagDao {
    val inserted = mutableListOf<ItemTag>()
    var shouldFail = false

    override suspend fun insert(itemTag: ItemTag) {
        if (shouldFail) throw RuntimeException("db exploded")
        inserted += itemTag
    }

    override suspend fun delete(itemTag: ItemTag) {
        inserted.remove(itemTag)
    }

    override suspend fun deleteAllForItem(itemId: Long) {
        if (shouldFail) throw RuntimeException("db exploded")
        inserted.removeAll { it.itemId == itemId }
    }

    /**
     * 契约如实实现 `UPDATE OR REPLACE`：撞复合主键的目标行先被顶掉，
     * 净效果是一条记录只挂一次目标标签。
     */
    override suspend fun repointTag(fromId: Long, intoId: Long): Int {
        if (shouldFail) throw RuntimeException("db exploded")
        val affectedItems = inserted.filter { it.tagId == fromId }.map { it.itemId }
        inserted.removeAll { it.tagId == intoId && it.itemId in affectedItems }
        var moved = 0
        inserted.replaceAll { link ->
            if (link.tagId == fromId) {
                moved++
                link.copy(tagId = intoId)
            } else {
                link
            }
        }
        return moved
    }

    override suspend fun tagsOfItem(itemId: Long): List<ItemTag> = inserted.filter { it.itemId == itemId }
    override suspend fun itemsOfTag(tagId: Long): List<ItemTag> = inserted.filter { it.tagId == tagId }
    override suspend fun tagCountOfItem(itemId: Long): Int = inserted.count { it.itemId == itemId }
}
