# -*- coding: utf-8 -*-
"""补：onCreate 里带 EXTRA_WP 进来（从壁纸库点壁纸）也要置 fromLibrary=true，否则动态壁纸不应用。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\EditorActivity.java'
s = io.open(P, encoding='utf-8').read()
a = """            desktopSource = wallpaper != null ? LastSet.KIND_DYNAMIC : LastSet.kind(this);"""
b = """            fromLibrary = wallpaper != null;      // 从壁纸库点进来 = 本次真的选了动态壁纸
            desktopSource = fromLibrary ? LastSet.KIND_DYNAMIC : LastSet.kind(this);"""
print(s.count(a))
if s.count(a) != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')