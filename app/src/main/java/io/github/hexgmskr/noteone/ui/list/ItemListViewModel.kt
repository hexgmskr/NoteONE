package io.github.hexgmskr.noteone.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.dao.TagDao
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderSource
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面的展示状态。
 *
 * [allItems] 保留全量，[displayItems] 是筛选后的结果——
 * 这样切换筛选条件时不需要重新查库。
 */
data class ListUiState(
    val allItems: List<ItemWithTags> = emptyList(),
    val displayItems: List<ItemWithTags> = emptyList(),
    /**
     * 筛选条的候选标签，**已按展示顺序排好**。
     *
     * 空列表 = 还没有任何标签：此时筛选条整条不显示——
     * 候选空着只剩一个「全部」，点它等于什么都不做，不如不显示。
     */
    val candidateTags: List<Tag> = emptyList(),
    /**
     * 选中的筛选标签，可多选。**空集 = 不筛（「全部」）**。
     *
     * 多选语义见 [ItemListViewModel.applyFilter]：同维度取「或」、跨维度取「且」。
     */
    val selectedTagIds: Set<Long> = emptySet(),
    /** 标签展示顺序偏好。展示紧凑标签时要用它，否则自定义顺序不生效。 */
    val ordering: TagOrdering = TagOrdering(),
    val isLoading: Boolean = true,
    /**
     * 长按多选选中的记录 id。
     *
     * **选中集非空 = 处于多选模式**（没有单独的布尔开关）——
     * 取消掉最后一条就自然退出，不会出现"模式开着但一条没选"的悬空态。
     */
    val selectedItemIds: Set<Long> = emptySet(),
    /** 删除失败之类的行内错误提示；下一次 refresh 清掉。 */
    val error: String? = null,
) {
    /** 是否处于多选模式。UI 用它决定工具栏/筛选条/FAB 的显隐。 */
    val inSelectionMode: Boolean get() = selectedItemIds.isNotEmpty()
}

