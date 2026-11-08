# -*- coding: utf-8 -*-
"""首页排版测量：三态标签→卡片间距、卡片内容垂直居中偏差。用法 python tools/gap_check.py shots/home_v8.png"""
import sys

import numpy as np
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else r'shots\home_v8.png'
rgb = np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
lum = rgb.mean(axis=2)
cnt = (lum > 150).sum(axis=1)
cardmask = (np.abs(rgb - np.array([28, 28, 30])).sum(axis=2) < 24).sum(axis=1)

# 卡片分段
seg = []
start = None
for y in range(1000, 2400):
    hit = cardmask[y] > 600
    if hit and start is None:
        start = y
    if not hit and start is not None:
        seg.append((start, y - 1))
        start = None
if start:
    seg.append((start, 2399))
print('卡片分段:', seg)

for i, (y0, y1) in enumerate(seg):
    sub = lum[y0:y1 + 1]
    tr = [y for y in range(y1 - y0 + 1) if (sub[y] > 150).sum() > 5]
    if not tr:
        continue
    off = (y0 + (tr[0] + tr[-1]) / 2) - (y0 + y1) / 2
    print('  卡片#%d y=%d-%d 高=%d 内容中心偏差=%+.1f px' % (i + 1, y0, y1, y1 - y0 + 1, off))
    if i == 0:
        labels = [y for y in range(max(0, y0 - 120), y0) if cnt[y] > 10]
        if labels:
            gap = y0 - labels[-1]
            print('  三态标签底 y=%d → 卡片顶 y=%d 间隙=%d px (%.1f dp)'
                  % (labels[-1], y0, gap, gap / 3.0))