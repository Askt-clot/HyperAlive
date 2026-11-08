# -*- coding: utf-8 -*-
"""rebuild_turn5.py 的两处补丁：Motion.java 的 HOME_BLUR 与它的说明注释。"""
import io
import os
import re

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '..', 'backup', '20261024_turn5_rebuilt', 'module', 'src', 'com', 'qihang', 'aod')
p = os.path.join(SRC, 'Motion.java')
s = io.open(p, encoding='utf-8').read()

# 1) 删掉 turn6 加的那段“桌面态 blur 取 0”的说明注释（措辞与前一轮正则不一致才漏掉）
s, n1 = re.subn(r'\n    /\*\*\n     \* 桌面态 blur。.*?\*/\n(?=    static final float HOME_BLUR)',
                '\n', s, count=1, flags=re.DOTALL)
# 2) 值改回官方 16
n2 = s.count('static final float HOME_BLUR = 0f;')
s = s.replace('static final float HOME_BLUR = 0f;', 'static final float HOME_BLUR = 16f;', 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('注释块删除: %d 处' % n1)
print('HOME_BLUR 改回 16: %d 处' % n2)