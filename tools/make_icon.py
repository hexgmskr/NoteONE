# -*- coding: utf-8 -*-
"""
NoteONE 启动图标生成器（2026-10-04）。

用法：
    python tools/make_icon.py 你画的图标.png

要求：正方形、1024×1024 或更大；内容自定（会被当作"图标整个面"）。

原理（一句话）：安卓自适应图标要在 108dp 画布上作图，但启动器只显示**中央
72dp 的可见区**。所以：
  ・把你的图缩放到中央 72dp 可见区（=864px @12px/dp）——内容按你的原设计尺寸，不放大；
  ・画布其余部分（108dp 的边缘环带）用同一张图**拉伸铺满**做溢出色——
    只会在个别启动器的开合动画露出，颜色无缝；
  ・输出为 App 的图标资源（WebP）。

产出：app/src/main/res/drawable-nodpi/ic_launcher_art.webp
之后构建安装即可看到新图标（同一签名覆盖，数据不动）。

自检：脚本会扫描"可见区"四角是否为纯白（某些图自带白色圆角）——若报 WARN，
说明该图在圆形/圆角遮罩下可能露白，把图的内容再往内缩一点重生成。
"""
import sys
import os
import math

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：python -m pip install pillow")

CANVAS = 1296   # 108dp × 12px/dp（画布）
FACE = 864      # 72dp 可见区

def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    if not os.path.isfile(src):
        sys.exit(f"找不到文件：{src}")

    im = Image.open(src).convert("RGB")
    w, h = im.size
    if w != h or w < 512:
        sys.exit(f"要求正方形且 ≥512×512，实际 {w}×{h}")

    # 1) 可见区：你的图缩放到 864×864 居中
    canvas = im.resize((CANVAS, CANVAS), Image.LANCZOS)          # 铺满层（溢出色）
    face = im.resize((FACE, FACE), Image.LANCZOS)
    canvas.paste(face, ((CANVAS - FACE) // 2, (CANVAS - FACE) // 2))

    # 2) 自检：可见区四角(向外 40px 起)是否露白
    px = canvas.load()
    ox = oy = (CANVAS - FACE) // 2
    warn = []
    for cx, cy, sx, sy in [(ox, oy, 1, 1), (ox + FACE, oy, -1, 1),
                           (ox, oy + FACE, 1, -1), (ox + FACE, oy + FACE, -1, -1)]:
        for r in range(10, 200, 6):
            x, y = cx + sx * r, cy + sy * r
            rr, gg, bb = px[x, y]
            if rr > 240 and gg > 240 and bb > 240:
                warn.append((x, y, r))
                break

    out = os.path.join("app", "src", "main", "res", "drawable-nodpi", "ic_launcher_art.webp")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    canvas.save(out, "WEBP", quality=92, method=6)
    print(f"已生成 {out}（{os.path.getsize(out)} 字节）")
    if warn:
        print("WARN: 可见区角落接近纯白——圆形/圆角遮罩下可能露白：", warn)
    else:
        print("自检通过：可见区两角无露白。装到手机看效果即可。")

if __name__ == "__main__":
    main()
