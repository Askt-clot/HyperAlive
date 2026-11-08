# -*- coding: utf-8 -*-
"""deploy.py 加固：install 后回读 LSPosed 库校验 apk_path；新增 restore 一键恢复。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\deploy.py'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n_expect=1):
    global s
    n = s.count(a)
    print('%d <- %r' % (n, a[:60].replace('\n', '\\n')))
    if n != n_expect:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("TMP_DEX = '/data/local/tmp/qihang_aod.dex'",
    "TMP_DEX = '/data/local/tmp/qihang_aod.dex'\nDB_PATH = '/data/adb/lspd/config/modules_config.db'")

rep("""def install():
    if not os.path.exists(APK):
        build()
    adb('install', '-r', '-d', APK)
    push_dex()
    path = apk_path()
    print('apk_path =', path)
    print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))""",
    """def db_state():
    \"\"\"回读 LSPosed 库，返回 {pkg: (apk_path, enabled)}。

    铁律：apk_path 过期时 system_server 侧模块**静默不加载**，
    症状 = 设壁纸报 Invalid package or package does not belong to uid:0、日志无 bypass 行。
    \"\"\"
    import base64
    import sqlite3
    raw = su('base64 ' + DB_PATH, check=False)
    try:
        data = base64.b64decode(''.join(raw.split()))
    except Exception:
        return {}
    tmp = os.path.join(ROOT, 'build', 'db_probe.sqlite')
    os.makedirs(os.path.dirname(tmp), exist_ok=True)
    with open(tmp, 'wb') as f:
        f.write(data)
    out = {}
    con = sqlite3.connect(tmp)
    q = ('select m.module_pkg_name, m.apk_path, s.enabled from modules m '
         'join modules_state s using(module_pkg_name)')
    for pkg, path, enabled in con.execute(q):
        out[pkg] = (path, enabled)
    con.close()
    return out


def install(verify='1'):
    if not os.path.exists(APK):
        build()
    adb('install', '-r', '-d', APK)
    push_dex()
    path = apk_path()
    print('apk_path =', path)
    print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))
    if str(verify) in ('0', 'no', 'false'):
        return True
    got = db_state().get(PKG)
    if not got or got[0] != path or not got[1]:
        print('!! LSPosed 库未同步：库里=%s 期望=(%s, 1) —— 重试一次' % (got, path))
        print(app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES))
        got = db_state().get(PKG)
    ok = bool(got) and got[0] == path and bool(got[1])
    print('LSPosed 库校验: %s -> %s' % (got, 'OK' if ok else '失败'))
    return ok


def restore():
    \"\"\"App 被系统/清理工具卸载后的一键恢复（安装 → 框架 → 白名单 → 壁纸槽位）。\"\"\"
    print('== 1/5 安装 + 同步 LSPosed 库 ==')
    install()
    print('== 2/5 省电白名单 ==')
    whitelist()
    print('== 3/5 重启框架（lspd → zygote）==')
    restart_framework()
    print('== 4/5 重设壁纸槽位（桌面=魅族 Alive，锁屏=本模块）==')
    setwall()
    print('== 5/5 状态 ==')
    state()""")

rep("""    'restart': restart_framework,""",
    """    'restart': restart_framework,
    'restore': restore,
    'dbstate': lambda: print(db_state()),""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')