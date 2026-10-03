package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderSource
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering

/**
 * 共享测试夹具（2026-10-04 收敛）。
 *
 * 此前 `InMemorySource` 在四个测试文件里逐字抄了四份、假的 TagOrderSource
 * 抄了三份——改一次口径要记得改七处，漏一处就是"测试之间悄悄不一致"。
 */

/** 内存里的副本A/B 密文。真实现是文件（见 KeyBlobStore）。 */
internal class InMemoryKeyBlob : KeyBlobSource {
    var text: String? = null
    override fun read(): String? = text
    override fun write(text: String) { this.text = text }
    override fun clear() { text = null }
}

/** 内存里的展示顺序偏好。真实现是 SharedPreferences（见 TagOrderStore）。 */
internal class InMemoryTagOrder(var ordering: TagOrdering = TagOrdering()) : TagOrderSource {
    override fun load(): TagOrdering = ordering
    override fun save(ordering: TagOrdering) { this.ordering = ordering }
}
