#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""400x300 墨水瓶渲染：4 级灰阶（0/85/170/255）→ EPD_4in2 双平面（0x10 / 0x13）。

参考 pengwon/epd42 的 canvas2gray 算法：
  white  (1,1) / gray1 (1,0) / gray2 (0,1) / black (0,0)
"""
from __future__ import annotations

import os
try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    raise SystemExit(
        "缺少 Pillow（PIL）。请先安装依赖：\n"
        "  python -m pip install pillow requests bleak\n"
        "（如果 pip 提示找不到，检查是否用了 Windows 应用商店的 python3.exe 占位程序）"
    )

WIDTH, HEIGHT = 400, 300
LEVELS = (0, 85, 170, 255)          # 黑 / 深灰 / 浅灰 / 白
BLACK, GRAY_D, GRAY_L, WHITE = LEVELS

_HERE = os.path.dirname(os.path.abspath(__file__))
FONT_REG = os.path.join(_HERE, "fonts", "NotoSansCJKsc-Regular.otf")
FONT_BOLD = os.path.join(_HERE, "fonts", "NotoSansCJKsc-Bold.otf")


def _font(path: str, size: int) -> ImageFont.FreeTypeFont:
    if os.path.exists(path):
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def _draw_text(draw, xy, text, font, fill, anchor="la"):
    draw.text(xy, text, font=font, fill=fill, anchor=anchor)


def render(
    balance_text: str,
    today_used_text: str,
    refreshed_text: str,
    peak: bool,
    *,
    subtitle_balance: str = "余额",
    subtitle_today: str = "今日使用",
    window_text: str | None = None,
) -> Image.Image:
    """渲染一帧 400x300、4 级灰阶的 L 模式图像。"""
    img = Image.new("L", (WIDTH, HEIGHT), WHITE)
    d = ImageDraw.Draw(img)

    f_label = _font(FONT_REG, 15)
    f_name = _font(FONT_BOLD, 58)
    f_side = _font(FONT_REG, 14)
    f_item_label = _font(FONT_REG, 21)
    f_item_value = _font(FONT_BOLD, 34)
    pad = 18
    # footer 字号自适应：带时区名后可能变长
    f_foot = _font(FONT_REG, 15)
    max_width = WIDTH - pad * 2
    for size in (15, 14, 13, 12, 11):
        f_foot = _font(FONT_REG, size)
        if d.textlength(refreshed_text, font=f_foot) <= max_width:
            break

    # ── 第一行：梁文峰（峰时）/ 梁文谷（谷时） ──
    name = "梁文峰" if peak else "梁文谷"
    window = window_text if window_text else ("峰时 08:30–00:30" if peak else "谷时 00:30–08:30")
    _draw_text(d, (pad, 18), "DeepSeek 计费时段", f_label, BLACK)
    _draw_text(d, (pad, 40), name, f_name, BLACK)
    _draw_text(d, (WIDTH - pad, 22), window, f_side, BLACK, anchor="ra")

    # 分隔线
    d.line([(pad, 118), (WIDTH - pad, 118)], fill=GRAY_L, width=1)

    # ── 第二行：余额 ──
    _draw_text(d, (pad, 140), subtitle_balance, f_item_label, BLACK)
    _draw_text(d, (WIDTH - pad, 134), balance_text, f_item_value, BLACK, anchor="ra")

    # ── 第三行：今日使用 ──
    _draw_text(d, (pad, 196), subtitle_today, f_item_label, BLACK)
    _draw_text(d, (WIDTH - pad, 190), today_used_text, f_item_value, BLACK, anchor="ra")

    # ── 第四行：上次刷新（小字，居中） ──
    d.line([(pad, 246), (WIDTH - pad, 246)], fill=GRAY_L, width=1)
    _draw_text(d, (WIDTH // 2, 262), refreshed_text, f_foot, BLACK, anchor="ma")

    return _quantize4(img)


def _quantize4(img: Image.Image) -> Image.Image:
    """8bit 灰度 → 4 级灰阶（最近邻，不抖动，保证文字边缘干净）。"""
    lut = []
    for v in range(256):
        lut.append(min(LEVELS, key=lambda L: abs(L - v)))
    return img.point(lut)


def to_planes(img: Image.Image):
    """4 级灰阶图 → (plane10, plane13) 两段 15000 字节。"""
    if img.mode != "L":
        img = img.convert("L")
    w, h = img.size
    px = img.load()
    out10 = bytearray()
    out13 = bytearray()
    b10 = b13 = 0
    n = 0
    for y in range(h):
        for x in range(w):
            v = px[x, y]
            lvl = min(range(4), key=lambda i: abs(LEVELS[i] - v))
            # white(3)->(1,1) gray1(2)->(1,0) gray2(1)->(0,1) black(0)->(0,0)
            bit10 = 1 if lvl >= 2 else 0
            bit13 = 1 if (lvl == 1 or lvl == 3) else 0
            b10 = (b10 << 1) | bit10
            b13 = (b13 << 1) | bit13
            n += 1
            if n == 8:
                out10.append(b10)
                out13.append(b13)
                b10 = b13 = 0
                n = 0
    if n:                      # 理论上 400 能被 8 整除，这里只是兜底
        b10 <<= (8 - n)
        b13 <<= (8 - n)
        out10.append(b10)
        out13.append(b13)
    return bytes(out10), bytes(out13)


def save_preview(img: Image.Image, path: str, scale: int = 1):
    """保存预览图（默认原尺寸；scale>1 时最近邻放大便于查看）。"""
    out = img
    if scale > 1:
        out = img.resize((WIDTH * scale, HEIGHT * scale), Image.NEAREST)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    out.save(path)
    return path


# 4 级灰阶 LUT —— 与 pengwon/epd42 的 send4GrayLut() 完全一致
LUT_4GRAY = [
    (0x20, "000A0000000160141400000100140000000100130A010001000000000000000000000000000000000000"),
    (0x21, "400A0000000190141400000110140A000001A01301000001000000000000000000000000000000000000"),
    (0x22, "400A0000000190141400000100140A000001990C01030401000000000000000000000000000000000000"),
    (0x23, "400A0000000190141400000100140A000001990B04040101000000000000000000000000000000000000"),
    (0x24, "800A0000000190141400000120140A000001501301000001000000000000000000000000000000000000"),
    (0x25, "400A00000001101414000001A01301000001000000000000000000000000000000000000000000000000"),
]
