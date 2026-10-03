package io.github.hexgmskr.noteone.ui.tagorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.dao.TagDao
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.tag.TagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 标签顺序调整页的状态。
 *
 * 一次只调一层：先在维度间挪，还是进某个维度挪它的值。[editingValues]
 * 为空表示正在调维度间顺序，非空表示正在调该维度内的值顺序。
 */
data class TagOrderUiState(
    /** 维度间顺序。 */
    val namespaces: List<String> = emptyList(),
    /** 正在调值顺序的维度；null 表示在调维度间顺序。 */
    val editingValues: String? = null,
    /** 当前正在调的那组值（归一化值的展示顺序，存的是原始值）。 */
    val values: List<String> = emptyList(),
    /** 正在改名/合并的标签；null = 没开对话框（2026-10-03 标签管理）。 */
    val managing: Tag? = null,
    /** [managing] 同维度的其他标签（合并候选，按展示顺序）。 */
    val mergeCandidates: List<Tag> = emptyList(),
    /** 改名输入框。 */
    val renameInput: String = "",
    /** 合并需要二次确认：点了哪个候选，就等这里的确认。 */
    val pendingMerge: Tag? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 是否正在调某个维度内的值顺序。 */
    val isEditingValues: Boolean get() = editingValues != null
}

/**
 * 标签管理页（原"标签顺序调整页"，2026-10-03 并入改名/合并）。
 *
 * 三件事：顺序的读入写出、改名、合并。
 * 排序规则本身在 [TagOrdering]，这里只负责「哪一项上移/下移」；
 * 改名/合并的写语义在 [io.github.hexgmskr.noteone.data.tag.TagRepository]。
 *
 * 改名/合并后会顺手**把展示顺序偏好迁移过去**（改名：旧归一化值换成新的，
 * 保住用户调过的位置；合并：去掉来源的条目，目标保留自己的位置）——
 * 顺序偏好归展示层（CLAUDE.md），这个迁移也只有展示层知道该怎么做。
 */
