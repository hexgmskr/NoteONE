package io.github.hexgmskr.noteone.data.export

import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.dao.ItemTagDao
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.tag.TagRepository

/** 一次导入的结果：新增几条、跳过几条（判定为已存在）。 */
data class ImportReport(val imported: Int, val skipped: Int)

/**
 * 把解析好的记录合并进库。**用户拍板：只做合并追加**——
 * 库里已有的记录不动、不提供"清空替换"。导入的定位是灾难恢复，不是搬迁工具：
 * 恢复时最怕的就是手一抖把现有数据换掉，而合并追加天然幂等、碰不到这个破坏面。
 * （记录删除的 UI 已交付，但这条定位不变。）
 *
 * 去重口径：`(content, createdAt)` 完全相同的记录视为"同一条"，跳过。
 *  - 同一份文件重复导入 = 无事发生（幂等）；
 *  - 之后手动录了同样内容、但时间不同的一条，**不算**重复（可能是刻意的）。
 * 文件内部自带的重复也会去（同一份文件里两条一模一样的，第二条按重复跳过）。
 *
 * 整个导入在一个事务里：要么全进，要么一条不进——不留"导了一半"的状态。
 * 事务口由调用方注入（生产走 `NoteOneDatabase.inTransaction`），本类可在 JVM 直测。
 *
 * 标签复用 [TagRepository.findOrCreate]：归一化去重（"MV"/"mv" 同一条）、
 * 保留最早的大小写，全是既有语义，这里不重造。
 */
class Importer(
    private val itemDao: ItemDao,
    private val tagRepository: TagRepository,
    private val itemTagDao: ItemTagDao,
    private val inTransaction: suspend (suspend () -> ImportReport) -> ImportReport,
) {

    /**
     * @param items 已由 [ImportParser] 校验过的记录
     * @param now 本次导入的"落库"时间戳（写入 updatedAt；createdAt 保留文件里的原值）
     */
    suspend fun import(items: List<ImportItem>, now: Long): ImportReport = inTransaction {
        // 已存在的 (content, createdAt)：一次性读进内存。个人量级（几千条封顶），
        // 比"每条一次查询"简单且同样正确；add() 的返回值顺手把文件内部重复也去了。
        val seen = itemDao.findAll().mapTo(HashSet()) { it.content to it.createdAt }

        var imported = 0
        var skipped = 0

        for (item in items) {
            if (!seen.add(item.content to item.createdAt)) {
                skipped++
                continue
            }

            val itemId = itemDao.insert(
                Item(
                    content = item.content,
                    note = item.note,
                    createdAt = item.createdAt,
                    updatedAt = now,
                ),
            )
            item.tags
                .map { tag -> tagRepository.findOrCreate(tag.namespace, tag.value) }
                .distinctBy { it.id }
                .forEach { tag -> itemTagDao.insert(ItemTag(itemId = itemId, tagId = tag.id)) }
            imported++
        }

        ImportReport(imported = imported, skipped = skipped)
    }
}
