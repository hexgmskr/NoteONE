package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagRepository
import io.github.hexgmskr.noteone.ui.detail.DetailViewModel
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 详情页的 JVM 单测：载入、缺失态、删除与信号消费。
 *
 * 复制走系统剪贴板，属 UI 层能力，JVM 测不到，由真机验证覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private lateinit var itemDao: FakeItemDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var itemTagDao: FakeItemTagDao

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        itemDao = FakeItemDao()
        tagDao = FakeTagDao()
        itemTagDao = FakeItemTagDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = ItemRepository(
        itemDao = itemDao,
        tagRepository = TagRepository(tagDao, itemTagDao),
        itemTagDao = itemTagDao,
        inTransaction = { block -> block() },   // 单测不需要真事务
    )

    /** 展示顺序偏好：内存版（真实现见 TagOrderStore）。 */
    private val orderSource = InMemoryTagOrder()

    private fun viewModel(id: Long) = DetailViewModel(id, itemDao, repository(), orderSource)

    private fun givenItem(id: Long, content: String, vararg tags: Tag) {
        val item = Item(id = id, content = content, createdAt = 777L, updatedAt = 777L)
        itemDao.inserted += item
        itemDao.itemsWithTags = itemDao.itemsWithTags + ItemWithTags(item = item, tags = tags.toList())
        for (t in tags) {
            if (tagDao.inserted.none { it.id == t.id }) tagDao.inserted += t
        }
    }

    @Test
    fun `load exposes content, tags and createdAt`() {
        givenItem(5L, "全文内容", Tag(id = 1L, namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))

        val vm = viewModel(5L)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.notFound)
        assertEquals("全文内容", state.content)
        assertEquals(1, state.tags.size)
        assertEquals(777L, state.createdAt)
    }

    @Test
    fun `load uses the stored tag ordering`() {
        orderSource.ordering = TagOrdering(namespaceOrder = listOf("手法"))
        givenItem(5L, "x")

        val vm = viewModel(5L)

        assertEquals(
            "详情里的标签顺序要和列表同一口径，否则同一记录两个位置排得不一样",
            listOf("手法"),
            vm.uiState.value.ordering.namespaceOrder,
        )
    }

    @Test
    fun `missing item is reported instead of crashing`() {
        val vm = viewModel(404L)

        assertTrue("找不到要落在缺失态", vm.uiState.value.notFound)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `delete removes the row and raises the deleted signal`() = runTest {
        givenItem(5L, "全文内容")
        val vm = viewModel(5L)

        vm.delete()

        assertTrue("记录应被删掉", itemDao.inserted.isEmpty())
        assertTrue(vm.uiState.value.deleted)
    }

    @Test
    fun `delete failure sets error and does not raise deleted`() = runTest {
        givenItem(5L, "全文内容")
        val vm = viewModel(5L)
        itemDao.shouldFail = true

        vm.delete()

        assertNotNull("失败要有提示", vm.uiState.value.error)
        assertFalse("失败不该触发返回列表", vm.uiState.value.deleted)
        assertEquals("数据不该被动", 1, itemDao.inserted.size)
    }

    @Test
    fun `deleted signal can be consumed`() = runTest {
        givenItem(5L, "x")
        val vm = viewModel(5L)
        vm.delete()

        vm.onDeletedConsumed()

        assertFalse("消费后清掉，避免返回列表后重复触发", vm.uiState.value.deleted)
    }
}
