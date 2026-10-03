package io.github.hexgmskr.noteone.ui.list

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hexgmskr.noteone.ui.common.MorphSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.acos
import kotlin.math.tan

/**
 * 右下角的按钮栈（2026-10-03 用户拍板的设计）。
 *
 * 四张卡片叠成一摞：[FabAction.Add]（新建）常驻最上面、在原 FAB 位置；
 * 下面依次是标签管理 / 恢复密钥 / 导出导入——**越靠里越暗**，像一沓纸的阴影。
 *
 * 交互：
 *  - **点**最上面那张 = 新建（和原来一样）；
 *  - **按住向上划** = 把一摞展开成一竖列，每张都可点（跟手展开、松手吸附）；
 *  - **展开状态下在列上滑** = 堆回去；点任意一张 = 它**原地长成整页**（不缩回），
 *    堆叠等进场形变把列表盖住后再悄悄收回（返回列表时仍是收起的）。
 *
 * 图标全用 Canvas 画的等高线笔画（[FabGlyph]）：项目零图标依赖，
 * 而且文字字形（+、#）与自绘笔画混排粗细对不上，"风格一致"就无从谈起。
 * 展开/收起是"跟手 + 吸附"而不是开关动画，手感与 Telegram 的滑动手势同路数。
 *
 * 列表滚过标签区之后，新建卡片头顶还会浮现一个**圆角三角形**的「回顶部」小钮：
 * 与 FAB 同色、底边同宽、尖朝上，中间留 [WEDGE_GAP] 的距离；它和新建卡片共用
 * 同一份位移，所以展开时一起向上走、相对位置不变（2026-10-03 用户二次设计，
 * 取代早先的圆形按钮）。形状与显隐见下面的 [roundedUpTriangleShape] 与实现处。
 */
internal enum class FabAction(val label: String) {
    Add("新建记录"),
    TagManage("标签管理"),
    RecoveryKey("恢复密钥"),
    Export("导出/导入"),
}

