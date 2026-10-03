package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.data.tag.TagDraft
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标签展示顺序自定义的 JVM 单测。
 *
 * 未知数是「自定义之后，未自定义的项落哪儿」——
 * 规则是排在已定义项之后，保证新标签不会突然插到最前面。
 */
class TagOrderingTest {

    private fun tag(namespace: String, value: String) = Tag(
        namespace = namespace,
        value = value,
        normalizedValue = value.trim().lowercase(),
    )

    private val pool = listOf(
        tag("n1", "a"),
        tag("n1", "b"),
        tag("n2", "c"),
        tag("n3", "d"),
    )

    // ---- 空顺序：退回 Unicode 序 ----

    @Test
    fun `empty ordering falls back to unicode order`() {
        val got = pool.sortedWith(TagOrdering().comparator)
        assertEquals(listOf("a", "b", "c", "d"), got.map { it.value })
    }

    // ---- 维度间顺序 ----

    @Test
    fun `namespace order overrides unicode order`() {
        val ordering = TagOrdering(namespaceOrder = listOf("n3", "n1", "n2"))
        val got = pool.sortedWith(ordering.comparator)
        assertEquals(listOf("d", "a", "b", "c"), got.map { it.value })
    }

    @Test
    fun `undefined namespaces rank after defined ones`() {
        // 只定义了 n2，n1/n3 未定义 → n2 打头，n1/n3 按 Unicode 跟在后面
        val ordering = TagOrdering(namespaceOrder = listOf("n2"))
        val got = pool.sortedWith(ordering.comparator)
        assertEquals(listOf("c", "a", "b", "d"), got.map { it.value })
    }

    // ---- 维度内顺序 ----

    @Test
    fun `value order overrides unicode order inside a namespace`() {
        val ordering = TagOrdering(valueOrder = mapOf("n1" to listOf("b", "a")))
        val got = pool.sortedWith(ordering.comparator)
        assertEquals(listOf("b", "a", "c", "d"), got.map { it.value })
    }

    @Test
    fun `undefined values rank after defined ones inside a namespace`() {
        val pool2 = listOf(tag("n1", "a"), tag("n1", "b"), tag("n1", "c"))
        val ordering = TagOrdering(valueOrder = mapOf("n1" to listOf("c")))
        val got = pool2.sortedWith(ordering.comparator)
        assertEquals(listOf("c", "a", "b"), got.map { it.value })
    }

    // ---- 两层同时 ----

    @Test
    fun `both layers combine`() {
        val pool2 = listOf(
            tag("n1", "a"),
            tag("n1", "b"),
            tag("n2", "c"),
        )
        val ordering = TagOrdering(
            namespaceOrder = listOf("n2", "n1"),
            valueOrder = mapOf("n1" to listOf("b", "a")),
        )
        val got = pool2.sortedWith(ordering.comparator)
        assertEquals(listOf("c", "b", "a"), got.map { it.value })
    }

    // ---- 归一化 key ----

    @Test
    fun `value order matches by normalized value not display value`() {
        val pool2 = listOf(tag("n1", "MV"), tag("n1", "abc"))
        // 存的是归一化值
        val ordering = TagOrdering(valueOrder = mapOf("n1" to listOf("mv", "abc")))
        val got = pool2.sortedWith(ordering.comparator)
        assertEquals(listOf("MV", "abc"), got.map { it.value })
    }

    // ---- 草稿同口径 ----

    @Test
    fun `draft comparator follows the same rules`() {
        val drafts = listOf(
            TagDraft("n1", "a"),
            TagDraft("n1", "b"),
            TagDraft("n2", "c"),
        )
        val ordering = TagOrdering(
            namespaceOrder = listOf("n2", "n1"),
            valueOrder = mapOf("n1" to listOf("b", "a")),
        )
        val got = drafts.sortedWith(ordering.draftComparator)
        assertEquals(listOf("c", "b", "a"), got.map { it.value })
    }

    // ---- 维度名排序 ----

    @Test
    fun `sortNamespaces honors custom order`() {
        val ordering = TagOrdering(namespaceOrder = listOf("n3", "n1"))
        assertEquals(listOf("n3", "n1", "n2"), ordering.sortNamespaces(listOf("n1", "n2", "n3")))
    }

    // ---- 替换 ----

    @Test
    fun `withNamespaceOrder replaces whole list`() {
        val ordering = TagOrdering(namespaceOrder = listOf("n1"))
            .withNamespaceOrder(listOf("n3", "n2", "n1"))
        assertEquals(listOf("n3", "n2", "n1"), ordering.namespaceOrder)
    }

    @Test
    fun `withValueOrder replaces one namespace only`() {
        val ordering = TagOrdering(valueOrder = mapOf("n1" to listOf("a")))
            .withValueOrder("n2", listOf("c", "b"))
        assertEquals(mapOf("n1" to listOf("a"), "n2" to listOf("c", "b")), ordering.valueOrder)
    }
}
