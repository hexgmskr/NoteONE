package io.github.hexgmskr.noteone.data.export

/**
 * 一段 JSON 值。数字保留原始文本（[JsonValue.Num.raw]）——本项目只需要把它
 * 读成 Long，确切的浮点语义留给将来真需要的时候再说。
 */
internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/** 解析失败。[message] 带行列号，坏文件能让用户自己看出坏在哪。 */
internal class JsonParseException(message: String) : Exception(message)

/**
 * 极小的递归下降 JSON 解析器（RFC 8259 的语法）。
 *
 * 为什么手写而不是引库：
 *  - org.json 是 Android 内置的，但在 JVM 单测里是空壳（一调用就抛）——
 *    导入路径是整个恢复故事的关键环，必须能在电脑上测；
 *  - kotlinx-serialization 刻意不进主 classpath（CLAUDE.md 债务②）。
 *
 * **从严解析**：尾逗号、未转义的控制字符、重复的键、结尾多余的字符、超深嵌套，
 * 一律报错。手上是坏文件时，早报错比猜着读强——和信封解析同一个立场，
 * 只是这里报错要带行列号（用户可能真的要去看那个文件）。
 */
internal object MiniJson {

    /** 嵌套深度上限：防一个坏文件用十万层 `[` 把递归栈撑爆。 */
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.expectEnd()
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("嵌套太深（超过 $MAX_DEPTH 层）")
            skipWhitespace()
            if (pos >= text.length) fail("内容意外结束")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("意外的字符 '$c'")
            }
        }

        fun expectEnd() {
            skipWhitespace()
            if (pos < text.length) fail("内容结尾有多余的东西")
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            pos++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象的键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("键「$key」后面缺少冒号")
                pos++
                val value = parseValue(depth + 1)
                if (fields.put(key, value) != null) fail("字段「$key」重复出现")
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("对象里缺少逗号或右花括号")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            pos++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("数组里缺少逗号或右方括号")
                }
            }
        }

        private fun parseString(): String {
            pos++ // 开引号
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("字符串没有闭合")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }

                    '\\' -> sb.append(parseEscape())

                    else -> {
                        if (c < ' ') {
                            fail("字符串里有未转义的控制字符（U+${hex4(c.code)}）")
                        }
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseEscape(): Char {
            pos++ // '\'
            if (pos >= text.length) fail("转义符后面没有字符")
            val e = text[pos]
            pos++
            return when (e) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) fail("\\u 转义不完整")
                    val hex = text.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制：$hex")
                    pos += 4
                    code.toChar()
                }

                else -> fail("不认识的转义：\\$e")
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            when {
                peek() == '0' -> pos++
                peek() in '1'..'9' -> while (peek() in '0'..'9') pos++
                else -> fail("数字格式不对")
            }
            if (peek() == '.') {
                pos++
                if (peek() !in '0'..'9') fail("小数点后面要有数字")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') fail("指数后面要有数字")
                while (peek() in '0'..'9') pos++
            }
            return JsonValue.Num(text.substring(start, pos))
        }

        private fun <T : JsonValue> literal(word: String, value: T): T {
            if (!text.startsWith(word, pos)) fail("这里应当是 $word")
            pos += word.length
            return value
        }

        /** 当前位置的字符；到结尾返回 NUL（调用处用范围判断，天然为假）。 */
        private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun fail(message: String): Nothing {
            var line = 1
            var lineStart = 0
            for (i in 0 until minOf(pos, text.length)) {
                if (text[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            throw JsonParseException("第 $line 行第 ${pos - lineStart + 1} 列：$message")
        }

        private fun hex4(code: Int): String =
            code.toString(16).uppercase().padStart(4, '0')
    }
}
