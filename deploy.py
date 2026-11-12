#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HyperAlive (qihang_lsp) —— 部署 / 运维 / 联调脚本（Windows 主机 + adb + root）。

用法：
  python deploy.py build       只执行构建
  python deploy.py install     构建 + 安装 + 注册 LSPosed 作用域 + 重启 lspd/zygote
  python deploy.py setwall     设置单槽位统一壁纸（which 3 = 桌面 + 锁屏一体化无缝衔接，根除双槽切换黑闪）
  python deploy.py unlock      解锁屏幕
  python deploy.py mode 0|1|2  给壁纸发三态广播（0=AOD, 1=LOCK, 2=HOME）
  python deploy.py state       查看当前壁纸槽位状态
  python deploy.py ui          启动模块设置界面（MainActivity）
  python deploy.py shot 名称   截屏到 shots/名称.png
  python deploy.py log [grep]  实时抓取 HyperAlive 核心日志
  python deploy.py all         一键执行：build + install + setwall + unlock + state
"""
import os
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
ADB = r'<ANDROID_SDK_PATH>\platform-tools\adb.exe'
APK = os.path.join(ROOT, 'HyperAlive.apk')
DEX = os.path.join(ROOT, 'build', 'cli_out', 'dex', 'classes.dex')
SHOTS = os.path.join(ROOT, 'shots')
TMP_DEX = '/data/local/tmp/qihang_aod.dex'

PKG = 'com.qihang.aod'
SELF_SVC = PKG + '.QihangWallpaper'
SCOPES = ['system', 'com.android.systemui', 'miui.systemui.plugin', 'com.miui.aod', 'com.android.thememanager', 'com.qihang.aod']


def run(args, check=True, quiet=False):
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = (r.stdout or '') + (r.stderr or '')
    if not quiet and out.strip():
        print(out.strip())
    if check and r.returncode != 0:
        raise SystemExit('命令失败 (%d): %s' % (r.returncode, ' '.join(args[:3])))
    return out


def adb(*args, **kw):
    return run([ADB] + list(args), **kw)


def shell(cmd, **kw):
    return adb('shell', cmd, **kw)


def su(cmd, **kw):
    return adb('shell', 'su -c %s' % quote(cmd), **kw)


def quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def push_dex():
    adb('push', DEX, TMP_DEX, quiet=True)
    su('chmod 644 %s' % TMP_DEX, quiet=True)
    font_src = os.path.join(ROOT, 'module', 'assets', 'fonts', 'FlymeNumber-VF.ttf')
    if os.path.exists(font_src):
        adb('push', font_src, '/data/local/tmp/FlymeNumber-VF.ttf', quiet=True)
        su('chmod 666 /data/local/tmp/FlymeNumber-VF.ttf', quiet=True)


def sync_de_storage():
    print('>> 同步 Device Protected 存储 (保证开机 Direct Boot 可用)...')
    su('mkdir -p /data/user_de/0/com.qihang.aod/files /data/user_de/0/com.qihang.aod/shared_prefs', quiet=True)
    su('cp -rf /sdcard/Android/data/com.qihang.aod/files/* /data/user_de/0/com.qihang.aod/files/ 2>/dev/null || true', quiet=True)
    su('cp -rf /data/user/0/com.qihang.aod/shared_prefs/* /data/user_de/0/com.qihang.aod/shared_prefs/ 2>/dev/null || true', quiet=True)
    su('chown -R $(stat -c %u:%g /data/user_de/0/com.qihang.aod) /data/user_de/0/com.qihang.aod', quiet=True)
    su('chmod -R 775 /data/user_de/0/com.qihang.aod', quiet=True)
    su('chcon -R $(stat -c %C /data/user_de/0/com.qihang.aod) /data/user_de/0/com.qihang.aod 2>/dev/null || true', quiet=True)


def app_process(main_class, *args):
    cmd = 'CLASSPATH=%s app_process /system/bin %s %s' % (TMP_DEX, main_class, ' '.join(args))
    return su(cmd)


def apk_path():
    out = shell('pm path %s' % PKG)
    for line in out.splitlines():
        if line.startswith('package:'):
            return line.split(':', 1)[1].strip()
    raise SystemExit('未安装 %s' % PKG)


def cmd_build():
    print('>> 执行项目构建...')
    run([sys.executable, os.path.join(ROOT, 'build.py')])


def db_state():
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


def cmd_install(verify='1'):
    cmd_build()
    print('\n>> 安装 APK 到设备...')
    adb('install', '-r', '-d', APK)
    push_dex()
    path = apk_path()
    print('>> APK 安装路径:', path)

    print('>> 注册 LSPosed 作用域...')
    app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES)
    sync_de_storage()

    lspd_script = os.path.join(ROOT, 'tools', 'lspd_start.sh')
    if os.path.exists(lspd_script):
        adb('push', lspd_script, '/data/local/tmp/lspd_start.sh', quiet=True)
    
    print('>> 重启 zygote 与 lspd 生效 hook...')
    su('sh /data/local/tmp/lspd_start.sh --restart', quiet=True)
    su('setprop ctl.restart zygote')

    print('>> 等待系统就绪...')
    time.sleep(3)
    deadline = time.time() + 150
    while time.time() < deadline:
        time.sleep(2)
        out = shell('getprop sys.boot_completed', quiet=True).strip()
        if '1' in out:
            print('>> 系统已重启完成 (boot_completed=1)')
            break
    time.sleep(3)

    if str(verify) not in ('0', 'no', 'false'):
        got = db_state().get(PKG)
        ok = bool(got) and got[0] == path and bool(got[1])
        print('>> LSPosed 作用域校验: %s -> %s' % (got, 'OK' if ok else '需重试'))


def cmd_setwall():
    push_dex()
    print('\n>> 设置单槽位统一壁纸（which 3 = 桌面 + 锁屏一体化无缝衔接，根除双槽切换黑闪）...')
    time.sleep(1)
    res = app_process('com.qihang.aod.root.SetWall', PKG, SELF_SVC, '3')
    print('>> SetWall 返回:', res.strip())
    su('dumpsys deviceidle whitelist +%s' % PKG, quiet=True)
    cmd_state()


def cmd_mode(m):
    print('>> 广播发送三态指令 mode=%s...' % m)
    shell('am broadcast -a com.qihang.aod.CMD -p %s --ei mode %s --ez animate true -f 0x10000000' % (PKG, m))


def cmd_state():
    print('\n>> 当前壁纸状态:')
    out = su("dumpsys wallpaper | grep -E 'mWallpaperComponent|mWhich|mSystemWasBoth|System|Lock' | head -20")
    print(out.strip())


def cmd_unlock():
    print('>> 执行屏幕自动解锁...')
    pin_file = os.path.join(ROOT, 'tools', 'pin.txt')
    pin = ''
    if os.path.exists(pin_file):
        with open(pin_file, 'r', encoding='utf-8') as f:
            pin = f.read().strip()
    cmd = [sys.executable, os.path.join(ROOT, 'tools', 'autounlock.py'), '--always', '--once']
    if pin:
        cmd.extend(['--pin', pin])
    run(cmd)


def cmd_ui():
    print('>> 启动设置界面...')
    shell('am start -n %s/.MainActivity' % PKG)


def cmd_shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    remote = '/sdcard/%s.png' % name
    disp = ''
    try:
        out = su('dumpsys SurfaceFlinger --display-id', quiet=True)
        m = re.search(r'Display (\d+).*?HWC display 0', out)
        if m:
            disp = '-d ' + m.group(1) + ' '
    except Exception as e:
        pass
    su('screencap %s-p %s' % (disp, remote), quiet=True)
    su('chmod 666 %s' % remote, quiet=True)
    dst = os.path.join(SHOTS, '%s.png' % name)
    adb('pull', remote, dst, quiet=True)
    su('rm -f %s' % remote, quiet=True)
    print('已保存截屏:', dst)


def cmd_log(filter_word=''):
    print('>> 开始抓取日志 (Ctrl+C 退出)...')
    try:
        p = subprocess.Popen([ADB, 'logcat', '-v', 'threadtime', '-s', 'QihangAod:V', 'QihangWallpaper:V', 'LensRenderer:V', 'Motion:V', '*:S'],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        for line in p.stdout:
            if not filter_word or filter_word in line:
                print(line, end='', flush=True)
    except KeyboardInterrupt:
        pass


def cmd_all():
    cmd_install()
    cmd_setwall()
    cmd_unlock()
    cmd_state()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    arg = sys.argv[1]
    if arg == 'build':
        cmd_build()
    elif arg == 'install':
        cmd_install()
    elif arg == 'setwall':
        cmd_setwall()
    elif arg == 'unlock':
        cmd_unlock()
    elif arg == 'mode':
        cmd_mode(sys.argv[2] if len(sys.argv) > 2 else '1')
    elif arg == 'state':
        cmd_state()
    elif arg == 'ui':
        cmd_ui()
    elif arg == 'shot':
        cmd_shot(sys.argv[2] if len(sys.argv) > 2 else 'screen')
    elif arg == 'log':
        cmd_log(sys.argv[2] if len(sys.argv) > 2 else '')
    elif arg == 'all':
        cmd_all()
    else:
        print('未知命令:', arg)
        print(__doc__)


if __name__ == '__main__':
    main()
