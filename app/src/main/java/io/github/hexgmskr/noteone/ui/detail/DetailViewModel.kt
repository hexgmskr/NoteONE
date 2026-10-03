package io.github.hexgmskr.noteone.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.dao.ItemDao
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
 * 详情页状态：一条记录的全文 + 标签 + 创建时间；供查看、复制、编辑、删除。
 *
 * 复制是纯 UI 动作（系统剪贴板），不出现在这里；本 VM 只管数据与删除。
 */
data class DetailUiState(
    val isLoading: Boolean = true,
    /** 记录不存在（打开后被删，或 id 失效）——界面显示缺失态而不是空白。 */
    val notFound: Boolean = false,
    val content: String = "",
    val tags: List<Tag> = emptyList(),
    val ordering: TagOrdering = TagOrdering(),
    val createdAt: Long = 0L,
    /** 删除完成信号，由界面消费后导航回列表（同 EditUiState.saved 模式）。 */
    val deleted: Boolean = false,
    val error: String? = null,
)

class DetailViewModel(
    private val itemId: Long,
    private val itemDao: ItemDao,
    private val items: ItemRepository,
    private val tagOrderStore: TagOrderSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 读这条记录（连同标签）与展示顺序。返回该页时 ViewModel 会重建，自动重读。 */
    fun load() {
        viewModelScope.launch {
            try {
                val ordering = tagOrderStore.load()
                val loaded = itemDao.findWithTagsById(itemId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notFound = loaded == null,
                    content = loaded?.item?.content.orEmpty(),
                    tags = loaded?.tags.orEmpty(),
                    ordering = ordering,
                    createdAt = loaded?.item?.createdAt ?: 0L,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 锁周期（后台超时）会关库；此刻在飞的查询会抛。这个 VM 随即被丢弃，
                // 正常路径下没人看见这条消息；但**不能不接**——viewModelScope 里
                // 未捕获的异常会直接崩进程（2026-10-04 审计 A5 的收尾）。
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "读取失败：${e.message ?: "未知原因"}",
                )
            }
        }
    }

    /** 删除这条记录。界面的确认框过了才调这里。 */
    fun delete() {
        viewModelScope.launch {
            try {
                items.deleteByIds(listOf(itemId))
                _uiState.value = _uiState.value.copy(deleted = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "删除失败：${e.message ?: "未知原因"}")
            }
        }
    }

    /** 消费掉删除信号，避免返回列表后再次触发导航。 */
    fun onDeletedConsumed() {
        _uiState.value = _uiState.value.copy(deleted = false)
    }
}