@Composable
internal fun ActionFabStack(
    /** 各入口的参数是**进场形变的起点**（被点卡片自己的位置/颜色/图标）。 */
    onAddClick: (MorphSource?) -> Unit,
    onTagManageClick: (MorphSource?) -> Unit,
    onRecoveryKeyClick: (MorphSource?) -> Unit,
    onExportClick: (MorphSource?) -> Unit,
    /**
     * 列表的滚动状态源。**直接收 LazyListState 而不是布尔值**——
     * 布尔值意味着调用方要在自己的组合体里读滚动状态，每次"开始/停止滚动"
     * 都会让整屏（连同所有可见卡片与标签区）重组一次，是真机上滚动卡顿的来源
     * （2026-10-03 gfxinfo 排查）。这里用 [snapshotFlow] 在协程里读，组合期零成本。
     */
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val items: List<Pair<FabAction, (MorphSource?) -> Unit>> = listOf(
        FabAction.Add to onAddClick,
        FabAction.TagManage to onTagManageClick,
        FabAction.RecoveryKey to onRecoveryKeyClick,
        FabAction.Export to onExportClick,
    )

    /**
     * 每张卡片最近一次量到的屏幕矩形，点击时当作进场形变的起点。
     *
     * 用"点击那一刻量到的值"而不是初始值：栈展开时新建卡浮在上方，
     * 形变得从它**当时所在的位置**长出来，而不是叠着时的位置。
     */
    val fabBounds = remember { mutableStateMapOf<FabAction, Rect>() }

    val density = LocalDensity.current
    // **手指 1:1 拖动**：分母 = 最上面那张卡的视觉行程（步距 × 层数）。
    // 手指走多远、卡片就走多远——不再按"提速"换算（2026-10-03 用户三轮：
    // 此前等比例增速，卡片比手指快、不跟手）。触发行程靠"位置阈值 + 甩速"
    // 两条判据（见 onDragStopped），所以不必牺牲跟手去换短行程。
    val travelPx = with(density) { (STACK_SPACING - STACK_PEEK).toPx() } * items.lastIndex
    val scope = rememberCoroutineScope()

    /** 展开进度：0 = 叠着，1 = 全展开。跟手 + 松手吸附。 */
    var progress by remember { mutableFloatStateOf(0f) }

    val dragState = rememberDraggableState { delta ->
        // 向上划（delta 为负）→ 进度增加
        progress = (progress - delta / travelPx).coerceIn(0f, 1f)
    }

    // 列表一滚动就把展开的栈收回去（只在开着的时候动，叠着时不做无谓动画）。
    // snapshotFlow：滚动状态**不进组合**，只有真的在滚动时才唤醒协程。
    // 与手动收起同一套弹簧，手感一致。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect {
                if (progress > 0f) {
                    animate(progress, 0f, animationSpec = STACK_SPRING) { value, _ ->
                        progress = value
                    }
                }
            }
    }

    // 相邻两层的间距：叠着时 = 露边宽，展开时 = 一竖列的步距（随进度插值）
    val perStep = STACK_PEEK + (STACK_SPACING - STACK_PEEK) * progress

    // 「回顶部」楔形钮的显隐：标签区（列表第 0 项）滚出画面才浮现、回到顶部自行
    // 隐去——浮现逻辑与早先的圆钮一致，只换了样式（2026-10-03）。
    // derivedStateOf：只有跨过这条边界时才唤醒重组，滚动本身不进组合体。
    val showScrollTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    // 楔形钮的间距也随展开进度插值：叠着时是 WEDGE_GAP，展开后拉到与
    // **展开态卡片之间的空隙**一致（STACK_SPACING − FAB_SIZE）——
    // 这样它在那根竖列里就是"紧挨新建的又一格"，不显得被吸在新建头顶
    // （2026-10-03 用户反馈：展开后这一串要和谐）。
    val expandedAir = (STACK_SPACING - FAB_SIZE).coerceAtLeast(WEDGE_GAP)
    val wedgeGap = WEDGE_GAP + (expandedAir - WEDGE_GAP) * progress

    // 楔形钮占的上方空间：**只在它出现时才计入盒子高度**。若常驻占位，
    // 按钮头顶会留一片被手势层罩住的死区——在那里往下拖着滚列表会失灵。
    val wedgeSpace = if (showScrollTop) WEDGE_HEIGHT + wedgeGap else 0.dp

    Box(
        modifier = modifier
            .width(FAB_SIZE)
            // 手势区随展开进度长高：叠着时刚好罩住这一沓，不挡身后的列表
            .height(FAB_SIZE + perStep * items.lastIndex + wedgeSpace)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    // 速度单位 px/s，向上为负。先看"甩"：快速上/下甩直接定目标；
                    // 否则按位置阈值吸附（阈值是满行程的比例，1:1 下约 78dp 手指位移）。
                    val fling = -velocity / travelPx
                    val target = when {
                        fling > FLING_PROGRESS_PER_SEC -> 1f
                        fling < -FLING_PROGRESS_PER_SEC -> 0f
                        progress > STACK_SNAP_THRESHOLD -> 1f
                        else -> 0f
                    }
                    // 弹簧到位（不是 tween）：到位时轻微过冲回弹——展开、收起都有
                    // "q弹"（2026-10-03 用户三轮：此前一到位置就瞬间定住）。
                    animate(progress, target, animationSpec = STACK_SPRING) { value, _ ->
                        progress = value
                    }
                },
            ),
    ) {
        val baseColor = MaterialTheme.colorScheme.primaryContainer
        val onColor = MaterialTheme.colorScheme.onPrimaryContainer
        val expanded = progress > STACK_CLICK_THRESHOLD

        // 倒着摆：Add 最后画 → 在最上层（盖着下面几张）
        items.reversed().forEachIndexed { reversedIndex, (action, onClick) ->
            val index = items.lastIndex - reversedIndex
            // **越靠前（index 越小）站得越高**：最后一张钉在原位，
            // 新更像"被抽出来的那张"——它带头向上滑，其余依次跟上，
            // 叠着时露出的就是下一层卡片的**下缘**（一沓纸从正面看的样子）。
            val y = perStep * (items.lastIndex - index)
            // 越靠里越透明（不是变暗——像纸片一层层透过去），展开时恢复不透明。
            // 弹簧过冲时 progress 会短暂越过 1，alpha 得夹住（>1 会给 Color 出怪值）
            val alpha = (1f - index * STACK_DEPTH_ALPHA * (1f - progress)).coerceIn(0f, 1f)

            FloatingActionButton(
                onClick = {
                    // 新建**任何时候都能点**（它是常驻入口，叠着也是它）；
                    // 其余几张只在展开态接受点击——叠着时露出的窄边不该误触。
                    if (action == FabAction.Add || expanded) {
                        // **点击后不立刻缩回**：被点的那张卡片马上要"长成整页"，
                        // 若同时缩回，看着像它原地分裂成两张（一张扩大成页、
                        // 一张落回底边）——2026-10-03 用户反馈。
                        // 等进场形变把列表盖住之后再悄悄收回：返回时仍是收起的，
                        // 与从前的行为一致，只是这一步不再被看见。
                        if (progress > 0f) {
                            scope.launch {
                                delay(STACK_COLLAPSE_DELAY_MS)
                                animate(progress, 0f, animationSpec = STACK_SPRING) { value, _ ->
                                    progress = value
                                }
                            }
                        }
                        onClick(
                            fabBounds[action]?.let { bounds ->
                                MorphSource(
                                    boundsInRoot = bounds,
                                    color = baseColor,
                                    cornerRadius = FAB_CORNER,
                                    icon = { FabGlyph(action) },
                                )
                            },
                        )
                    }
                },
                containerColor = baseColor.copy(alpha = alpha),
                contentColor = onColor.copy(alpha = alpha),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = -y)
                    .onGloballyPositioned { fabBounds[action] = it.boundsInRoot() }
                    .semantics { contentDescription = action.label },
            ) {
                FabGlyph(action)
            }
        }

        // 「回顶部」楔形钮：与新建按钮同风格的圆角三角形（底边同宽、尖朝上），
        // 悬在新建卡片头顶 wedgeGap 处。位移与新建卡片**共用同一份 perStep**，
        // 所以按钮栈展开时它跟着新建卡片一起向上、相对位置不变。
        val wedgeCornerPx = with(density) { WEDGE_CORNER.toPx() }
        val wedgeShape = remember(wedgeCornerPx) { roundedUpTriangleShape(wedgeCornerPx) }
        WedgeButton(
            visible = showScrollTop,
            shape = wedgeShape,
            onClick = {
                // 滚动一起步，上面那条 snapshotFlow 就会把展开的栈自动收回去
                scope.launch { listState.animateScrollToItem(0) }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(y = -(perStep * items.lastIndex + FAB_SIZE + wedgeGap)),
        )
    }
}

