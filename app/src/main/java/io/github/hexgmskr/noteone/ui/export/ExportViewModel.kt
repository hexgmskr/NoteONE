package io.github.hexgmskr.noteone.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hexgmskr.noteone.data.crypto.CryptoException
import io.github.hexgmskr.noteone.data.crypto.RecoveryKey
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.dao.ItemDao
import io.github.hexgmskr.noteone.data.export.ExportFileException
import io.github.hexgmskr.noteone.data.export.Exporter
import io.github.hexgmskr.noteone.data.export.ImportFormat
import io.github.hexgmskr.noteone.data.export.ImportParser
import io.github.hexgmskr.noteone.data.export.ImportReport
import io.github.hexgmskr.noteone.data.export.Importer
import java.time.OffsetDateTime
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户点的是哪种导出——决定系统文件选择器的默认文件名与 MIME。 */
enum class ExportKind { ENCRYPTED, PLAINTEXT }

/**
 * 导出页的状态。
 *
 * [pendingPicker] 是一次性事件：数据在后台备好（加密要跑几秒 PBKDF2）之后，
 * 界面看到它非空就去拉起系统文件选择器；拉起后立刻调 [ExportViewModel.onPickerLaunched]
 * 清掉，避免重组时反复弹。
 */
data class ExportUiState(
    val busy: Boolean = false,
    val password: String = "",
    val error: String? = null,
    val notice: String? = null,
    /** 明文导出的确认对话框是否展开（明文落盘是敏感动作，问一次）。 */
    val confirmPlaintext: Boolean = false,
    val pendingPicker: ExportKind? = null,

    // ---- 导入 ----

    /** 已由页面读进来的文件内容（null = 还没选）。 */
    val importText: String? = null,
    /** 选中文件的形态：明文直接可用；加密的等密码；识别不了的当场报错。 */
    val importFormat: ImportFormat? = null,
    /** 明文文件解析出来的条数（加密的在输密码前不知道）。 */
    val importItemCount: Int? = null,
    /** 加密文件：输入密码后才能导入。 */
    val importNeedsPassword: Boolean = false,
    /**
     * 导入用的密码。**与导出用的主密码分开**：导出文件可能是改密码之前导的，
     * 那时用的是旧密码——两个输入框各管各的，不互相污染。
     */
    val importPassword: String = "",
)

/**
 * 导出页的逻辑（spec 6：JSON 导出，用户拍板"加密/明文二选一"）。
 *
 * 关键职责是**加密导出的密码核对**：先把用户输的密码拿去解一次恢复密钥（副本B），
 * 对了才用它去封导出文件。否则打错一个字母就会得到一份"用某个记错的密码封起来的备份"——
 * 用户以为它是主密码锁的，灾难恢复时才发现打不开，那时已经没救了。
 * 代价是一次额外的 PBKDF2（约 2.5 秒），这个低频操作付得起。
 *
 * 明文导出的确认对话框由 [ExportUiState.confirmPlaintext] 驱动：文件不加密，
 * 它离开 App 就是一份谁都能读的笔记全量副本。
 */
