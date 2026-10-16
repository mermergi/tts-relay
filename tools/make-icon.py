#!/data/data/com.termux/files/usr/bin/python3
# -*- coding: utf-8 -*-
"""
生成 app 图标：喇叭 + 声波，配色跟界面主色一致（#4F6BFF → #6C8CFF）。

以前只生一张 192×192 的图、还只放在 xxhdpi 一档 —— 别的机器要么糊要么被 launcher
拉伸，而且颜色跟界面主色（#6C8CFF）对不上。现在一次生全套：

    res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        传统图标
    res/mipmap-{...}/ic_launcher_foreground.png                        自适应图标前景（108dp 画布）
    res/mipmap-{...}/ic_launcher_background.png                        自适应图标背景
    res/mipmap-anydpi-v26/ic_launcher.xml                              自适应图标（API 26+ 走这个）

用法：python3 tools/make-icon.py      （要 PIL；Termux：pkg install python-pillow）
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "res"))

TOP = (79, 107, 255)      # #4F6BFF —— 浅色主题的 accent
BOTTOM = (108, 140, 255)  # #6C8CFF —— 深色主题的 accent
WHITE = (255, 255, 255, 255)

SS = 4                    # 超采样：先画大图再缩，边缘才不毛

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def gradient(size):
    """左上 → 右下的线性渐变。"""
    small = Image.new("RGB", (2, 2))
    small.putpixel((0, 0), TOP)
    small.putpixel((1, 0), TOP)
    small.putpixel((0, 1), BOTTOM)
    small.putpixel((1, 1), BOTTOM)
    return small.resize((size, size), Image.BILINEAR)


def rounded_mask(size, radius_ratio=0.22):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    return m


def glyph(size):
    """白喇叭 + 三道声波，画在透明层上。size = 画布边长。"""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 喇叭：后面的方箱 + 前面的锥面
    d.rounded_rectangle([0.19 * s, 0.40 * s, 0.33 * s, 0.60 * s],
                        radius=0.025 * s, fill=WHITE)
    d.polygon([(0.31 * s, 0.40 * s), (0.50 * s, 0.23 * s),
               (0.50 * s, 0.77 * s), (0.31 * s, 0.60 * s)], fill=WHITE)

    # 声波：三圈弧，越外越细
    cx, cy = 0.50 * s, 0.50 * s
    for rad, w in ((0.15, 0.055), (0.24, 0.05), (0.33, 0.045)):
        r = rad * s
        d.arc([cx - r, cy - r, cx + r, cy + r], start=-56, end=56,
              fill=WHITE, width=int(w * s))
    return img.resize((size, size), Image.LANCZOS)


def legacy_icon(size):
    s = size * SS
    bg = gradient(s).convert("RGBA")
    bg.putalpha(rounded_mask(s))
    return Image.alpha_composite(bg, glyph(size * SS)).resize((size, size), Image.LANCZOS)


def adaptive_bg(size):
    return gradient(size * SS).convert("RGBA").resize((size, size), Image.LANCZOS)


def adaptive_fg(size):
    """自适应图标：系统只保证中间 72/108 可见，所以喇叭画在那一圈里。"""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    inner = int(s * 72.0 / 108.0)
    g = glyph(inner)
    off = (s - inner) // 2
    img.paste(g, (off, off), g)
    return img.resize((size, size), Image.LANCZOS)


def save(img, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print("  %-46s %6d B" % ("/".join(parts), os.path.getsize(path)))


ANYDPI = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标（API 26+）：形状交给 launcher 裁，所以前景留了安全区 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def main():
    print("传统图标：")
    for d, size in LEGACY.items():
        save(legacy_icon(size), "mipmap-" + d, "ic_launcher.png")
    print("自适应图标：")
    for d, size in ADAPTIVE.items():
        save(adaptive_bg(size), "mipmap-" + d, "ic_launcher_background.png")
        save(adaptive_fg(size), "mipmap-" + d, "ic_launcher_foreground.png")
    xml = os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml")
    os.makedirs(os.path.dirname(xml), exist_ok=True)
    with open(xml, "w") as f:
        f.write(ANYDPI)
    print("  mipmap-anydpi-v26/ic_launcher.xml")


if __name__ == "__main__":
    main()
