package io.github.hexgmskr.noteone.ui.tagorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 展示顺序的读写口。抽成接口是为了 JVM 单测能塞假实现——
 * [TagOrderStore] 要真 Context 开 SharedPreferences，单测跑不了。
 */
interface TagOrderSource {
    fun load(): TagOrdering
    fun save(ordering: TagOrdering)
}

/**
 * [TagOrdering] 的持久化。
 *
 * **放 SharedPreferences，不放 Room**——CLAUDE.md / spec 3.2 明确
 * 「排序偏好归展示层管理，不进核心表」。这样表结构不动、不用写迁移、
 * 导出 JSON 也照旧只含核心数据（不会把展示偏好混进用户数据）。
 *
 * 代价：卸载重装会丢。排序是低频调整，可接受；真要留得久，
 * 将来再考虑随导出一起走。
 *
 * 序列化用 Android 自带的 org.json，不引 kotlinx-serialization——
 * 主 classpath 里没有它，为一个偏好设置开一个依赖面不划算。
 * 列表用 JSONArray 存，不拼分隔符，值里带什么字符都不怕。
 *
 * 读写都是「整份替换」：调用方拿 [load] 出来的整个 [TagOrdering] 改完再 [save]。
 */
class TagOrderStore(
    context: Context,
    /**
     * 偏好文件名。默认生产名；**仪器测试传隔离名**——测这个 store 要真写
     * SharedPreferences，沿用生产名会把用户的自定义顺序清掉。这是与测试库名
     * 同一条红线（2026-10-04 审计）。
     */
    prefsName: String = PREFS_NAME,
) : TagOrderSource {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** 读出当前顺序。没存过就是空的 [TagOrdering]，等价于「全按 Unicode 排」。 */
    override fun load(): TagOrdering {
        val raw = prefs.getString(KEY_ORDERING, null) ?: return TagOrdering()
        return runCatching { parse(raw) }.getOrDefault(TagOrdering())
    }

    /**
     * 整份写回。写失败静默——这是展示偏好，写不进去不该打断用户正在做的事。
     */
    override fun save(ordering: TagOrdering) {
        runCatching {
            prefs.edit().putString(KEY_ORDERING, serialize(ordering)).apply()
        }
    }

    private fun serialize(ordering: TagOrdering): String {
        val values = JSONObject()
        for ((namespace, list) in ordering.valueOrder) {
            values.put(namespace, JSONArray(list))
        }
        return JSONObject()
            .put(KEY_NAMESPACES, JSONArray(ordering.namespaceOrder))
            .put(KEY_VALUES, values)
            .toString()
    }

    private fun parse(raw: String): TagOrdering {
        val json = JSONObject(raw)
        val namespaces = json.optJSONArray(KEY_NAMESPACES).toList()
        val values = mutableMapOf<String, List<String>>()
        val valuesJson = json.optJSONObject(KEY_VALUES)
        if (valuesJson != null) {
            for (key in valuesJson.keys()) {
                values[key] = valuesJson.optJSONArray(key).toList()
            }
        }
        return TagOrdering(namespaceOrder = namespaces, valueOrder = values)
    }

    private fun JSONArray?.toList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }
    }

    private companion object {
        const val PREFS_NAME = "tag_display_order"
        const val KEY_ORDERING = "ordering"
        const val KEY_NAMESPACES = "namespaces"
        const val KEY_VALUES = "values"
    }
}
