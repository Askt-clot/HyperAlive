# -*- coding: utf-8 -*-
"""预览里 熄屏→桌面 过渡中期的清晰度检查（应该已经变清晰，而不是糊到动画结束）。"""
import subprocess
import time

import numpy as np
from PIL import Image

ADB = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"
SHOTS = r"D:\DSH\default-workspace-2\qihang_lsp\shots"


def su(cmd):
    subprocess.run([ADB, "shell", "su", "-c", cmd], capture_output=True)


def shot(name):
    p = SHOTS + "\\" + name + ".png"
    with open(p, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f)
    return p


def sharp(p):
    a = np.asarray(Image.open(p).convert("L").crop((100, 400, 1100, 1300)), dtype=np.float32)
    lap = (a[1:-1, 1:-1] * 4 - a[:-2, 1:-1] - a[2:, 1:-1] - a[1:-1, :-2] - a[1:-1, 2:])
    return float(lap.std())


su("am start -S -n com.qihang.aod/.EditorActivity --ei scene 0")
time.sleep(7)
t0 = time.time()
su("input tap 831 2079")            # 胶囊「桌面」→ 触发 tr2
mid = shot("pv_mid")                # 截图本身有 ~0.3s 延迟，落在过渡中段
t_mid = time.time() - t0
time.sleep(2.0)
end = shot("pv_end")                # 稳态对比
print("过渡中截图 拍摄时刻≈%.2fs  锐度=%.2f" % (t_mid, sharp(mid)))
print("稳态截图                   锐度=%.2f" % sharp(end))