#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HyperAlive 1.1 — 部署 / 运维 / 联调脚本（Windows 主机 + adb + root）

用法：
  python deploy.py build       只执行构建
  python deploy.py install     构建 + 安装 + 注册 LSPosed 作用域 + 重启 lspd/zygote
  python deploy.py setwall     设置双槽位壁纸（桌面=QihangHomeWallpaper, 锁屏=QihangWallpaper）
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
HOME_SVC = PKG + '.QihangHomeWallpaper'
SCOPES = ['system', 'com.android.systemui', 'miui.systemui.plugin', 'com.miui.aod']


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
    return run([ADB, 'shell', cmd], **kw)


def su(cmd, **kw):
    return run([ADB, 'shell', 'su', '-c', cmd], **kw)


def app_process(cls, *args, **kw):
    path = apk_path()
    cp = f'{path}:{TMP_DEX}' if path else TMP_DEX
    arg_str = ' '.join(f'"{a}"' for a in args)
    return su(f'CLASSPATH={cp} app_process /system/bin {cls} {arg_str}', **kw)


def apk_path():
    out = shell(f'pm path {PKG}', check=False, quiet=True).strip()
    for line in out.splitlines():
        if line.startswith('package:'):
            return line.split('package:', 1)[1].strip()
    return ''


def push_dex():
    if os.path.isfile(DEX):
        adb('push', DEX, TMP_DEX, quiet=True)
        su(f'chmod 644 {TMP_DEX}', quiet=True)


def cmd_build():
    r = subprocess.run([sys.executable, os.path.join(ROOT, 'build.py')], cwd=ROOT)
    if r.returncode != 0:
        raise SystemExit(r.returncode)


def cmd_install(verify='1'):
    cmd_build()
    print('\n>> 安装 APK 到设备...')
    adb('install', '-r', '-d', APK)
    push_dex()
    path = apk_path()
    print('>> APK 安装路径:', path)

    print('>> 注册 LSPosed 作用域...')
    app_process('com.qihang.aod.root.LspDb', path, PKG, *SCOPES)

    print('>> 重启 zygote 与 lspd 生效 hook...')
    su('pkill -9 -f lspd || true', quiet=True)
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
        time.sleep(2)
        cmd_verify()


def cmd_verify():
    out = app_process('com.qihang.aod.root.LspDb', 'query', PKG, check=False, quiet=True).strip()
    print('>> LSPosed 作用域校验:', out)


def cmd_ui():
    shell(f'am start -n {PKG}/.MainActivity')


def cmd_setwall():
    print('>> 正在通过 root.SetWall 绑定双槽位壁纸...')
    app_process('com.qihang.aod.root.SetWall', PKG, HOME_SVC, SELF_SVC, 'dual')
    print('>> 壁纸绑定完成！')


def cmd_mode(m):
    shell(f'am broadcast -a com.qihang.aod.ACTION_WALLPAPER_MODE --ei mode {m} -f 0x01000000')


def cmd_state():
    shell('dumpsys wallpaper | grep -iE "mWallpaperComponent|mWidth|mHeight"')


def cmd_shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    out = os.path.join(SHOTS, f'{name}.png')
    shell('screencap -p /sdcard/s.png')
    adb('pull', '/sdcard/s.png', out, quiet=True)
    shell('rm /sdcard/s.png', quiet=True)
    print(f'>> 截图保存到: {out}')


def cmd_log(grep=None):
    filt = f'| grep -iE "{grep}"' if grep else ''
    os.system(f'"{ADB}" logcat -v time -s QihangAod:V QihangWall:V QihangHome:V QihangLSP:V {filt}')


def cmd_unlock():
    shell('input keyevent 224')
    time.sleep(0.3)
    shell('input keyevent 82')


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    cmd = args[0]
    if cmd == 'build':
        cmd_build()
    elif cmd == 'install':
        cmd_install(*args[1:])
    elif cmd == 'verify':
        cmd_verify()
    elif cmd == 'ui':
        cmd_ui()
    elif cmd == 'setwall':
        cmd_setwall()
    elif cmd == 'mode':
        cmd_mode(args[1] if len(args) > 1 else '1')
    elif cmd == 'state':
        cmd_state()
    elif cmd == 'unlock':
        cmd_unlock()
    elif cmd == 'shot':
        cmd_shot(args[1] if len(args) > 1 else 'shot')
    elif cmd == 'log':
        cmd_log(args[1] if len(args) > 1 else None)
    elif cmd == 'all':
        cmd_install()
        cmd_setwall()
        cmd_unlock()
        cmd_state()
    else:
        print('未知命令:', cmd)
        print(__doc__)


if __name__ == '__main__':
    main()
