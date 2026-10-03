package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.data.tag.TagRepository
import io.github.hexgmskr.noteone.ui.edit.EditViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 编辑页保存逻辑的 JVM 单测。
 *
 * 未知数是保存语义（空内容拒存、时间戳、成功信号）与标签草稿的增删校验。
 * 剪贴板读取属 UI 层能力，JVM 测不到，由真机验证覆盖。
 *
 * 落库编排本身由 ItemRepositoryTest 覆盖，这里用真
 * [ItemRepository] + 假 DAO，只断言 ViewModel 交出去的东西对。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditViewModelTest {

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

    /** 内存版顺序存储：单测不碰 SharedPreferences。 */

    /** 展示顺序偏好：内存版（真实现见 TagOrderStore）。 */
    private val orderSource = InMemoryTagOrder()

    private fun viewModel(clockAt: Long = 12345L, editingItemId: Long? = null) =
        EditViewModel(repository(), itemDao, tagDao, orderSource, editingItemId) { clockAt }

    // ---- 保存语义 ----

    @Test
    fun `empty content cannot be saved`() {
        val vm = viewModel()
        vm.onContentChange("   ")
        assertFalse("空白内容不允许保存", vm.uiState.value.canSave)
    }

    @Test
    fun `non blank content can be saved`() {
        val vm = viewModel()
        vm.onContentChange("https://example.com")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `save inserts item with trimmed content and clock timestamps`() = runTest {
        val vm = viewModel(clockAt = 99999L)
        vm.onContentChange("  hello  ")
        vm.save()

        assertEquals(1, itemDao.inserted.size)
        assertEquals("保存时应去掉首尾空格", "hello", itemDao.inserted[0].content)
        assertEquals(99999L, itemDao.inserted[0].createdAt)
        assertEquals(99999L, itemDao.inserted[0].updatedAt)
    }

    @Test
    fun `save sets saved flag exactly once`() = runTest {
        val vm = viewModel()
        vm.onContentChange("x")
        vm.save()
        assertTrue("保存后应置 saved 标志", vm.uiState.value.saved)

        vm.onSavedConsumed()
        assertFalse("消费后应清掉，避免重复跳转", vm.uiState.value.saved)
    }

    @Test
    fun `blank content save is a no-op`() = runTest {
        val vm = viewModel()
        vm.onContentChange("   ")
        vm.save()
        assertEquals("空白内容不应写库", 0, itemDao.inserted.size)
    }

    // ---- 剪贴板 ----

    @Test
    fun `paste replaces content`() {
        val vm = viewModel()
        vm.onContentChange("old")
        vm.onPaste("https://pasted.example")
        assertEquals("https://pasted.example", vm.uiState.value.content)
    }

    @Test
    fun `paste with blank clipboard sets error and keeps content`() {
        val vm = viewModel()
        vm.onContentChange("keep me")

        vm.onPaste(null)
        assertNotNull("空剪贴板应给出提示", vm.uiState.value.error)
        assertEquals("内容不应被清掉", "keep me", vm.uiState.value.content)

        vm.onPaste("   ")
        assertNotNull(vm.uiState.value.error)
        assertEquals("keep me", vm.uiState.value.content)
    }

    @Test
    fun `paste with text clears prior error`() {
        val vm = viewModel()
        vm.onPaste(null)
        assertNotNull(vm.uiState.value.error)

        vm.onPaste("text")
        assertNull("粘到内容后应清掉错误提示", vm.uiState.value.error)
    }

    // ---- 失败路径 ----

    @Test
    fun `failed save surfaces error and resets saving flag`() = runTest {
        itemDao.shouldFail = true
        val vm = viewModel()
        vm.onContentChange("x")
        vm.save()

        assertFalse("失败后不应停留在 isSaving", vm.uiState.value.isSaving)
        assertNotNull("应给出错误提示", vm.uiState.value.error)
        assertFalse("失败不应置 saved", vm.uiState.value.saved)
    }

    @Test
    fun `changing content after failed save clears error`() {
        itemDao.shouldFail = true
        val vm = viewModel()
        vm.onContentChange("x")
        kotlinx.coroutines.runBlocking { vm.save() }
        assertNotNull(vm.uiState.value.error)

        vm.onContentChange("y")
        assertNull("用户继续输入时应清掉旧错误", vm.uiState.value.error)
    }

    // ---- 标签草稿 ----

    @Test
    fun `addTag appends a draft`() {
        val vm = viewModel()
        assertTrue(vm.addTag("手法", "MV"))
        assertEquals(listOf(TagDraft("手法", "MV")), vm.uiState.value.tags)
    }

    @Test
    fun `addTag trims namespace and value`() {
        val vm = viewModel()
        vm.addTag("  手法  ", "  MV  ")
        assertEquals(listOf(TagDraft("手法", "MV")), vm.uiState.value.tags)
    }

    @Test
    fun `empty namespace is rejected`() {
        val vm = viewModel()
        assertFalse(vm.addTag("   ", "MV"))
        assertTrue("空维度不该进草稿", vm.uiState.value.tags.isEmpty())
        assertNotNull("应给出拒因", vm.uiState.value.error)
    }

    @Test
    fun `empty value is rejected`() {
        val vm = viewModel()
        assertFalse(vm.addTag("手法", "   "))
        assertTrue(vm.uiState.value.tags.isEmpty())
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `case-variant duplicate draft is rejected`() {
        val vm = viewModel()
        assertTrue(vm.addTag("手法", "MV"))

        assertFalse("同一维度下的 mv 应视为重复", vm.addTag("手法", "mv"))
        assertEquals("草稿列表不该堆积同一词", 1, vm.uiState.value.tags.size)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `same value under different namespace is allowed`() {
        val vm = viewModel()
        assertTrue(vm.addTag("长度", "MV"))
        assertTrue("同名不同维度必须放行（faceted）", vm.addTag("手法", "MV"))
        assertEquals(2, vm.uiState.value.tags.size)
    }

    @Test
    fun `removeTag drops the draft`() {
        val vm = viewModel()
        vm.addTag("手法", "MV")
        vm.addTag("主题", "萌宠")

        vm.removeTag(TagDraft("手法", "MV"))
        assertEquals(listOf(TagDraft("主题", "萌宠")), vm.uiState.value.tags)
    }

    @Test
    fun `save writes drafts through to the repository`() = runTest {
        val vm = viewModel()
        vm.onContentChange("x")
        vm.addTag("手法", "MV")
        vm.addTag("主题", "萌宠")
        vm.save()

        assertEquals("草稿应落成标签", 2, tagDao.inserted.size)
        assertEquals("记录应挂上两条关联", 2, itemTagDao.inserted.size)
    }

    @Test
    fun `save with no drafts writes only the item`() = runTest {
        val vm = viewModel()
        vm.onContentChange("x")
        vm.save()

        assertEquals(1, itemDao.inserted.size)
        assertTrue("没打标签时不该建标签", tagDao.inserted.isEmpty())
        assertTrue(itemTagDao.inserted.isEmpty())
    }

    @Test
    fun `adding a tag clears prior error`() {
        val vm = viewModel()
        vm.addTag("", "MV")
        assertNotNull(vm.uiState.value.error)

        vm.addTag("手法", "MV")
        assertNull("成功加标签后应清掉错误", vm.uiState.value.error)
    }

    // ---- 标签面板：选已有 ----

    @Test
    fun `existing tags load on init`() {
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 1, namespace = "手法", value = "MV", normalizedValue = "mv",
        )
        val vm = viewModel()
        assertEquals(1, vm.uiState.value.existingTags.size)
    }

    @Test
    fun `toggleExisting adds then removes`() {
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 1, namespace = "手法", value = "MV", normalizedValue = "mv",
        )
        val vm = viewModel()
        val existing = vm.uiState.value.existingTags.single()

        vm.toggleExisting(existing)
        assertTrue("选中后应进草稿", vm.uiState.value.isSelected(existing))

        vm.toggleExisting(existing)
        assertFalse("再点一下应取消选中", vm.uiState.value.isSelected(existing))
    }

    @Test
    fun `existing tags grouped by namespace`() {
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 1, namespace = "主题", value = "萌宠", normalizedValue = "萌宠",
        )
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 2, namespace = "手法", value = "MV", normalizedValue = "mv",
        )
        val vm = viewModel()
        assertEquals(listOf("主题", "手法"), vm.uiState.value.existingByNamespace.map { it.namespace })
    }

    // ---- 标签面板：模糊匹配 ----

    @Test
    fun `typing a value drives suggestions`() {
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 1, namespace = "手法", value = "MV", normalizedValue = "mv",
        )
        val vm = viewModel()

        assertTrue("没输入时不该有建议", vm.uiState.value.suggestions.isEmpty())

        vm.onValueChange("m")
        assertEquals("输入应触发匹配", listOf("MV"), vm.uiState.value.suggestions.map { it.value })
    }

    @Test
    fun `already-selected tags are not suggested`() {
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 1, namespace = "手法", value = "MV", normalizedValue = "mv",
        )
        val vm = viewModel()
        val existing = vm.uiState.value.existingTags.single()
        vm.toggleExisting(existing)

        vm.onValueChange("mv")
        assertTrue("已选中的不该再出现在建议里", vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `case-variant selection suppresses suggestion`() {
        tagDao.inserted += io.github.hexgmskr.noteone.data.entity.Tag(
            id = 1, namespace = "手法", value = "MV", normalizedValue = "mv",
        )
        val vm = viewModel()
        // 手敲草稿，与已有标签归一化后相同
        vm.addTag("手法", "mv")

        vm.onValueChange("MV")
        assertTrue("归一化相同就算选中，不该重复提示", vm.uiState.value.suggestions.isEmpty())
    }

    // ---- 标签面板：新建 ----

    @Test
    fun `addTagFromInputs uses the input fields`() {
        val vm = viewModel()
        vm.onNamespaceChange("主题")
        vm.onValueChange("萌宠")

        assertTrue(vm.addTagFromInputs())
        assertEquals(listOf(TagDraft("主题", "萌宠")), vm.uiState.value.tags)
    }

    @Test
    fun `addTagFromInputs clears value but keeps namespace`() {
        val vm = viewModel()
        vm.onNamespaceChange("主题")
        vm.onValueChange("萌宠")
        vm.addTagFromInputs()

        assertEquals("值输入清空，好接着建同维度的下一个", "", vm.uiState.value.valueInput)
        assertEquals("维度留着", "主题", vm.uiState.value.namespaceInput)
    }

    @Test
    fun `addTagFromInputs rejects empty value and keeps inputs`() {
        val vm = viewModel()
        vm.onNamespaceChange("主题")
        vm.onValueChange("   ")

        assertFalse(vm.addTagFromInputs())
        assertTrue(vm.uiState.value.tags.isEmpty())
        assertNotNull(vm.uiState.value.error)
        assertEquals("被拒时维度输入该留着", "主题", vm.uiState.value.namespaceInput)
    }

    // ---- 新建标签区折叠 ----

    @Test
    fun `tag creator starts collapsed`() {
        val vm = viewModel()
        assertFalse("建记录时新建区默认折叠，免得把保存按钮挤出首屏", vm.uiState.value.isTagCreatorExpanded)
    }

    @Test
    fun `expandTagCreator expands and stays expanded`() {
        val vm = viewModel()
        vm.expandTagCreator()
        assertTrue(vm.uiState.value.isTagCreatorExpanded)

        // 连加几个标签后也不该自己收回去
        vm.onNamespaceChange("主题")
        vm.onValueChange("萌宠")
        vm.addTagFromInputs()
        vm.onValueChange("灵动")
        vm.addTagFromInputs()

        assertTrue(
            "展开状态要持久——加完标签自动收回的话，连建几个标签得反复展开",
            vm.uiState.value.isTagCreatorExpanded,
        )
    }

    @Test
    fun `collapseTagCreator collapses`() {
        val vm = viewModel()
        vm.expandTagCreator()
        vm.collapseTagCreator()
        assertFalse(vm.uiState.value.isTagCreatorExpanded)
    }

    @Test
    fun `expandTagCreator when already expanded is a no-op`() {
        val vm = viewModel()
        vm.expandTagCreator()
        vm.onValueChange("x")
        vm.expandTagCreator()
        // 二次展开不该清掉正在输入的内容
        assertEquals("x", vm.uiState.value.valueInput)
    }

    // ---- 编辑既有记录（editingItemId 非空）----

    private fun givenExistingItem(id: Long, content: String, vararg tags: Tag) {
        val item = Item(id = id, content = content, createdAt = 100L, updatedAt = 100L)
        // 读路径（findWithTagsById）与写路径（findById/update）各摆一份，见 FakeDaos 注释
        itemDao.inserted += item
        itemDao.itemsWithTags = itemDao.itemsWithTags + ItemWithTags(item = item, tags = tags.toList())
    }

    @Test
    fun `create mode is not editing`() {
        assertFalse(viewModel().isEditing)
    }

    @Test
    fun `edit mode loads content and tags from the item`() {
        givenExistingItem(7L, "旧内容", Tag(id = 1L, namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))
        val vm = viewModel(editingItemId = 7L)

        assertTrue(vm.isEditing)
        assertEquals("旧内容", vm.uiState.value.content)
        assertEquals("标签应转成草稿预填", 1, vm.uiState.value.tags.size)
        assertEquals("主题", vm.uiState.value.tags[0].namespace)
        assertEquals("萌宠", vm.uiState.value.tags[0].value)
    }

    @Test
    fun `edit mode save updates instead of inserting`() = runTest {
        givenExistingItem(7L, "旧内容")
        val vm = viewModel(clockAt = 999L, editingItemId = 7L)
        vm.onContentChange(" 新内容 ")

        vm.save()

        assertEquals("编辑不该插入新行", 1, itemDao.inserted.size)
        assertEquals("新内容", itemDao.inserted[0].content)
        assertEquals("createdAt 不动", 100L, itemDao.inserted[0].createdAt)
        assertEquals("updatedAt 记本次编辑", 999L, itemDao.inserted[0].updatedAt)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `edit mode save rewrites tag links wholesale`() = runTest {
        givenExistingItem(7L, "旧内容", Tag(id = 1L, namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))
        itemTagDao.inserted += ItemTag(itemId = 7L, tagId = 1L)   // 种子：旧关联
        val vm = viewModel(editingItemId = 7L)

        vm.removeTag(vm.uiState.value.tags[0])
        vm.addTag("手法", "延时摄影")
        vm.save()

        assertEquals("旧关联应被清掉、只留新的一挂", 1, itemTagDao.inserted.size)
        assertEquals(7L, itemTagDao.inserted[0].itemId)
    }

    @Test
    fun `edit mode of missing item surfaces an error`() {
        val vm = viewModel(editingItemId = 404L)

        assertNotNull("目标不存在要给提示", vm.uiState.value.error)
        assertFalse("载入失败后内容为空，不该允许保存", vm.uiState.value.canSave)
    }

    @Test
    fun `edit mode save of missing item reports failure`() = runTest {
        // 载入为空但内容被用户填上（理论上不可达，防御性覆盖）：
        // 保存时 updateWithTags 找不到目标 → 错误提示而不是崩溃
        val vm = viewModel(editingItemId = 404L)
        vm.onContentChange("x")

        vm.save()

        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.saved)
        assertEquals("不该插入任何行", 0, itemDao.inserted.size)
    }
}