/**
 * 「回顶部」楔形钮本体。形状/颜色与新建按钮一致，只多了"浮现"。
 *
 * **独立成一个小组合体是有意的**：浮现动画每帧都在变，装在这里，
 * 重组范围就只有这颗钮，不会把旁边四张 FAB 一起拖着重组
 * （2026-10-03 卡顿排查的经验：状态在读在哪，重组就发生在哪）。
 *
 * 阴影高度也做动画的原因：阴影是独立的一层，**不跟着 alpha 走**——
 * 只淡内容的话，三角淡完阴影才"啪"地出现（2026-10-03 反馈"太突兀"）。
 * 让 elevation 从 0 长到 6dp，阴影就有了生长过程，和内容同步。
 */
@Composable
private fun WedgeButton(
    visible: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fadeAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(WEDGE_FADE_MS),
        label = "wedgeAlpha",
    )
    val elevation by animateDpAsState(
        targetValue = if (visible) WEDGE_SHADOW else 0.dp,
        animationSpec = tween(WEDGE_FADE_MS),
        label = "wedgeElevation",
    )

    // 淡出到底就把节点摘掉：留着会挡住背后的列表
    if (visible || fadeAlpha > 0f) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = elevation,
            modifier = modifier
                .graphicsLayer {
                    alpha = fadeAlpha
                    // 缩放与 alpha 同步：浮现时从 0.85 长回原大小，不另开一份状态
                    val s = WEDGE_SCALE_MIN + (1f - WEDGE_SCALE_MIN) * fadeAlpha
                    scaleX = s
                    scaleY = s
                }
                .size(width = FAB_SIZE, height = WEDGE_HEIGHT)
                .semantics { contentDescription = "回到顶部" },
        ) {}
    }
}

/**
 * 圆角三角形（尖朝上、底边在下）——「回顶部」楔形钮的轮廓。
 *
 * 三个角的圆角**视觉半径一致**：每个角沿它的两条边各退 `d = r / tan(θ/2)`
 * （θ 是该角的内角），再以顶点为控制点用二次贝塞尔把两个切点连起来。
 * 若图省事对所有角切同一个固定距离，尖角会比底角圆出一倍多，形状就"塌"了。
 *
 * **底边看着是一道弧、没有"直底"，这是本形状的固有代价，不是缺陷**
 * （2026-10-03 用户看过 40dp 高的"直底"版本后，选择回到这个比例）：
 * 56dp 宽的三角形底角只有 49°，圆角每 1dp 要沿底边吃 2.2dp，12dp 半径
 * 把底边吃得只剩中段 4.5dp 直线，肉眼读成连续弧。想让底边变直只有两条路——
 * 圆角缩到 7dp 以下（角看着比 FAB 小一截），或把 [WEDGE_HEIGHT] 加到 40dp
 * 撑陡底角（角要跟着缩到 10dp 才留得住直线）。两头都要动，别只改一个。
 *
 * 半径也可能给过头：同一条边上两个切点会互相越过、路径自交。所以按最紧的
 * 一条边统一缩一刀——手调区把半径改大只会"顶到上限"，不会画出畸形。
 */
