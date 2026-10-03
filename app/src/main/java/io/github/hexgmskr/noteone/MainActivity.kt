package io.github.hexgmskr.noteone

import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.hexgmskr.noteone.ui.common.MorphSource
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import io.github.hexgmskr.noteone.ui.detail.DetailScreen
import io.github.hexgmskr.noteone.ui.detail.DetailViewModel
import io.github.hexgmskr.noteone.ui.edit.EditScreen
import io.github.hexgmskr.noteone.ui.edit.EditViewModel
import io.github.hexgmskr.noteone.ui.export.ExportScreen
import io.github.hexgmskr.noteone.ui.export.ExportViewModel
import io.github.hexgmskr.noteone.ui.list.ItemListScreen
import io.github.hexgmskr.noteone.ui.list.ItemListViewModel
import io.github.hexgmskr.noteone.ui.lock.LockState
import io.github.hexgmskr.noteone.ui.lock.UnlockGate
import io.github.hexgmskr.noteone.ui.nav.Screen
import io.github.hexgmskr.noteone.ui.recoverykey.RecoveryKeyScreen
import io.github.hexgmskr.noteone.ui.recoverykey.RecoveryKeyViewModel
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderScreen
import io.github.hexgmskr.noteone.ui.tagorder.TagOrderViewModel
import io.github.hexgmskr.noteone.ui.theme.NoteONETheme
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * 入口 Activity。
 *
 * **生命周期接线**：把 `onStop` / `onStart` 映射到锁状态机的
 * [io.github.hexgmskr.noteone.ui.lock.LockController.onBackground] / `onForeground`。
 *
 * 用 `onStop`/`onStart` 而非 `onPause`/`onResume`：后者在分屏、弹出对话框、
 * 权限弹窗时都会触发，会导致用户明明还在用却被要求重新验证。
 * `onStop` 只在完全不可见时触发，正好对应「离开使用焦点」这个语义。
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    /**
     * 列表 ViewModel，只创建一次。
     *
     * **不能在 Composable 里新建**——重组很频繁，每次新建会把 UI 状态
     * （比如选中的筛选标签）反复重置，表现为「列表闪一下」，极难排查。
     * 阶段 3 若要跨配置变更（旋转）保留，再迁到 ViewModelProvider。
     */
    private var itemListViewModel: ItemListViewModel? = null

    /** 当前屏幕。用 sealed class 切换，不引 Jetpack Navigation。 */
    private var currentScreen: Screen by mutableStateOf<Screen>(Screen.List)

    /**
     * 极简返回栈：只服务「列表 → 详情 → 编辑」这一条两级链。
     *
     * 提交返回时弹回上一层，弹空落回列表。**屏幕数再增长就该按 CLAUDE.md
     * 的迁移条件迁 Navigation**，别把这个手搓栈养大。
     */
    private val backStack = mutableStateListOf<Screen>()

    /** 压栈当前屏并切到目标屏。子屏跳转统一走它，返回语义才完整。 */
    private fun navigate(to: Screen, from: MorphSource? = null) {
        backStack.add(currentScreen)
        currentScreen = to
        morphSource = from
        morphProgress.floatValue = 0f
        enterSeq++
    }

    /** 提交返回：弹回上一层（空则列表）；落回列表要刷新（写操作后重读）。 */
    private fun goBack() {
        currentScreen = backStack.removeLastOrNull() ?: Screen.List
        endEnter()
        if (currentScreen == Screen.List) itemListViewModel?.refresh()
    }

    /** 直接回列表并清栈：记录被删后不该再回到详情。 */
    private fun backToList() {
        backStack.clear()
        currentScreen = Screen.List
        endEnter()
        itemListViewModel?.refresh()
    }

    /**
     * 弹栈时收掉进场状态：底层页面在退场动画里已经露过脸，**不能重放进场**
     * （否则退出去又立刻长回来）。形变起点一并清掉，等下一次压栈再给。
     */
    private fun endEnter() {
        morphSource = null
        morphProgress.floatValue = 1f
    }

    /**
     * 预测式返回的进度，0f..1f。
     *
     * 系统在用户沿边缘滑动时持续回调进度但**尚未提交返回**；
     * 进度归零表示没提交或已撤销。用它驱动覆盖层缩放位移，
     * 这样「不抬手就能看到返回后的样子，滑回去还能撤销」。
     */
    private var backProgress by mutableFloatStateOf(0f)

    /** 本次手势的起始边缘。决定覆盖层该往哪个方向退。 */
    private var backSwipeEdge by mutableStateOf(BackSwipeEdge.Left)

    /**
     * 退场进度：0 = 停在手势松开的位置，1 = 完全滑出屏幕。
     *
     * 与 [backProgress] 分开：手势进度决定"缩多少 + 让开多少"，
     * 退场只接着把剩余的路走完，不再改缩放。
     */
    private var backExit by mutableFloatStateOf(0f)

    /** 退场动画进行中：这期间的返回请求直接忽略，防双次弹栈。 */
    private var backExiting = false

    /**
     * 进场形变的起点（被点元素）。null = 没有合适起点，只做"内容淡入"。
     * 拿不到起点时（如从详情的 ⋮ 菜单进编辑）不硬凑，淡入本身就不突兀。
     */
    private var morphSource by mutableStateOf<MorphSource?>(null)

    /**
     * 进场进度：0 = 形变层还停在起点上、内容全透明，1 = 形变层已铺满并交班。
     *
     * 刻意存成 State 对象而不是 `by` 委托的 Float：把它传进形变层，
     * **逐帧读发生在那一层里**，页面内容不会跟着每帧重组。
     */
    private val morphProgress = mutableFloatStateOf(1f)

    /** 进场序号：每次压栈 +1，触发一次性进场动画；弹栈不加，所以不重放。 */
    private var enterSeq by mutableIntStateOf(0)

    /**
     * 把退场动画走完：页面从当前位置快速滑出屏幕。
     *
     * **提交返回不能直接弹栈**——那样页面会停在手势松开的位置"凭空消失"
     * （2026-10-03 用户反馈）。让它把剩下的路走完再弹，动作才连贯。
     */
    private suspend fun playBackExit() {
        animate(
            initialValue = backExit,
            targetValue = 1f,
            animationSpec = tween(BACK_EXIT_MS, easing = LinearOutSlowInEasing),
        ) { value, _ -> backExit = value }
    }

    /**
     * 带退场动画的弹栈：按钮返回、保存、删除后回列表都走它。
     * 与手势提交同一套动作——页面一律"滑走"，不凭空消失。
     *
     * [scope] 必须**带帧时钟**（组合体里的 `rememberCoroutineScope()`）：
     * `animate()` 靠 `withFrameNanos` 推进，而 Activity 的 lifecycleScope
     * 上下文里没有 MonotonicFrameClock，用它一调就抛异常、当场闪退
     * （2026-10-03 真机：编辑页取消、密钥/导出页返回都崩）。
     */
    private fun popWithExit(scope: CoroutineScope, toList: Boolean = false) {
        if (backExiting) return
        backExiting = true
        scope.launch {
            playBackExit()
            backExiting = false
            backProgress = 0f
            backExit = 0f
            if (toList) backToList() else goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(applicationContext)
        enableEdgeToEdge()

        // 冷启动：状态机初始就是 Locked，界面直接渲染锁门；冷启动自动弹一次
        // 指纹的逻辑在锁门页内部（UnlockGate），验证通过后由 completeUnlock 推进。

        setContent {
            NoteONETheme {
                val lockState by container.lockController.state.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (lockState) {
                        // 锁门 = 整个 App 的入口：密码验证、还没设置时的引导都在里面
                        is LockState.Locked -> UnlockGate(
                            manager = container.recoveryKeyManager,
                            attemptUnlock = container::unlockWithPassword,
                            biometric = container.biometricUnlock,
                            modifier = Modifier.padding(innerPadding),
                        )

                        is LockState.Unlocked -> {
                            // 列表作基层常驻——预测式返回要在手势中露出「返回后是它」，
                            // 所以不能等真返回了才组合出来。
                            val listVm = itemListViewModel ?: run {
                                val db = container.openDatabase()
                                ItemListViewModel(
                                    itemDao = db.itemDao(),
                                    tagDao = db.tagDao(),
                                    tagOrderStore = container.tagOrderStore,
                                    items = container.itemRepository(db),
                                ).also { itemListViewModel = it }
                            }
                            val listState by listVm.uiState.collectAsState()

                            // 带帧时钟的作用域：退场动画要用（见 popWithExit）。用 Activity 的
                            // lifecycleScope 会因为缺 MonotonicFrameClock 直接抛异常。
                            val uiScope = rememberCoroutineScope()

                            // 覆盖层原点（根坐标）。形变层拿它把元素报上来的根坐标
                            // 换算到自己的坐标系——同处一层时它就是 0。
                            var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
                            // 形变层的终点尺寸 = 窗口尺寸（px）
                            val windowSize = LocalWindowInfo.current.containerSize

                            // 预测式返回：挂在解锁分支内（要读列表的多选态）。
                            //  - 多选模式：提交时只退出多选，**不退 App**
                            //  - 子屏：提交时弹回上一层（详情→列表、编辑→详情/列表）
                            //  - 普通列表页：不接，交给系统默认（返回=退出应用）
                            // 滑动时收进度、抬起时提交、滑回去则撤销。
                            PredictiveBackHandler(
                                enabled = currentScreen != Screen.List || listState.inSelectionMode,
                            ) { progress ->
                                try {
                                    progress.collect { event ->
                                        backSwipeEdge = when (event.swipeEdge) {
                                            BackEventCompat.EDGE_LEFT -> BackSwipeEdge.Left
                                            else -> BackSwipeEdge.Right
                                        }
                                        backProgress = event.progress
                                    }
                                    // 手指抬起且没撤销 = 提交返回
                                    if (currentScreen == Screen.List) {
                                        // 列表页还能开启手势，唯一情形就是多选模式：
                                        // 它没有覆盖层，不存在"退场"，直接退出多选
                                        backProgress = 0f
                                        listVm.clearSelection()
                                    } else if (!backExiting) {
                                        // 把退场动画走完再弹栈（理由见 playBackExit）。
                                        // 这里本来就是挂起上下文，直接等它，不用另起协程。
                                        backExiting = true
                                        playBackExit()
                                        backExiting = false
                                        backProgress = 0f
                                        backExit = 0f
                                        goBack()
                                    }
                                } catch (e: CancellationException) {
                                    // 滑回去了 = 撤销。进度清零，界面弹回原样
                                    backProgress = 0f
                                    throw e
                                }
                            }

                            // 进场动画：压栈时从 0 走到 1（形变层长满 + 内容淡入）。
                            // 以 enterSeq 为键——弹栈不加序号，所以不会在返回时重放。
                            LaunchedEffect(enterSeq) {
                                if (morphProgress.floatValue < 1f) {
                                    animate(
                                        initialValue = 0f,
                                        targetValue = 1f,
                                        animationSpec = tween(MORPH_MS, easing = FastOutSlowInEasing),
                                    ) { value, _ -> morphProgress.floatValue = value }
                                }
                            }

                            // 内容淡入的透明度：形变后段才出场（读的是 State 对象，
                            // 具体读取发生在 graphicsLayer / 形变层里——逐帧变化
                            // 不会让整页重组）。
                            val enterAlpha = remember {
                                derivedStateOf {
                                    ((morphProgress.floatValue - MORPH_CONTENT_START) /
                                        (1f - MORPH_CONTENT_START)).coerceIn(0f, 1f)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // 覆盖层与列表同处根坐标系：量下这个 Box 的原点，
                                    // 把元素报上来的根坐标换算给形变层用
                                    .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
                            ) {
                                ItemListScreen(
                                    viewModel = listVm,
                                    onAddClick = { from -> navigate(Screen.Edit(), from) },
                                    onItemClick = { itemId, from -> navigate(Screen.Detail(itemId), from) },
                                    onTagOrderClick = { from -> navigate(Screen.TagOrder, from) },
                                    onRecoveryKeyClick = { from -> navigate(Screen.RecoveryKey, from) },
                                    onExportClick = { from -> navigate(Screen.Export, from) },
                                    modifier = Modifier.padding(innerPadding),
                                )

                                // 覆盖层：子屏。返回手势中按进度缩放/让位
                                val screen = currentScreen
                                if (screen != Screen.List) {
                                    // 进场形变层垫在页面**下面**：从被点元素长成整屏
                                    MorphLayer(
                                        source = morphSource,
                                        progress = morphProgress,
                                        screenSize = windowSize,
                                        originInRoot = overlayOrigin,
                                    )

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = enterAlpha.value }
                                            .predictiveBackTransform(backProgress, backExit, backSwipeEdge),
                                        color = MaterialTheme.colorScheme.background,
                                    ) {
                                        when (screen) {
                                            is Screen.Edit -> {
                                                // itemId 非空 = 编辑既有记录（从详情进来）；
                                                // 空 = 新建（从列表 FAB 进来）。VM 用 itemId 作
                                                // remember key：换一条记录不必复用旧状态。
                                                val db = container.openDatabase()
                                                val vm = remember(screen.itemId) {
                                                    EditViewModel(
                                                        items = container.itemRepository(db),
                                                        itemDao = db.itemDao(),
                                                        tagDao = db.tagDao(),
                                                        tagOrderStore = container.tagOrderStore,
                                                        editingItemId = screen.itemId,
                                                    )
                                                }
                                                EditScreen(
                                                    viewModel = vm,
                                                    // 保存/取消都回上一层：从详情进的回详情
                                                    // （VM 重建自动重读新内容），从列表进的回列表
                                                    onSaved = { popWithExit(uiScope) },
                                                    onBack = { popWithExit(uiScope) },
                                                    modifier = Modifier.padding(innerPadding),
                                                )
                                            }

                                            is Screen.Detail -> {
                                                val db = container.openDatabase()
                                                val vm = remember(screen.itemId) {
                                                    DetailViewModel(
                                                        itemId = screen.itemId,
                                                        itemDao = db.itemDao(),
                                                        items = container.itemRepository(db),
                                                        tagOrderStore = container.tagOrderStore,
                                                    )
                                                }
                                                DetailScreen(
                                                    viewModel = vm,
                                                    onBack = { popWithExit(uiScope) },
                                                    onEdit = { navigate(Screen.Edit(screen.itemId)) },
                                                    // 记录没了，回列表而不是回详情
                                                    onDeleted = { popWithExit(uiScope, toList = true) },
                                                    modifier = Modifier.padding(innerPadding),
                                                )
                                            }

                                            is Screen.TagOrder -> {
                                                val db = container.openDatabase()
                                                val vm = remember {
                                                    TagOrderViewModel(
                                                        tagDao = db.tagDao(),
                                                        repo = container.tagRepository(db),
                                                        store = container.tagOrderStore,
                                                    )
                                                }
                                                TagOrderScreen(
                                                    viewModel = vm,
                                                    // 顺序改了，goBack 落回列表时统一刷新（重读 ordering）
                                                    onBack = { popWithExit(uiScope) },
                                                    modifier = Modifier.padding(innerPadding),
                                                )
                                            }

                                            is Screen.RecoveryKey -> {
                                                // 恢复密钥页不碰数据库（只读写那个密文文件），
                                                // 所以不需要 openDatabase，也不依赖解锁状态
                                                val vm = remember {
                                                    RecoveryKeyViewModel(
                                                        container.recoveryKeyManager,
                                                        container.biometricUnlock,
                                                    )
                                                }
                                                RecoveryKeyScreen(
                                                    viewModel = vm,
                                                    onBack = { popWithExit(uiScope) },
                                                    modifier = Modifier.padding(innerPadding),
                                                )
                                            }

                                            is Screen.Export -> {
                                                // 导出要读全量记录：走解锁后的库实例。
                                                // 主密码核对（加密态）复用恢复密钥管家。
                                                val db = container.openDatabase()
                                                val vm = remember {
                                                    ExportViewModel(
                                                        itemDao = db.itemDao(),
                                                        recoveryKeyManager = container.recoveryKeyManager,
                                                        importer = container.importer(db),
                                                    )
                                                }
                                                ExportScreen(
                                                    viewModel = vm,
                                                    // 导入会改库：goBack 落回列表时统一刷新重读
                                                    onBack = { popWithExit(uiScope) },
                                                    modifier = Modifier.padding(innerPadding),
                                                )
                                            }

                                            is Screen.List -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.lockController.onForeground()

        // 宽限期耗尽 → 已锁定：关库、丢弃内存里的 DEK。
        // **这里不自动发起验证**：锁门本身就是验证入口（页内会按需弹指纹），
        // 在这里自动发起只会绕过它。
        if (container.lockController.state.value is LockState.Locked) {
            // 清会话会关库。列表 VM 是 Activity 字段、跨锁周期存活，里面握着
            // 那个已关闭库的 DAO——解锁后任何一次 refresh（写操作回列表的必经之路）
            // 都会打到已关闭的连接池上。丢掉它，解锁后按新库实例重建（init 里会自刷新）。
            itemListViewModel = null
            container.clearSession()
        }
    }

    override fun onStop() {
        container.lockController.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            container.closeDatabase()
        }
        super.onDestroy()
    }

}

/** 返回手势的起始边缘。决定覆盖层朝哪边退。 */
private enum class BackSwipeEdge { Left, Right }

/**
 * 进场形变的"容器"：从被点元素的矩形长成整屏的那块色板
 * （2026-10-03 用户选定的进场方案，即 Material 的 container transform）。
 *
 * 它只画背景（外加起点图标），没有别的内容——所以每帧改尺寸的代价只有
 * 一次空布局。页面内容由上面的 Surface 按普通方式铺满、后段淡入，
 * **内容不参与形变**，因此不会出现被拉伸变形的字。
 *
 * 三个细节都是有原因的：
 *  - 颜色从起点色插值到页面底色：不插值的话，内容淡入到九成时底下还是
 *    起点色，交班的最后一帧会闪一下；
 *  - 起点图标由本层接着画、前半程淡出：容器一起步正好盖住原按钮，
 *    不接手的话按钮上的 "+" 会瞬灭；
 *  - 进度读在**本组合体内**（传 State 而不是 Float），逐帧变化的只有这一层，
 *    页面内容不会跟着每帧重组。
 */
@Composable
private fun MorphLayer(
    source: MorphSource?,
    progress: State<Float>,
    screenSize: IntSize,
    originInRoot: Offset,
    modifier: Modifier = Modifier,
) {
    val p = progress.value
    if (p >= 1f) return

    // 没有起点时就地取整屏：底色照铺，只是不发生位移。**这一层不能省**——
    // 内容淡入需要一块不透明的台子，否则会先露出底下的列表再被内容盖住。
    val origin = source ?: MorphSource(
        boundsInRoot = Rect(0f, 0f, screenSize.width.toFloat(), screenSize.height.toFloat()),
        color = MaterialTheme.colorScheme.background,
        cornerRadius = 0.dp,
    )

    val density = LocalDensity.current

    // Float 的 lerp 在 ui.util 包、Color 的在 ui.graphics 包，两个同名导入会打架；
    // 这里只需要给几个坐标做插值，写开更省事
    fun mix(from: Float, to: Float) = from + (to - from) * p

    val start = origin.boundsInRoot.translate(-originInRoot.x, -originInRoot.y)
    val left = mix(start.left, 0f)
    val top = mix(start.top, 0f)
    val width = mix(start.width, screenSize.width.toFloat())
    val height = mix(start.height, screenSize.height.toFloat())
    val corner = origin.cornerRadius * (1f - p)
    val color = lerp(origin.color, MaterialTheme.colorScheme.background, p)

    Surface(
        color = color,
        shape = RoundedCornerShape(corner),
        modifier = modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() }),
    ) {
        val icon = origin.icon
        if (icon != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 图标只在前半程还在：容器一大就不该再挂着按钮的图标
                        alpha = (1f - p / MORPH_ICON_FADE_UNTIL).coerceIn(0f, 1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer,
                ) { icon() }
            }
        }
    }
}

/**
 * 覆盖层在返回手势中的变换。
 *
 * 未手势时缩放 1、位移 0；进度推满时缩到 0.92 并朝手势方向让开约 1/4 屏宽，
 * 露出底下的列表。[exit] 是抬起后"把剩下的路走完"的进度：它只推进位移，
 * 从手势松开时的位置**接着**滑到完全出屏，缩放保持松开时的值不再收。
 * 参数集中在这里方便调手感：
 *  - [SCALE_MIN] 进度为 1 时的缩放，越小「退得越远」
 *  - [SHIFT_RATIO] 手势让开距离占屏宽的比例
 */
private fun Modifier.predictiveBackTransform(
    progress: Float,
    exit: Float,
    edge: BackSwipeEdge,
) = graphicsLayer {
    val scale = 1f - (1f - SCALE_MIN) * progress
    scaleX = scale
    scaleY = scale
    // 退场从当前位置起步、把剩余距离走完：在手势让开的距离上再插值到满屏宽
    val shiftRatio = SHIFT_RATIO * progress + (1f - SHIFT_RATIO * progress) * exit
    val shift = size.width * shiftRatio
    translationX = if (edge == BackSwipeEdge.Left) shift else -shift
}

// ---- 返回手势手感参数（想调改这里）----
private const val SCALE_MIN = 0.92f
private const val SHIFT_RATIO = 0.25f

/** 退场（滑出屏幕）的时长。要"快速滑走"但不至于看不清，200ms 上下。 */
private const val BACK_EXIT_MS = 200

/** 进场形变的时长。容器从按钮大小长到整屏，太快看不出"长"，太慢显得拖。 */
private const val MORPH_MS = 350

/**
 * 内容从形变的哪个进度开始淡入（0..1）。
 *
 * 前段容器还小、还在长，此时露内容会被看穿是"一页被压扁了"；
 * 等容器铺满大半再淡入，读起来就是"页面成形了"。
 */
private const val MORPH_CONTENT_START = 0.35f

/** 起点图标在形变的这个进度上完全淡出（再往后容器里不该还有按钮图标）。 */
private const val MORPH_ICON_FADE_UNTIL = 0.35f
