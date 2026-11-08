# -*- coding: utf-8 -*-
"""日志去掉 motion.mode()（Motion 没有该访问器）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LensRenderer.java'
s = io.open(P, encoding='utf-8').read()
a = """        Log.i(TAG, "预览 mode=" + (motion == null ? -1 : motion.mode())
                + " angle=" + (motion == null ? 0 : motion.photoAngle())
                + " fps=" + Math.round(previewFrames / sec));"""
b = """        Log.i(TAG, "预览 angle=" + (motion == null ? 0 : motion.photoAngle())
                + " fps=" + Math.round(previewFrames / sec));"""
print(s.count(a))
if s.count(a) != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')