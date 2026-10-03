package io.github.hexgmskr.noteone.data.item

import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.dao.ItemTagDao
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.data.tag.TagRepository

/**
 * 记录的写入口：一条记录 + 它的标签，一次事务落库。
 *
 * 为什么要事务：item / tag / item_tag 是三张表分三步写，中途失败会留下
 * 「有记录没标签」或「有标签没记录」的半截状态。做成原子的，不给用户
 * 留需要手动清理的孤儿行。
 *
 * 事务由调用方注入（生产环境是 `RoomDatabase.withTransaction`，测试里直通），
 * 本类因此不依赖 [io.github.hexgmskr.noteone.data.db.NoteOneDatabase]，JVM 单测能直接跑。
 */
class ItemRepository(
    private val itemDao: ItemDao,
    private val tagRepository: TagRepository,
    private val itemTagDao: ItemTagDao,
    private val inTransaction: suspend (block: suspend () -> Long) -> Long,
) {

    /**
     * 保存一条记录并挂上标签草稿。
     *
     * 草稿经 [TagRepository.findOrCreate] 解析成真实标签——归一化去重在那边，
     * 这里不重复实现。解析后再按 `tag.id` 去一次重：「MV」和「mv」两条草稿会
     * 解析到同一个 tag，不去重就撞 `item_tag` 的 (itemId, tagId) 主键。
     *
     * @param content 已由调用方整理好的正文，本类不再 trim
     * @param now 创建/更新时间戳，epoch 毫秒
     * @return 新记录 id
     */
    suspend fun saveWithTags(content: String, drafts: List<TagDraft>, now: Long): Long =
        inTransaction {
            val itemId = itemDao.insert(Item(content = content, createdAt = now, updatedAt = now))
            drafts
                .map { tagRepository.findOrCreate(it.namespace, it.value) }
                .distinctBy { it.id }
                .forEach { tag -> itemTagDao.insert(ItemTag(itemId = itemId, tagId = tag.id)) }
            itemId
        }

    /**
     * 覆盖式更新一条已有记录：内容 + 标签整体重挂。
     *
     * 与 [saveWithTags] 同一套语义（草稿解析、按 tag.id 去重都收口在这里），区别只是
     * 目标已存在：`createdAt` 保持原值，`updatedAt` 写 [now]。
     *
     * 标签采用「先全清、再重挂」而不是差分：一条记录的标签量级很小，差分换不来
     * 收益，还容易留半截状态。整个更新在一个事务里，中途失败不留「新内容配旧标签」。
     *
     * @return 被更新的记录 id
     * @throws IllegalStateException 目标记录不存在（可能已被删）
     */
    suspend fun updateWithTags(itemId: Long, content: String, drafts: List<TagDraft>, now: Long): Long =
        inTransaction {
            val existing = itemDao.findById(itemId) ?: error("记录不存在")
            itemDao.update(existing.copy(content = content, updatedAt = now))
            itemTagDao.deleteAllForItem(itemId)
            drafts
                .map { tagRepository.findOrCreate(it.namespace, it.value) }
                .distinctBy { it.id }
                .forEach { tag -> itemTagDao.insert(ItemTag(itemId = itemId, tagId = tag.id)) }
            itemId
        }

    /**
     * 批量删除记录（列表多选删除用）。
     *
     * 关联行由外键 CASCADE 清掉。走事务口是为了守住本类「写路径都过事务接缝」
     * 的约定（单条 DELETE 语句本身已原子，事务在此是冗余但无害）。
     *
     * @return 实际删除的行数（传入 id 可能已不存在）
     */
    suspend fun deleteByIds(ids: List<Long>): Long {
        // SQLite 的 `IN ()` 是语法错误——空列表直接短路，不给调用方埋雷
        if (ids.isEmpty()) return 0L
        return inTransaction { itemDao.deleteByIds(ids).toLong() }
    }
}
