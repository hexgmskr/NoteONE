package io.github.hexgmskr.noteone.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个标签。
 *
 * 标签体系是**多维分面**，不是树状层级：namespace 是维度（如「长度」「主题」「手法」），
 * value 是该维度下的取值。二者共同标识一个标签，互不隶属。
 *
 * 唯一索引建在 `(namespace, normalizedValue)` 而不是 `(namespace, value)`：
 * 用户输入「MV」和「mv」应当被识别为同一个标签，展示为最早创建时的那个大小写。
 * 归一化规则见 docs/spec.md 3.3。
 */
@Entity(
    tableName = "tag",
    indices = [
        Index(value = ["namespace", "normalizedValue"], unique = true),
    ],
)
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 维度名。**不写死枚举**：以后新增维度（如「评分」「地点」）只加数据，
     * 不改表结构。任何标签都必须显式归属某个 namespace，不存在游离标签。
     */
    val namespace: String,
    /** 展示用原始值，保留用户输入的大小写，如 "MV"。 */
    val value: String,
    /** 归一化后用于去重匹配（小写 + 去首尾空格），如 "mv"。用户全程无感。 */
    val normalizedValue: String,
) {
    companion object {
        /**
         * 标签的展示顺序：先维度，再值。
         *
         * **全项目唯一一份**。列表项、筛选候选、标签面板都用它——
         * 多处各写一遍 compareBy 迟早会漂，漂了的表现是同一个词在两个地方
         * 排在不同位置，极难察觉。
         *
         * 排序偏好归展示层管理，不进核心表（spec 3.2）：表里不存 sortOrder，
         * 换排序规则不用写迁移。
         */
        val DISPLAY_ORDER: Comparator<Tag> =
            compareBy({ it.namespace }, { it.value })
    }
}
