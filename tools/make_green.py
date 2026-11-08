# -*- coding: utf-8 -*-
"""生成测试图：纯绿底 + 白圆（用来区分锁屏/桌面两张照片）。"""
from PIL import Image, ImageDraw

im = Image.new('RGB', (1200, 2608), (0, 140, 60))
d = ImageDraw.Draw(im)
d.ellipse((300, 800, 900, 1400), fill=(255, 255, 255))
im.save(r'D:\DSH\default-workspace-2\qihang_lsp\build\green.png')
print('green.png ok')