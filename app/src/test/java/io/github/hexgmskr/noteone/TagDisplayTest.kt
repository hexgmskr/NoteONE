package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.entity.Tag
import io.github.hexgmskr.noteone.ui.tagorder.toCompactDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标签紧凑展示的 JVM 单测。
 *
 * 未知数是「维度名藏起来之后，维度归属还看得出来吗」——
 * 靠分组间距 + 稳定顺序暗示，不靠文字标注。
 */
class TagDisplayTest {

    private fun tag(namespace: String, value: String) = Tag(
        namespace = namespace,
        value = value,
        normalizedValue = value.trim().lowercase(),
    )

    @Test
    fun `single namespace shows values adjacent without namespace name`() {
        val tags = listOf(tag("主题", "a"), tag("主题", "b"), tag("主题", "c"))
        assertEquals("#a#b#c", tags.toCompactDisplay())
    }

    @Test
    fun `multiple namespaces separated by gap`() {
        val tags = listOf(tag("n1", "a"), tag("n2", "b"), tag("n3", "c"))
        assertEquals("#a  #b  #c", tags.toCompactDisplay())
    }

    @Test
    fun `values inside one namespace stay together`() {
        val tags = listOf(
            tag("n1", "a"),
            tag("n2", "b1"),
            tag("n2", "b2"),
            tag("n3", "c"),
        )
        assertEquals("#a  #b1#b2  #c", tags.toCompactDisplay())
    }

    @Test
    fun `empty list shows nothing`() {
        assertEquals("", emptyList<Tag>().toCompactDisplay())
    }

    @Test
    fun `unsorted input is sorted before display`() {
        val tags = listOf(
            tag("n2", "b"),
            tag("n1", "a2"),
            tag("n1", "a1"),
        )
        assertEquals("#a1#a2  #b", tags.toCompactDisplay())
    }

    /**
     * 中文维度名/值按 Unicode 码点排，不是拼音序。
     *
     * 这条是**行为契约**，不是凑数：用户举例写的是「#萌宠#灵动#自然」，
     * 但萌(U+840C) > 自(U+81EA) > 灵(U+7075)，实际渲染是 #灵动#自然#萌宠。
     * 钉在这里免得日后当成 bug 反复查。
     */
    @Test
    fun `chinese sorts by unicode code point not pinyin`() {
        val tags = listOf(tag("主题", "萌宠"), tag("主题", "灵动"), tag("主题", "自然"))
        assertEquals("#灵动#自然#萌宠", tags.toCompactDisplay())
    }

    /** 中文维度名同理：主(U+4E3B) < 拍(U+62CD) < 长(U+957F)。 */
    @Test
    fun `chinese namespaces sort by unicode code point`() {
        val tags = listOf(tag("长度", "a"), tag("拍摄手法", "b"), tag("主题", "c"))
        assertEquals("#c  #b  #a", tags.toCompactDisplay())
    }
}
