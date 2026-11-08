#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""手机重启后自动解锁（PC 端守护脚本）——省掉每次开机手输密码。

原理：整机重启后设备处于 BFU（Before First Unlock），`dumpsys window` 里
`isKeyguardShowing=true`，此时只能输密码。本脚本轮询检测到这种状态后，
用 adb 唤醒屏幕 → 上滑 → 输入密码 → 回车，并校验是否已进入桌面。

用法：
    python autounlock.py                 # 只在「重启后」自动解锁（默认）
    python autounlock.py --always        # 平时熄屏亮起也自动解锁
    python autounlock.py --pin 123456    # 不使用 pin.txt，直接给密码
    python autounlock.py --interval 3    # 轮询间隔秒（默认 3）

密码来源：优先 --pin，其次同目录 pin.txt（第一行，UTF-8）。**明文存放，只放在你这台电脑上。**
"""
from __future__ import print_function

import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ADB = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"


def adb_path():
    if os.path.exists(DEFAULT_ADB):
        return DEFAULT_ADB
    return "adb"


def sh(args, timeout=20):
    try:
        p = subprocess.run([adb_path()] + args, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=timeout)
        return p.stdout.decode("utf-8", "replace")
    except Exception as e:  # adb 掉了/设备不在
        return "ERR %s" % e


def shell(cmd, timeout=20, root=False):
    """root=True 时走 su（input 事件在部分 ROM 上需要 root）。"""
    if root:
        return sh(["shell", "su -c \"%s\"" % cmd], timeout)
    return sh(["shell", cmd], timeout)


def keyguard_state():
    """返回 (是否存在锁屏, 是否 BFU 受限)。"""
    out = sh(["shell", "dumpsys window"])
    showing = bool(re.search(r"isKeyguardShowing=true", out))
    restricted = bool(re.search(r"mInputRestricted=true", out))
    return showing, restricted


def at_launcher():
    out = sh(["shell", "dumpsys activity activities"])
    m = re.search(r"topResumedActivity=.*?\s([\w\.]+)/", out)
    return m.group(1) if m else ""


def unlock(pin, log=print):
    log("检测到锁屏，开始自动解锁 …")
    shell("input keyevent 224", root=True)          # 唤醒
    time.sleep(1.2)
    shell("input swipe 600 1800 600 600 250", root=True)   # 上滑出密码框
    time.sleep(1.5)
    shell("input text %s" % pin, root=True)
    time.sleep(0.8)
    shell("input keyevent 66", root=True)           # 回车
    time.sleep(2.5)
    showing, _ = keyguard_state()
    if showing:
        # 有些机型要先点一下密码框再输
        shell("input keyevent 82", root=True)
        time.sleep(1.0)
        shell("input text %s" % pin, root=True)
        time.sleep(0.6)
        shell("input keyevent 66", root=True)
        time.sleep(2.5)
        showing, _ = keyguard_state()
    ok = not showing
    log("解锁%s（前台=%s）" % ("成功" if ok else "失败", at_launcher()))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pin")
    ap.add_argument("--always", action="store_true",
                    help="平时熄屏亮起也自动解锁（默认只在重启后）")
    ap.add_argument("--interval", type=float, default=3.0)
    ap.add_argument("--once", action="store_true", help="只检查/解锁一次就退出")
    args = ap.parse_args()

    pin = args.pin
    if not pin:
        f = os.path.join(HERE, "pin.txt")
        if os.path.exists(f):
            with open(f, encoding="utf-8") as fh:
                pin = fh.readline().strip()
    if not pin:
        print("没有密码：请放一个 %s，或用 --pin" % os.path.join(HERE, "pin.txt"))
        return 2

    print("守护启动：%s（%s）" % (adb_path(), "含普通锁屏" if args.always else "仅重启后"))
    done_for_boot = False
    while True:
        show, restricted = keyguard_state()
        if show and (args.always or restricted):
            if not (restricted and done_for_boot):
                if unlock(pin):
                    if restricted:
                        done_for_boot = True
        elif not show and restricted is False:
            done_for_boot = False   # 已经解锁/正常使用，等下次重启
        if args.once:
            return 0
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())