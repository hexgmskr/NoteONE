package io.github.hexgmskr.noteone.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.hexgmskr.noteone.data.entity.Tag

/** 标签表的存取接口。 */
@Dao
interface TagDao {


    @Insert
    suspend fun insert(tag: Tag): Long

    @Update
    suspend fun update(tag: Tag)

    /**
     * 按 id 批量删除（标签管理的多选删除用）。
     *
     * 单条 DELETE 语句天然原子；`item_tag` 里指向这些标签的关联行由外键 CASCADE
     * 一并清掉——**记录本身保留**，只是不再挂这个标签。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM tag WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    /** 当前主代码未用（删除走 [deleteByIds]）；测试的级联用例在用。 */
    @Delete
    suspend fun delete(tag: Tag)

    /** 当前主代码未用；测试观察级联时配合 [delete] 使用。 */
    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun findById(id: Long): Tag?

    /**
     * 按归一化值精确匹配。这是「MV」和「mv」判为同一个标签的查询入口。
     *
     * 走的是 `(namespace, normalizedValue)` 联合唯一索引，不走全表扫描。
     */
    @Query("SELECT * FROM tag WHERE namespace = :namespace AND normalizedValue = :normalizedValue")
    suspend fun findByNormalized(namespace: String, normalizedValue: String): Tag?

    /** 某个维度下的全部标签，按展示值排序。 */
    @Query("SELECT * FROM tag WHERE namespace = :namespace ORDER BY value COLLATE NOCASE")
    suspend fun findByNamespace(namespace: String): List<Tag>

    /** 全部维度名，去重。当前主代码未用（面板分组走 TagMatcher），保留作通用查询。 */
    @Query("SELECT DISTINCT namespace FROM tag ORDER BY namespace COLLATE NOCASE")
    suspend fun allNamespaces(): List<String>

    @Query("SELECT * FROM tag ORDER BY namespace COLLATE NOCASE, value COLLATE NOCASE")
    suspend fun findAll(): List<Tag>
}
