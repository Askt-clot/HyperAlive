# -*- coding: utf-8 -*-
"""db_state 改为设备侧读（避免 WAL 导致的旧快照误判）。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\deploy.py'
s = io.open(P, encoding='utf-8').read()

start = s.index('def db_state():')
end = s.index('def install(')
new = '''def db_state():
    """读回 LSPosed 库里的模块状态（**设备侧读**）。

    这个库是 WAL 模式：只把主 .db 拷到 PC 打开会看到旧快照（-wal 里的新数据看不到），
    所以走 LspDb -get 在设备上读。返回 {pkg: (apk_path, enabled)}。
    """
    out = app_process('com.qihang.aod.root.LspDb', '-get', PKG)
    state = {}
    for line in out.splitlines():
        line = line.strip()
        if ' path=' not in line:
            continue
        path = line.split(' path=', 1)[1].split(' enabled=')[0].strip()
        try:
            enabled = int(line.split(' enabled=', 1)[1].split(' scope=')[0].strip())
        except Exception:
            enabled = -1
        state[PKG] = (path, enabled)
    return state


'''
s = s[:start] + new + s[end:]
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')