package io.github.hexgmskr.noteone.data.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * 一条记录及其全部标签。用于列表展示和按标签筛选。
 *
 * 通过中间表 [ItemTag] 做多对多关联：
 * `parentColumn = "item.id"` → `ItemTag.itemId` → `ItemTag.tagId` → `tag.id`。
 * Room 在编译期生成 JOIN，不需要手写 SQL。
 */
data class ItemWithTags(
    @Embedded val item: Item,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            ItemTag::class,
            parentColumn = "itemId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
) {
    /**
     * 展示用的确定性顺序：先按 namespace，再按 value。
     *
     * **请在展示时用这个，不要直接用 [tags]**——`@Relation` 装回来的列表
     * 顺序由 SQLite 决定，不作保证。同一条记录刷新一下，标签顺序就可能变，
     * 表现为列表里的标签看着「乱跳」。这个 bug 在阶段 2 才会显形，
     * 到时候会误以为是 Compose 的问题，故现在就把契约定死。
     *
     * 不用 `ItemTag.sortOrder`：那是给用户手动排序预留的（spec 3.1），
     * 且 spec 3.2 明确「排序偏好归展示层管理，不进核心表」。
     * 现阶段没有手动排序需求，故取一个稳定默认序即可。
     */
    val sortedTags: List<Tag>
        get() = tags.sortedWith(Tag.DISPLAY_ORDER)
}
