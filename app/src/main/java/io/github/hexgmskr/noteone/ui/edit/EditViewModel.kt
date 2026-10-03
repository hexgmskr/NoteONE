package io.github.hexgmskr.noteone.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.dao.TagDao
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.NamespaceGroup
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.data.tag.TagMatcher
import io.github.hexgmskr.noteone.data.tag.TagNormalizer
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderSource
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 编辑页状态。MVP 只有 content 一个正文输入；note 是预留字段，暂不出 UI。
 *
 * [tags] 是**尚未落库**的标签草稿，保存时整体交给
 * [io.github.hexgmskr.noteone.data.item.ItemRepository.saveWithTags]
 * （编辑既有记录时走 `updateWithTags`）。
 *
 * 标签面板（spec 7.3）三层：选已有 → 模糊匹配提示 → 新建维度/值。
 * 建议列表是**派生值**不入 state——由 [valueInput] + [existingTags] + [tags]
 * 算出来，不会跟输入漂掉。
 */
data class EditUiState(
    val content: String = "",
    val tags: List<TagDraft> = emptyList(),
    /** 全部已有标签，供面板陈列与匹配。 */
    val existingTags: List<Tag> = emptyList(),
    /** 展示顺序偏好。面板陈列、建议、草稿 chip 都要走它。 */
    val ordering: TagOrdering = TagOrdering(),
    /** 新建标签的维度输入。 */
    val namespaceInput: String = "",
    /** 新建标签的值输入。 */
    val valueInput: String = "",
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    /**
     * 「新建标签」区是否展开。
     *
     * **默认折叠**——建记录时更常用的是「从已有标签里选」，新建是低频动作，
     * 默认展开会把保存按钮挤出首屏。
     *
     * **展开状态持久**：一旦展开就保持到用户主动收起，加完标签不自动收回。
     * 自动收回是典型的「打断式 UI」——用户连着建几个标签时要反复展开，
     * 反而比不折叠更费手。
     */
    val isTagCreatorExpanded: Boolean = false,
) {
    /** 内容为空或纯空白时不允许保存。标签可空——记录可以先归档、后补标签。 */
    val canSave: Boolean get() = content.isNotBlank() && !isSaving

    /** 模糊匹配建议。已选中的不重复提示。 */
    val suggestions: List<Tag>
        get() = TagMatcher.suggest(valueInput, existingTags, order = ordering.comparator)
            .filterNot { suggestion -> tags.any { it.sameAs(suggestion) } }

    /** 已有标签按维度分组，供「选已有」陈列。 */
    val existingByNamespace: List<NamespaceGroup>
        get() = TagMatcher.groupByNamespace(existingTags, order = ordering.comparator)

    /** 已选草稿，按展示顺序排好。 */
    val sortedTags: List<TagDraft>
        get() = tags.sortedWith(ordering.draftComparator)

    /** 某条已有标签是否已被选中。 */
    fun isSelected(tag: Tag): Boolean = tags.any { it.sameAs(tag) }
}

