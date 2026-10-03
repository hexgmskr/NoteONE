package io.github.hexgmskr.noteone.data.tag

import io.github.hexgmskr.noteone.data.dao.ItemTagDao
import io.github.hexgmskr.noteone.data.dao.TagDao
import io.github.hexgmskr.noteone.data.entity.Tag

/**
 * 标签的写入口。
 *
 * 把「归一化 + 查重 + 建」这套语义收在一个地方，DAO 保持只管存取。
 * 这是 spec 3.3「MV 与 mv 是同一个标签」的唯一实现处——
 * 别处不要自己拼 normalizedValue，否则会出现两个不一致的去重口径。
 *
 * 标签管理（改名 / 合并，2026-10-03）也在这里：合并要动 `item_tag` 的引用，
 * 仍然属于"标签怎么改"的语义，不该散到界面层去。
 */
class TagRepository(
    private val tagDao: TagDao,
    private val itemTagDao: ItemTagDao,
) {

    /**
     * 取得一个标签，不存在则创建。
     *
     * **保留最早创建时的大小写**：用户先建了「MV」，之后输「mv」会拿到「MV」那条，
     * 不会产生第二个标签，也不会改写已有展示值。
     *
     * @param namespace 维度名，如「长度」「主题」。首尾空格会被去掉
     * @param rawValue 用户原始输入，大小写会被保留在展示字段里
     * @return 已有或新建的标签
     * @throws IllegalArgumentException namespace 或 rawValue 归一化后为空
     */
    suspend fun findOrCreate(namespace: String, rawValue: String): Tag {
        val ns = normalizeNamespace(namespace)
        val normalized = TagNormalizer.normalize(rawValue)

        tagDao.findByNormalized(ns, normalized)?.let { existing ->
            return existing
        }

        val fresh = Tag(
            namespace = ns,
            value = rawValue,
            normalizedValue = normalized,
        )
        val id = tagDao.insert(fresh)
        return fresh.copy(id = id)
    }

    /**
     * 批量版本。用于一条记录打多个标签（spec 7.3）。
     *
     * 同一次调用里出现重复项时，后面的会复用前面建出来的，不产生重复行。
     */
    suspend fun findOrCreateAll(namespace: String, rawValues: List<String>): List<Tag> {
        val result = LinkedHashMap<String, Tag>()
        for (raw in rawValues) {
            val normalized = TagNormalizer.normalize(raw)
            result.getOrPut(normalized) {
                findOrCreate(namespace, raw)
            }
        }
        return result.values.toList()
    }

    // ---- 标签管理：改名 / 合并（2026-10-03）----

    /**
     * 重命名一个标签：只换展示值与归一化值，维度不变。引用的是 id，天然保留。
     *
     * **同维度撞名会被拒绝**（而不是静默合并）：`(namespace, normalizedValue)` 有
     * 唯一索引，硬改会撞库；而且"改名"与"合并"是两件事——撞名时让调用方明确选择
     * 合并到对方，避免一次改名顺手吞掉另一个标签的记录。
     *
     * @param newValue 调用方整理过的展示值（本方法会 trim）
     * @throws IllegalArgumentException 新值为空
     * @throws IllegalStateException 名字没变 / 同维度已有同名标签
     */
    suspend fun rename(tag: Tag, newValue: String): Tag {
        val ns = normalizeNamespace(tag.namespace)
        val trimmed = newValue.trim()
        val normalized = TagNormalizer.normalize(trimmed)
        require(normalized.isNotEmpty()) { "标签值不能为空" }

        if (normalized == tag.normalizedValue) {
            throw IllegalStateException("名字没变")
        }
        tagDao.findByNormalized(ns, normalized)?.let { clash ->
            throw IllegalStateException("同维度已有「${clash.value}」——改用「合并到它」")
        }

        val renamed = tag.copy(value = trimmed, normalizedValue = normalized)
        tagDao.update(renamed)
        return renamed
    }

    /**
     * 批量删除标签（标签管理的多选删除）。
     *
     * 标签会从所有挂过它的记录上消失（`item_tag` 由外键 CASCADE 清掉），
     * **记录本身保留**。单条 DELETE 语句已原子，不额外包事务。
     *
     * @return 实际删除的行数（传入 id 可能已不存在）
     */
    suspend fun deleteByIds(ids: List<Long>): Long {
        // SQLite 的 `IN ()` 是语法错误——空列表直接短路
        if (ids.isEmpty()) return 0L
        return tagDao.deleteByIds(ids).toLong()
    }

    /**
     * 把 [from] 合并进 [into]：引用整体改挂到目标，再删掉来源。
     *
     * **两条护栏都在这里**（2026-10-04 审计）：不能合并到自己；必须同维度。
     * 界面本来就只列同维度的候选，但护栏不能只靠 UI 自觉——跨维度合并会产出
     * "标签挂错轴"的静默污点数据（分面体系里不同维度是不同的轴），
     * 而这类数据事后很难发现、更难清理。
     *
     * 失败窗口说明：改挂成功后若删除失败（极罕见），来源标签会剩一个
     * "零引用"的空壳——无害且可重试（再做一次合并即可），因此不额外包事务。
     */
    suspend fun merge(from: Tag, into: Tag) {
        require(from.id != into.id) { "不能合并到自己" }
        require(from.namespace == into.namespace) {
            "不能跨维度合并：「${from.namespace}」的标签只能并进同维度的标签"
        }
        itemTagDao.repointTag(fromId = from.id, intoId = into.id)
        tagDao.delete(from)
    }

    /**
     * 维度名的边界规则。
     *
     * **必须非空**：spec 3.2 明确禁止「游离标签」——标签必须归属某个维度。
     * 仅靠 `(namespace, normalizedValue)` 联合唯一索引并不能保证这一点，
     * 那个索引只保证「给定 namespace 内不重复」，管不了 namespace 本身是不是空的。
     * 所以校验必须在写入口这层做。
     *
     * 首尾空格去掉：否则「长度」与「长度 」会变成两个幽灵维度，
     * 与 value 侧的归一化口径不一致。
     *
     * **大小写不折叠**（已知边界）：spec 3.3 只对 value 定义了归一化，没提 namespace。
     * 所以「MV」与「mv」作为维度名会是两个不同维度。中文维度名无此问题。
     * 若将来英文维度名成为实际用法，再决定是否补归一化——届时需要加
     * normalizedNamespace 列并写迁移。
     */
    private fun normalizeNamespace(rawNamespace: String): String {
        val ns = rawNamespace.trim()
        require(ns.isNotEmpty()) {
            "维度名不能为空（标签必须归属某个 namespace，见 spec 3.2）"
        }
        return ns
    }
}
