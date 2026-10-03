package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagRepository
import io.github.hexgmskr.noteone.ui.list.ItemListViewModel
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
 * 列表页多选筛选用例。
 *
 * 这是列表页里唯一有分支的逻辑，重点是**分面语义**：
 * 同维度多选取「或」（选了长视频+短视频不该筛成空）、
 * 跨维度取「且」（再选萌宠要层层收窄）、按标签 **id** 而不是值匹配
 * （分面体系里同名不同维度是合法的）。另有选中态在刷新后不丢。
 *
 * chip 的渲染与点击属 UI 层，JVM 测不到，由真机验证覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemListViewModelTest {

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

    /** 内存版顺序存储：单测不碰 SharedPreferences。 */

    private fun repository() = ItemRepository(
        itemDao = itemDao,
        tagRepository = TagRepository(tagDao, itemTagDao),
        itemTagDao = itemTagDao,
        inTransaction = { block -> block() },   // 单测不需要真事务
    )

    /** 展示顺序偏好：内存版（真实现见 TagOrderStore）。 */
    private val orderSource = InMemoryTagOrder()

    private fun viewModel() = ItemListViewModel(itemDao, tagDao, orderSource, repository())

    /** 当前列表里显示的记录 id，按顺序。 */
    private fun shownIds(vm: ItemListViewModel) = vm.uiState.value.displayItems.map { it.item.id }

    // ---- 造数据 ----

    private fun tag(id: Long, namespace: String, value: String) =
        Tag(id = id, namespace = namespace, value = value, normalizedValue = value.lowercase())

    /**
     * 摆一条可见记录。[tags] 既是这条记录挂的标签，也自动登记为筛选条候选
     * （本来就该如此：标签表里的标签才可能挂到记录上）。
     */
    private fun givenItem(id: Long, vararg tags: Tag) {
        val item = Item(id = id, content = "记录$id", createdAt = id, updatedAt = id)
        itemDao.itemsWithTags = itemDao.itemsWithTags + ItemWithTags(
            item = item,
            tags = tags.toList(),
        )
        // 删除类用例要断言记录表本体，这里一并登记（写路径会同时维护两个视图，见 FakeDaos）
        if (itemDao.inserted.none { it.id == id }) itemDao.inserted += item
        for (t in tags) {
            if (tagDao.inserted.none { it.id == t.id }) tagDao.inserted += t
        }
    }

    // ---- 选中 / 取消 ----

    @Test
    fun `no filter shows everything`() {
        givenItem(1, tag(1, "主题", "萌宠"))
        givenItem(2)
        val vm = viewModel()

        assertTrue("初始应一个都没选", vm.uiState.value.selectedTagIds.isEmpty())
        assertEquals(listOf(1L, 2L), shownIds(vm))
    }

    @Test
    fun `toggling a tag filters to items carrying it`() {
        val mv = tag(1, "手法", "MV")
        givenItem(1, mv)
        givenItem(2)
        val vm = viewModel()

        vm.toggleTag(mv.id)

        assertEquals(setOf(1L), vm.uiState.value.selectedTagIds)
        assertEquals(listOf(1L), shownIds(vm))
        assertEquals("全量不能被筛掉，清空筛选还要用", 2, vm.uiState.value.allItems.size)
    }

    @Test
    fun `toggling the same tag again deselects it`() {
        val mv = tag(1, "手法", "MV")
        givenItem(1, mv)
        givenItem(2)
        val vm = viewModel()

        vm.toggleTag(mv.id)
        vm.toggleTag(mv.id)

        assertTrue(vm.uiState.value.selectedTagIds.isEmpty())
        assertEquals(listOf(1L, 2L), shownIds(vm))
    }

    @Test
    fun `clearFilter restores all items`() {
        val a = tag(1, "手法", "MV")
        val b = tag(2, "主题", "萌宠")
        givenItem(1, a)
        givenItem(2, b)
        givenItem(3, a, b)
        val vm = viewModel()

        vm.toggleTag(a.id)
        vm.toggleTag(b.id)
        vm.clearFilter()

        assertTrue(vm.uiState.value.selectedTagIds.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), shownIds(vm))
    }

    @Test
    fun `filter matches by tag id not by value`() {
        // 同名不同维度是合法的（分面）：两个都叫「短」，但是两个标签。
        // 若按值筛会把两个维度的记录一锅端，必须按 id 筛。
        val shortLength = tag(1, "长度", "短")
        val shortTopic = tag(2, "主题", "短")
        givenItem(1, shortLength)
        givenItem(2, shortTopic)
        val vm = viewModel()

        vm.toggleTag(shortLength.id)
        assertEquals(listOf(1L), shownIds(vm))

        vm.toggleTag(shortLength.id)
        vm.toggleTag(shortTopic.id)
        assertEquals(listOf(2L), shownIds(vm))
    }

    // ---- 多选语义：同维度「或」、跨维度「且」 ----

    @Test
    fun `same namespace selections are OR`() {
        // 用户说「长视频或短视频都行」——同维度多选不能被筛成空
        val long = tag(1, "长度", "长视频")
        val short = tag(2, "长度", "短视频")
        givenItem(1, long)
        givenItem(2, short)
        givenItem(3)
        val vm = viewModel()

        vm.toggleTag(long.id)
        vm.toggleTag(short.id)

        assertEquals("同维度多选取或：两种都留下", listOf(1L, 2L), shownIds(vm))
    }

    @Test
    fun `cross namespace selections are AND`() {
        // 用户说「既要萌宠、又要短视频」——跨维度层层收窄
        val short = tag(1, "长度", "短视频")
        val pet = tag(2, "主题", "萌宠")
        givenItem(1, pet)
        givenItem(2, short)
        givenItem(3, short, pet)
        val vm = viewModel()

        vm.toggleTag(short.id)
        vm.toggleTag(pet.id)

        assertEquals("跨维度取且：只剩两个都带的", listOf(3L), shownIds(vm))
    }

    @Test
    fun `OR within a namespace combines with AND across namespaces`() {
        // 完整的分面场景：长度(长视频|短视频) 且 主题(萌宠)
        val long = tag(1, "长度", "长视频")
        val short = tag(2, "长度", "短视频")
        val pet = tag(3, "主题", "萌宠")
        val scenery = tag(4, "主题", "风光")
        givenItem(1, long, pet)
        givenItem(2, short, pet)
        givenItem(3, long, scenery)
        givenItem(4, short)
        givenItem(5)
        val vm = viewModel()

        vm.toggleTag(long.id)
        vm.toggleTag(short.id)
        vm.toggleTag(pet.id)

        assertEquals(
            "长度任一命中 且 主题有萌宠：留下 1、2",
            listOf(1L, 2L),
            shownIds(vm),
        )
    }

    @Test
    fun `deselecting one value of a namespace keeps the others active`() {
        val long = tag(1, "长度", "长视频")
        val short = tag(2, "长度", "短视频")
        givenItem(1, long)
        givenItem(2, short)
        val vm = viewModel()

        vm.toggleTag(long.id)
        vm.toggleTag(short.id)
        vm.toggleTag(short.id)   // 又取消了一个

        assertEquals("取消一个值不该把整个维度取消掉", setOf(1L), vm.uiState.value.selectedTagIds)
        assertEquals(listOf(1L), shownIds(vm))
    }

    @Test
    fun `filter with no matches keeps allItems intact`() {
        // 跨维度「且」可以合法地筛空（没有记录同时满足两个维度）
        val short = tag(1, "长度", "短视频")
        val pet = tag(2, "主题", "萌宠")
        givenItem(1, short)
        givenItem(2, pet)
        val vm = viewModel()

        vm.toggleTag(short.id)
        vm.toggleTag(pet.id)

        assertTrue("筛空是合法的空结果", vm.uiState.value.displayItems.isEmpty())
        assertEquals("筛空不等于清库", 2, vm.uiState.value.allItems.size)
        assertEquals(setOf(1L, 2L), vm.uiState.value.selectedTagIds)
    }

    // ---- 刷新（新建记录 / 调完顺序回来）----

    @Test
    fun `refresh keeps the selection and re-applies it`() {
        val short = tag(1, "长度", "短视频")
        val pet = tag(2, "主题", "萌宠")
        givenItem(1, short, pet)
        val vm = viewModel()
        vm.toggleTag(short.id)
        vm.toggleTag(pet.id)

        // 期间又存了一条满足条件的记录
        givenItem(2, short, pet)
        vm.refresh()

        assertEquals("刷新后选中的筛选不该丢", setOf(1L, 2L), vm.uiState.value.selectedTagIds)
        assertEquals(listOf(1L, 2L), shownIds(vm))
    }

    // ---- 筛选条候选 ----

    @Test
    fun `candidate tags load on init`() {
        givenItem(1, tag(1, "手法", "MV"))
        val vm = viewModel()

        assertEquals(listOf("MV"), vm.uiState.value.candidateTags.map { it.value })
    }

    @Test
    fun `candidate tags follow custom display order`() {
        // 默认按 Unicode 码点：主(U+4E3B) 在 长(U+957F) 前
        tagDao.inserted += tag(1, "主题", "萌宠")
        tagDao.inserted += tag(2, "长度", "长视频")
        val vm = viewModel()
        assertEquals(
            listOf("主题", "长度"),
            vm.uiState.value.candidateTags.map { it.namespace },
        )

        // 用户把「长度」调到前面
        orderSource.ordering = TagOrdering(namespaceOrder = listOf("长度", "主题"))
        vm.refresh()

        assertEquals(
            "自定义顺序要在筛选条候选里生效，否则与列表项里的标签排得不一样",
            listOf("长度", "主题"),
            vm.uiState.value.candidateTags.map { it.namespace },
        )
    }

    @Test
    fun `items matching more selected tags come first`() {
        // 同维度多选是「或」：只带一个的也留在列表里，
        // 但"两个都带"（交集）要浮到最上面（2026-10-03 用户拍板）
        val pet = tag(1, "主题", "萌宠")
        val nature = tag(2, "主题", "自然")
        givenItem(1, pet)             // 只带萌宠
        givenItem(2, pet, nature)     // 两个都带 ← 应排最前
        givenItem(3, nature)          // 只带自然
        val vm = viewModel()

        vm.toggleTag(pet.id)
        vm.toggleTag(nature.id)

        assertEquals(
            "两个都命中的应排最前，其余保持原序",
            listOf(2L, 1L, 3L),
            shownIds(vm),
        )
    }

    @Test
    fun `candidate tags are empty when no tags exist`() {
        givenItem(1)
        val vm = viewModel()

        assertTrue("没有标签时候选为空，筛选条整条不显示", vm.uiState.value.candidateTags.isEmpty())
    }

    // ---- 长按多选与删除 ----

    @Test
    fun `long press enters selection with that item`() {
        givenItem(1)
        givenItem(2)
        val vm = viewModel()

        vm.startSelection(1L)

        assertTrue(vm.uiState.value.inSelectionMode)
        assertEquals(setOf(1L), vm.uiState.value.selectedItemIds)
    }

    @Test
    fun `tap toggles selection while in selection mode`() {
        givenItem(1)
        givenItem(2)
        val vm = viewModel()
        vm.startSelection(1L)

        vm.toggleSelection(2L)
        assertEquals(setOf(1L, 2L), vm.uiState.value.selectedItemIds)

        vm.toggleSelection(1L)
        assertEquals(setOf(2L), vm.uiState.value.selectedItemIds)
    }

    @Test
    fun `deselecting the last item exits selection mode`() {
        givenItem(1)
        val vm = viewModel()
        vm.startSelection(1L)

        vm.toggleSelection(1L)

        assertFalse("取消掉最后一条应退出多选，不留悬空模式", vm.uiState.value.inSelectionMode)
        assertTrue(vm.uiState.value.selectedItemIds.isEmpty())
    }

    @Test
    fun `clearSelection exits selection mode`() {
        givenItem(1)
        givenItem(2)
        val vm = viewModel()
        vm.startSelection(1L)
        vm.toggleSelection(2L)

        vm.clearSelection()

        assertFalse(vm.uiState.value.inSelectionMode)
    }

    @Test
    fun `deleteSelected removes rows, clears selection and refreshes`() = runTest {
        givenItem(1)
        givenItem(2)
        givenItem(3)
        val vm = viewModel()
        vm.startSelection(1L)
        vm.toggleSelection(3L)

        vm.deleteSelected()

        assertEquals("被点名的行应删掉", listOf(2L), itemDao.inserted.map { it.id })
        assertFalse("删除后应退出多选", vm.uiState.value.inSelectionMode)
        assertEquals("列表应刷新成剩下的", listOf(2L), shownIds(vm))
    }

    @Test
    fun `delete failure keeps selection and reports error`() = runTest {
        givenItem(1)
        val vm = viewModel()
        vm.startSelection(1L)
        itemDao.shouldFail = true

        vm.deleteSelected()

        assertNotNull("失败要有提示", vm.uiState.value.error)
        assertTrue("失败后保留选择，便于重试", vm.uiState.value.inSelectionMode)
        assertEquals("数据不该被动", listOf(1L), itemDao.inserted.map { it.id })
    }

    @Test
    fun `refresh drops selected ids of items that no longer exist`() {
        givenItem(1)
        givenItem(2)
        val vm = viewModel()
        vm.startSelection(1L)
        vm.toggleSelection(2L)

        // 模拟别处删掉了 2：直接改数据，不走 VM 的删除路径
        itemDao.itemsWithTags = itemDao.itemsWithTags.filterNot { it.item.id == 2L }
        itemDao.inserted.removeAll { it.id == 2L }
        vm.refresh()

        assertEquals("幽灵 id 应被丢掉，不能带着它去删", setOf(1L), vm.uiState.value.selectedItemIds)
    }
}
