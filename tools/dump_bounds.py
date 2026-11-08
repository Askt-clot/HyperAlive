# -*- coding: utf-8 -*-
"""从 uiautomator dump 里打印关键控件的 bounds。"""
import io
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r'build\ui.xml'
s = io.open(path, encoding='utf-8', errors='replace').read()
keys = ('息屏', '锁屏', '桌面', '取消', '应用', '壁纸', '纹理', '特效', '跟随锁屏', '风格',
        '动态壁纸', '完成', '确定', 'Done', '原图', '编辑')
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s):
    t = m.group(1).strip()
    if any(k == t or k in t for k in keys):
        print('%-8s x=%s-%s y=%s-%s' % (t, m.group(2), m.group(4), m.group(3), m.group(5)))