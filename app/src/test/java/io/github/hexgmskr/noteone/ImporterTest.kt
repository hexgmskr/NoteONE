package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.export.ImportItem
import io.github.hexgmskr.noteone.data.export.ImportReport
import io.github.hexgmskr.noteone.data.export.ImportTag
import io.github.hexgmskr.noteone.data.export.Importer
import io.github.hexgmskr.noteone.data.tag.TagRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 合并导入（[Importer]）的 JVM 单测。
 *
 * 去重口径是用户拍板的：`(content, createdAt)` 相同才算"同一条"。
 * 这里把"幂等"钉死（同一份文件导一百遍 = 一遍），以及"看着像但不是"的不误杀。
 * 事务口直通（`{ it() }`）——事务本身由真机的 SqlCipher 用例守着。
 */
class ImporterTest {

    private lateinit var itemDao: FakeItemDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var itemTagDao: FakeItemTagDao
    private lateinit var importer: Importer

    @Before
    fun setUp() {
        itemDao = FakeItemDao()
        tagDao = FakeTagDao()
        itemTagDao = FakeItemTagDao()
        importer = Importer(
            itemDao = itemDao,
            tagRepository = TagRepository(tagDao, itemTagDao),
            itemTagDao = itemTagDao,
            inTransaction = { block -> block() },
        )
    }

    private fun item(content: String, createdAt: Long, vararg tags: Pair<String, String>) =
        ImportItem(
            content = content,
            note = null,
            createdAt = createdAt,
            tags = tags.map { ImportTag(it.first, it.second) },
        )

    @Test
    fun `imports into an empty database`() = runTest {
        val report = importer.import(listOf(item("a", 1, "主题" to "萌宠"), item("b", 2)), now = 100)

        assertEquals(ImportReport(imported = 2, skipped = 0), report)
        assertEquals(listOf("a", "b"), itemDao.inserted.map { it.content })
        assertEquals(1, tagDao.inserted.size)
        assertEquals(1, itemTagDao.inserted.size)
    }

    @Test
    fun `re-importing the same file is a no-op`() = runTest {
        val items = listOf(item("a", 1), item("b", 2))
        importer.import(items, now = 100)

        val again = importer.import(items, now = 200)

        assertEquals(ImportReport(imported = 0, skipped = 2), again)
        assertEquals("第二次不许再插行", 2, itemDao.inserted.size)
    }

    @Test
    fun `only exact content-and-time matches are duplicates`() = runTest {
        // 库里已有 "a"（时间 1）；文件里 "a" 出现了两次但时间不同——
        // 时间不同的那条是"刻意的第二条"，不是重复
        itemDao.insert(Item(content = "a", createdAt = 1, updatedAt = 1))

        val report = importer.import(listOf(item("a", 1), item("a", 2), item("b", 3)), now = 100)

        assertEquals(ImportReport(imported = 2, skipped = 1), report)
    }

    @Test
    fun `deduplicates within the file itself`() = runTest {
        val report = importer.import(listOf(item("a", 1), item("a", 1)), now = 100)

        assertEquals(ImportReport(imported = 1, skipped = 1), report)
    }

    @Test
    fun `reuses existing tags by normalized value`() = runTest {
        tagDao.insert(Tag(namespace = "来源", value = "MV", normalizedValue = "mv"))

        importer.import(listOf(item("a", 1, "来源" to "mv")), now = 100)

        assertEquals("「mv」必须复用既有的「MV」，不许建第二个", 1, tagDao.inserted.size)
        assertEquals("链接要指向已有那条的 id", 1L, itemTagDao.inserted.single().tagId)
    }

    @Test
    fun `duplicate tags on one item link once`() = runTest {
        // "MV" 和 "mv" 会解析到同一个标签——item_tag 不许撞复合主键
        importer.import(listOf(item("a", 1, "来源" to "MV", "来源" to "mv")), now = 100)

        assertEquals(1, tagDao.inserted.size)
        assertEquals(1, itemTagDao.inserted.size)
    }

    @Test
    fun `keeps note and original createdAt, stamps updatedAt with import time`() = runTest {
        importer.import(
            listOf(ImportItem(content = "a", note = "备注", createdAt = 5, tags = emptyList())),
            now = 999,
        )

        val row = itemDao.inserted.single()
        assertEquals(5L, row.createdAt)
        assertEquals(999L, row.updatedAt)
        assertEquals("备注", row.note)
    }
}
