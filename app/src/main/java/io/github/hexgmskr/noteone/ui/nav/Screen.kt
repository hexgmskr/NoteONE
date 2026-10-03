package io.github.hexgmskr.noteone.ui.nav

/**
 * 应用内导航。
 *
 * 用 sealed class 而非 Jetpack Navigation：现阶段只有两三个屏幕，
 * 而本项目已两次踩到依赖版本冲突，少一个依赖少一个冲突面。
 * 屏幕增多后再迁移。
 */
sealed interface Screen {
    /** 主界面：记录列表 + 按标签筛选。 */
    data object List : Screen

    /** 新建/编辑记录。 */
    data class Edit(val itemId: Long? = null) : Screen

    /** 记录详情：全文 + 标签 + 创建时间；可复制、进编辑、删除。 */
    data class Detail(val itemId: Long) : Screen

    /** 标签顺序调整（低频设置）。 */
    data object TagOrder : Screen

    /** 恢复密钥（副本B）：设置主密码、导出备份、恢复。低频设置。 */
    data object RecoveryKey : Screen

    /** 导出全部记录（加密或明文，用户选）。低频维护动作，spec 6。 */
    data object Export : Screen
}