class EditViewModel(
    private val items: ItemRepository,
    /** 载入既有记录走它（写路径走 [items]）；仅 [editingItemId] 非空时使用。 */
    private val itemDao: ItemDao,
    private val tagDao: TagDao,
    private val tagOrderStore: TagOrderSource,
    /** 要编辑的记录 id；null = 新建。 */
    private val editingItemId: Long? = null,
    /** 取当前时刻，epoch 毫秒。抽出来便于测试。 */
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** 编辑既有记录（true）还是新建（false）。界面标题据此切换。 */
    val isEditing: Boolean = editingItemId != null

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    init {
        refreshExistingTags()
        if (editingItemId != null) loadExisting(editingItemId)
    }

    /**
     * 编辑模式：把既有记录的正文与标签载进状态。
     *
     * 标签转成 [TagDraft] 草稿（用落库时的显示写法），之后走与新建完全相同的
     * 面板/去重/保存路径——编辑不是另一套语义，只是初始值不同。
     * 目标不存在（编辑期间被删）时只报错，不崩：`canSave` 会因内容为空挡住保存。
     */
    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            val loaded = itemDao.findWithTagsById(id)
            if (loaded == null) {
                _uiState.value = _uiState.value.copy(error = "记录不存在（可能已被删除）")
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                content = loaded.item.content,
                tags = loaded.sortedTags.map { TagDraft(it.namespace, it.value) },
            )
        }
    }

    /**
     * 重新载入已有标签。新建标签后要调一次，否则面板还停在旧快照。
     */
    fun refreshExistingTags() {
        viewModelScope.launch {
            val tags = tagDao.findAll()
            _uiState.value = _uiState.value.copy(
                existingTags = tags,
                ordering = tagOrderStore.load(),
            )
        }
    }

    fun onContentChange(newContent: String) {
        _uiState.value = _uiState.value.copy(content = newContent, error = null)
    }

    fun onNamespaceChange(text: String) {
        _uiState.value = _uiState.value.copy(namespaceInput = text, error = null)
    }

    fun onValueChange(text: String) {
        _uiState.value = _uiState.value.copy(valueInput = text, error = null)
    }

    /** 展开「新建标签」区。已展开时是空操作——避免重复触发动画。 */
    fun expandTagCreator() {
        if (_uiState.value.isTagCreatorExpanded) return
        _uiState.value = _uiState.value.copy(isTagCreatorExpanded = true)
    }

    /** 收起「新建标签」区。只有用户主动收起才调，加完标签不自动收。 */
    fun collapseTagCreator() {
        _uiState.value = _uiState.value.copy(isTagCreatorExpanded = false)
    }

    /**
     * 追加一条标签草稿。
     *
     * 输入校验在这里做（UI 层不该把空维度/空值塞进仓库层），
     * 但**去重判官仍是 [io.github.hexgmskr.noteone.data.tag.TagRepository]**——
     * 这里的重复拦截只是不让草稿列表堆积同一个词，复用同一个
     * [TagNormalizer]，不是另立一套规则。
     *
     * @return true 表示草稿已加入；false 表示被拒（输入非法或重复），拒因见 [EditUiState.error]
     */
    fun addTag(namespace: String, value: String): Boolean {
        val ns = namespace.trim()
        if (ns.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "维度名不能为空——标签必须归属某个维度")
            return false
        }
        if (!TagNormalizer.isValid(value)) {
            _uiState.value = _uiState.value.copy(error = "标签值不能为空")
            return false
        }

        val current = _uiState.value
        val candidate = TagDraft(ns, value.trim())
        if (current.tags.any { it.sameAs(candidate) }) {
            _uiState.value = current.copy(error = "「$ns#$value」已经在列表里了")
            return false
        }

        _uiState.value = current.copy(tags = current.tags + candidate, error = null)
        return true
    }

    /** 选中一条已有标签作为草稿。已选中则取消选中（面板 chip 点一下切换）。 */
    fun toggleExisting(tag: Tag) {
        val current = _uiState.value
        val existing = current.tags.find { it.sameAs(tag) }
        if (existing != null) {
            _uiState.value = current.copy(tags = current.tags - existing, error = null)
        } else {
            // 已落库的标签维度/值都是合法的，addTag 不会拒绝
            addTag(tag.namespace, tag.value)
        }
    }

    /**
     * 用当前输入框新建一条草稿，并清空值输入（维度留着，方便连着建同维度的几个）。
     *
     * @return 见 [addTag]
     */
    fun addTagFromInputs(): Boolean {
        val ok = addTag(_uiState.value.namespaceInput, _uiState.value.valueInput)
        if (ok) {
            _uiState.value = _uiState.value.copy(valueInput = "", error = null)
        }
        return ok
    }

    /** 移除一条标签草稿。按对象移除，不按索引——UI 重组时索引会漂。 */
    fun removeTag(draft: TagDraft) {
        _uiState.value = _uiState.value.copy(tags = _uiState.value.tags - draft, error = null)
    }

    /**
     * 用剪贴板内容覆盖输入框。
     *
     * 由**用户点击**触发，不是后台自动读取——Android 10+ 后台读剪贴板受限，
     * 而且自动读会打扰用户（spec 7.3）。
     */
    fun onPaste(text: String?) {
        if (text.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(error = "剪贴板里没有可粘贴的文本")
            return
        }
        _uiState.value = _uiState.value.copy(content = text, error = null)
    }

    /**
     * 保存记录 + 标签草稿：新建走 insert、编辑走覆盖更新（`createdAt` 不动）。
     * 成功后置 [EditUiState.saved]，由界面据此返回。
     */
    fun save() {
        val snapshot = _uiState.value
        if (!snapshot.canSave) return

        _uiState.value = snapshot.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                val now = clock()
                val id = editingItemId
                if (id != null) {
                    items.updateWithTags(id, snapshot.content.trim(), snapshot.tags, now)
                } else {
                    items.saveWithTags(
                        content = snapshot.content.trim(),
                        drafts = snapshot.tags,
                        now = now,
                    )
                }
                _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "保存失败：${e.message}",
                )
            }
        }
    }

    /** 消费掉 saved 信号，避免返回列表后再次触发跳转。 */
    fun onSavedConsumed() {
        _uiState.value = _uiState.value.copy(saved = false)
    }
}
