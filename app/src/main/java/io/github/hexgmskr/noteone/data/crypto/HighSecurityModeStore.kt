package io.github.hexgmskr.noteone.data.crypto

import android.content.Context

/**
 * 高安全模式（副本A 用哪把钥匙）的读写口。
 *
 * 抽接口的理由与 [io.github.hexgmskr.noteone.ui.tagorder.TagOrderSource] 相同：
 * 真实现要 Context，JVM 单测塞内存实现。
 */
interface HighSecurityModeSource {
    /** 当前偏好：true = 副本A 用"录入新指纹即作废"的钥匙（[KeystoreKeyWrapper.KeyMode.HIGH_SECURITY]）。 */
    fun isEnabled(): Boolean

    /**
     * 写入偏好。**返回是否成功**——这不是展示偏好，写失败的后果是
     * "偏好与落盘信封对不上、指纹解锁失灵"（可用主密码恢复，但不该悄悄发生），
     * 所以调用方要拿返回值决定是否回滚（见 AppContainer 的模式切换）。
     *
     * 用同步 `commit()` 而不是 `apply()`：这条写入紧跟在信封落盘之后，
     * 必须是"写没写进去"当场可知。
     */
    fun setEnabled(enabled: Boolean): Boolean
}

/** SharedPreferences 实现。 */
class HighSecurityModeStore(context: Context) : HighSecurityModeSource {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()

    private companion object {
        const val PREFS_NAME = "security_mode"
        const val KEY_ENABLED = "high_security"
    }
}
