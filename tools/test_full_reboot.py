#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = r'<ANDROID_SDK_PATH>\platform-tools\adb.exe'
SHOTS = os.path.join(ROOT, 'shots')

def run(args, check=True):
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = (r.stdout or '') + (r.stderr or '')
    if check and r.returncode != 0:
        print('命令失败 (%d): %s\n%s' % (r.returncode, ' '.join(args[:3]), out))
    return out

def adb(*args, **kw):
    return run([ADB] + list(args), **kw)

def su(cmd, **kw):
    return run([ADB, 'shell', 'su -c %s' % quote(cmd)], **kw)

def quote(s):
    return "'" + s.replace("'", "'\\''") + "'"

def main():
    print("==================================================")
    print("开始完整设备重启验证 (Full Device Cold Reboot Test)")
    print("==================================================")

    print(">> 1. 触发整机重启 (adb reboot)...")
    adb('reboot', check=False)

    print(">> 2. 等待设备断开与重连...")
    time.sleep(10)
    connected = False
    start_t = time.time()
    while time.time() - start_t < 150:
        st = adb('get-state', check=False).strip()
        if st == 'device':
            print(">> 设备已重连 (state=device)")
            connected = True
            break
        time.sleep(3)

    if not connected:
        print("!! 超时未检测到设备连接")
        return 1

    print(">> 3. 等待系统启动完成 (sys.boot_completed=1)...")
    booted = False
    start_t = time.time()
    while time.time() - start_t < 120:
        res = adb('shell', 'getprop sys.boot_completed', check=False).strip()
        if res == '1':
            print(">> 系统已完全就绪 (boot_completed=1)")
            booted = True
            break
        time.sleep(2)

    if not booted:
        print("!! 等待 boot_completed 超时")
        return 1

    print(">> 4. 等待 5 秒让开机自启服务稳定...")
    time.sleep(5)

    print(">> 5. 执行自动解锁 (autounlock)...")
    pin_file = os.path.join(ROOT, 'tools', 'pin.txt')
    pin = ''
    if os.path.exists(pin_file):
        with open(pin_file, 'r', encoding='utf-8') as f:
            pin = f.read().strip()
    cmd = [sys.executable, os.path.join(ROOT, 'tools', 'autounlock.py'), '--always', '--once']
    if pin:
        cmd.extend(['--pin', pin])
    run(cmd, check=False)
    time.sleep(3)

    print(">> 6. 检查壁纸槽位状态 (dumpsys wallpaper)...")
    wp_out = su("dumpsys wallpaper | grep -E 'mWallpaperComponent|mWhich|mSystemWasBoth|System|Lock' | head -20")
    print(wp_out.strip())

    has_home = "com.qihang.aod.QihangHomeWallpaper" in wp_out
    has_lock = "com.qihang.aod.QihangWallpaper" in wp_out
    has_image = "ImageWallpaper" in wp_out

    print(">> 槽位校验: QihangHomeWallpaper(which=1)=%s, QihangWallpaper(which=2)=%s" % (has_home, has_lock))

    print(">> 7. 检查拦截 com.miui.miwallpaper 日志与渲染线程状态...")
    log_out = adb('logcat', '-d', check=False)
    intercepted = [l for l in log_out.splitlines() if '成功拦截 com.miui.miwallpaper' in l]
    crashes = [l for l in log_out.splitlines() if '渲染线程异常' in l or 'IllegalStateException: SharedPreferences' in l]

    if intercepted:
        print(">> ★ 成功命中拦截记录 (%d 条):" % len(intercepted))
        for l in intercepted[:3]:
            print("   ", l.strip()[:140])
    else:
        print(">> (无静默重置触发或无需拦截)")

    if crashes:
        print("!! 警告: 发现渲染线程异常:")
        for l in crashes[:3]:
            print("   ", l.strip()[:140])
    else:
        print(">> ★ 零渲染线程崩溃！Direct Boot 完美运行！")

    print(">> 8. 测试熄屏进入 AOD (input keyevent 26)...")
    su("input keyevent 26")
    time.sleep(2)

    print(">> 9. 检查熄屏动画 (tr=3) 日志...")
    log_sleep = adb('logcat', '-d', check=False)
    sleep_lines = [l for l in log_sleep.splitlines() if 'tr=3' in l or 'mode=0' in l]
    for l in sleep_lines[-5:]:
        print("   ", l.strip()[:140])

    print(">> 10. 截取 AOD 画面留存凭证...")
    os.makedirs(SHOTS, exist_ok=True)
    shot_path = os.path.join(SHOTS, 'full_reboot_aod.png')
    su("screencap -p /sdcard/full_reboot_aod.png")
    adb('pull', '/sdcard/full_reboot_aod.png', shot_path, check=False)
    su("rm -f /sdcard/full_reboot_aod.png")
    print(">> 截屏已保存至:", shot_path)

    print(">> 11. 重新解锁屏幕...")
    run(cmd, check=False)
    print("==================================================")
    print("验证全部完成！")
    print("==================================================")
    return 0

if __name__ == '__main__':
    sys.exit(main())
