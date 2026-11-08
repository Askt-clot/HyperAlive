# -*- coding: utf-8 -*-
"""setMode：next == prev 时直接返回（否则 AOD 抖动会算出 prev=0 next=0 tr=2 这种假过渡）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\Motion.java'
s = io.open(P, encoding='utf-8').read()
a = """        int prev = mode;
        if (next == prev && !animating) {
            return;
        }"""
b = """        int prev = mode;
        if (next == prev) {
            // 同态重复事件（AOD↔KEYGUARD 抖动常见）直接忽略：否则 transition(0,0) 会算出
            // tr2 这种不存在的过渡，把正在播的动画打断成"立刻跳到目标态"。
            return;
        }"""
print(s.count(a))
if s.count(a) != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')