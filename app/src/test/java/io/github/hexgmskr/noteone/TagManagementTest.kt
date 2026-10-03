package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.tag.TagRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.fail

/**
 * 标签改名/合并的 JVM 单测（标签管理，2026-10-03）。
 *
 * 未知数是两条写语义：
 *  - 改名撞名**必须被拒**（而不是静默吞并另一个标签）——"改名"与"合并"是两件事；
 *  - 合并＝先改挂引用、再删来源，且同一条记录挂过两个标签时要塌缩成一条关联。
 *
 * 真库上 `UPDATE OR REPLACE` 的撞键行为由仪器测试守（TagRepositoryTest）。
 */
class TagManagementTest {

    private lateinit var tagDao: FakeTagDao
    private lateinit var itemTagDao: FakeItemTagDao
    private lateinit var repo: TagRepository

    @Before
    fun setUp() {
        tagDao = FakeTagDao()
        itemTagDao = FakeItemTagDao()
        repo = TagRepository(tagDao, itemTagDao)
    }

    // ---- 改名 ----

    @Test
    fun `rename rewrites value and normalized value`() = runTest {
        val existing = repo.findOrCreate("主题", "萌宠")

        val renamed = repo.rename(existing, "宠物")

        assertEquals("宠物", renamed.value)
        assertEquals("宠物", renamed.normalizedValue)
        assertEquals("不该多出标签", 1, tagDao.inserted.size)
        assertEquals("宠物", tagDao.inserted[0].value)
    }

    @Test
    fun `rename trims the new value`() = runTest {
        val existing = repo.findOrCreate("主题", "萌宠")

        val renamed = repo.rename(existing, "  宠物  ")

        assertEquals("宠物", renamed.value)
    }

    @Test
    fun `rename rejects a blank value`() = runTest {
        val existing = repo.findOrCreate("主题", "萌宠")

        try {
            repo.rename(existing, "   ")
            fail("空名字应被拒")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals("原值不该被动", "萌宠", tagDao.inserted[0].value)
    }

    @Test
    fun `rename rejects an unchanged name`() = runTest {
        val existing = repo.findOrCreate("主题", "萌宠")

        try {
            repo.rename(existing, " 萌宠 ")
            fail("同名应被拒")
        } catch (e: IllegalStateException) {
            assertTrue("应提示名字没变：${e.message}", e.message!!.contains("没变"))
        }
    }

    @Test
    fun `rename rejects a name already taken in the same namespace`() = runTest {
        val a = repo.findOrCreate("主题", "萌宠")
        repo.findOrCreate("主题", "宠物")

        try {
            repo.rename(a, "宠物")
            fail("同维度撞名应被拒——引导改用合并")
        } catch (e: IllegalStateException) {
            assertTrue("提示应指向合并：${e.message}", e.message!!.contains("合并"))
        }
    }

    @Test
    fun `rename allows the same value in a different namespace`() = runTest {
        val a = repo.findOrCreate("主题", "萌宠")
        repo.findOrCreate("手法", "宠物")

        val renamed = repo.rename(a, "宠物")

        assertEquals("跨维度重名是合法的（分面）", "宠物", renamed.value)
    }

    // ---- 合并 ----

    @Test
    fun `merge repoints links then deletes the source`() = runTest {
        val from = repo.findOrCreate("主题", "萌宠")
        val into = repo.findOrCreate("主题", "宠物")
        itemTagDao.inserted += ItemTag(itemId = 1L, tagId = from.id)
        itemTagDao.inserted += ItemTag(itemId = 2L, tagId = from.id)

        repo.merge(from, into)

        assertEquals(
            "两条引用都应改挂到目标",
            listOf(into.id, into.id),
            itemTagDao.inserted.map { it.tagId },
        )
        assertTrue("来源标签应被删除", tagDao.inserted.none { it.id == from.id })
        assertTrue("目标标签应保留", tagDao.inserted.any { it.id == into.id })
    }

    @Test
    fun `merge collapses an item that carried both tags`() = runTest {
        val from = repo.findOrCreate("主题", "萌宠")
        val into = repo.findOrCreate("主题", "宠物")
        // 同一条记录两个标签都挂了——REPLACE 语义必须塌缩成一条关联
        itemTagDao.inserted += ItemTag(itemId = 1L, tagId = from.id)
        itemTagDao.inserted += ItemTag(itemId = 1L, tagId = into.id)

        repo.merge(from, into)

        assertEquals("同一条记录只该剩一次目标关联", 1, itemTagDao.inserted.size)
        assertEquals(into.id, itemTagDao.inserted.single().tagId)
    }

    @Test
    fun `merge onto itself is rejected`() = runTest {
        val a = repo.findOrCreate("主题", "萌宠")

        try {
            repo.merge(a, a)
            fail("合并到自己应被拒")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    // ---- 批量删除（标签管理的多选删除）----

    @Test
    fun `deleteByIds removes the named tags and reports count`() = runTest {
        val a = repo.findOrCreate("主题", "萌宠")
        val b = repo.findOrCreate("主题", "宠物")
        repo.findOrCreate("主题", "美景")

        val removed = repo.deleteByIds(listOf(a.id, b.id))

        assertEquals(2L, removed)
        assertEquals(listOf("美景"), tagDao.inserted.map { it.value })
    }

    @Test
    fun `deleteByIds with an empty list is a no-op`() = runTest {
        repo.findOrCreate("主题", "萌宠")

        assertEquals(0L, repo.deleteByIds(emptyList()))
        assertEquals("不该误删", 1, tagDao.inserted.size)
    }

    // 关联行的级联清除是真库行为（外键 CASCADE），由仪器测试守。
}
