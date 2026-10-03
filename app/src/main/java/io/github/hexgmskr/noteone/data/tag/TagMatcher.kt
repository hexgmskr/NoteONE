package io.github.hexgmskr.noteone.data.tag

import io.github.hexgmskr.noteone.data.entity.Tag

/** 一个维度下的标签分组，供「选已有标签」面板按维度陈列（spec 7.3）。 */
data class NamespaceGroup(val namespace: String, val tags: List<Tag>)

/**
 * 标签面板的两条纯逻辑：模糊匹配、按维度分组（spec 7.3）。
 *
 * 与 [TagNormalizer] 是一对——那边产出归一化 key 用于去重，这边按同一个 key
 * 做匹配，保证「输入 mv 能提示到 MV」和「mv 与 MV 是同一个标签」永远同进同出。
 */
object TagMatcher {

    /** 建议条数上限。面板是提示不是列表，铺一屏反而要翻。 */
    const val MAX_SUGGESTIONS = 6

    /**
     * 找出与 [query] 模糊匹配的已有标签。
     *
     * 排序：完全相同 → 前缀 → 子串，同档内按 [Tag.DISPLAY_ORDER]。
     * [query] 归一化后为空就不给建议——空输入跟什么都像，等于没提示。
     *
     * **跨维度匹配**：同名词在不同维度下是不同标签（faceted），
     * 建议里带上 namespace 就是让看的人自己分辨该选哪一个，
     * 而不是在这儿替用户做「只在当前维度里找」的假设。
     */
    fun suggest(
        query: String,
        candidates: List<Tag>,
        limit: Int = MAX_SUGGESTIONS,
        order: Comparator<Tag> = Tag.DISPLAY_ORDER,
    ): List<Tag> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()

        return candidates
            .mapNotNull { tag ->
                val rank = when {
                    tag.normalizedValue == normalizedQuery -> 0
                    tag.normalizedValue.startsWith(normalizedQuery) -> 1
                    tag.normalizedValue.contains(normalizedQuery) -> 2
                    else -> return@mapNotNull null
                }
                rank to tag
            }
            .sortedWith(Comparator { a, b ->
                val byRank = a.first.compareTo(b.first)
                if (byRank != 0) byRank else order.compare(a.second, b.second)
            })
            .take(limit)
            .map { it.second }
    }

    /**
     * 按维度分组陈列。
     *
     * 先按 [Tag.DISPLAY_ORDER] 排全量，再 groupBy——分组顺序跟着维度名走，
     * 组内跟着值走，就是 DISPLAY_ORDER 在分组视图上的样子。
     */
    fun groupByNamespace(
        tags: List<Tag>,
        order: Comparator<Tag> = Tag.DISPLAY_ORDER,
    ): List<NamespaceGroup> =
        tags.sortedWith(order)
            .groupBy { it.namespace }
            .map { (namespace, group) -> NamespaceGroup(namespace, group) }
}
