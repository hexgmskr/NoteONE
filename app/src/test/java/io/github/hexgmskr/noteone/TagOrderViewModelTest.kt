package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.tag.TagRepository
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderViewModel
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 标签管理页（改名/合并）的 JVM 单测，2026-10-03。
 *
 * 未知数是**编排**：候选只列同维度的、合并要二次确认、改名/合并后
 * ① 当前视图要刷新、② 展示顺序偏好要跟着迁移（改名的位置保住、
 * 合并的来源条目去掉）。写语义本身由 [TagManagementTest] 覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagOrderViewModelTest {

    private lateinit var tagDao: FakeTagDao
    private lateinit var itemTagDao: FakeItemTagDao
    private lateinit var repo: TagRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tagDao = FakeTagDao()
        itemTagDao = FakeItemTagDao()
        repo = TagRepository(tagDao, itemTagDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 展示顺序偏好：内存版（真实现见 TagOrderStore）。 */
    private val orderSource = InMemoryTagOrder()

    private fun viewModel() = TagOrderViewModel(tagDao, repo, orderSource)

    /** 直接往假表里摆标签（id 自定，顺序测试要用）。 */
    private fun seedTag(id: Long, namespace: String, value: String) {
        tagDao.inserted += Tag(
            id = id,
            namespace = namespace,
            value = value,
            normalizedValue = value.lowercase(),
        )
    }

    // ---- 候选 ----

    @Test
    fun `startManaging prefills the name and lists same-namespace siblings only`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        seedTag(3, "手法", "延时摄影")
        val vm = viewModel()

        vm.startManaging(vm.findTag("主题", "萌宠")!!)

        assertEquals("萌宠", vm.uiState.value.renameInput)
        assertEquals(
            "候选只该有同维度的其他标签",
            listOf("宠物"),
            vm.uiState.value.mergeCandidates.map { it.value },
        )
    }

    // ---- 落库时机：边改边存（2026-10-03 用户反馈后定的语义）----

    @Test
    fun `moving a namespace persists immediately`() {
        // 用户实际踩到的坑：调完维度顺序、侧滑返回——顺序没变。
        // 原因是只有点「完成」才落库。现在每次移动就写。
        seedTag(1, "主题", "萌宠")
        seedTag(2, "手法", "延时摄影")
        val vm = viewModel()
        val before = vm.uiState.value.namespaces
        assertEquals("默认按 Unicode 排：主题 在 手法 前", listOf("主题", "手法"), before)

        vm.moveNamespaceUp(1)

        assertEquals(
            "移动后应立刻写进顺序偏好，不等完成",
            listOf("手法", "主题"),
            orderSource.ordering.namespaceOrder,
        )
    }

    @Test
    fun `moving a value persists immediately`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        val vm = viewModel()
        vm.editValuesOf("主题")

        assertEquals("默认按 Unicode：宠物 在 萌宠 前", listOf("宠物", "萌宠"), vm.uiState.value.values)
        vm.moveValueUp(1)

        assertEquals(
            "值这一层同样随改随存",
            listOf("萌宠", "宠物"),
            orderSource.ordering.valueOrder["主题"],
        )
    }

    @Test
    fun `reorder survives a rename that reloads the view`() {
        // 旧写法里 reload() 会用库里的顺序重建视图，把还没落库的重排冲掉
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        seedTag(3, "手法", "延时摄影")
        val vm = viewModel()
        vm.moveNamespaceUp(1)   // 手法 提到最前

        vm.startManaging(vm.findTag("主题", "萌宠")!!)
        vm.onRenameInputChange("毛球")
        vm.submitRename()

        assertEquals(
            "重排之后改名，重排不能丢",
            listOf("手法", "主题"),
            orderSource.ordering.namespaceOrder,
        )
        assertEquals(
            "改名只动它自己那条，别的不受影响",
            listOf("手法", "主题"),
            vm.uiState.value.namespaces,
        )
    }

    @Test
    fun `cancel restores the ordering from when the screen was opened`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        seedTag(3, "手法", "延时摄影")
        orderSource.ordering = TagOrdering(namespaceOrder = listOf("手法", "主题"))
        val vm = viewModel()
        vm.moveNamespaceUp(1)                     // 把 主题 提到最前
        vm.editValuesOf("主题")
        vm.moveValueUp(1)
        assertEquals(listOf("主题", "手法"), vm.uiState.value.namespaces)

        vm.revert()

        assertEquals(
            "取消 = 回到进来时的维度顺序",
            listOf("手法", "主题"),
            orderSource.ordering.namespaceOrder,
        )
        assertTrue(
            "值顺序也一并回退（进来时没自定义过值顺序，回退后也不该有）",
            orderSource.ordering.valueOrder["主题"].isNullOrEmpty(),
        )
        assertEquals(
            "界面上的维度顺序跟着回退，不能停在改过的样子",
            listOf("手法", "主题"),
            vm.uiState.value.namespaces,
        )
    }

    @Test
    fun `cancel from inside a value level also rolls back that level`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        orderSource.ordering = TagOrdering(valueOrder = mapOf("主题" to listOf("宠物", "萌宠")))
        val vm = viewModel()
        vm.editValuesOf("主题")

        vm.moveValueDown(0)                        // 把 宠物 挪到后面
        assertEquals(listOf("萌宠", "宠物"), orderSource.ordering.valueOrder["主题"])

        vm.revert()

        assertEquals(
            "取消要回退到进来时的值顺序",
            listOf("宠物", "萌宠"),
            orderSource.ordering.valueOrder["主题"],
        )
        assertEquals(
            "当前层的可见顺序也要回退",
            listOf("宠物", "萌宠"),
            vm.uiState.value.values,
        )
    }

    // ---- 改名 ----

    @Test
    fun `rename updates the tag and keeps its custom order position`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        orderSource.ordering = TagOrdering(valueOrder = mapOf("主题" to listOf("宠物", "萌宠")))
        val vm = viewModel()

        vm.startManaging(vm.findTag("主题", "萌宠")!!)
        vm.onRenameInputChange("毛球")
        vm.submitRename()

        assertTrue("标签表应有新值", tagDao.inserted.any { it.value == "毛球" })
        assertEquals(
            "改名后顺序偏好的位置要保住",
            listOf("宠物", "毛球"),
            orderSource.ordering.valueOrder["主题"],
        )
        assertNotNull(vm.uiState.value.notice)
        assertNull("对话框应关掉", vm.uiState.value.managing)
    }

    @Test
    fun `rename to a taken name keeps the dialog open with an error`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        val vm = viewModel()

        vm.startManaging(vm.findTag("主题", "萌宠")!!)
        vm.onRenameInputChange("宠物")
        vm.submitRename()

        assertNotNull("撞名要报错", vm.uiState.value.error)
        assertNotNull("对话框不能关，名字还没改成", vm.uiState.value.managing)
        assertTrue("原标签不该被动", tagDao.inserted.any { it.value == "萌宠" })
    }

    // ---- 合并 ----

    @Test
    fun `merge needs an explicit second confirmation`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        val vm = viewModel()
        vm.startManaging(vm.findTag("主题", "萌宠")!!)
        val target = vm.findTag("主题", "宠物")!!

        vm.askMergeInto(target)

        assertEquals("只是选中，还没合并", target.id, vm.uiState.value.pendingMerge?.id)
        assertTrue("来源标签必须还在", tagDao.inserted.any { it.value == "萌宠" })
    }

    @Test
    fun `confirmMerge deletes the source, drops its order entry and refreshes the view`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        seedTag(3, "手法", "延时摄影")
        orderSource.ordering = TagOrdering(valueOrder = mapOf("主题" to listOf("宠物", "萌宠")))
        val vm = viewModel()
        vm.editValuesOf("主题")
        val before = vm.uiState.value.values
        assertTrue("合并前值列表里有萌宠", before.contains("萌宠"))

        vm.startManaging(vm.findTag("主题", "萌宠")!!)
        vm.askMergeInto(vm.findTag("主题", "宠物")!!)
        vm.confirmMerge()

        assertTrue("来源标签应被删掉", tagDao.inserted.none { it.value == "萌宠" })
        assertEquals(
            "顺序偏好里来源的条目应去掉",
            listOf("宠物"),
            orderSource.ordering.valueOrder["主题"],
        )
        assertEquals(
            "当前值列表应只剩目标",
            listOf("宠物"),
            vm.uiState.value.values,
        )
        assertNotNull(vm.uiState.value.notice)
    }

    // ---- 删除单个标签（滑开行露出的按钮，2026-10-03 按用户反馈改版）----

    @Test
    fun `deleteTag removes the tag, drops its order entry and refreshes the view`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        orderSource.ordering = TagOrdering(valueOrder = mapOf("主题" to listOf("宠物", "萌宠")))
        val vm = viewModel()
        vm.editValuesOf("主题")

        vm.deleteTag(vm.findTag("主题", "萌宠")!!)

        assertTrue("标签应被删", tagDao.inserted.none { it.id == 1L })
        assertEquals(
            "顺序偏好里的条目应一并清掉",
            listOf("宠物"),
            orderSource.ordering.valueOrder["主题"],
        )
        assertEquals("当前值列表应刷新", listOf("宠物"), vm.uiState.value.values)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `deleting the last tag of a namespace falls back to the namespace view`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "手法", "延时摄影")
        val vm = viewModel()
        vm.editValuesOf("主题")

        vm.deleteTag(vm.findTag("主题", "萌宠")!!)

        assertNull("维度空了就该退回维度层，不能停在空列表上", vm.uiState.value.editingValues)
    }

    @Test
    fun `cancelling the dialog writes nothing`() {
        seedTag(1, "主题", "萌宠")
        seedTag(2, "主题", "宠物")
        val vm = viewModel()

        vm.startManaging(vm.findTag("主题", "萌宠")!!)
        vm.onRenameInputChange("毛球")
        vm.cancelManaging()

        assertNull(vm.uiState.value.managing)
        assertTrue("不该写任何东西", tagDao.inserted.any { it.value == "萌宠" })
        assertTrue(tagDao.inserted.none { it.value == "毛球" })
    }
}