class ExportViewModel(
    private val itemDao: ItemDao,
    private val recoveryKeyManager: RecoveryKeyManager,
    /** 把解析好的记录合并进库（幂等：同一份文件重复导入＝无事发生）。 */
    private val importer: Importer,
    /** 封导出文件用的迭代数。生产永远用默认；单测注入低值。 */
    private val iterations: Int = RecoveryKey.DEFAULT_ITERATIONS,
    /** 跑全量读 + 密钥派生用的调度器。参数化是为了单测注入测试调度器。 */
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    /** 已备好、等用户选完位置就落盘的字节。 */
    @Volatile
    private var pendingBytes: ByteArray? = null
    private var pendingCount: Int = 0

    // ---- 输入 ----

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, error = null) }

    // ---- 明文导出：先弹一次确认 ----

    fun requestPlaintextExport() =
        _uiState.update { it.copy(confirmPlaintext = true, error = null, notice = null) }

    fun dismissPlaintextExport() = _uiState.update { it.copy(confirmPlaintext = false) }

    fun confirmPlaintextExport() {
        _uiState.update { it.copy(confirmPlaintext = false) }
        prepare(ExportKind.PLAINTEXT)
    }

    // ---- 加密导出 ----

    fun requestEncryptedExport() {
        val state = _uiState.value
        if (state.busy) return
        if (state.password.isEmpty()) return showError("先输入主密码")
        prepare(ExportKind.ENCRYPTED)
    }

    // ---- 备数据（可能几秒：PBKDF2） ----

    private fun prepare(kind: ExportKind) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null, notice = null) }

        viewModelScope.launch {
            val failure: Throwable? = try {
                withContext(ioContext) {
                    val items = itemDao.findAllWithTags()
                    val now = OffsetDateTime.now()
                    val bytes = when (kind) {
                        ExportKind.PLAINTEXT -> Exporter.plaintextBytes(items, now)

                        ExportKind.ENCRYPTED -> {
                            // 核对：这个密码必须真的能解开本机的恢复密钥，
                            // 否则导出的是一份"用记错的密码封起来"的备份
                            recoveryKeyManager.unlock(_uiState.value.password.toCharArray())
                            Exporter.encryptedText(items, now, _uiState.value.password.toCharArray(), iterations)
                                .toByteArray(Charsets.UTF_8)
                        }
                    }
                    pendingBytes = bytes
                    pendingCount = items.size
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }
            _uiState.update {
                it.copy(
                    busy = false,
                    error = failure?.let(::messageOf),
                    pendingPicker = if (failure == null) kind else null,
                )
            }
        }
    }

    // ---- 与文件选择器/写文件协作 ----

    /** 界面已拉起文件选择器（一次性事件消费掉）。 */
    fun onPickerLaunched() = _uiState.update { it.copy(pendingPicker = null) }

    /** 用户在选择器里取消了。 */
    fun onExportCancelled() {
        pendingBytes = null
        _uiState.update { it.copy(notice = "已取消，没有写出文件") }
    }

    /** 写文件的结果（页面写完回调）。 */
    fun onExportFinished(success: Boolean) {
        val count = pendingCount
        pendingBytes = null
        _uiState.update {
            if (success) {
                it.copy(error = null, notice = "已导出 $count 条记录。", password = "")
            } else {
                it.copy(error = "导出没有完成：写入文件失败")
            }
        }
    }

    /** 给界面写文件用（选择器回调里取）。 */
    fun pendingBytes(): ByteArray? = pendingBytes

    // ---- 导入：读回导出文件 ----

    /**
     * 页面把选中的文件内容交进来。
     *
     * 明文当场解析（格式错立刻反馈）；加密的只记下"还要密码"；
     * 识别不了的当场给出原因（拿成恢复密钥文件的会被告知去哪个页面）。
     */
    fun onImportFilePicked(text: String) {
        when (ImportParser.detect(text)) {
            ImportFormat.JSON -> try {
                val items = ImportParser.parseItems(text)
                _uiState.update {
                    it.copy(
                        importText = text,
                        importFormat = ImportFormat.JSON,
                        importItemCount = items.size,
                        importNeedsPassword = false,
                        importPassword = "",
                        error = null,
                        notice = null,
                    )
                }
            } catch (e: ExportFileException) {
                clearImport(message = e.message ?: "这个文件的内容不符合导出格式")
            }

            ImportFormat.ENCRYPTED -> _uiState.update {
                it.copy(
                    importText = text,
                    importFormat = ImportFormat.ENCRYPTED,
                    importItemCount = null,
                    importNeedsPassword = true,
                    importPassword = "",
                    error = null,
                    notice = null,
                )
            }

            ImportFormat.UNKNOWN -> clearImport(message = ImportParser.unknownReason(text))
        }
    }

    /** 选了读不出来的文件（与恢复密钥页同一话术）。 */
    fun onImportFileUnreadable() = showError("这个文件读不出来，换一个试试")

    fun onImportPasswordChange(value: String) =
        _uiState.update { it.copy(importPassword = value, error = null) }

    /**
     * 开始导入：解密（若需要）→ 解析 → 合并落库（一个事务，要么全进要么不进）。
     * 重复导入同一份文件是安全操作——按 `(content, createdAt)` 全被跳成"已存在"。
     */
    fun startImport() {
        val state = _uiState.value
        if (state.busy) return
        val text = state.importText ?: return showError("先选择导出文件")
        if (state.importNeedsPassword && state.importPassword.isEmpty()) {
            return showError("先输入这份文件的密码")
        }

        _uiState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val result: Result<ImportReport> = try {
                Result.success(
                    withContext(ioContext) {
                        val json = if (state.importFormat == ImportFormat.ENCRYPTED) {
                            Exporter.openEncryptedText(text, state.importPassword.toCharArray())
                        } else {
                            text
                        }
                        importer.import(ImportParser.parseItems(json), System.currentTimeMillis())
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }

            _uiState.update { current ->
                result.fold(
                    onSuccess = { report ->
                        current.copy(
                            busy = false,
                            error = null,
                            notice = "导入完成：新增 ${report.imported} 条，" +
                                "跳过 ${report.skipped} 条（库里已有）。",
                            importText = null,
                            importFormat = null,
                            importItemCount = null,
                            importNeedsPassword = false,
                            importPassword = "",
                        )
                    },
                    onFailure = { e -> current.copy(busy = false, error = messageOf(e)) },
                )
            }
        }
    }

    private fun clearImport(message: String) = _uiState.update {
        it.copy(
            importText = null,
            importFormat = null,
            importItemCount = null,
            importNeedsPassword = false,
            importPassword = "",
            error = message,
        )
    }

    // ---- 内部 ----

    private fun showError(message: String) = _uiState.update { it.copy(error = message) }

    private fun messageOf(e: Throwable): String = when (e) {
        // 密码错了、密文坏了（导出/导入两条路共用一个异常），消息可直接展示
        is CryptoException -> e.message ?: "操作失败"
        // 导入文件不符合 schema：报错已经带"第几条记录"，原样给用户
        is ExportFileException -> e.message ?: "文件内容不符合导出格式"
        else -> "出了点问题：${e.message ?: e.javaClass.simpleName}"
    }
}
