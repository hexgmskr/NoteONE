package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.tag.TagMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签面板匹配逻辑的 JVM 单测。
 *
 * 唯一未知数是「输入新词时怎么提示已有标签」（spec 7.3）——
 * 归一化口径已由 TagNormalizerTest 覆盖，这里只测匹配与排序。
 */
class TagMatcherTest {

    private fun tag(id: Long = 0, namespace: String, value: String) = Tag(
        id = id,
        namespace = namespace,
        value = value,
        normalizedValue = value.trim().lowercase(),
    )

    private val pool = listOf(
        tag(1, "手法", "MV"),
        tag(2, "手法", "多镜头"),
        tag(3, "主题", "萌宠"),
        tag(4, "长度", "长视频"),
        tag(5, "主题", "萌物"),
    )

    // ---- 空输入 ----

    @Test
    fun `empty query gives nothing`() {
        assertTrue("空输入不该有建议", TagMatcher.suggest("", pool).isEmpty())
    }

    @Test
    fun `whitespace-only query gives nothing`() {
        assertTrue(TagMatcher.suggest("   ", pool).isEmpty())
    }

    // ---- 归一化匹配 ----

    @Test
    fun `query matches case-insensitively`() {
        val got = TagMatcher.suggest("mv", pool)
        assertTrue("「mv」应命中「MV」", got.any { it.value == "MV" })
    }

    @Test
    fun `query with surrounding spaces is normalized`() {
        val got = TagMatcher.suggest("  mv  ", pool)
        assertTrue(got.any { it.value == "MV" })
    }

    @Test
    fun `query matches through normalized value not display value`() {
        // 展示值是「MV」，归一化值是「mv」，按「M」找应命中
        val got = TagMatcher.suggest("m", pool)
        assertTrue(got.any { it.value == "MV" })
    }

    // ---- 排序：完全 → 前缀 → 子串 ----

    @Test
    fun `exact match ranks before prefix match`() {
        val candidates = listOf(
            tag(1, "手法", "mvp"),
            tag(2, "手法", "mv"),
        )
        val got = TagMatcher.suggest("mv", candidates)
        assertEquals("精确匹配应排最前", "mv", got.first().value)
    }

    @Test
    fun `prefix match ranks before substring match`() {
        val candidates = listOf(
            tag(1, "手法", "xmv"),   // 子串
            tag(2, "手法", "mvx"),   // 前缀
        )
        val got = TagMatcher.suggest("mv", candidates)
        assertEquals("前缀应排在子串前", listOf("mvx", "xmv"), got.map { it.value })
    }

    @Test
    fun `same rank falls back to display order`() {
        val candidates = listOf(
            tag(1, "手法", "多镜头"),
            tag(2, "长度", "多机位"),
            tag(3, "主题", "多线程"),
        )
        val got = TagMatcher.suggest("多", candidates)
        // 同为前缀，按 namespace 再 value。中文维度名按 Unicode 码点排：
        // 主(U+4E3B) < 手(U+624B) < 长(U+957F)
        assertEquals(listOf("主题", "手法", "长度"), got.map { it.namespace })
    }

    // ---- 跨维度 ----

    @Test
    fun `same value across namespaces appears once per namespace`() {
        val candidates = listOf(
            tag(1, "手法", "MV"),
            tag(2, "长度", "MV"),
        )
        val got = TagMatcher.suggest("MV", candidates)
        assertEquals("同名词在不同维度下是两条，都该提示", 2, got.size)
    }

    // ---- 上限 ----

    @Test
    fun `suggestions are capped`() {
        val many = (1..20).map { tag(it.toLong(), "主题", "标签$it") }
        val got = TagMatcher.suggest("标签", many)
        assertEquals(TagMatcher.MAX_SUGGESTIONS, got.size)
    }

    @Test
    fun `cap respects the passed limit`() {
        val got = TagMatcher.suggest("标签", (1..20).map { tag(it.toLong(), "主题", "标签$it") }, limit = 3)
        assertEquals(3, got.size)
    }

    // ---- 分组 ----

    @Test
    fun `groups are ordered by namespace then value`() {
        val groups = TagMatcher.groupByNamespace(pool)
        // DISPLAY_ORDER：namespace 先，value 后。中文维度名按 Unicode 码点：
        // 主(U+4E3B) < 手(U+624B) < 长(U+957F)
        assertEquals(listOf("主题", "手法", "长度"), groups.map { it.namespace })
    }

    @Test
    fun `tags inside a group follow display order`() {
        val groups = TagMatcher.groupByNamespace(pool)
        val 主题 = groups.first { it.namespace == "主题" }
        assertEquals(listOf("萌宠", "萌物"), 主题.tags.map { it.value })
    }

    @Test
    fun `empty list groups to empty`() {
        assertTrue(TagMatcher.groupByNamespace(emptyList()).isEmpty())
    }
}
