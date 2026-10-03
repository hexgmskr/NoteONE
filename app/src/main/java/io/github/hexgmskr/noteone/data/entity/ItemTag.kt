package io.github.hexgmskr.noteone.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 记录与标签的多对多关联（中间表）。
 *
 * `onDelete = CASCADE`：删记录或删标签时，这里的关联行自动跟着删掉，
 * 不会留下指向已消失对象的悬空引用。
 *
 * itemId 和 tagId 都建了索引——**这是实现层补充，spec 原文没写**：
 * 外键列不建索引 Room 会告警，而且「按标签筛选记录」会退化成全表扫描。
 */
@Entity(
    tableName = "item_tag",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Item::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("itemId"),
        Index("tagId"),
    ],
)
data class ItemTag(
    val itemId: Long,
    val tagId: Long,
    /** 预留：标签展示顺序。 */
    val sortOrder: Int = 0,
)
