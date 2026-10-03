package io.github.hexgmskr.noteone.data.tag

/**
 * 标签归一化（docs/spec.md 3.3）。
 *
 * 用户无感：输入「MV」和「mv」被识别为同一个标签，展示用最早创建时的大小写。
 * 这里只产出用于去重匹配的 key，**不修改展示用的原始值**。
 *
 * 规则严格按 spec：转小写 + 去首尾空格。
 * **内部空格不折叠**——「多 镜头」与「多镜头」视为两个标签，这是 spec 明文定义的
 * 边界，不是疏漏。残余的近似重复交给标签管理页的手动合并（spec 7.4）。
 */
object TagNormalizer {

    /**
     * 归一化一个标签值。
     *
     * @throws IllegalArgumentException 归一化后为空（原值全是空白）
     */
    fun normalize(rawValue: String): String {
        val normalized = rawValue.trim().lowercase()
        require(normalized.isNotEmpty()) {
            "标签值归一化后为空（原值只能是空白字符）"
        }
        return normalized
    }

    /** 宽松版：用于输入框实时校验，不抛异常。 */
    fun isValid(rawValue: String): Boolean = rawValue.trim().isNotEmpty()
}
