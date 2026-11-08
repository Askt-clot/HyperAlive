# -*- coding: utf-8 -*-
"""restart_framework 改为强制重启 lspd（否则新作用域不生效、SystemUI 不加载模块）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\deploy.py'
s = io.open(P, encoding='utf-8').read()

a = """    su('sh /data/local/tmp/lspd_start.sh', check=False)"""
b = """    # 必须 --restart：lspd 会把模块表缓存在内存里，改过库（apk_path/作用域）后不重启它，
    # 新起的进程（尤其 SystemUI）就不会加载模块。
    su('sh /data/local/tmp/lspd_start.sh --restart', check=False)"""
n = s.count(a)
print(n)
if n != 1:
    raise SystemExit('匹配数不对')
io.open(P, 'w', encoding='utf-8', newline='\n').write(s.replace(a, b))
print('ok')