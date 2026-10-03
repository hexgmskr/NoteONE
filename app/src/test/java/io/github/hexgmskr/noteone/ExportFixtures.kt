package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 导出测试共用的夹具（[ExportJsonTest] 与 [ExporterTest]）。
 *
 * 固定时间戳与固定数据：导出是确定性输出，测试全部按全文/全字节比对。
 */
internal val TEST_EXPORTED_AT: OffsetDateTime =
    OffsetDateTime.of(2026, 10, 1, 23, 30, 0, 0, ZoneOffset.ofHours(8))

/**
 * 拼一条记录 + 标签的测试数据。
 *
 * 注意 tags **不保证顺序**地传进来（按传入顺序存着），
 * 导出应该走 [ItemWithTags.sortedTags] 重排——这正是要测的点之一。
 */
internal fun testItem(
    id: Long,
    content: String,
    createdAt: Long,
    note: String? = null,
    vararg tags: Pair<String, String>,
): ItemWithTags = ItemWithTags(
    item = Item(id = id, content = content, note = note, createdAt = createdAt, updatedAt = createdAt),
    tags = tags.mapIndexed { index, (namespace, value) ->
        Tag(
            id = index + 1L,
            namespace = namespace,
            value = value,
            // 内部去重字段：导出**不得**包含它，测试专门断言它不出现
            normalizedValue = value.lowercase(),
        )
    },
)
