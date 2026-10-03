package io.github.hexgmskr.noteone.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import io.github.hexgmskr.noteone.data.entity.ItemTag

/** 关联表的存取接口。 */
@Dao
interface ItemTagDao {

    @Insert
    suspend fun insert(itemTag: ItemTag)

    @Delete
    suspend fun delete(itemTag: ItemTag)

    /** 清掉某条记录的全部标签关联（编辑记录时整体重挂用）。 */
    @Query("DELETE FROM item_tag WHERE itemId = :itemId")
    suspend fun deleteAllForItem(itemId: Long)

    /**
     * 把某个标签的全部关联改挂到另一个标签（标签合并用）。
     *
     * `OR REPLACE` 处理撞主键：一条记录同时挂了来源与目标时，
     * 目标侧的旧行会被替换掉——净效果是一条记录只挂一次目标标签。
     *
     * @return 被改挂的行数（含被 REPLACE 掉的撞键行，仅作参考）
     */
    @Query("UPDATE OR REPLACE item_tag SET tagId = :intoId WHERE tagId = :fromId")
    suspend fun repointTag(fromId: Long, intoId: Long): Int

    @Query("SELECT * FROM item_tag WHERE itemId = :itemId")
    suspend fun tagsOfItem(itemId: Long): List<ItemTag>

    /** 当前主代码未用（测试的级联用例在用）；保留作通用查询。 */
    @Query("SELECT * FROM item_tag WHERE tagId = :tagId")
    suspend fun itemsOfTag(tagId: Long): List<ItemTag>

    /**
     * 某条记录用了多少标签。
     *
     * 当前主代码未用；测试靠它观察"删记录/删标签后关联行被 CASCADE 清掉"。
     */
    @Query("SELECT COUNT(*) FROM item_tag WHERE itemId = :itemId")
    suspend fun tagCountOfItem(itemId: Long): Int
}
