package io.github.hexgmskr.noteone.data.db

import androidx.room.Transactor
import androidx.room.useWriterConnection

/**
 * 在写连接上开一个事务跑 [block]。**这是 driver 模式下唯一正确的事务口**。
 *
 * 为什么不能用 `RoomDatabase.withTransaction`：那条路要拿 `openHelper`
 * （传统的 SupportSQLite 路径），而 driver 模式（本项目的 SQLCipher 实现）没有它，
 * 调用会抛：
 * ```
 * Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory was configured with Room.
 * ```
 * 这个坑在真机上以"保存失败"的形态暴露过一次——**48 条仪器测试全绿也没挡住**，
 * 因为它们都用 `inMemoryDatabaseBuilder`（传统路径），"driver 模式 + 事务"这个组合
 * 从没被跑到过。现在有专门的用例守着（[SqlCipherDatabaseProviderTest]）。
 *
 * 用 IMMEDIATE 而非 DEFERRED：写事务直接拿写锁，避免"读着读着要升级成写"
 * 时的锁竞争死结——本项目的事务都是纯写，没有用 DEFERRED 的理由。
 */
suspend fun <R> NoteOneDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor ->
        transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
    }
