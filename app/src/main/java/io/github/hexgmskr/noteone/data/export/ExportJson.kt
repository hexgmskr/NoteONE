package io.github.hexgmskr.noteone.data.export

import io.github.hexgmskr.noteone.data.entity.ItemWithTags
import io.github.hexgmskr.noteone.data.entity.Tag
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 导出 JSON 的构造器（spec 6.1 / 6.2）。
 *
 * **手写序列化，不引 JSON 库**：
 *  - org.json 是 Android 内置的，但在 JVM 单测里是空壳（一调用就抛），
 *    而这个格式必须能在电脑上测；
 *  - kotlinx-serialization 本项目刻意没进主 classpath（依赖纪律，见 CLAUDE.md 债务②）。
 * 输出是**确定性**的（字段顺序、缩进全固定），于是单测可以直接对全文断言，
 * 用户在文本编辑器里看也顺眼。
 *
 * 内容规则（spec 6.2）：
 *  - 只导出核心数据表的东西，**不含**内部字段 `normalizedValue`、不含展示层状态；
 *  - `schema_version` 永远在第一位，格式变化时递增版本号；
 *  - 标签用 [ItemWithTags.sortedTags]（先维度再值），顺序稳定可复现。
 */
object ExportJson {

    /** 导出格式版本。**改格式必递增**，解析脚本按它分支（spec 6.2）。 */
    const val SCHEMA_VERSION = 1

    private val TIMESTAMP_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun build(items: List<ItemWithTags>, exportedAt: OffsetDateTime): String {
        val sb = StringBuilder(256 + items.size * 256)
        sb.append("{\n")
        sb.append("  \"schema_version\": ").append(SCHEMA_VERSION).append(",\n")
        sb.append("  \"exported_at\": ")
        // 截到秒：ISO 格式会按需带小数，截掉让时间戳干净且确定（测试可精确断言）
        sb.appendQuoted(exportedAt.truncatedTo(ChronoUnit.SECONDS).format(TIMESTAMP_FORMAT))
        sb.append(",\n")
        sb.appendItems(items)
        sb.append("}\n")
        return sb.toString()
    }

    private fun StringBuilder.appendItems(items: List<ItemWithTags>) {
        if (items.isEmpty()) {
            append("  \"items\": []\n")
            return
        }
        append("  \"items\": [\n")
        items.forEachIndexed { index, entry ->
            appendItem(entry)
            append(if (index == items.lastIndex) "\n" else ",\n")
        }
        append("  ]\n")
    }

    private fun StringBuilder.appendItem(entry: ItemWithTags) {
        val item = entry.item
        append("    {\n")
        append("      \"id\": ").append(item.id).append(",\n")
        append("      \"content\": ").appendQuoted(item.content).append(",\n")
        append("      \"note\": ")
        if (item.note == null) append("null") else appendQuoted(item.note)
        append(",\n")
        append("      \"created_at\": ").append(item.createdAt).append(",\n")
        appendTags(entry.sortedTags)
        append("    }")
    }

    private fun StringBuilder.appendTags(tags: List<Tag>) {
        if (tags.isEmpty()) {
            append("      \"tags\": []\n")
            return
        }
        append("      \"tags\": [\n")
        tags.forEachIndexed { index, tag ->
            append("        { \"namespace\": ").appendQuoted(tag.namespace)
            append(", \"value\": ").appendQuoted(tag.value).append(" }")
            append(if (index == tags.lastIndex) "\n" else ",\n")
        }
        append("      ]\n")
    }

    /**
     * 按 RFC 8259 写一个 JSON 字符串字面量（含两侧引号）。
     *
     * 转义的是"必须转义的"：引号、反斜杠、控制字符（含 \n \r \t \b \f 的短写）。
     * **中文与 emoji 原样写出**（文件本来就是 UTF-8）——全转成 \uXXXX 虽然合法，
     * 但用户拿文本编辑器打开时就看不懂了，这个导出是给人看的备份。
     *
     * 孤立的代理项（Java 字符串理论和实践上都可能存在）写成 `�`：
     * 直接输出会让 UTF-8 编码阶段悄悄变成 `?`，宁可显式标记成"这个字符坏了"。
     */
    private fun StringBuilder.appendQuoted(text: String): StringBuilder {
        append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    // 合法的一对代理项 = 一个 BMP 外字符（如 emoji）：原样写出
                    append(c).append(text[i + 1])
                    i++
                }
                Character.isHighSurrogate(c) || Character.isLowSurrogate(c) -> append("\\ufffd")
                else -> append(c)
            }
            i++
        }
        append('"')
        return this
    }
}
