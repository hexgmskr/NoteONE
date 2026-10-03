package io.github.hexgmskr.noteone.data.crypto

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 一份密钥材料密文的落盘口——**副本A（Keystore）与副本B（主密码）共用**。
 * 2026-10-01 从 `RecoveryKeySource` 改名而来：副本A 也要用同一种"小文本文件 +
 * 原子写入"，名字带 Recovery 会让后来人以为是恢复路径专属。
 *
 * 抽成接口是照 [io.github.hexgmskr.noteone.ui.tagorder.TagOrderSource] 的路子：
 * 真实现要碰文件系统，单测可以塞内存实现，也可以直接拿临时目录测真实现。
 */
interface KeyBlobSource {
    /** 读密文文本。没存过返回 null。 */
    fun read(): String?

    /** 整份覆盖写入。 */
    fun write(text: String)

    /** 删除（吊销副本时用）。 */
    fun clear()
}

/**
 * 落盘实现：App 私有目录里的一个文本文件。
 *
 * **写入是「先写临时文件 → sync 落盘 → 原子改名」**：密文是解出 DEK 的唯一凭证，
 * 半截写入（断电、崩溃、磁盘满）比不写更糟——旧内容被截断成半个文件，等于密文损坏。
 * 改名在文件系统层面是原子的：要么还是旧内容，要么已经是完整的新内容，不存在中间态。
 * 改名失败时**保持旧内容并抛错**，绝不退化成非原子覆盖写（见 write 内注释）。
 *
 * 不用 SharedPreferences：密钥材料放在一个会被系统按自己的时机重写/清理的存储里不合适，
 * 而且副本B就是要导出给用户的那段文本，文件形态最直接。
 *
 * 接受 `File` 而不是 `Context`：这样 JVM 单测能拿临时目录测**真实现**，
 * 不必为"文件读写"这种纯逻辑再写一层设备测试。
 */
class KeyBlobStore(private val file: File) : KeyBlobSource {

    override fun read(): String? =
        if (file.exists()) file.readText() else null

    override fun write(text: String) {
        file.parentFile?.mkdirs()

        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }

        // 原子替换用 Files.move(ATOMIC_MOVE + REPLACE_EXISTING)，**不用 File.renameTo**：
        // 后者在目标已存在时于 Windows 上直接失败——旧实现正是因此退化成"覆盖写"，
        // 等于原子性在测试环境从未生效（2026-10-04 审计连出两个问题：回退不原子 +
        // 它其实是 Windows 上的常规路径）。NIO 的 move 在 Linux/Android 走 rename(2)、
        // 在 Windows 走 MoveFileEx(REPLACE_EXISTING)，两边都是原子替换。
        // 真失败（如同名目录占位）时：保持旧内容原样、清掉临时文件、抛出说清原因的错——
        // 绝不退化成非原子覆盖（半截密文比"这次没写成"严重得多）。
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("密钥密文写入失败（原子改名未成功）：${file.name}", e)
        }
    }

    override fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}
