# -*- coding: utf-8 -*-
"""列出所有可点击节点（不限大小），排查选择器确认键。"""
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
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    c = re.search(r'class="([^"]*)"', tag)
    t = re.search(r'text="([^"]*)"', tag)
    d = re.search(r'content-desc="([^"]*)"', tag)
    if not b:
        continue
    label = ((t.group(1) if t else '') or (d.group(1) if d else ''))[:30]
    print('%-22s %-30s x=%s-%s y=%s-%s' % ((c.group(1) if c else '?').split('.')[-1], label,
                                           b.group(1), b.group(3), b.group(2), b.group(4)))
    n += 1
    if n >= 20:
        break
print('可点击节点 %d 个' % n)