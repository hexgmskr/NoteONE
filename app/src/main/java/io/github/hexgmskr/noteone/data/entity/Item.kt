package io.github.hexgmskr.noteone.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条归档记录。
 *
 * content 是正文（链接、文本等）。note 是预留字段，MVP 阶段可能始终为 null，
 * 但表结构先占住位，避免将来加字段还要写 Migration（迁移脚本）。
 *
 * createdAt 上的索引是 v2 加的：列表查询一律 `ORDER BY createdAt DESC`，
 * 不建索引就是全表扫描。这是实现层补充，spec 3.1 未写。
 */
@Entity(
    tableName = "item",
    indices = [
        Index("createdAt"),
    ],
)
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val note: String? = null,
    /** 创建时刻，epoch 毫秒。 */
    val createdAt: Long,
    /** 最后修改时刻，epoch 毫秒。 */
    val updatedAt: Long,
)
