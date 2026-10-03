package io.github.hexgmskr.noteone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.entity.Tag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 步骤 2 测试：多对多关系查询（@Relation + Junction）。
 *
 * 唯一未知数是关系映射本身。它最危险的失败模式是**标签串台**——
 * 记录 A 拿到记录 B 的标签。声明写错时编译照样通过，只能靠运行期测试抓。
 * 所以下面的重点是关联归属正确性，不只是「能查出东西」。
 */
@RunWith(AndroidJUnit4::class)
class ItemWithTagsRelationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NoteOneDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NoteOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun tag(ns: String, v: String): Long =
        db.tagDao().insert(Tag(namespace = ns, value = v, normalizedValue = v))

    private suspend fun item(content: String, at: Long): Long =
        db.itemDao().insert(Item(content = content, createdAt = at, updatedAt = at))

    @Test
    fun tagsAreAttachedToTheRightItemNotCrossed() = runBlocking {
        val tCat = tag("主题", "萌宠")
        val tDog = tag("主题", "狗狗")

        val i1 = item("A", 1000)
        val i2 = item("B", 2000)
        db.itemTagDao().insert(ItemTag(itemId = i1, tagId = tCat))
        db.itemTagDao().insert(ItemTag(itemId = i2, tagId = tDog))

        val all = db.itemDao().findAllWithTags().associateBy { it.item.content }

        assertEquals("B", all.getValue("B").item.content)
        assertEquals(
            "记录 A 只应有萌宠标签。串台的话这里会拿到狗狗",
            listOf("萌宠"),
            all.getValue("A").tags.map { it.value },
        )
        assertEquals(
            "记录 B 只应有狗狗标签",
            listOf("狗狗"),
            all.getValue("B").tags.map { it.value },
        )
    }

    @Test
    fun oneItemCanCarryMultipleTags() = runBlocking {
        val t1 = tag("长度", "长视频")
        val t2 = tag("主题", "萌宠")
        val t3 = tag("手法", "多镜头")
        val id = item("multi", 1000)
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = t1))
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = t2))
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = t3))

        val got = db.itemDao().findWithTagsById(id)
        assertNotNull(got)
        assertEquals(3, got!!.tags.size)
        assertEquals(
            "三维度标签应齐全",
            setOf("长视频", "萌宠", "多镜头"),
            got.tags.map { it.value }.toSet(),
        )
    }

    @Test
    fun sharedTagAppearsOnEveryItemThatUsesIt() = runBlocking {
        val t = tag("主题", "萌宠")
        val i1 = item("one", 1000)
        val i2 = item("two", 2000)
        val i3 = item("three", 3000)
        db.itemTagDao().insert(ItemTag(itemId = i1, tagId = t))
        db.itemTagDao().insert(ItemTag(itemId = i3, tagId = t))
        // i2 不打这个标签

        val filtered = db.itemDao().findByTag(t)
        assertEquals("只有打过该标签的记录应被筛出", 2, filtered.size)
        assertEquals(
            setOf("one", "three"),
            filtered.map { it.item.content }.toSet(),
        )
        // 筛出的记录要带着自己的标签
        assertTrue(filtered.all { it.tags.map { x -> x.value } == listOf("萌宠") })
    }

    @Test
    fun itemWithNoTagsYieldsEmptyListNotNull() = runBlocking {
        val id = item("bare", 1000)
        val got = db.itemDao().findWithTagsById(id)
        assertNotNull("无标签记录也应能取到", got)
        assertTrue("标签应为空列表而非 null", got!!.tags.isEmpty())
    }

    @Test
    fun findAllWithTagsOrdersByCreatedAtDescending() = runBlocking {
        item("oldest", 1000)
        item("newest", 9000)
        item("middle", 5000)

        val ordered = db.itemDao().findAllWithTags().map { it.item.content }
        assertEquals(listOf("newest", "middle", "oldest"), ordered)
    }

    @Test
    fun findByTagReturnsEmptyWhenTagUnused() = runBlocking {
        val t = tag("主题", "无人使用")
        item("orphan", 1000)
        assertTrue(db.itemDao().findByTag(t).isEmpty())
    }

    @Test
    fun sortedTagsOrdersByNamespaceThenValue() = runBlocking {
        // 插入顺序刻意打乱：展示序不得依赖插入顺序
        val tTheme = tag("主题", "萌宠")
        val tLength = tag("长度", "长视频")
        val tTech = tag("手法", "多镜头")
        val id = item("order", 1000)
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = tTheme))
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = tLength))
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = tTech))

        val got = db.itemDao().findWithTagsById(id)!!

        // 契约：按 namespace 升序，同 namespace 内按 value 升序
        val namespaces = got.sortedTags.map { it.namespace }
        assertEquals("namespace 应升序", namespaces.sorted(), namespaces)
        assertEquals(setOf("主题", "长度", "手法"), namespaces.toSet())
    }

    @Test
    fun sortedTagsIsStableAcrossRepeats() = runBlocking {
        val t1 = tag("b", "2")
        val t2 = tag("a", "1")
        val t3 = tag("a", "0")
        val id = item("stable", 1000)
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = t1))
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = t2))
        db.itemTagDao().insert(ItemTag(itemId = id, tagId = t3))

        val first = db.itemDao().findWithTagsById(id)!!.sortedTags.map { it.value }
        val second = db.itemDao().findWithTagsById(id)!!.sortedTags.map { it.value }

        // 同 namespace 内按 value 升序：a/0, a/1, b/2
        assertEquals(listOf("0", "1", "2"), first)
        assertEquals("重复查询顺序必须一致", first, second)
    }
}
