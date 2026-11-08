# -*- coding: utf-8 -*-
"""列出 uiautomator dump 里可点击的图片格子（用于精确点相册里的照片）。"""
import io
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r'build\ui.xml'
s = io.open(path, encoding='utf-8', errors='replace').read()
n = 0
for m in re.finditer(r'<node[^>]*>', s):
    tag = m.group(0)
    if 'clickable="true"' not in tag:
        continue
    cls = re.search(r'class="([^"]*)"', tag)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        continue
    x0, y0, x1, y1 = (int(v) for v in b.groups())
    w, h = x1 - x0, y1 - y0
    if w < 120 or h < 120:
        continue
    print('%-60s x=%d-%d y=%d-%d  (%dx%d)' % (cls.group(1) if cls else '?', x0, x1, y0, y1, w, h))
    n += 1
    if n >= 8:
        break
if n == 0:
    print('没有找到大的可点击格子（相册可能还在相册列表页）')