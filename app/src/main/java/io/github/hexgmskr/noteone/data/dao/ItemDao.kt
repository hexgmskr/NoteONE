package io.github.hexgmskr.noteone.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemWithTags

/** 记录表的存取接口。只管读写，不掺业务规则。 */
@Dao
interface ItemDao {

    /** 插入并返回自增 id。 */
    @Insert
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)

    /**
     * 按 id 批量删除（列表多选删除用）。
     *
     * 单条 DELETE 语句天然原子；`item_tag` 的关联行由外键 CASCADE 一并清掉。
     *
     * @return 实际删除的行数（传入的 id 可能已经不存在）
     */
    @Query("DELETE FROM item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT * FROM item WHERE id = :id")
    suspend fun findById(id: Long): Item?

    /** 按创建时间倒序，最新的在前。 */
    @Query("SELECT * FROM item ORDER BY createdAt DESC")
    suspend fun findAll(): List<Item>

    /**
     * 全部记录连同各自的标签。
     *
     * `@Transaction` 是必须的：[io.github.hexgmskr.noteone.data.entity.ItemWithTags]
     * 的关系映射会发多条查询，不加事务可能读到不一致的中间状态。
     */
    @Transaction
    @Query("SELECT * FROM item ORDER BY createdAt DESC")
    suspend fun findAllWithTags(): List<ItemWithTags>

    /**
     * 按标签筛选记录（spec 7.2）。
     *
     * 子查询走 item_tag 的 tagId 索引，不扫全表。
     *
     * **主界面筛选不走这里**，走 [io.github.hexgmskr.noteone.ui.list.ItemListViewModel]
     * 的内存过滤（全量已装入，切换筛选不用等 IO）。两者源自同一份 item_tag
     * 关联，结果必然一致，不存在两套筛选语义。本方法留给需要「只要这一批」
     * 的查询场景——当前无人调用，保留作通用查询（测试在用）。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM item
        WHERE id IN (SELECT itemId FROM item_tag WHERE tagId = :tagId)
        ORDER BY createdAt DESC
        """
    )
    suspend fun findByTag(tagId: Long): List<ItemWithTags>

    /** 单条记录连同标签。 */
    @Transaction
    @Query("SELECT * FROM item WHERE id = :id")
    suspend fun findWithTagsById(id: Long): ItemWithTags?
}
