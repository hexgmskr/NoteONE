package io.github.hexgmskr.noteone.data.export

import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope

/** 从导出文件解析出来的一条记录（还没落库）。 */
data class ImportItem(
    val content: String,
    val note: String?,
    val createdAt: Long,
    val tags: List<ImportTag>,
)

/** 导入记录上的一个标签。 */
data class ImportTag(val namespace: String, val value: String)

/** 导入文件被识别成什么形态。 */
enum class ImportFormat {
    /** 明文 JSON（我们的 [ExportJson] 输出，或符合同一 schema 的文件）。 */
    JSON,

    /** 加密信封：还要密码才能往下走。 */
    ENCRYPTED,

    /** 都不是。具体原因问 [ImportParser.unknownReason]。 */
    UNKNOWN,
}

/** 导入文件有问题（识别不了 / 格式不对 / 字段不合规）。message 可直接展示。 */
class ExportFileException(message: String) : Exception(message)

/**
 * 导出文件 → [ImportItem] 的解析层（spec 6.2 的"按 schema_version 分支"在这）。
 *
 * **宽容与严格的边界**（刻意的，与信封解析不同）：
 *  - 严格：`schema_version` 必须存在且认识；每条记录的 `content`（非空字符串）与
 *    `created_at`（整数）必须存在；`tags` 里 `namespace`/`value` 必须是非空字符串；
 *    JSON 本身必须严格执行语法。
 *  - 宽容：**未知字段忽略**（将来格式演进时老版本至少能读出已知部分……
 *    不，真正的演进靠 schema_version 拦——忽略未知字段只是不让手工加过注释字段
 *    的文件整份报废）；`note`/`tags` 缺省视为 null/空；`id` 不读（它是源设备的
 *    自增主键，导入后由本机重新分配，见 [Importer] 的去重口径）。
 *
 * 所有错误消息都尽量指向"第几条记录/第几个标签"，用户手上是坏文件时能自己定位。
 */
object ImportParser {

    /** 看这个文件是什么形态。识别不了时具体原因用 [unknownReason]。 */
    fun detect(text: String): ImportFormat {
        val head = text.trimStart()
        return when {
            head.startsWith(KeyEnvelope.MAGIC_EXPORT) -> ImportFormat.ENCRYPTED
            head.startsWith("{") -> ImportFormat.JSON
            else -> ImportFormat.UNKNOWN
        }
    }

    /** [detect] 返回 UNKNOWN 时，给一句能帮到用户的原因。 */
    fun unknownReason(text: String): String {
        val head = text.trimStart()
        return if (head.startsWith(KeyEnvelope.MAGIC_RECOVERY)) {
            "这是「恢复密钥」文件，不是记录导出——密钥请去「恢复密钥」页面导入"
        } else {
            "这不像是 NoteONE 的导出文件：既不是 JSON，也没有导出文件的标识行"
        }
    }

    /** 解析明文 JSON。不合规一律抛 [ExportFileException]，一条都不放过。 */
    fun parseItems(json: String): List<ImportItem> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: JsonParseException) {
            throw ExportFileException("这份 JSON 读不动（${e.message}）")
        }

        val obj = root as? JsonValue.Obj
            ?: throw ExportFileException("导出文件的根节点应当是一个对象")

        val versionValue = obj.fields["schema_version"]
            ?: throw ExportFileException("缺少 schema_version 字段——这不像是 NoteONE 的导出文件")
        val version = (versionValue as? JsonValue.Num)?.raw?.toIntOrNull()
            ?: throw ExportFileException("schema_version 不是整数")
        if (version != ExportJson.SCHEMA_VERSION) {
            throw ExportFileException(
                "这份文件的格式版本是 $version，本 App 只认识 ${ExportJson.SCHEMA_VERSION}（可能需要更新 App）",
            )
        }

        val itemsValue = obj.fields["items"]
            ?: throw ExportFileException("缺少 items 字段")
        val items = itemsValue as? JsonValue.Arr
            ?: throw ExportFileException("items 应当是一个数组")

        return items.items.mapIndexed { index, value -> parseItem(index, value) }
    }

    private fun parseItem(index: Int, value: JsonValue): ImportItem {
        val label = "第 ${index + 1} 条记录"
        val obj = value as? JsonValue.Obj
            ?: throw ExportFileException("$label 不是对象")

        val content = (obj.fields["content"] as? JsonValue.Str)?.value
            ?: throw ExportFileException("$label 缺少 content（应为字符串）")
        if (content.isBlank()) throw ExportFileException("$label 的 content 是空的")

        val createdAt = (obj.fields["created_at"] as? JsonValue.Num)?.raw?.toLongOrNull()
            ?: throw ExportFileException("$label 的 created_at 缺失或不是整数")

        val note = when (val raw = obj.fields["note"]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> raw.value
            else -> throw ExportFileException("$label 的 note 不是字符串或 null")
        }

        val tags = when (val raw = obj.fields["tags"]) {
            null -> emptyList()
            is JsonValue.Arr -> raw.items.mapIndexed { i, tagValue -> parseTag(label, i, tagValue) }
            else -> throw ExportFileException("$label 的 tags 不是数组")
        }

        return ImportItem(content = content, note = note, createdAt = createdAt, tags = tags)
    }

    private fun parseTag(itemLabel: String, index: Int, value: JsonValue): ImportTag {
        val label = "$itemLabel 的第 ${index + 1} 个标签"
        val obj = value as? JsonValue.Obj
            ?: throw ExportFileException("$label 不是对象")
        val namespace = (obj.fields["namespace"] as? JsonValue.Str)?.value
            ?: throw ExportFileException("$label 缺少 namespace（应为字符串）")
        val tagValue = (obj.fields["value"] as? JsonValue.Str)?.value
            ?: throw ExportFileException("$label 缺少 value（应为字符串）")
        if (namespace.isBlank() || tagValue.isBlank()) {
            throw ExportFileException("$label 的维度或值是空的")
        }
        return ImportTag(namespace = namespace, value = tagValue)
    }
}
