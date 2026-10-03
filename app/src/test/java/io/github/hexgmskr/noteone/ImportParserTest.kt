package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.export.ExportFileException
import io.github.hexgmskr.noteone.data.export.ExportJson
import io.github.hexgmskr.noteone.data.export.ImportFormat
import io.github.hexgmskr.noteone.data.export.ImportItem
import io.github.hexgmskr.noteone.data.export.ImportParser
import io.github.hexgmskr.noteone.data.export.ImportTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出文件 → [ImportItem] 的解析层单测。
 *
 * 两个方向都要钉：
 *  - **往返**：我们自己导出的东西必须能原样读回来（与 ExportJsonTest 互为镜像）；
 *  - **从严**：坏文件、缺字段、类型不对，一律给带"第几条"的明确报错。
 */
class ImportParserTest {

    // ---- 往返：导出的 → 读回来 ----

    @Test
    fun `parses our own export back into items`() {
        val exported = ExportJson.build(
            listOf(
                testItem(
                    id = 3,
                    content = "https://example.com/x",
                    createdAt = 1758000000000,
                    tags = arrayOf("长度" to "长视频", "主题" to "萌宠"),
                ),
                testItem(id = 2, content = "只有文本", createdAt = 1758000000001, note = "备注"),
            ),
            TEST_EXPORTED_AT,
        )

        val items = ImportParser.parseItems(exported)

        assertEquals(2, items.size)
        assertEquals(
            ImportItem(
                content = "https://example.com/x",
                note = null,
                createdAt = 1758000000000,
                // 导出侧按「先维度再值」排序，读回来保持同一顺序
                tags = listOf(ImportTag("主题", "萌宠"), ImportTag("长度", "长视频")),
            ),
            items[0],
        )
        assertEquals(ImportItem("只有文本", "备注", 1758000000001, emptyList()), items[1])
    }

    // ---- 形态识别 ----

    @Test
    fun `detects plain json encrypted envelope and unknown`() {
        assertEquals(ImportFormat.JSON, ImportParser.detect("  {\"a\":1}"))
        assertEquals(
            ImportFormat.ENCRYPTED,
            ImportParser.detect(KeyEnvelope.MAGIC_EXPORT + "\nversion=1\n"),
        )
        assertEquals(ImportFormat.UNKNOWN, ImportParser.detect("随便什么文本"))
    }

    @Test
    fun `a recovery key file is named as such instead of a generic error`() {
        val text = KeyEnvelope.MAGIC_RECOVERY + "\nversion=1\n"

        assertEquals(ImportFormat.UNKNOWN, ImportParser.detect(text))
        assertTrue(ImportParser.unknownReason(text).contains("恢复密钥"))
    }

    // ---- 从严：坏文件 ----

    private fun parseFails(text: String): String =
        assertThrows(ExportFileException::class.java) { ImportParser.parseItems(text) }
            .message!!

    @Test
    fun `rejects json that is not readable`() {
        assertTrue(parseFails("[1,]").contains("行"))
    }

    @Test
    fun `rejects missing or unknown schema version`() {
        assertTrue(parseFails("""{"items":[]}""").contains("schema_version"))
        assertTrue(parseFails("""{"schema_version":2,"items":[]}""").contains("版本"))
    }

    @Test
    fun `rejects malformed items`() {
        assertTrue(parseFails("""{"schema_version":1,"items":{}}""").contains("数组"))
        assertTrue(parseFails("""{"schema_version":1,"items":[42]}""").contains("第 1 条"))
        assertTrue(
            parseFails("""{"schema_version":1,"items":[{"content":"",  "created_at":1}]}""")
                .contains("第 1 条"),
        )
        assertTrue(
            parseFails("""{"schema_version":1,"items":[{"content":"a"}]}""")
                .contains("created_at"),
        )
        assertTrue(
            parseFails(
                """{"schema_version":1,"items":[{"content":"a","created_at":1,"tags":[{"namespace":"","value":"x"}]}]}""",
            ).contains("第 1 个标签"),
        )
    }

    @Test
    fun `tolerates absent optional fields and ignores unknown ones`() {
        // note/tags 缺省；手工在文件里加过注释字段——都不该让整份文件报废
        val items = ImportParser.parseItems(
            """
            {
              "schema_version": 1,
              "future_top_level": "x",
              "items": [
                { "content": "a", "created_at": 5, "future": [1,2] }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(ImportItem("a", null, 5, emptyList()), items.single())
    }
}
