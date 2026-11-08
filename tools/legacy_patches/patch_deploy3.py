# -*- coding: utf-8 -*-
"""install() 里同步完库立刻重启 lspd——否则 lspd 会用内存里的旧表把 DB 覆盖回去。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\deploy.py'
s = io.open(P, encoding='utf-8').read()

a = """    path = apk_path()
    print('apk_path =', path)
    print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))
    if str(verify) in ('0', 'no', 'false'):"""
b = """    path = apk_path()
    print('apk_path =', path)
    print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))
    # 关键：lspd 会把内存里的模块表回写覆盖 DB，写完必须让它重启（用新表重新加载）
    lspd_script = os.path.join(ROOT, 'tools', 'lspd_start.sh')
    if os.path.exists(lspd_script):
        adb('push', lspd_script, '/data/local/tmp/lspd_start.sh', quiet=True)
        su('sh /data/local/tmp/lspd_start.sh --restart', check=False)
    if str(verify) in ('0', 'no', 'false'):"""
n = s.count(a)
print('anchor', n)
if n != 1:
    raise SystemExit('匹配数不对')
s = s.replace(a, b)

a2 = """        print('!! LSPosed 库未同步：库里=%s 期望=(%s, 1) —— 重试一次' % (got, path))
        print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))
        got = db_state().get(PKG)"""
b2 = """        print('!! LSPosed 库未同步：库里=%s 期望=(%s, 1) —— 再写一次并重启 lspd' % (got, path))
        print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))
        su('sh /data/local/tmp/lspd_start.sh --restart', check=False)
        got = db_state().get(PKG)"""
n2 = s.count(a2)
print('anchor2', n2)
if n2 != 1:
    raise SystemExit('匹配数不对 2')
s = s.replace(a2, b2)

# db_state 的 base64 输出别刷屏
s = s.replace("raw = su('base64 ' + DB_PATH, check=False)",
              "raw = su('base64 ' + DB_PATH, check=False, quiet=True)")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')