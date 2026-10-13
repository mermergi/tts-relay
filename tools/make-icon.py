#!/data/data/com.termux/files/usr/bin/python3
# -*- coding: utf-8 -*-
"""纯标准库生成 app 图标（不依赖 PIL）：渐变圆角方块 + 白色声波柱。"""
import os
import struct
import zlib

S = 192
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res", "mipmap-xxhdpi", "ic_launcher.png")


def png_write(path, w, h, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    head = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)      # 8bit RGBA
    blob = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(blob)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def rr_alpha(x, y, x0, y0, x1, y1, r):
    """圆角矩形的覆盖率（简单 4x4 超采样，得到抗锯齿边缘）。"""
    hits = 0
    for sy in range(4):
        for sx in range(4):
            px, py = x + (sx + 0.5) / 4.0, y + (sy + 0.5) / 4.0
            if not (x0 <= px <= x1 and y0 <= py <= y1):
                continue
            cx = min(max(px, x0 + r), x1 - r)
            cy = min(max(py, y0 + r), y1 - r)
            if (px - cx) ** 2 + (py - cy) ** 2 <= r * r:
                hits += 1
    return hits / 16.0


TOP, BOT = (79, 70, 229), (168, 85, 247)          # indigo -> purple
rows = []
for y in range(S):
    row = bytearray()
    for x in range(S):
        a = rr_alpha(x, y, 10, 10, S - 10, S - 10, 44)
        if a <= 0:
            row += b"\x00\x00\x00\x00"
            continue
        r, g, b = lerp(TOP, BOT, y / float(S))
        row += bytes((r, g, b, int(round(a * 255))))
    rows.append(row)

# 声波柱：5 根圆头竖条，中间高两边低
BARS = [(58, 84), (78, 58), (99, 28), (119, 62), (140, 92)]   # (x, 距顶距离)
BW, BR = 13, 6.5
for bx, off in BARS:
    y0, y1 = off, S - off
    for y in range(int(y0) - 8, int(y1) + 8):
        for x in range(bx - 8, bx + BW + 8):
            a = rr_alpha(x, y, bx, y0, bx + BW, y1, min(BR, BW / 2.0))
            if a <= 0:
                continue
            i = (x * 4)
            old = rows[y][i + 3]
            na = int(round(a * 255))
            if na <= old:
                continue
            rows[y][i:i + 4] = bytes((255, 255, 255, na))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
png_write(OUT, S, S, rows)
print("icon ->", os.path.normpath(OUT), os.path.getsize(OUT), "bytes")
