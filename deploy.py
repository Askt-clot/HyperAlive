#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HyperAlive — 部署 / 调试脚本（Windows 主机 + adb + root）。
用法：
  python deploy.py install     构建 + 安装 + 设 LSPosed 作用域 + 重启 lspd/zygote
  python deploy.py ui          只装 APK 并启动界面（调 UI 时用，不必重启框架）
  python deploy.py setwall     设壁纸：锁屏=本模块，桌面=魅族 Alive
  python deploy.py mode 0|1|2  给壁纸发三态广播
  python deploy.py state       dumpsys wallpaper 摘要
  python deploy.py shot 名称   截屏到 shots/名称.png
  python deploy.py log [grep]  抓本模块日志（默认 400 行）
  python deploy.py disable-old 关掉旧模块（保留安装，仅停用）
  python deploy.py all         install + setwall + state
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
DB_PATH = '/data/adb/lspd/config/modules_config.db'

PKG = 'com.qihang.aod'
SELF_SVC = PKG + '.QihangWallpaper'
HOME_SVC = PKG + '.QihangHomeWallpaper'
ALIVE_PKG = 'com.flyme.alivewallpaper'
ALIVE_SVC = 'com.flyme.alive.CosmicWallpaperV6'
OLD_PKG = 'com.alive.wallpaper.bridge'
SCOPES = ['system', 'com.android.systemui', 'miui.systemui.plugin', 'com.miui.aod']


def run(args, check=True, quiet=False):
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = (r.stdout or '') + (r.stderr or '')
    if not quiet:
        print(out.strip())
    if check and r.returncode != 0:
        raise SystemExit('命令失败 (%d): %s' % (r.returncode, ' '.join(args[:3])))
    return out


def adb(*args, **kw):
    return run([ADB] + list(args), **kw)


def adb_root(*cmds):
    cmd = ' && '.join(cmds)
    return run([ADB, 'shell', 'su', '-c', cmd])


def cmd_build():
    print('=== 1. 构建 APK ===')
    r = subprocess.run([sys.executable, os.path.join(ROOT, 'build.py')], cwd=ROOT)
    if r.returncode != 0:
        raise SystemExit('构建失败')


def cmd_install():
    cmd_build()
    print('\n=== 2. 安装 APK ===')
    adb('install', '-r', '-d', APK)

    print('\n=== 3. 部署 classes.dex 到 /data/local/tmp/ ===')
    adb('push', DEX, TMP_DEX)
    adb_root('chmod 644 ' + TMP_DEX)

    print('\n=== 4. 配置 LSPosed 数据库 ===')
    enable_sql = (
        f"INSERT OR REPLACE INTO modules (module_pkg_name, enabled) VALUES ('{PKG}', 1);"
    )
    for s in SCOPES:
        enable_sql += f"INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name) VALUES ('{PKG}', '{s}');"
    adb_root(f"sqlite3 {DB_PATH} \"{enable_sql}\"")

    print('\n=== 5. 快速重启 SystemUI 与 lspd ===')
    adb_root(
        'pkill -9 -f lspd || true',
        'pkill -9 -f com.android.systemui || true'
    )
    print('\n[完成] 模块已安装并激活作用域。')


def cmd_ui():
    print('=== 安装并启动设置界面 ===')
    cmd_build()
    adb('install', '-r', '-d', APK)
    adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity')


def cmd_setwall():
    print('=== 设壁纸：锁屏=HyperAlive，桌面=魅族Alive ===')
    adb_root(
        f'am startservice -n {PKG}/.root.SetWall --es lock {SELF_SVC} --es home {ALIVE_SVC}'
    )


def cmd_mode(m):
    print(f'=== 发送三态广播 mode={m} ===')
    adb('shell', 'am', 'broadcast', '-a', 'com.qihang.aod.ACTION_WALLPAPER_MODE',
        '--ei', 'mode', str(m), '-f', '0x01000000')


def cmd_state():
    print('=== 当前壁纸状态 ===')
    adb('shell', 'dumpsys', 'wallpaper')


def cmd_shot(name='screenshot'):
    os.makedirs(SHOTS, exist_ok=True)
    dst = os.path.join(SHOTS, f'{name}.png')
    adb('shell', 'screencap', '-p', '/sdcard/tmp_shot.png')
    adb('pull', '/sdcard/tmp_shot.png', dst)
    adb('shell', 'rm', '/sdcard/tmp_shot.png')
    print(f'截图已保存: {dst}')


def cmd_log(grep=None):
    print('=== 抓取日志 ===')
    pattern = grep or 'Qihang|HyperAlive|FlymeClock'
    adb('shell', f'logcat -d -t 400 | grep -E "{pattern}"')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    act = sys.argv[1].lower()
    if act == 'build':
        cmd_build()
    elif act == 'install':
        cmd_install()
    elif act == 'ui':
        cmd_ui()
    elif act == 'setwall':
        cmd_setwall()
    elif act == 'mode':
        cmd_mode(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    elif act == 'state':
        cmd_state()
    elif act == 'shot':
        cmd_shot(sys.argv[2] if len(sys.argv) > 2 else 'screenshot')
    elif act == 'log':
        cmd_log(sys.argv[2] if len(sys.argv) > 2 else None)
    elif act == 'all':
        cmd_install()
        time.sleep(1)
        cmd_setwall()
        time.sleep(0.5)
        cmd_state()
    else:
        print('未知操作:', act)
        print(__doc__)


if __name__ == '__main__':
    main()
