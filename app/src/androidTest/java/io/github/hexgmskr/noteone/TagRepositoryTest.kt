package io.github.hexgmskr.noteone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.data.db.NoteOneDatabase
import io.github.hexgmskr.noteone.data.entity.Item
import io.github.hexgmskr.noteone.data.entity.ItemTag
import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.tag.TagRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 步骤 3：去重语义（findOrCreate）。
 *
 * 唯一未知数是「MV ≡ mv」这套语义的实现是否自洽。
 * 归一化规则本身已由 TagNormalizerTest（JVM）覆盖，这里只测与数据库交互的部分。
 */
@RunWith(AndroidJUnit4::class)
class TagRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: NoteOneDatabase
    private lateinit var repo: TagRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NoteOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TagRepository(db.tagDao(), db.itemTagDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mvAndLowercaseMvAreTheSameTag() = runBlocking {
        val a = repo.findOrCreate("手法", "MV")
        val b = repo.findOrCreate("手法", "mv")

        assertEquals("大小写不同的同一词必须命中同一条标签", a.id, b.id)
        assertEquals("表里只应有一条", 1, db.tagDao().findAll().size)
    }

    @Test
    fun firstCreatedCasingIsPreserved() = runBlocking {
        repo.findOrCreate("手法", "MV")
        val later = repo.findOrCreate("手法", "mv")

        assertEquals(
            "后输的 mv 应保留最早创建时的展示形式 MV",
            "MV",
            later.value,
        )
    }

    @Test
    fun firstCreatedCasingIsPreservedRegardlessOfOrder() = runBlocking {
        // 反向顺序：先小写后大写，展示应保持小写
        repo.findOrCreate("手法", "mv")
        val later = repo.findOrCreate("手法", "MV")

        assertEquals("mv", later.value)
    }

    @Test
    fun sameValueInDifferentNamespaceStaysSeparate() = runBlocking {
        val inLength = repo.findOrCreate("长度", "MV")
        val inTechnique = repo.findOrCreate("手法", "MV")

        assertNotEquals("不同维度下的同名值是两个标签", inLength.id, inTechnique.id)
        assertEquals(2, db.tagDao().findAll().size)
    }

    @Test
    fun findOrCreateIsIdempotent() = runBlocking {
        val first = repo.findOrCreate("主题", "萌宠")
        repeat(5) { repo.findOrCreate("主题", "  萌宠  ") }

        assertEquals(1, db.tagDao().findAll().size)
        assertEquals(first.id, repo.findOrCreate("主题", "萌宠").id)
    }

    @Test
    fun surroundingWhitespaceIsIgnoredForMatching() = runBlocking {
        val a = repo.findOrCreate("主题", "萌宠")
        val b = repo.findOrCreate("主题", "   萌宠   ")

        assertEquals("首尾空格不影响匹配，应命中同一条", a.id, b.id)
        // b 复用的是 a 那条，展示值应保持最早创建的形式，不被后输的空格污染
        assertEquals("展示值保持最早创建的形式", "萌宠", b.value)
    }

    @Test
    fun internalWhitespaceIsNotCollapsed() = runBlocking {
        // spec 明文边界：只去首尾空格。「多  镜头」与「多 镜头」是两个标签。
        val a = repo.findOrCreate("手法", "多  镜头")
        val b = repo.findOrCreate("手法", "多 镜头")

        assertNotEquals(a.id, b.id)
        assertEquals(2, db.tagDao().findAll().size)
    }

    @Test
    fun blankInputIsRejected() = runBlocking {
        for (bad in listOf("", "   ", "\t")) {
            try {
                repo.findOrCreate("主题", bad)
                fail("空白标签必须被拒绝，实际接受了：'$bad'")
            } catch (e: IllegalArgumentException) {
                // 预期
            }
        }
        assertEquals("拒绝的输入不应留下脏行", 0, db.tagDao().findAll().size)
    }

    @Test
    fun blankNamespaceIsRejected() = runBlocking {
        // spec 3.2 禁止「游离标签」：标签必须归属某个维度。
        // 联合唯一索引管不了 namespace 是否为空，必须在写入口这层拦住。
        for (badNs in listOf("", "   ", "\t", "\n")) {
            try {
                repo.findOrCreate(badNs, "MV")
                fail("空白维度名必须被拒绝，实际接受了：'$badNs'")
            } catch (e: IllegalArgumentException) {
                // 预期
            }
        }
        assertEquals("拒绝的输入不应留下脏行", 0, db.tagDao().findAll().size)
    }

    @Test
    fun namespaceSurroundingWhitespaceIsTrimmed() = runBlocking {
        val a = repo.findOrCreate("长度", "MV")
        val b = repo.findOrCreate("  长度  ", "MV")

        assertEquals("维度名首尾空格不影响匹配，应命中同一条", a.id, b.id)
        assertEquals("长度", b.namespace)
        assertEquals(1, db.tagDao().findAll().size)
    }

    @Test
    fun createAllCollapsesDuplicatesWithinOneCall() = runBlocking {
        val made = repo.findOrCreateAll("主题", listOf("MV", "mv", " Mv ", "长视频"))

        assertEquals("同一次调用内的重复项应被合并", 2, made.size)
        assertEquals(2, db.tagDao().findAll().size)
        assertEquals(listOf("MV", "长视频"), made.map { it.value })
    }

    @Test
    fun createAllReturnsTagsInFirstSeenOrder() = runBlocking {
        val made = repo.findOrCreateAll("主题", listOf("b", "a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), made.map { it.value })
    }

    @Test
    fun normalizedValueIsStoredLowercasedAndTrimmed() = runBlocking {
        repo.findOrCreate("手法", "  Hello World  ")
        val stored = db.tagDao().findByNormalized("手法", "hello world")

        assertTrue("按归一化值应能查到", stored != null)
        assertEquals("  Hello World  ", stored!!.value)
        assertEquals("hello world", stored.normalizedValue)
    }

    // ---- 改名 / 合并（标签管理，2026-10-03）----

    @Test
    fun renameUpdatesInPlaceWithoutTouchingLinks() = runBlocking {
        val tagId = db.tagDao().insert(Tag(namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))
        val itemId = db.itemDao().insert(Item(content = "x", createdAt = 1, updatedAt = 1))
        db.itemTagDao().insert(ItemTag(itemId = itemId, tagId = tagId))

        repo.rename(db.tagDao().findById(tagId)!!, "宠物")

        val renamed = db.tagDao().findById(tagId)!!
        assertEquals("宠物", renamed.value)
        assertEquals("宠物", renamed.normalizedValue)
        assertEquals("引用的是 id，天然保留", tagId, db.itemTagDao().tagsOfItem(itemId).single().tagId)
    }

    @Test
    fun deletingTagsCascadesTheirLinksAndKeepsItems() = runBlocking {
        val tagId = db.tagDao().insert(Tag(namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))
        val itemId = db.itemDao().insert(Item(content = "x", createdAt = 1, updatedAt = 1))
        db.itemTagDao().insert(ItemTag(itemId = itemId, tagId = tagId))

        val removed = repo.deleteByIds(listOf(tagId))

        assertEquals(1L, removed)
        assertEquals("标签应被删", null, db.tagDao().findById(tagId))
        assertTrue("指向它的关联行应被级联清掉", db.itemTagDao().tagsOfItem(itemId).isEmpty())
        assertNotNull("记录本身必须保留", db.itemDao().findById(itemId))
    }

    @Test
    fun mergeRepointsLinksAndDedupesSharedItems() = runBlocking {
        val fromId = db.tagDao().insert(Tag(namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))
        val intoId = db.tagDao().insert(Tag(namespace = "主题", value = "宠物", normalizedValue = "宠物"))
        val bothId = db.itemDao().insert(Item(content = "both", createdAt = 1, updatedAt = 1))
        val onlyFromId = db.itemDao().insert(Item(content = "only-from", createdAt = 2, updatedAt = 2))
        db.itemTagDao().insert(ItemTag(itemId = bothId, tagId = fromId))
        db.itemTagDao().insert(ItemTag(itemId = bothId, tagId = intoId))
        db.itemTagDao().insert(ItemTag(itemId = onlyFromId, tagId = fromId))

        repo.merge(
            from = db.tagDao().findById(fromId)!!,
            into = db.tagDao().findById(intoId)!!,
        )

        assertEquals("来源标签应被删掉", null, db.tagDao().findById(fromId))
        assertEquals(
            "两个标签都挂过的记录只该剩一次目标关联（UPDATE OR REPLACE 撞主键）",
            1,
            db.itemTagDao().tagCountOfItem(bothId),
        )
        assertEquals(
            "只挂过来源的记录改挂到目标",
            intoId,
            db.itemTagDao().tagsOfItem(onlyFromId).single().tagId,
        )
    }

    @Test
    fun mergeRefusesCrossNamespace() = runBlocking {
        // 2026-10-04 审计补的护栏：界面只列同维度候选，但"绕过 UI 的调用"也得挡住——
        // 跨维度合并会产出"标签挂错轴"的静默污点数据（分面体系里维度是不同的轴）。
        val fromId = db.tagDao().insert(Tag(namespace = "主题", value = "萌宠", normalizedValue = "萌宠"))
        val intoId = db.tagDao().insert(Tag(namespace = "长度", value = "短视频", normalizedValue = "短视频"))
        val itemId = db.itemDao().insert(Item(content = "x", createdAt = 1, updatedAt = 1))
        db.itemTagDao().insert(ItemTag(itemId = itemId, tagId = fromId))

        val thrown = runCatching {
            repo.merge(
                from = db.tagDao().findById(fromId)!!,
                into = db.tagDao().findById(intoId)!!,
            )
        }.exceptionOrNull()

        assertTrue("跨维度合并必须被拒绝，实际=$thrown", thrown is IllegalArgumentException)
        assertNotNull("来源标签必须原封不动", db.tagDao().findById(fromId))
        assertEquals("关联也不能被动过", fromId, db.itemTagDao().tagsOfItem(itemId).single().tagId)
    }
}