private fun roundedUpTriangleShape(radiusPx: Float): Shape = GenericShape { size, _ ->
    val apex = Offset(size.width / 2f, 0f)
    val left = Offset(0f, size.height)
    val right = Offset(size.width, size.height)

    /** 从 [from] 朝 [to] 走 [distance]。 */
    fun along(from: Offset, to: Offset, distance: Float): Offset {
        val delta = to - from
        return from + delta / delta.getDistance() * distance
    }

    /** [vertex] 处由两条边夹出的内角 θ。 */
    fun interiorAngleAt(vertex: Offset, a: Offset, b: Offset): Float {
        val u = a - vertex
        val v = b - vertex
        val cos = (u.x * v.x + u.y * v.y) / (u.getDistance() * v.getDistance())
        return acos(cos.coerceIn(-1f, 1f))
    }

    val angleApex = interiorAngleAt(apex, left, right)
    val angleBase = interiorAngleAt(left, apex, right)   // 等腰，两个底角对称

    val dApexRaw = radiusPx / tan(angleApex / 2f)
    val dBaseRaw = radiusPx / tan(angleBase / 2f)

    // 每条边上两个切点之和不得超过边长本身（留 8% 余量），否则形状自交
    val sideEdge = (left - apex).getDistance()
    val baseEdge = (right - left).getDistance()
    val scale = minOf(
        1f,
        0.92f * sideEdge / (dApexRaw + dBaseRaw),
        0.92f * baseEdge / (dBaseRaw * 2f),
    )
    val dApex = dApexRaw * scale
    val dBase = dBaseRaw * scale

    val start = along(apex, left, dApex)       // 左上斜边上，靠近尖角
    val leftTop = along(left, apex, dBase)     // 左角，斜边一侧
    val leftBottom = along(left, right, dBase) // 左角，底边一侧
    val rightBottom = along(right, left, dBase)
    val rightTop = along(right, apex, dBase)
    val end = along(apex, right, dApex)

    moveTo(start.x, start.y)
    lineTo(leftTop.x, leftTop.y)
    quadraticTo(left.x, left.y, leftBottom.x, leftBottom.y)     // 左底角
    lineTo(rightBottom.x, rightBottom.y)
    quadraticTo(right.x, right.y, rightTop.x, rightTop.y)       // 右底角
    lineTo(end.x, end.y)
    quadraticTo(apex.x, apex.y, start.x, start.y)               // 尖角
    close()
}

/**
 * 四种图标：等高线笔画自绘（笔宽/端点一致，"风格一致"）。
 * 坐标写在一个 24×24 的网格里再按实际尺寸缩放，改形状不用换算像素。
 *
 * internal 而非 private：进场形变层要把同一个图标画在容器上（见 [MorphSource.icon]），
 * 两处必须**同一个画法**，复制一份迟早会漂。
 */
@Composable
internal fun FabGlyph(action: FabAction) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(GLYPH_SIZE)) {
        val w = GLYPH_STROKE.toPx()
        val s = size.minDimension
        fun p(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)

        when (action) {
            FabAction.Add -> {
                drawLine(color, p(12f, 5f), p(12f, 19f), w, StrokeCap.Round)
                drawLine(color, p(5f, 12f), p(19f, 12f), w, StrokeCap.Round)
            }

            // 「#」：两道斜竖 + 两道横
            FabAction.TagManage -> {
                drawLine(color, p(10f, 4f), p(8f, 20f), w, StrokeCap.Round)
                drawLine(color, p(16f, 4f), p(14f, 20f), w, StrokeCap.Round)
                drawLine(color, p(4f, 9f), p(20f, 9f), w, StrokeCap.Round)
                drawLine(color, p(4f, 15f), p(20f, 15f), w, StrokeCap.Round)
            }

            // 钥匙：圆柄 + 斜杆 + 两齿
            FabAction.RecoveryKey -> {
                drawCircle(color, radius = 3.6f / 24f * s, center = p(8.5f, 8.5f), style = Stroke(w))
                drawLine(color, p(11.2f, 11.2f), p(19.5f, 19.5f), w, StrokeCap.Round)
                drawLine(color, p(15.5f, 15.5f), p(13.5f, 17.5f), w, StrokeCap.Round)
                drawLine(color, p(18.5f, 18.5f), p(16.5f, 20.5f), w, StrokeCap.Round)
            }

            // 导入/导出：两个反向箭头（⇄）——进与出，通用表意
            FabAction.Export -> {
                drawLine(color, p(4.5f, 8f), p(19f, 8f), w, StrokeCap.Round)
                drawLine(color, p(15.5f, 4.5f), p(19.5f, 8f), w, StrokeCap.Round)
                drawLine(color, p(15.5f, 11.5f), p(19.5f, 8f), w, StrokeCap.Round)

                drawLine(color, p(19.5f, 16f), p(5f, 16f), w, StrokeCap.Round)
                drawLine(color, p(8.5f, 12.5f), p(4.5f, 16f), w, StrokeCap.Round)
                drawLine(color, p(8.5f, 19.5f), p(4.5f, 16f), w, StrokeCap.Round)
            }
        }
    }
}