class TagOrderViewModel(
    private val tagDao: TagDao,
    private val repo: TagRepository,
    private val store: TagOrderSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TagOrderUiState())
    val uiState: StateFlow<TagOrderUiState> = _uiState.asStateFlow()

    /** 全部标签。载入一次，之后的排序都在内存里做。 */
    private var allTags: List<Tag> = emptyList()

    /**
     * 进来时的顺序快照。「取消」= 恢复它。
     *
     * 本页的语义是**边改边存**（见 [moveNamespace] / [moveValue]），所以
     * 「取消」不再是"不保存就走"（那样跟完成没区别），而是唯一的撤销手段。
     */
    private var entryOrdering: TagOrdering? = null

    init {
        viewModelScope.launch {
            allTags = tagDao.findAll()
            val ordering = store.load()
            entryOrdering = ordering
            val namespaces = ordering.sortNamespaces(allTags.map { it.namespace }.distinct())
            _uiState.value = TagOrderUiState(namespaces = namespaces)
        }
    }

    /**
     * 取消：把顺序恢复成进来时的样子，同步落库。
     *
     * 刻意做成同步的——调用方点完「取消」马上就离开这个页面，ViewModel 会被
     * 销毁，起协程写库有写不完的风险。这里只碰内存里的 [allTags] 和顺序偏好，
     * 不需要查库，同步就够。
     */
    fun revert() {
        val snapshot = entryOrdering ?: return
        store.save(snapshot)
        val state = _uiState.value
        _uiState.value = state.copy(
            namespaces = snapshot.sortNamespaces(allTags.map { it.namespace }.distinct()),
            values = state.editingValues?.let { allTags.displayValuesIn(it, snapshot) }
                ?: emptyList(),
            error = null,
        )
    }

    /** 进入某维度，调它的值顺序。 */
    fun editValuesOf(namespace: String) {
        val ordering = currentOrdering()
        val values = allTags.displayValuesIn(namespace, ordering)
        _uiState.value = _uiState.value.copy(editingValues = namespace, values = values)
    }

    /**
     * 回到维度间顺序。
     *
     * **刻意不清理 [TagOrderUiState.values]**：退层动画还要用上一份值列表
     * 把出场内容画出来（见 TagOrderScreen 的 AnimatedContent）。它不参与
     * 任何逻辑——[editingValues] 为 null 时没有一处读它，下次
     * [editValuesOf] 也会整个覆盖。
     */
    fun doneEditingValues() {
        persist()
        _uiState.value = _uiState.value.copy(editingValues = null)
    }

    /** 维度间：把 [index] 处的维度上移一格。越界不动。 */
    fun moveNamespaceUp(index: Int) = moveNamespace(index, index - 1)

    /** 维度间：把 [index] 处的维度下移一格。越界不动。 */
    fun moveNamespaceDown(index: Int) = moveNamespace(index, index + 1)

    /** 维度内：把 [index] 处的值上移一格。 */
    fun moveValueUp(index: Int) = moveValue(index, index - 1)

    /** 维度内：把 [index] 处的值下移一格。 */
    fun moveValueDown(index: Int) = moveValue(index, index + 1)

    /**
     * 维度间：挪一格并**立刻落库**。
     *
     * 边改边存是刻意的（2026-10-03 用户反馈后统一）：早先只在「完成」和
     * "离开值层"时落库，于是"调完维度顺序侧滑返回"会静默丢改动，
     * 而"重排中途改名/合并"会被 reload 冲掉——两条路都是同一处割裂。
     * 现在两级一致：改动即时生效，不存在"还没保存"的中间态。
     */
    private fun moveNamespace(from: Int, to: Int) {
        val list = _uiState.value.namespaces
        if (from !in list.indices || to !in list.indices) return
        _uiState.value = _uiState.value.copy(namespaces = list.swap(from, to))
        persist()
    }

    /** 维度内：挪一格并立刻落库（理由同 [moveNamespace]）。 */
    private fun moveValue(from: Int, to: Int) {
        val list = _uiState.value.values
        if (from !in list.indices || to !in list.indices) return
        _uiState.value = _uiState.value.copy(values = list.swap(from, to))
        persist()
    }

    /**
     * 落库。把当前可见的顺序整体写回——未自定义的项就此转正，
     * 之后位置固定，新标签仍进未定义区。
     *
     * 现在每次移动都已经写过一遍，这个入口只剩两处用途：界面上的「完成」
     * （保险，无操作）和离开值层时收尾。
     */
    /** 把当前生效的顺序落盘（边改边存的写入口）。 */
    fun persist() = store.save(currentOrdering())

    /** 当前生效的顺序偏好 = 磁盘上的偏好 + 界面上的维度顺序（在值层时再叠上值顺序）。 */
    private fun currentOrdering(): TagOrdering {
        val state = _uiState.value
        var ordering = store.load().withNamespaceOrder(state.namespaces)
        state.editingValues?.let { ordering = ordering.withValueOrder(it, state.values) }
        return ordering
    }

    // ---- 改名 / 合并（标签管理，2026-10-03）----

    /** 按展示值找标签——值列表只存展示值，改名/合并需要拿到 Tag（要 id）。 */
    fun findTag(namespace: String, value: String): Tag? =
        allTags.find { it.namespace == namespace && it.value == value }

    /** 打开某个标签的改名/合并对话框。 */
    fun startManaging(tag: Tag) {
        _uiState.value = _uiState.value.copy(
            managing = tag,
            mergeCandidates = allTags
                .filter { it.namespace == tag.namespace && it.id != tag.id }
                .sortedWith(currentOrdering().comparator),
            renameInput = tag.value,
            pendingMerge = null,
            error = null,
            notice = null,
        )
    }

    fun onRenameInputChange(value: String) {
        _uiState.value = _uiState.value.copy(renameInput = value, error = null)
    }

    /** 点了一个合并候选：进入二次确认。 */
    fun askMergeInto(target: Tag) {
        _uiState.value = _uiState.value.copy(pendingMerge = target, error = null)
    }

    /** 从二次确认退回候选列表。 */
    fun cancelPendingMerge() {
        _uiState.value = _uiState.value.copy(pendingMerge = null, error = null)
    }

    /** 关闭对话框（改到一半放弃，什么都不写）。 */
    fun cancelManaging() {
        _uiState.value = _uiState.value.copy(
            managing = null,
            mergeCandidates = emptyList(),
            renameInput = "",
            pendingMerge = null,
            error = null,
        )
    }

    /** 提交改名。撞名等拒绝理由由仓库层抛出，原样展示。 */
    fun submitRename() {
        val state = _uiState.value
        val tag = state.managing ?: return

        viewModelScope.launch {
            try {
                val renamed = repo.rename(tag, state.renameInput)
                migrateOrderingOnRename(tag, renamed)
                _uiState.value = _uiState.value.copy(
                    managing = null,
                    mergeCandidates = emptyList(),
                    renameInput = "",
                    pendingMerge = null,
                    error = null,
                    notice = "已改名为「${renamed.value}」",
                )
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "改名失败")
            }
        }
    }

    /** 确认合并：把管理中的标签合并进 [target]。 */
    fun confirmMerge() {
        val state = _uiState.value
        val from = state.managing ?: return
        val target = state.pendingMerge ?: return

        viewModelScope.launch {
            try {
                repo.merge(from, target)
                migrateOrderingOnMerge(from)
                _uiState.value = _uiState.value.copy(
                    managing = null,
                    mergeCandidates = emptyList(),
                    renameInput = "",
                    pendingMerge = null,
                    error = null,
                    notice = "已把「${from.value}」合并到「${target.value}」",
                )
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "合并失败")
            }
        }
    }

    // ---- 删除单个标签（值列表里把行滑开露出的那个按钮）----

    /**
     * 删除一个标签（确认框过了才调）。
     *
     * 标签会从所有记录上消失（外键 CASCADE 清关联），**记录本身保留**；
     * 顺序偏好里对应的条目一并清掉（不留指向不存在标签的幽灵条目）。
     */
    fun deleteTag(tag: Tag) {
        viewModelScope.launch {
            try {
                repo.deleteByIds(listOf(tag.id))
                migrateOrderingOnDelete(tag)
                _uiState.value = _uiState.value.copy(
                    error = null,
                    notice = "已删除「${tag.value}」——它从挂过的记录上移除了，记录本身保留",
                )
                reload()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "删除失败")
            }
        }
    }

    /**
     * 顺序偏好里某个维度的值序列做一次变换。
     *
     * 三个迁移（删除/合并去掉条目、改名换条目）此前各写一份"取→判→存"的骨架，
     * 2026-10-04 收敛到这里；旧序列不存在、或不含 [target] 时不动磁盘。
     *
     * 注意值序列存的是**归一化值**（与库里的 `normalizedValue` 对齐），
     * 不是展示值。
     */
    private fun updateValueOrder(
        namespace: String,
        target: String,
        transform: (List<String>) -> List<String>,
    ) {
        val ordering = store.load()
        val values = ordering.valueOrder[namespace] ?: return
        if (target !in values) return
        store.save(ordering.copy(valueOrder = ordering.valueOrder + (namespace to transform(values))))
    }

    /** 重新读标签表，并刷新当前可见的列表（改名/合并后调用）。 */
    private suspend fun reload() {
        allTags = tagDao.findAll()
        val ordering = store.load()
        val state = _uiState.value
        val namespaces = ordering.sortNamespaces(allTags.map { it.namespace }.distinct())
        // 正在调值的维度若被合并/改名弄没了，退回维度层（不能停在空列表上）
        val editing = state.editingValues?.takeIf { it in namespaces }
        _uiState.value = state.copy(
            namespaces = namespaces,
            editingValues = editing,
            values = editing?.let { ns -> allTags.displayValuesIn(ns, ordering) } ?: emptyList(),
        )
    }

    /** 改名后：把顺序偏好里旧归一化值换成新的，保住用户调过的位置。 */
    private fun migrateOrderingOnRename(old: Tag, renamed: Tag) =
        updateValueOrder(old.namespace, old.normalizedValue) { values ->
            values.map { if (it == old.normalizedValue) renamed.normalizedValue else it }
        }

    /** 删除后：去掉顺序偏好里的对应条目。 */
    private fun migrateOrderingOnDelete(tag: Tag) =
        updateValueOrder(tag.namespace, tag.normalizedValue) { it - tag.normalizedValue }

    /** 合并后：去掉来源的排序条目（目标保留自己原有的位置；调用方保证同维度）。 */
    private fun migrateOrderingOnMerge(from: Tag) =
        updateValueOrder(from.namespace, from.normalizedValue) { it - from.normalizedValue }

    private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
        val result = toMutableList()
        val tmp = result[a]
        result[a] = result[b]
        result[b] = tmp
        return result
    }
}

/**
 * 该维度下所有标签按当前顺序排好后的展示值序列。
 * 单独抽出来是为了让 UI 只拿展示所需的东西，不直接碰 [Tag]。
 */
internal fun List<Tag>.displayValuesIn(namespace: String, ordering: TagOrdering): List<String> =
    filter { it.namespace == namespace }
        .sortedWith(ordering.comparator)
        .map { it.value }
