package io.github.hexgmskr.noteone

import android.content.Context
import java.io.File

/**
 * 删掉一个测试库及其附属文件（`-wal` / `-shm` / `-journal`）。
 *
 * 2026-10-04 收敛：此前四个仪器测试各抄了一份同样的清理代码。
 *
 * **只许传隔离库名**——`androidTest` 永远不许用生产名 `noteone.db`
 * （CLAUDE.md 红线：用生产名 = 每跑一次测试删一次真机数据，已实际发生过一次）。
 */
internal fun Context.deleteTestDatabase(name: String) {
    deleteDatabase(name)
    val dir = getDatabasePath(name).parentFile ?: return
    listOf("-wal", "-shm", "-journal").forEach { suffix ->
        File(dir, name + suffix).delete()
    }
}
