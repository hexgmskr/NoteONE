package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.export.JsonParseException
import io.github.hexgmskr.noteone.data.export.JsonValue
import io.github.hexgmskr.noteone.data.export.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写 JSON 解析器的 JVM 单测。
 *
 * 导入路径靠它读回用户的备份文件——一份坏文件必须**明确报错**（带行列号），
 * 不许猜着读。这里逐条钉死语法从严的地方。
 */
class MiniJsonTest {

    @Test
    fun `parses scalar kinds`() {
        assertEquals(JsonValue.Num("1"), MiniJson.parse("1"))
        assertEquals(JsonValue.Num("-2.5e3"), MiniJson.parse("-2.5e3"))
        assertEquals(JsonValue.Str("abc"), MiniJson.parse("\"abc\""))
        assertEquals(JsonValue.Bool(true), MiniJson.parse("true"))
        assertEquals(JsonValue.Bool(false), MiniJson.parse("false"))
        assertEquals(JsonValue.Null, MiniJson.parse("null"))
    }

    @Test
    fun `parses nested structures with any whitespace`() {
        val text = """
            {
              "a": [1, 2, { "b": "c" }],
              "d": {}
            }
        """.trimIndent()

        val root = MiniJson.parse(text) as JsonValue.Obj
        val a = root.fields["a"] as JsonValue.Arr
        assertEquals(3, a.items.size)
        assertEquals(JsonValue.Obj(emptyMap()), root.fields["d"])
    }

    @Test
    fun `unescapes every escape sequence`() {
        val parsed = MiniJson.parse(
            "\"quote:\\\" back:\\\\ slash:\\/ \\b\\f\\n\\r\\t 中文:\\u4E2D\"",
        )

        assertEquals("quote:\" back:\\ slash:/ \b\u000C\n\r\t 中文:中", (parsed as JsonValue.Str).value)
    }

    @Test
    fun `decodes surrogate pair escapes into one character`() {
        val parsed = MiniJson.parse("\"\\uD83D\\uDE00\"")

        assertEquals("😀", (parsed as JsonValue.Str).value)
    }

    @Test
    fun `numbers keep their raw text`() {
        // schema 层再决定怎么解读；这里保证不丢精度、不擅自转 Double
        assertEquals(JsonValue.Num("1790865111564"), MiniJson.parse("1790865111564"))
    }

    // ---- 从严：坏文件必须报错，且报错带行列号 ----

    @Test
    fun `rejects trailing garbage`() {
        assertThrows(JsonParseException::class.java) { MiniJson.parse("{} {}") }
    }

    @Test
    fun `rejects unterminated string`() {
        assertThrows(JsonParseException::class.java) { MiniJson.parse("\"没有闭合") }
    }

    @Test
    fun `rejects unescaped control character in string`() {
        assertThrows(JsonParseException::class.java) { MiniJson.parse("\"坏\u0001字符\"") }
    }

    @Test
    fun `rejects unknown escape`() {
        assertThrows(JsonParseException::class.java) { MiniJson.parse("\"\\x\"") }
    }

    @Test
    fun `rejects duplicate keys`() {
        assertThrows(JsonParseException::class.java) { MiniJson.parse("""{"a":1,"a":2}""") }
    }

    @Test
    fun `rejects trailing commas`() {
        assertThrows(JsonParseException::class.java) { MiniJson.parse("[1,]") }
        assertThrows(JsonParseException::class.java) { MiniJson.parse("""{"a":1,}""") }
    }

    @Test
    fun `rejects absurd nesting`() {
        val deep = "[".repeat(100) + "]".repeat(100)

        assertThrows(JsonParseException::class.java) { MiniJson.parse(deep) }
    }

    @Test
    fun `error message carries line and column`() {
        val thrown = assertThrows(JsonParseException::class.java) {
            MiniJson.parse("{\n  \"a\": ,\n}")
        }

        assertTrue("要能指到出错的行列", thrown.message!!.contains("第 2 行"))
    }
}
