# -*- coding: utf-8 -*-
"""比较两张截图差异（判断"有没有变"）。用法 python tools/diff2.py a.png b.png"""
import sys

import numpy as np
from PIL import Image

a = np.asarray(Image.open(sys.argv[1]).convert('RGB'), dtype=np.int16)
b = np.asarray(Image.open(sys.argv[2]).convert('RGB'), dtype=np.int16)
d = np.abs(a - b).mean(axis=2)
print('%s vs %s：差异均值 %.1f  变化像素(>20)占比 %.3f  亮度 %.1f/%.1f'
      % (sys.argv[1].split('\\')[-1], sys.argv[2].split('\\')[-1], d.mean(),
         (d > 20).mean(), a.mean(), b.mean()))