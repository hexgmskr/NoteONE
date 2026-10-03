package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.export.ExportJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出 JSON（spec 6.1 / 6.2）的 JVM 单测。
 *
 * 输出是确定性的，所以这里用**全文精确比对**：字段顺序、缩进、转义形态
 * 全都锁死。用户在电脑上用什么工具解析这份文件都依赖这些。
 */
class ExportJsonTest {

    @Test
    fun `builds the spec format for a full item`() {
        val items = listOf(
            testItem(
                id = 3,
                content = "https://example.com/x",
                createdAt = 1758000000000,
                tags = arrayOf("长度" to "长视频", "主题" to "萌宠"),
            ),
        )

        val expected = """
            {
              "schema_version": 1,
              "exported_at": "2026-10-01T23:30:00+08:00",
              "items": [
                {
                  "id": 3,
                  "content": "https://example.com/x",
                  "note": null,
                  "created_at": 1758000000000,
                  "tags": [
                    { "namespace": "主题", "value": "萌宠" },
                    { "namespace": "长度", "value": "长视频" }
                  ]
                }
              ]
            }
        """.trimIndent() + "\n"

        assertEquals(expected, ExportJson.build(items, TEST_EXPORTED_AT))
    }

    @Test
    fun `item with note and no tags`() {
        val items = listOf(
            testItem(id = 4, content = "一段纯文本", createdAt = 1, note = "给自己留的备注"),
        )

        val json = ExportJson.build(items, TEST_EXPORTED_AT)

        assertTrue(json.contains("\"note\": \"给自己留的备注\""))
        assertTrue(json.contains("\"tags\": []"))
    }

    @Test
    fun `empty database exports an empty items array`() {
        val json = ExportJson.build(emptyList(), TEST_EXPORTED_AT)

        assertTrue(json.contains("\"items\": []"))
        assertFalse("空表不该出现 items 的数组体", json.contains("\"items\": [\n"))
    }

    @Test
    fun `tags come out in display order regardless of input order`() {
        // 故意乱序传入：长度/短视频、主题/萌宠、长度/长视频
        val items = listOf(
            testItem(
                id = 1,
                content = "x",
                createdAt = 0,
                tags = arrayOf("长度" to "短视频", "主题" to "萌宠", "长度" to "长视频"),
            ),
        )

        val json = ExportJson.build(items, TEST_EXPORTED_AT)

        // 先维度（主题 < 长度），再值（Unicode 码点：短 U+77ED < 长 U+957F）
        val order = listOf("主题", "短视频", "长视频")
            .map { json.indexOf(it) }
        assertEquals("标签必须按「先维度再值」出现", order.sorted(), order)
    }

    @Test
    fun `internal normalizedValue never appears in the export`() {
        val items = listOf(testItem(1, "x", 0, tags = arrayOf("来源" to "MV")))

        val json = ExportJson.build(items, TEST_EXPORTED_AT)

        assertTrue("展示值要在", json.contains("\"value\": \"MV\""))
        assertFalse("内部归一化值不得出现", json.contains("\"mv\""))
        assertFalse(json.contains("normalizedValue"))
    }

    @Test
    fun `escapes quotes backslashes and control characters`() {
        val content = "quote\" back\\slash\nline\ttab\u0001ctrl \b\u000C"

        val json = ExportJson.build(listOf(testItem(1, content, 0)), TEST_EXPORTED_AT)

        assertTrue(
            json.contains("\"quote\\\" back\\\\slash\\nline\\ttab\\u0001ctrl \\b\\f\""),
        )
        assertFalse("原始换行不得出现在字符串值里", json.contains("line\ttab"))
    }

    @Test
    fun `chinese and emoji are written as plain utf8 text`() {
        val items = listOf(testItem(1, "宠物🐱中文", 0))

        val json = ExportJson.build(items, TEST_EXPORTED_AT)

        assertTrue("给人看的备份，中文不转义", json.contains("\"宠物🐱中文\""))
        // 编成 UTF-8 再读回来必须一字不差（没有代理项被编码器替换掉）
        assertEquals(json, json.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test
    fun `lone surrogate is replaced instead of silently corrupting the utf8`() {
        val content = "bad\uD800end"

        val json = ExportJson.build(listOf(testItem(1, content, 0)), TEST_EXPORTED_AT)

        assertTrue("孤立代理项写成替换符的转义", json.contains("bad\\ufffdend"))
        assertEquals(
            "输出必须能无损地走一遍 UTF-8 编码",
            json,
            json.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `output is deterministic`() {
        val items = listOf(testItem(1, "a", 2, tags = arrayOf("n" to "v")))

        assertEquals(
            ExportJson.build(items, TEST_EXPORTED_AT),
            ExportJson.build(items, TEST_EXPORTED_AT),
        )
    }
}
