package io.github.hexgmskr.noteone.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 进场「容器变换」的起点：被点元素在**根坐标系**里的矩形，加上它自己的
 * 容器色、圆角与图标（2026-10-03 用户选定的进场方案）。
 *
 * 新屏进场时先从这块矩形长成整屏（同色、同圆角），页面内容在后半程淡入——
 * 所以「主页 → 新建」看起来是右下角那颗按钮自己铺开成一整页，而不是凭空
 * 跳出一屏；点卡片进详情同理，卡片铺开成详情页。
 *
 * 坐标统一用 `boundsInRoot()`：列表、覆盖层、形变层同处 Compose 根坐标系，
 * 形变层自己抹掉所在原点（见 `MainActivity.MorphLayer`），不做逐层换算。
 *
 * 拿不到合适起点（比如从详情的 ⋮ 菜单进编辑）时传 null——那就退化成
 * "内容原地淡入"，不起形变层，不硬凑一个假的起点。
 */
data class MorphSource(
    val boundsInRoot: Rect,
    /** 起点的容器色。形变层从这里起步、渐变为页面底色，交班才无痕。 */
    val color: Color,
    val cornerRadius: Dp,
    /**
     * 起点的图标（FAB 上的 +/#/钥匙/⇄）。
     *
     * **带上它才不会瞬灭**：容器一起步就正好盖住原按钮，按钮上的图标会被
     * 挡住，得由容器自己接着画、前半程淡出，视觉上才是"图标跟着容器走"。
     * 卡片这类起点没有需要延续的图标，传 null 即可（底色是连着的）。
     */
    val icon: (@Composable () -> Unit)? = null,
)
