# -*- coding: utf-8 -*-
"""三态那一排与下面卡片之间加间距（原来只剩 3.7dp）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\MainActivity.java'
s = io.open(P, encoding='utf-8').read()

a = """        group("布局");
        col.addView(sceneTiles(), new LinearLayout.LayoutParams(-1, -2));"""
b = """        group("布局");
        // 三态这一排和下面的卡片要拉开一点（之前只剩 3.7dp，太挤）
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(-1, -2);
        tilesLp.bottomMargin = ui.dp(18);
        col.addView(sceneTiles(), tilesLp);"""
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')