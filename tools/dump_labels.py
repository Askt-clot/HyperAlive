# -*- coding: utf-8 -*-
"""列出 dump 里有文本/描述的节点（排查相册选择器用）。"""
import io
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r'build\ui.xml'
s = io.open(path, encoding='utf-8', errors='replace').read()
n = 0
for m in re.finditer(r'<node[^>]*>', s):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag)
    d = re.search(r'content-desc="([^"]*)"', tag)
    c = re.search(r'class="([^"]*)"', tag)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    label = (t.group(1) if t else '') or (d.group(1) if d else '')
    if not label.strip() or not b:
        continue
    print('%-28s %-34s x=%s-%s y=%s-%s' % ((c.group(1) if c else '?').split('.')[-1],
                                           label[:32], b.group(1), b.group(3), b.group(2), b.group(4)))
    n += 1
    if n >= 25:
        break
print('共 %d 个带标签节点' % n)