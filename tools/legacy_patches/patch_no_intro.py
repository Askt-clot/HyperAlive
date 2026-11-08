# -*- coding: utf-8 -*-
"""预览首次 attach 用静稳态（不重播入场动画）；用户切场景才播过渡。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LivePreviewView.java'
s = io.open(P, encoding='utf-8').read()
a = """        // 熄屏要像真机一样持续动（启航圆内照片 40°/s 自转），其余场景直接落位
        renderer.setMode(mode, mode == Motion.AOD);"""
b = """        // 首次落位一律用静稳态：熄屏靠 speed=1 持续自转，但不重播入场动画
        //（否则每次进页面都"嗖"一下，看起来比编辑器里转得快）。用户切场景才播过渡。
        renderer.setMode(mode, false);"""
print(s.count(a))
if s.count(a) != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')