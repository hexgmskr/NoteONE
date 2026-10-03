package io.github.hexgmskr.noteone.data.db

/**
 * SQLCipher 原生库加载。
 *
 * `net.zetetic:sqlcipher-android` 4.19.1 **不自动加载** `libsqlcipher.so`：
 * AAR 的 Manifest 里没有 Initializer，源码里也没有任何 System.loadLibrary 调用，
 * 加载责任完全在调用方。不做这一步就会在第一次开库时抛
 * `UnsatisfiedLinkError: No implementation found for ... nativeOpen`。
 * （这是阶段 0 探针在真机上实测出来的，不是纸面推断。）
 *
 * 放在接缝 A 这一侧的原因：「怎么打开数据库」本来就是 [DatabaseProvider] 的职责，
 * 而加载原生库是开库的前置条件，不该让每个调用方各自记得去调。
 */
object SqlCipher {
    private const val LIBRARY_NAME = "sqlcipher"

    /** 加载原生库。可重复调用，实际只加载一次。 */
    fun load() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