class ItemListViewModel(
    private val itemDao: ItemDao,
    private val tagDao: TagDao,
    private val tagOrderStore: TagOrderSource,
    /** 写路径（多选删除）；与 EditViewModel 同一约定：读写分家。 */
    private val items: ItemRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListUiState())
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 重新加载全部数据。 */
    fun refresh() {
        viewModelScope.launch {
            try {
                refreshInner()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 锁周期（后台超时）会关库；此刻在飞的刷新会抛——写操作回列表时
                // 必经这条。未捕获会崩进程（2026-10-04 审计 A5 的收尾）；锁周期里
                // 这个 VM 随即便被丢弃，这条消息正常没人看见，看到就是真出问题了。
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "读取失败：${e.message ?: "未知原因"}",
                )
            }
        }
    }

    private suspend fun refreshInner() {
        val current = _uiState.value
        val items = itemDao.findAllWithTags()
        val ordering = tagOrderStore.load()
        // 候选标签与列表项里的标签走同一份排序（TagOrdering）：
        // 用户调整过的顺序在筛选条和列表项里必须同进同出，
        // 否则同一个标签在两个位置排得不一样，看着像坏了。
        val candidates = tagDao.findAll().sortedWith(ordering.comparator)
        _uiState.value = ListUiState(
            allItems = items,
            displayItems = applyFilter(items, current.selectedTagIds, candidates),
            candidateTags = candidates,
            selectedTagIds = current.selectedTagIds,
            ordering = ordering,
            isLoading = false,
            // 多选态跨刷新保留，但只留仍然存在的 id
            // （在别处被删的记录自动掉出选中集，不会带着幽灵 id 去删）
            selectedItemIds = current.selectedItemIds intersect items.mapTo(mutableSetOf()) { it.item.id },
            error = null,
        )
    }

    /** 点选 / 取消一个筛选标签（多选，点一下切换）。 */
    fun toggleTag(tagId: Long) {
        val selected = _uiState.value.selectedTagIds
        updateFilter(if (tagId in selected) selected - tagId else selected + tagId)
    }

    /** 清空筛选，回到「全部」。 */
    fun clearFilter() = updateFilter(emptySet())

    // ---- 长按多选（删除用）----

    /** 长按一条卡片进入多选：选中集非空即多选模式（见 [ListUiState.selectedItemIds]）。 */
    fun startSelection(itemId: Long) {
        _uiState.value = _uiState.value.copy(selectedItemIds = setOf(itemId), error = null)
    }

    /** 多选模式下点卡片：切换选中；取消掉最后一条即自动退出多选。 */
    fun toggleSelection(itemId: Long) {
        val current = _uiState.value
        val next = if (itemId in current.selectedItemIds) {
            current.selectedItemIds - itemId
        } else {
            current.selectedItemIds + itemId
        }
        _uiState.value = current.copy(selectedItemIds = next, error = null)
    }

    /** 退出多选（取消按钮 / 返回手势）。 */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedItemIds = emptySet(), error = null)
    }

    /**
     * 删除选中的记录。界面的确认框过了才调。
     *
     * 成功后清选择并 [refresh]；失败**保留选择**（用户可以重试），错误走行内提示。
     */
    fun deleteSelected() {
        val ids = _uiState.value.selectedItemIds.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            try {
                items.deleteByIds(ids)
                _uiState.value = _uiState.value.copy(selectedItemIds = emptySet())
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "删除失败：${e.message ?: "未知原因"}")
            }
        }
    }

    private fun updateFilter(selected: Set<Long>) {
        val current = _uiState.value
        _uiState.value = current.copy(
            selectedTagIds = selected,
            displayItems = applyFilter(current.allItems, selected, current.candidateTags),
        )
    }

    /**
     * 多选筛选的语义：**同维度取「或」，跨维度取「且」**。
     *
     * 例（维度：长度 / 主题）：
     *  - 选「长视频」+「短视频」（同维度）→ 两者任一命中的记录都留下——
     *    若这里取「且」会筛成空（没有记录同时是长视频又是短视频），
     *    看起来就像坏了。多选同一维度表达的是「这几种都行」。
     *  - 再加选「萌宠」（另一维度）→ 在上面基础上必须也带「萌宠」。
     *    跨维度是层层收窄，表达的是「既要…又要…」。
     *
     * 这是分面检索的标准语义（facets）：维度内是选项，维度间是条件。
     * **排序**：命中选中标签**越多越靠前**（同维度多选时，"两个都带"的记录
     * 浮到最上面）；命中数相同的保持原有顺序（列表本身是 createdAt 倒序，
     * [sortedByDescending] 是稳定排序，天然保住）。
     *
     * 筛选在内存里做而不重查库：条目量是低频采集的量级，全量载入后过滤更简单，
     * 切换筛选条件也不用等 IO。
     *
     * 与 [io.github.hexgmskr.noteone.data.dao.ItemDao.findByTag] 是**同一份
     * item_tag 关联数据的两种取法**，不是两套语义：那边走 SQL 子查询，
     * 这边走 `@Relation` 装回来的 [io.github.hexgmskr.noteone.data.entity.ItemWithTags.tags]，
     * 两条路都源自 item_tag，因此不会筛出不同结果。UI 用内存版，SQL 版留给
     * 查询场景（如标签管理页的引用计数）。
     */
    private fun applyFilter(
        items: List<ItemWithTags>,
        selected: Set<Long>,
        candidateTags: List<Tag>,
    ): List<ItemWithTags> {
        if (selected.isEmpty()) return items

        // 按维度把选中的标签分组。维度名从候选标签里查（选中态只存 id）。
        // 查不到的 id 理论上不该出现（选中态只由筛选条写入），真出现就让它
        // 各自成组、按「且」处理——行为可预期，不会静默失效。
        val namespaceOf = candidateTags.associate { it.id to it.namespace }
        val groupOf = selected.groupBy { namespaceOf[it] ?: "?$it" }

        return items
            .filter { entry ->
                val ownTagIds = entry.tags.mapTo(mutableSetOf()) { it.id }
                // 每个维度至少要命中一个选中值（维度间「且」，维度内「或」）
                groupOf.values.all { ids -> ids.any { it in ownTagIds } }
            }
            .sortedByDescending { entry ->
                val ownTagIds = entry.tags.mapTo(mutableSetOf()) { it.id }
                selected.count { it in ownTagIds }
            }
    }
}
