package io.github.hexgmskr.noteone.data.tag

import io.github.hexgmskr.noteone.data.entity.Tag

/**
 * 保存前的标签草稿：用户敲定的「维度 + 值」，还没落库。
 *
 * **不做归一化/去重**——那套语义只属于 [TagRepository.findOrCreate]。
 * 草稿若也维护一份去重口径，就会出现两处规则、两个判官。
 * 这里只存原样输入，落库时统一交给 TagRepository 解析成真正的 [Tag]。
 */
data class TagDraft(val namespace: String, val value: String) {

    /**
     * 归一化值；[value] 全是空白时为 null。
     *
     * 走 [TagNormalizer]，不自己拼小写——否则就又多一个归一化口径。
     */
    fun normalizedValueOrNull(): String? =
        if (TagNormalizer.isValid(value)) TagNormalizer.normalize(value) else null

    /** 是否与另一个草稿指同一个标签。维度 trim 后比，值走归一化。 */
    fun sameAs(other: TagDraft): Boolean =
        namespace.trim() == other.namespace.trim() &&
            normalizedValueOrNull() == other.normalizedValueOrNull()

    /** 是否与已落库的 [tag] 指同一个。面板用它判断「这条已被选中」。 */
    fun sameAs(tag: Tag): Boolean =
        namespace.trim() == tag.namespace && normalizedValueOrNull() == tag.normalizedValue
}