// =====================================================================
// 可调布局参数（手调区）
//
// 想让这一摞更大/更松/更暗，改这里就够了。改完
// `./gradlew :app:assembleDebug` 重新装即可看到效果。
// =====================================================================

/** FAB 直径（56dp 是 Material 标准尺寸）。 */
private val FAB_SIZE = 56.dp

/** FAB 的圆角（= Material3 FAB 默认形状 16dp）。进场形变要从同样的圆角起步。 */
private val FAB_CORNER = 16.dp

/** 图标尺寸。 */
private val GLYPH_SIZE = 24.dp

/** 图标笔画粗细。 */
private val GLYPH_STROKE = 2.dp

/** 叠着时每张卡片往上露出的边宽（一沓纸的厚度感）。 */
private val STACK_PEEK = 7.dp

/** 展开后相邻卡片的上移间距。 */
private val STACK_SPACING = 72.dp

/**
 * 甩速阈值（换算成"进度/秒"）：松手瞬间手指速度超过它就**直接**展开/收起，
 * 不看当前位置。轻快一甩就能开合、不必把整段行程拖满——这是"触发行程合适"
 * 的另一半（配合 1:1 跟手，2026-10-03 用户三轮）。
 */
private const val FLING_PROGRESS_PER_SEC = 1.2f

/** 松手后按位置吸附：拖过满行程的这个比例就展开/收起。1:1 下约 78dp 手指位移。 */
private const val STACK_SNAP_THRESHOLD = 0.4f

/**
 * 点了卡片之后，隔多久才悄悄收回堆叠（毫秒）。
 *
 * 对齐 `MainActivity` 的 `MORPH_MS`（350ms，进场形变把列表盖住的时长）——
 * 晚一点无妨（多盖一会儿），早于形变完成就会露馅（能在列表上看见它在收）。
 */
private const val STACK_COLLAPSE_DELAY_MS = 400L

/**
 * 展开/收起到位的弹簧。
 *
 * **`MediumBouncy` = 到位时轻微过冲回弹**（用户要的"q弹"手感，2026-10-03 三轮）；
 * 嫌弹过头就调向 `LowBouncy`/`NoBouncy`，嫌肉就把 `stiffness` 加大。
 */
private val STACK_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** 展开超过这个进度，非"新建"的卡片才接受点击（叠着时露出的窄边不误触）。 */
private const val STACK_CLICK_THRESHOLD = 0.5f

/** 每往里一层的透明度递减（渐变透明，不是变暗）。 */
private const val STACK_DEPTH_ALPHA = 0.25f

/** 「回顶部」楔形钮的高度（底边宽度直接用 [FAB_SIZE]，与新建按钮一致）。 */
private val WEDGE_HEIGHT = 32.dp

/** 楔形钮与新建卡片之间的距离——"挨着，但留一点舒适的距离"。 */
private val WEDGE_GAP = 6.dp

/**
 * 楔形钮三个角的圆角半径（视觉半径一致，见 [roundedUpTriangleShape]）。
 *
 * 12dp 是 32dp 高时的几何上限附近：底角只有 49°，每 1dp 半径要沿底边吃
 * 2.2dp，再大底角切点就会越过彼此（形状函数里有等比缩刀兜底）。
 * 方形 FAB 的 16dp 圆角在这个比例下数学上做不到，详见形状函数的说明。
 */
private val WEDGE_CORNER = 12.dp

/**
 * 楔形钮的阴影。与方形 FAB 默认的 elevation 对齐（FabPrimaryTokens
 * ContainerElevation = 6dp）；Surface 不显式给就是 0，会显得比那一摞"薄"。
 */
private val WEDGE_SHADOW = 6.dp

/** 楔形钮浮现/隐去的时长。alpha 与阴影高度用同一个时长，步调才一致。 */
private const val WEDGE_FADE_MS = 200

/** 浮现时从多小长回原大小（缩放与 alpha 同步）。 */
private const val WEDGE_SCALE_MIN = 0.85f
