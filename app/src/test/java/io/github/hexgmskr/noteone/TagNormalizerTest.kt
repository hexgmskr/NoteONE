package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.tag.TagNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 步骤 3：归一化规则的 JVM 单测。
 *
 * 纯函数不依赖 Android，走 JVM 单测即可——比仪器测试快一个数量级，
 * 反馈信号也更干净（失败只可能是规则写错，不可能是设备/数据库问题）。
 */
class TagNormalizerTest {

    @Test
    fun lowercasesAndTrims() {
        assertEquals("mv", TagNormalizer.normalize("  MV  "))
        assertEquals("mv", TagNormalizer.normalize("mv"))
        assertEquals("mv", TagNormalizer.normalize("Mv"))
    }

    @Test
    fun preservesInternalWhitespace() {
        // spec 明文：只去首尾空格，不折叠内部。近似重复交给手动合并。
        assertEquals("多 镜头", TagNormalizer.normalize(" 多 镜头 "))
        assertTrue(
            "内部空格不同的两个值应被当作不同标签",
            TagNormalizer.normalize("多  镜头") != TagNormalizer.normalize("多 镜头"),
        )
    }

    @Test
    fun chineseIsUnchangedByLowercase() {
        assertEquals("萌宠", TagNormalizer.normalize("萌宠"))
        assertEquals("长视频", TagNormalizer.normalize("  长视频  "))
    }

    @Test
    fun blankInputThrows() {
        for (bad in listOf("", "   ", "\t", "\n")) {
            try {
                TagNormalizer.normalize(bad)
                fail("归一化后为空的输入必须抛异常，实际接受了：'$bad'")
            } catch (e: IllegalArgumentException) {
                // 预期
            }
        }
    }

    @Test
    fun isValidRejectsBlankWithoutThrowing() {
        assertTrue(TagNormalizer.isValid("MV"))
        assertTrue(TagNormalizer.isValid(" MV "))
        assertFalse(TagNormalizer.isValid(""))
        assertFalse(TagNormalizer.isValid("   "))
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = TagNormalizer.normalize("  Hello World  ")
        assertEquals(once, TagNormalizer.normalize(once))
    }
}
