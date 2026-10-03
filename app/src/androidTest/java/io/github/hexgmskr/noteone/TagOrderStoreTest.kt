package io.github.hexgmskr.noteone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hexgmskr.noteone.ui.tagorder.TagOrdering
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 展示顺序持久化的真机回归（2026-10-04 补，审计 #8 覆盖缺口）。
 *
 * 此前 TagOrderStore 只有 JVM 侧的假实现被间接带到——真实现（org.json 序列化 +
 * SharedPreferences 落盘）零覆盖，而它是"用户调过的标签顺序"的唯一住处
 * （卸载重装才丢，见类注释）。
 *
 * **用隔离偏好名**：测真持久化必须真写 SharedPreferences，沿用生产名会把
 * 用户的自定义顺序清掉（与测试库名同一条红线）。
 */
@RunWith(AndroidJUnit4::class)
class TagOrderStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefsName = "tag-order-store-test"
    private val store = TagOrderStore(context, prefsName = prefsName)

    @Before
    fun setUp() = clearPrefs()

    @After
    fun tearDown() = clearPrefs()

    @Test
    fun `round trip keeps namespace order and per-namespace value order`() {
        val ordering = TagOrdering(
            namespaceOrder = listOf("长度", "主题", "手法"),
            valueOrder = mapOf(
                "长度" to listOf("短视频", "长视频", "延时摄影"),
                "主题" to listOf("萌宠", "自然"),
            ),
        )

        store.save(ordering)
        val loaded = store.load()

        assertEquals("维度顺序应原样回来", ordering.namespaceOrder, loaded.namespaceOrder)
        assertEquals("维度内值顺序应原样回来", ordering.valueOrder, loaded.valueOrder)
    }

    @Test
    fun `values with tricky characters survive the round trip`() {
        // 序列化用 JSONArray（不拼分隔符）——值里带逗号/引号/换行都不该出事
        val ordering = TagOrdering(
            namespaceOrder = listOf("含,逗号", "含\"引号", "含\n换行"),
            valueOrder = mapOf("含,逗号" to listOf("值,一", "值\"二")),
        )

        store.save(ordering)

        assertEquals(ordering.namespaceOrder, store.load().namespaceOrder)
        assertEquals(ordering.valueOrder, store.load().valueOrder)
    }

    private fun clearPrefs() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
