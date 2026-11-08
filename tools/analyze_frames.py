# -*- coding: utf-8 -*-
"""分析「锁屏→熄屏」帧序列：亮度 + 圆半径（沿过圆心的扫描线找边界）。
设备屏幕 1200x2608；圆心 x=600 y≈430（顶部往下 16.5%）；AOD 圆半径 0.22*1200=264。
"""
import glob
import os
import sys

from PIL import Image

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sq_frames')
times = {}
tp = os.path.join(D, 'times.txt')
if os.path.exists(tp):
    for line in open(tp, encoding='utf-8', errors='replace'):
        p = line.split()
        if len(p) == 2:
            times[p[0]] = int(p[1])

CX, CY = 600, 430


def lum(px, x, y):
    r, g, b = px[x, y][:3]
    return (r + g + b) / 3.0


def radius(px, w, h, thresh=28):
    """从圆心向四个方向扫，找第一个亮度 <= thresh 的位置，返回四个方向的半径。"""
    res = []
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        x, y = CX, CY
        r = None
        while 0 <= x < w and 0 <= y < h:
            if lum(px, x, y) <= thresh:
                r = abs(x - CX) + abs(y - CY)
                break
            x += dx
            y += dy
        res.append(r if r is not None else -1)
    return res


def main():
    files = sorted(glob.glob(os.path.join(D, 'f*.png')))
    prev_t = None
    print('frame      time      dt    avg   corner  center   R(right,left,down,up)')
    for f in files:
        name = os.path.basename(f)[:-4]
        im = Image.open(f).convert('RGB')
        w, h = im.size
        px = im.load()
        tot = 0
        n = 0
        for y in range(0, h, 24):
            for x in range(0, w, 24):
                tot += lum(px, x, y)
                n += 1
        avg = tot / n
        corner = lum(px, 40, 40)
        center = lum(px, CX, CY)
        rr = radius(px, w, h) if center > 28 else ['-'] * 4
        t = times.get(name[1:])
        dt = '' if (t is None or prev_t is None) else '%5d' % (t - prev_t)
        if t is not None:
            prev_t = t
        ts = '' if t is None else '%d.%03d' % (t // 1000 % 1000, t % 1000)
        print('%-6s %-12s %s %6.1f %7.1f %7.1f   %s' % (name, ts, dt, avg, corner, center, rr))


if __name__ == '__main__':
    main()