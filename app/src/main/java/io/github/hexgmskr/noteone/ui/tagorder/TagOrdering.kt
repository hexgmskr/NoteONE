package io.github.hexgmskr.noteone.ui.tagorder

import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.tag.TagDraft

/**
 * 标签展示顺序的**用户自定义部分**。
 *
 * CLAUDE.md / spec 3.2：排序偏好归展示层管理，不进核心表。
 * 所以这里不碰 Room，持久化交给 [TagOrderStore]（SharedPreferences）。
 *
 * 两层顺序，对应紧凑展示的两层间距：
 *  1. **维度间**（组间）：`#延时摄影  #长视频` 谁在前
 *  2. **维度内**（组内）：`#灵动#自然#萌宠` 谁在前
 *
 * 不在清单里的项保持 Unicode 码点序，且排在已定义项**之后**。
 * 这样用户只改关心的那几个，其余不被打扰；新建的标签落进未定义区，
 * 位置可预期（不会突然插到最前面）。
 *
 * @param namespaceOrder 维度名的展示顺序，靠前的先显示
 * @param valueOrder 维度内值的展示顺序，key 是维度名，
 *   value 是该维度下归一化值的序列（用归一化值做 key，与去重口径一致）
 */
data class TagOrdering(
    val namespaceOrder: List<String> = emptyList(),
    val valueOrder: Map<String, List<String>> = emptyMap(),
) {

    private val nsRank: Map<String, Int> =
        namespaceOrder.withIndex().associate { (i, ns) -> ns to i }

    private val valueRank: Map<Pair<String, String>, Int> =
        valueOrder.flatMap { (ns, values) ->
            values.withIndex().map { (i, normalized) -> (ns to normalized) to i }
        }.toMap()

    /** 维度名的展示顺序。 */
    val namespaceComparator: Comparator<String> = compareBy(
        { name: String -> rankOf(nsRank[name]) },
        { name: String -> name },
    )

    /** 标签的展示顺序。全项目标签排序都该走它。 */
    val comparator: Comparator<Tag> = compareBy(
        { tag: Tag -> rankOf(nsRank[tag.namespace]) },
        { tag: Tag -> tag.namespace },
        { tag: Tag -> rankOf(valueRank[tag.namespace to tag.normalizedValue]) },
        { tag: Tag -> tag.normalizedValue },
    )

    /**
     * 标签草稿的展示顺序，与 [comparator] 同口径。
     *
     * 草稿还没落库，没有 [Tag.normalizedValue]，用 [TagDraft.normalizedValueOrNull]
     * 现算；算不出来（值是空白）就排最后。
     */
    val draftComparator: Comparator<TagDraft> = compareBy(
        { draft: TagDraft -> rankOf(nsRank[draft.namespace.trim()]) },
        { draft: TagDraft -> draft.namespace.trim() },
        { draft: TagDraft ->
            rankOf(valueRank[draft.namespace.trim() to (draft.normalizedValueOrNull() ?: "")])
        },
        { draft: TagDraft -> draft.normalizedValueOrNull() ?: "" },
    )

    /** 维度名按当前顺序排。 */
    fun sortNamespaces(names: List<String>): List<String> = names.sortedWith(namespaceComparator)

    /**
     * 整体替换维度顺序。[newOrder] 应是**完整序列**——
     * 调整页把已定义和未定义的一起列出来，用户挪完再整体写回，
     * 未定义项就此转正，之后位置固定。
     */
    fun withNamespaceOrder(newOrder: List<String>): TagOrdering =
        copy(namespaceOrder = newOrder)

    /** 整体替换某个维度下的值顺序，语义同 [withNamespaceOrder]。 */
    fun withValueOrder(namespace: String, newOrder: List<String>): TagOrdering =
        copy(valueOrder = valueOrder + (namespace to newOrder))

    /** 未自定义的排最后。 */
    private fun rankOf(rank: Int?): Int = rank ?: Int.MAX_VALUE
}

/**
 * 标签的紧凑展示：`#长视频  #延时摄影  #萌宠#灵动#自然`
 *
 * **只显示值，不显示维度名**。维度名（长度/主题/拍摄手法）是分类体系的
 * 定义，不是记录的内容——展示它既没信息量又占位置。真正需要明文露出维度名
 * 的只有「定义分类体系」的场合（新建维度输入框、标签管理页），查看记录时不需要。
 *
 * 维度归属靠**分组间距**暗示，不靠文字标注：同一维度的值挨着写
 * （`#萌宠#灵动#自然`），不同维度之间留空（`#长视频  #延时摄影`）。
 * 顺序由 [TagOrdering] 保证，所以分组位置是稳定的。
 */
fun List<Tag>.toCompactDisplay(ordering: TagOrdering = TagOrdering()): String =
    sortedWith(ordering.comparator)
        .groupBy { it.namespace }
        .values.joinToString("  ") { group ->
            group.joinToString("") { "#${it.value}" }
        }
