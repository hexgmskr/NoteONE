package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.item.ItemRepository
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.data.tag.TagRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 打标签写路径的 JVM 单测。
 *
 * 唯一未知数是「一条记录 + 它的标签」的落库语义：
 * 事务是否真的包住了三张表、重复草稿是否塌缩成一条关联、失败是否留半截数据。
 *
 * 归一化/去重本身已由 TagNormalizerTest 与 TagRepositoryTest 覆盖，
 * 这里只测 [ItemRepository] 怎么编排它们。
 */
class ItemRepositoryTest {

    private lateinit var itemDao: FakeItemDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var itemTagDao: FakeItemTagDao

    /** 记录事务是否被使用，以及失败时是否回滚假表。 */
    private var transactionEntered = 0

    private lateinit var repo: ItemRepository

    @Before
    fun setUp() {
        itemDao = FakeItemDao()
        tagDao = FakeTagDao()
        itemTagDao = FakeItemTagDao()
        transactionEntered = 0

        // 假事务：照抄 Room 的语义——异常时回滚掉本次已写入的行。
        val inTransaction: suspend (block: suspend () -> Long) -> Long = { block ->
            transactionEntered++
            val itemSnapshot = itemDao.inserted.toList()
            val tagSnapshot = tagDao.inserted.toList()
            val linkSnapshot = itemTagDao.inserted.toList()
            try {
                block()
            } catch (e: Throwable) {
                itemDao.inserted.clear()
                itemDao.inserted.addAll(itemSnapshot)
                tagDao.inserted.clear()
                tagDao.inserted.addAll(tagSnapshot)
                itemTagDao.inserted.clear()
                itemTagDao.inserted.addAll(linkSnapshot)
                throw e
            }
        }

        repo = ItemRepository(
            itemDao = itemDao,
            tagRepository = TagRepository(tagDao, itemTagDao),
            itemTagDao = itemTagDao,
            inTransaction = inTransaction,
        )
    }

    @Test
    fun `save writes item with content and timestamp`() = runTest {
        val id = repo.saveWithTags("hello", emptyList(), now = 42L)

        assertEquals(1, itemDao.inserted.size)
        assertEquals("hello", itemDao.inserted[0].content)
        assertEquals(42L, itemDao.inserted[0].createdAt)
        assertEquals(42L, itemDao.inserted[0].updatedAt)
        assertEquals(id, itemDao.inserted[0].id)
    }

    @Test
    fun `item without tags writes no links`() = runTest {
        repo.saveWithTags("bare", emptyList(), now = 0L)

        assertTrue("无标签时不该产生关联行", itemTagDao.inserted.isEmpty())
    }

    @Test
    fun `drafts land as tags plus links`() = runTest {
        val id = repo.saveWithTags(
            content = "x",
            drafts = listOf(TagDraft("手法", "MV"), TagDraft("主题", "萌宠")),
            now = 0L,
        )

        assertEquals("两条草稿应建两条标签", 2, tagDao.inserted.size)
        assertEquals("每条标签各挂一条关联", 2, itemTagDao.inserted.size)
        assertTrue(itemTagDao.inserted.all { it.itemId == id })
    }

    @Test
    fun `case-variant drafts collapse to one link`() = runTest {
        val id = repo.saveWithTags(
            content = "x",
            drafts = listOf(TagDraft("手法", "MV"), TagDraft("手法", "mv"), TagDraft("手法", " Mv ")),
            now = 0L,
        )

        assertEquals("三种写法应解析成同一条标签", 1, tagDao.inserted.size)
        assertEquals("不能重复挂关联——item_tag 是复合主键", 1, itemTagDao.inserted.size)
        assertEquals(id, itemTagDao.inserted[0].itemId)
    }

    @Test
    fun `same value in different namespaces is two tags`() = runTest {
        repo.saveWithTags(
            content = "x",
            drafts = listOf(TagDraft("长度", "MV"), TagDraft("手法", "MV")),
            now = 0L,
        )

        assertEquals("同名不同维度是两条标签（faceted）", 2, tagDao.inserted.size)
        assertEquals(2, itemTagDao.inserted.size)
    }

    @Test
    fun `writes happen inside a transaction`() = runTest {
        repo.saveWithTags("x", listOf(TagDraft("主题", "萌宠")), now = 0L)

        assertEquals("三张表的写入必须由事务包住", 1, transactionEntered)
    }

