# -*- coding: utf-8 -*-
"""修正 lspd 判定：pgrep -f ' lspd$' 匹配不到（ps 列格式才有前导空格），改用 ps -A | grep -c。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\MainActivity.java'
s = io.open(P, encoding='utf-8').read()

a = """boolean lspd = !Root.su("pgrep -f ' lspd$' | head -1", 8000).trim().isEmpty();"""
b = """boolean lspd = !Root.su("ps -A | grep -c ' lspd$'", 8000).trim().equals("0");"""
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')