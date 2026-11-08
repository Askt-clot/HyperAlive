# -*- coding: utf-8 -*-
"""分析录屏抽出的帧序列（600x1296，圆心 x=300 y≈214，AOD 半径≈132）。"""
import glob
import os
import sys

from PIL import Image

D = sys.argv[1] if len(sys.argv) > 1 else 'rec1_frames'
W, H = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (600, 1296)
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', D)
CX, CY = W // 2, int(H * 0.165)


def lum(px, x, y):
    r, g, b = px[x, y][:3]
    return (r + g + b) / 3.0


def radius(px, thresh=24):
    out = []
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        x, y, r = CX, CY, None
        while 0 <= x < W and 0 <= y < H:
            if lum(px, x, y) <= thresh:
                r = abs(x - CX) + abs(y - CY)
                break
            x += dx
            y += dy
        out.append(r if r is not None else -1)
    return out


def main():
    files = sorted(glob.glob(os.path.join(D, '*.png')))
    print('idx  t(s)   avg   ctr   R(right,left,down,up)')
    for i, f in enumerate(files):
        im = Image.open(f).convert('RGB')
        px = im.load()
        tot = n = 0
        for y in range(0, H, 12):
            for x in range(0, W, 12):
                tot += lum(px, x, y)
                n += 1
        avg = tot / n
        c = lum(px, CX, CY)
        rr = radius(px) if c > 24 else ['-'] * 4
        print('%-4d %5.2f %6.1f %5.1f  %s' % (i + 1, i / 12.0, avg, c, rr))


if __name__ == '__main__':
    main()