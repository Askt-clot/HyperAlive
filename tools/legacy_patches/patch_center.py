# -*- coding: utf-8 -*-
"""动态壁纸卡片内容垂直居中：卡片（card_preference_layout）内部上下留白不对称，补 7dp 下内边距。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\MiSettingsUi.java'
s = io.open(P, encoding='utf-8').read()

a = """        box.setPadding(0, dp(6), 0, dp(6));"""
b = """        // 卡片（card_preference_layout）内部上下留白不对称：实测内容比卡片中心偏上 21px(7dp)，
        // 这里多补 7dp 下内边距，让「图标 + 文字」落在正中间。
        box.setPadding(0, dp(6), 0, dp(13));"""
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')