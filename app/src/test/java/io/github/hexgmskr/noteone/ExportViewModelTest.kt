package io.github.hexgmskr.noteone

import io.github.hexgmskr.noteone.data.crypto.KeyBlobSource
import io.github.hexgmskr.noteone.data.crypto.KeyEnvelope
import io.github.hexgmskr.noteone.data.crypto.RecoveryKeyManager
import io.github.hexgmskr.noteone.data.export.ExportJson
import io.github.hexgmskr.noteone.data.export.Exporter
import io.github.hexgmskr.noteone.data.export.ImportFormat
import io.github.hexgmskr.noteone.data.export.Importer
import io.github.hexgmskr.noteone.data.tag.TagRepository
import io.github.hexgmskr.noteone.ui.export.ExportKind
import io.github.hexgmskr.noteone.ui.export.ExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 导出页逻辑的 JVM 单测。
 *
 * 重点：**加密导出前必须核对主密码**——不对就一个字节都不许备好（否则用户会得到
 * 一份用记错的密码封起来的备份，灾难恢复时才发现打不开）。其余是状态流转：
 * 一次性拉起文件选择器的信号、取消、收尾计数与清密码。
 *
 * 用真管家（迭代数调低）+ 内存落盘；调度器注入测试实现，全部"调完即断言"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val password = "the-real-master-password"

    /** 导出文件自己的密码——和上面那把"金库"主密码本来就是两码事。 */
    private val filePassword = "password-of-the-export-file"
    private lateinit var manager: RecoveryKeyManager
    private lateinit var itemDao: FakeItemDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var itemTagDao: FakeItemTagDao

    private val items = listOf(
        testItem(id = 1, content = "https://a.example", createdAt = 1),
        testItem(id = 2, content = "第二条", createdAt = 2, tags = arrayOf("主题" to "萌宠")),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        manager = RecoveryKeyManager(InMemoryKeyBlob(), databaseExists = { false }, iterations = 100)
        manager.setup(password.toCharArray())
        itemDao = FakeItemDao().apply { itemsWithTags = items }
        tagDao = FakeTagDao()
        itemTagDao = FakeItemTagDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ExportViewModel(
        itemDao = itemDao,
        recoveryKeyManager = manager,
        importer = Importer(
            itemDao = itemDao,
            tagRepository = TagRepository(tagDao, itemTagDao),
            itemTagDao = itemTagDao,
            inTransaction = { block -> block() },
        ),
        iterations = 100,
        ioContext = UnconfinedTestDispatcher(scheduler),
    )

    // ---- 加密导出：密码核对是硬门槛 ----

    @Test
    fun `wrong password fails before anything is prepared`() {
        val vm = viewModel()
        vm.onPasswordChange("wrong password")

        vm.requestEncryptedExport()

        assertNotNull("密码不对必须报错", vm.uiState.value.error)
        assertNull("不能问出文件选择器", vm.uiState.value.pendingPicker)
        assertNull("不能留下任何待写字节", vm.pendingBytes())
    }

    @Test
    fun `empty password is rejected immediately`() {
        val vm = viewModel()

        vm.requestEncryptedExport()

        assertEquals("先输入主密码", vm.uiState.value.error)
        assertNull(vm.pendingBytes())
    }

    @Test
    fun `correct password prepares an export envelope and asks for the picker`() {
        val vm = viewModel()
        vm.onPasswordChange(password)

        vm.requestEncryptedExport()

        assertEquals(ExportKind.ENCRYPTED, vm.uiState.value.pendingPicker)
        assertNull(vm.uiState.value.error)
        val text = vm.pendingBytes()!!.toString(Charsets.UTF_8)
        assertTrue(
            "备好的字节必须是导出用途的信封",
            text.startsWith(KeyEnvelope.MAGIC_EXPORT + "\n"),
        )
        assertTrue("信封要带密文体", text.contains("payload="))
        assertFalse("密文里不该出现明文", text.contains("a.example"))
    }

    // ---- 明文导出：先确认再动手 ----

    @Test
    fun `plaintext export asks for confirmation before preparing`() {
        val vm = viewModel()

        vm.requestPlaintextExport()

        assertTrue("先弹确认框", vm.uiState.value.confirmPlaintext)
        assertNull("确认之前不许备数据", vm.pendingBytes())
    }

    @Test
    fun `confirming plaintext export prepares the json`() {
        val vm = viewModel()
        vm.requestPlaintextExport()

        vm.confirmPlaintextExport()

        assertEquals(ExportKind.PLAINTEXT, vm.uiState.value.pendingPicker)
        val text = vm.pendingBytes()!!.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("{\n  \"schema_version\": 1"))
        assertTrue(text.contains("第二条"))
    }

    // ---- 选择器往返 ----

    @Test
    fun `picker signal is consumed once and cancel clears the bytes`() {
        val vm = viewModel()
        vm.requestPlaintextExport()
        vm.confirmPlaintextExport()

        vm.onPickerLaunched()
        assertNull("信号消费后必须清掉，避免反复弹选择器", vm.uiState.value.pendingPicker)

        vm.onExportCancelled()
        assertNull(vm.pendingBytes())
        assertNotNull(vm.uiState.value.notice)
    }

    @Test
    fun `finishing successfully reports the count and clears the password`() {
        val vm = viewModel()
        vm.onPasswordChange(password)
        vm.requestEncryptedExport()

        vm.onExportFinished(true)

        assertTrue("要告诉用户导出了几条", vm.uiState.value.notice!!.contains("2 条"))
        assertEquals("用过的密码不留在界面上", "", vm.uiState.value.password)
        assertNull(vm.pendingBytes())
    }

    @Test
    fun `write failure surfaces as an error`() {
        val vm = viewModel()
        vm.requestPlaintextExport()
        vm.confirmPlaintextExport()

        vm.onExportFinished(false)

        assertNotNull(vm.uiState.value.error)
    }

    // ---- 导入：读回导出文件 ----

    @Test
    fun `picking a plain json file reports its item count`() {
        val vm = viewModel()

        vm.onImportFilePicked(ExportJson.build(items, TEST_EXPORTED_AT))

        assertEquals(ImportFormat.JSON, vm.uiState.value.importFormat)
        assertEquals(2, vm.uiState.value.importItemCount)
        assertFalse(vm.uiState.value.importNeedsPassword)
    }

    @Test
    fun `picking an encrypted file asks for its password`() {
        val vm = viewModel()
        val text = Exporter.encryptedText(items, TEST_EXPORTED_AT, filePassword.toCharArray(), iterations = 100)

        vm.onImportFilePicked(text)

        assertEquals(ImportFormat.ENCRYPTED, vm.uiState.value.importFormat)
        assertTrue(vm.uiState.value.importNeedsPassword)
        assertNull(vm.uiState.value.importItemCount)
    }

    @Test
    fun `picking a recovery key file is named as such`() {
        val vm = viewModel()

        vm.onImportFilePicked(KeyEnvelope.MAGIC_RECOVERY + "\nversion=1\n")

        assertTrue("要说清这是恢复密钥文件", vm.uiState.value.error!!.contains("恢复密钥"))
        assertNull("识别失败不能留下待导入的文件", vm.uiState.value.importText)
    }

    @Test
    fun `importing a plain file merges and reports the counts`() {
        val vm = viewModel()
        vm.onImportFilePicked(ExportJson.build(items, TEST_EXPORTED_AT))

        vm.startImport()

        val notice = vm.uiState.value.notice!!
        assertTrue("要报告新增条数：$notice", notice.contains("新增 2 条"))
        assertEquals(2, itemDao.inserted.size)
        assertNull("导入完成后清掉待导入状态", vm.uiState.value.importText)
    }

    @Test
    fun `re-importing the same file through the ui changes nothing`() {
        val vm = viewModel()
        val json = ExportJson.build(items, TEST_EXPORTED_AT)
        vm.onImportFilePicked(json)
        vm.startImport()

        vm.onImportFilePicked(json)
        vm.startImport()

        assertTrue("第二次应当全是「跳过」", vm.uiState.value.notice!!.contains("新增 0 条"))
        assertEquals(2, itemDao.inserted.size)
    }

    @Test
    fun `wrong password on an encrypted file inserts nothing`() {
        val vm = viewModel()
        val text = Exporter.encryptedText(items, TEST_EXPORTED_AT, filePassword.toCharArray(), iterations = 100)
        vm.onImportFilePicked(text)
        vm.onImportPasswordChange("wrong")

        vm.startImport()

        assertNotNull("密码不对必须报错", vm.uiState.value.error)
        assertEquals("一个字节都不许落库", 0, itemDao.inserted.size)
    }

    @Test
    fun `import with the right password decrypts and merges`() {
        val vm = viewModel()
        val text = Exporter.encryptedText(items, TEST_EXPORTED_AT, filePassword.toCharArray(), iterations = 100)
        vm.onImportFilePicked(text)
        vm.onImportPasswordChange(filePassword)

        vm.startImport()

        assertTrue(vm.uiState.value.notice!!.contains("新增 2 条"))
        assertEquals(2, itemDao.inserted.size)
    }
}