    @Test
    fun `failure mid-write leaves no partial rows`() = runTest {
        itemTagDao.shouldFail = true

        try {
            repo.saveWithTags("x", listOf(TagDraft("主题", "萌宠")), now = 0L)
            fail("关联写失败应向上抛")
        } catch (e: RuntimeException) {
            // 预期
        }

        assertTrue("记录不该留下孤儿", itemDao.inserted.isEmpty())
        assertTrue("标签不该留下孤儿", tagDao.inserted.isEmpty())
        assertTrue("关联表也不该有残留", itemTagDao.inserted.isEmpty())
    }

    // ---- 编辑 / 删除（使用期第一项）----

    @Test
    fun `update replaces content and keeps createdAt`() = runTest {
        val id = repo.saveWithTags("old", emptyList(), now = 100L)

        repo.updateWithTags(itemId = id, content = "new", drafts = emptyList(), now = 200L)

        val stored = itemDao.inserted.single()
        assertEquals("new", stored.content)
        assertEquals("createdAt 不该被编辑动到", 100L, stored.createdAt)
        assertEquals("updatedAt 应记下本次编辑", 200L, stored.updatedAt)
    }

    @Test
    fun `update re-attaches tags wholesale`() = runTest {
        val id = repo.saveWithTags("x", listOf(TagDraft("主题", "萌宠")), now = 0L)

        repo.updateWithTags(id, "x", listOf(TagDraft("手法", "延时摄影")), now = 0L)

        assertEquals("旧的关联应被整体清掉，只剩新的一挂", 1, itemTagDao.inserted.size)
        val newTag = tagDao.inserted.single { it.value == "延时摄影" }
        assertEquals(newTag.id, itemTagDao.inserted[0].tagId)
        assertEquals(id, itemTagDao.inserted[0].itemId)
    }

    @Test
    fun `update with case-variant drafts collapses to one link`() = runTest {
        val id = repo.saveWithTags("x", emptyList(), now = 0L)

        repo.updateWithTags(id, "x", listOf(TagDraft("手法", "MV"), TagDraft("手法", "mv")), now = 0L)

        assertEquals("与新建同一条去重口径", 1, itemTagDao.inserted.size)
    }

    @Test
    fun `update of missing item throws and writes nothing`() = runTest {
        try {
            repo.updateWithTags(
                itemId = 999L,
                content = "x",
                drafts = listOf(TagDraft("主题", "萌宠")),
                now = 0L,
            )
            fail("目标不存在应向上抛")
        } catch (e: IllegalStateException) {
            assertEquals("记录不存在", e.message)
        }

        assertTrue("不该留下标签", tagDao.inserted.isEmpty())
        assertTrue("不该留下关联", itemTagDao.inserted.isEmpty())
    }

    @Test
    fun `update happens inside a transaction`() = runTest {
        val id = repo.saveWithTags("x", emptyList(), now = 0L)
        transactionEntered = 0

        repo.updateWithTags(id, "y", listOf(TagDraft("主题", "萌宠")), now = 0L)

        assertEquals("三张表的改动必须由事务包住", 1, transactionEntered)
    }

    @Test
    fun `update failure rolls back the content change`() = runTest {
        val id = repo.saveWithTags("old", emptyList(), now = 0L)
        itemTagDao.shouldFail = true

        try {
            repo.updateWithTags(id, "new", listOf(TagDraft("主题", "萌宠")), now = 200L)
            fail("关联写失败应向上抛")
        } catch (e: RuntimeException) {
            // 预期
        }

        val stored = itemDao.inserted.single()
        assertEquals("内容改动应随事务回滚", "old", stored.content)
        assertEquals("updatedAt 也不该留下", 0L, stored.updatedAt)
    }

    @Test
    fun `deleteByIds removes the given rows and reports count`() = runTest {
        val a = repo.saveWithTags("a", emptyList(), now = 0L)
        val b = repo.saveWithTags("b", emptyList(), now = 0L)
        val c = repo.saveWithTags("c", emptyList(), now = 0L)

        val removed = repo.deleteByIds(listOf(a, c))

        assertEquals(2L, removed)
        assertEquals("只该剩没被点名的 b", listOf(b), itemDao.inserted.map { it.id })
    }

    @Test
    fun `deleteByIds of unknown ids reports zero`() = runTest {
        repo.saveWithTags("a", emptyList(), now = 0L)

        assertEquals(0L, repo.deleteByIds(listOf(999L)))
        assertEquals("真行不该被误删", 1, itemDao.inserted.size)
    }

    @Test
    fun `deleteByIds with empty list is a no-op`() = runTest {
        repo.saveWithTags("a", emptyList(), now = 0L)
        transactionEntered = 0

        assertEquals(0L, repo.deleteByIds(emptyList()))
        assertEquals("不该误删", 1, itemDao.inserted.size)
        assertEquals("空列表不该进事务", 0, transactionEntered)
    }
}
