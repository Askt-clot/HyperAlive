# -*- coding: utf-8 -*-
"""量首页三张瓦片里"绿色"的占比：用来判断哪几张跟了锁屏照片（绿色测试图）。"""
import sys

import numpy as np
from PIL import Image

path = sys.argv[1] if len(sys.argv) > 1 else r'shots\fix3_home.png'
a = np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
for name, x0, x1 in (('熄屏', 45, 390), ('锁屏', 425, 770), ('桌面', 805, 1150)):
    r = a[430:1030, x0:x1]
    green = (r[:, :, 1] > 90) & (r[:, :, 1] > r[:, :, 0] + 30) & (r[:, :, 1] > r[:, :, 2] + 30)
    print('%-4s 绿占比 %.3f  均值 %.1f' % (name, green.mean(), r.mean()))