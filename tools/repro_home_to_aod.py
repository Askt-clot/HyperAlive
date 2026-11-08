#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""复现「桌面 → 息屏」：解锁 → 回桌面 → 录屏 → 按电源 → 拉取录屏与日志。
用法: python tools/repro_home_to_aod.py <tag>
输出: evidence/<tag>.mp4, evidence/<tag>.log
"""
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = r'<ANDROID_SDK_PATH>\platform-tools\adb.exe'
EVID = os.path.join(ROOT, 'evidence')


def sh(cmd, check=False):
    r = subprocess.run([ADB, 'shell', cmd], capture_output=True, text=True, encoding='utf-8', errors='replace')
    return (r.stdout or '') + (r.stderr or '')


def su(cmd):
    return sh("su -c '%s'" % cmd)


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else 'repro'
    os.makedirs(EVID, exist_ok=True)
    # 亮屏 + 解锁
    pin_file = os.path.join(ROOT, 'tools', 'pin.txt')
    cmd = [sys.executable, os.path.join(ROOT, 'tools', 'autounlock.py'), '--always', '--once']
    if os.path.exists(pin_file):
        cmd += ['--pin', open(pin_file, encoding='utf-8').read().strip()]
    subprocess.run(cmd)
    time.sleep(1.5)
    su('input keyevent KEYCODE_HOME')
    time.sleep(1.0)
    # 确保壁纸槽位恢复为双槽
    wp = sh("dumpsys wallpaper | grep mWallpaperComponent")
    if 'QihangHomeWallpaper' not in wp or 'QihangWallpaper' not in wp:
        su('CLASSPATH=/data/local/tmp/qihang_aod.dex app_process /system/bin com.qihang.aod.root.SetWall com.qihang.aod com.qihang.aod.QihangHomeWallpaper 1')
        time.sleep(1.0)
        su('CLASSPATH=/data/local/tmp/qihang_aod.dex app_process /system/bin com.qihang.aod.root.SetWall com.qihang.aod com.qihang.aod.QihangWallpaper 2')
        time.sleep(1.0)
    time.sleep(1.5)
    print(sh('dumpsys power | grep mWakefulness=').strip())
    sh('logcat -c')
    # 录屏（后台）
    remote = '/sdcard/%s.mp4' % tag
    rec = subprocess.Popen([ADB, 'shell', 'screenrecord --time-limit 6 --bit-rate 20000000 %s' % remote])
    time.sleep(1.2)
    su('input keyevent KEYCODE_POWER')
    rec.wait()
    time.sleep(1.0)
    subprocess.run([ADB, 'pull', remote, os.path.join(EVID, tag + '.mp4')], capture_output=True)
    sh('rm -f %s' % remote)
    log = sh('logcat -d -v threadtime -s QihangAod:V QihangWallpaper:V')
    with open(os.path.join(EVID, tag + '.log'), 'w', encoding='utf-8') as f:
        f.write(log)
    print('saved', tag)


if __name__ == '__main__':
    main()
