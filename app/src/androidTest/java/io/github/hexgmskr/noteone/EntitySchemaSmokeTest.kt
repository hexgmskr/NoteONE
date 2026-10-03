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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 步骤 1 冒烟测试：三表结构是否真的按设计意图建出来了。
 *
 * **故意用内存库，不走 DatabaseProvider**——
 * 如果这里也走接缝，失败时就分不清是「表定义错了」还是「开库方式错了」，
 * 两种原因会混进同一个信号里。接缝本身已由 SqlCipherEncryptionTest 单独证明。
 *
 * 覆盖点（每条对应一个表结构特性）：
 *   1. 三表可建、基础增删改查通
 *   2. (namespace, normalizedValue) 联合唯一索引真的拒绝重复
 *   3. 联合唯一是**联合**的——同名归一化值放在不同 namespace 不冲突
 *      （这条证伪的话，就等于出现「同一 value 跨维度语义冲突」的结构漏洞）
 *   4. 删记录时关联行级联删除（CASCADE）
 *   5. 删标签时关联行级联删除
 */
@RunWith(AndroidJUnit4::class)
class EntitySchemaSmokeTest {

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

    @Test
    fun tablesAreCreatedAndBasicCrudWorks() = runBlocking {
        val itemId = db.itemDao().insert(
            Item(content = "https://example.com/v/1", createdAt = 1000, updatedAt = 1000)
        )
        val tagId = db.tagDao().insert(
            Tag(namespace = "主题", value = "萌宠", normalizedValue = "萌宠")
        )

        val item = db.itemDao().findById(itemId)
        assertNotNull("插入后应能按 id 取回", item)
        assertEquals("https://example.com/v/1", item!!.content)

        val tag = db.tagDao().findById(tagId)
        assertNotNull(tag)
        assertEquals("主题", tag!!.namespace)

        db.itemTagDao().insert(ItemTag(itemId = itemId, tagId = tagId))
        assertEquals(1, db.itemTagDao().tagCountOfItem(itemId))
        // （原先还有一条 referenceCountOfTag 断言——该方法随"引用计数"功能一起删了）
    }

    @Test
    fun uniqueIndexRejectsDuplicateNormalizedValueInSameNamespace() = runBlocking {
        db.tagDao().insert(Tag(namespace = "长度", value = "MV", normalizedValue = "mv"))

        val second = runCatching {
            db.tagDao().insert(Tag(namespace = "长度", value = "mv", normalizedValue = "mv"))
        }

        assertTrue(
            "同 namespace 下插入相同 normalizedValue 必须被唯一索引拒绝，实际=$second",
            second.isFailure,
        )
    }

    @Test
    fun uniqueIndexIsCompositeNotGlobal() = runBlocking {
        // 同一个 normalizedValue 落在不同 namespace，必须允许共存。
        // 这是「标签必须归属 namespace、不存在游离标签」的结构保证。
        db.tagDao().insert(Tag(namespace = "手法", value = "多镜头", normalizedValue = "多镜头"))
        val other = runCatching {
            db.tagDao().insert(Tag(namespace = "主题", value = "多镜头", normalizedValue = "多镜头"))
        }

        assertTrue(
            "不同 namespace 下相同 normalizedValue 必须允许共存（索引是联合的），实际=$other",
            other.isSuccess,
        )
        assertEquals(2, db.tagDao().findAll().size)
    }

    @Test
    fun deletingItemCascadesToItemTag() = runBlocking {
        val itemId = db.itemDao().insert(Item(content = "x", createdAt = 1, updatedAt = 1))
        val tagId = db.tagDao().insert(Tag(namespace = "n", value = "v", normalizedValue = "v"))
        db.itemTagDao().insert(ItemTag(itemId = itemId, tagId = tagId))

        db.itemDao().delete(db.itemDao().findById(itemId)!!)

        assertEquals("删记录后关联行必须被级联清掉", 0, db.itemTagDao().tagCountOfItem(itemId))
        // 标签本身不该被误删
        assertNotNull("标签不应随记录一起消失", db.tagDao().findById(tagId))
    }

    @Test
    fun deleteByIdsCascadesToItemTag() = runBlocking {
        val keepId = db.itemDao().insert(Item(content = "keep", createdAt = 1, updatedAt = 1))
        val dropId = db.itemDao().insert(Item(content = "drop", createdAt = 2, updatedAt = 2))
        val tagId = db.tagDao().insert(Tag(namespace = "n", value = "v", normalizedValue = "v"))
        db.itemTagDao().insert(ItemTag(itemId = keepId, tagId = tagId))
        db.itemTagDao().insert(ItemTag(itemId = dropId, tagId = tagId))

        val removed = db.itemDao().deleteByIds(listOf(dropId))

        assertEquals("应删掉一条", 1, removed)
        assertEquals("被删记录的关联行必须被级联清掉", 0, db.itemTagDao().tagCountOfItem(dropId))
        assertEquals("没被点名的记录不受影响", 1, db.itemTagDao().tagCountOfItem(keepId))
        assertNotNull("标签本身不该被误删", db.tagDao().findById(tagId))
    }

    @Test
    fun deletingTagCascadesToItemTag() = runBlocking {
        val itemId = db.itemDao().insert(Item(content = "x", createdAt = 1, updatedAt = 1))
        val tagId = db.tagDao().insert(Tag(namespace = "n", value = "v", normalizedValue = "v"))
        db.itemTagDao().insert(ItemTag(itemId = itemId, tagId = tagId))

        db.tagDao().delete(db.tagDao().findById(tagId)!!)

        assertEquals("删标签后关联行必须被级联清掉", 0, db.itemTagDao().tagCountOfItem(itemId))
        // 记录本身不该被误删
        assertNotNull("记录不应随标签一起消失", db.itemDao().findById(itemId))
    }

    @Test
    fun noteFieldDefaultsToNull() = runBlocking {
        val id = db.itemDao().insert(Item(content = "c", createdAt = 1, updatedAt = 1))
        assertNull("note 是预留字段，不填时应为 null", db.itemDao().findById(id)!!.note)
    }
}
