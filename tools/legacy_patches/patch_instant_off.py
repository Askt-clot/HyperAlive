# -*- coding: utf-8 -*-
"""按用户要求改回来：按键立刻开始缩放（不等清底），同时把"清黑底"做成熄屏后 1.5s 内 60ms 快扫，
让熄屏尽可能早亮起 —— 于是可见部分是缩放的尾段，且不用额外等待。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:44].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:90])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ① hook：mode=0 立即广播（撤掉"等清底"的延迟）；快扫改为"熄屏后前 25 轮 60ms"
patch('XposedInit.java', [
    ("""        if (mode == 0) {
            // ★ 熄屏后系统有约 0.5s 黑屏（AOD 窗口黑底还没清掉）。这时就让引擎收缩，
            //   收缩动画会在黑屏里播完，等熄屏露出来只剩圆圈。改成：先快速清黑底，
            //   **清掉的那一刻**才广播 mode=0 —— 缩回动画才看得见。兜底 700ms 必发。
            sSweepCtx = ctx;
            sPendingAodMode = true;
            startSweep(sAodRoot);
            if (sMain != null && sAodFallback != null) {
                sMain.removeCallbacks(sAodFallback);
            }
            sAodFallback = new Runnable() {
                @Override
                public void run() {
                    if (sPendingAodMode) {
                        sPendingAodMode = false;
                        log("AOD 兜底广播（黑底未清掉）");
                        broadcastMode(sSweepCtx, 0, "doze-fallback");
                    }
                }
            };
            if (sMain != null) {
                sMain.postDelayed(sAodFallback, 700);
            }
        } else {
            stopSweep();
            if (sPendingAodMode) {
                sPendingAodMode = false;
                if (sMain != null && sAodFallback != null) {
                    sMain.removeCallbacks(sAodFallback);
                }
            }
            broadcastMode(ctx, mode, doze ? "doze" : sStateName);
        }""",
     """        if (mode == 0) {
            // 按用户要求：**按键立刻开始缩放**（不等清底），缩放覆盖那段系统黑屏，
            // 所以熄屏亮起时看到的是缩放的尾段，且不用额外等待。
            sSweepCtx = ctx;
            broadcastMode(ctx, 0, "doze");
            startSweep(sAodRoot);
        } else {
            stopSweep();
            broadcastMode(ctx, mode, doze ? "doze" : sStateName);
        }"""),

    ("""                if (sPendingAodMode && cleared > 0) {
                    sPendingAodMode = false;
                    if (sMain != null && sAodFallback != null) {
                        sMain.removeCallbacks(sAodFallback);
                    }
                    log("AOD 黑底已清 → 通知引擎进息屏（收缩动画此刻开始）");
                    broadcastMode(sSweepCtx, 0, "doze");
                }
                if (sSweep != null) {
                    // 等黑底清掉期间快扫（60ms），清完回到 500ms 省电
                    sMain.postDelayed(this, sPendingAodMode ? 60 : 500);
                }""",
     """                if (cleared > 0) {
                    log("AOD 黑底已清（第 " + sSweepCount + " 轮）");
                }
                if (sSweep != null) {
                    // 熄屏后前 ~1.5s 用 60ms 快扫，尽量让熄屏早亮起；之后 500ms 省电
                    sMain.postDelayed(this, sSweepCount < 25 ? 60 : 500);
                }"""),
])

# ② 引擎：熄屏方向立刻进息屏（撤掉 900ms 兜底等待）
patch('QihangWallpaper.java', [
    ("""            if (dozing) {
                // 熄屏方向**不立刻收缩**：系统还要约 0.5s 才把 AOD 露出来，立刻收缩会在黑屏里播完。
                // 优先等 hook 的 mode=0（黑底清掉那一刻发），900ms 兜底。
                if (cur != Motion.AOD && !aodPending) {
                    aodPending = true;
                    if (aodHandler == null) {
                        aodHandler = new Handler(Looper.getMainLooper());
                    }
                    aodHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            aodPending = false;
                            if (renderer != null && renderer.currentMode() != Motion.AOD) {
                                renderer.setMode(Motion.AOD, true);
                                Log.i(TAG, "显示直驱 → 息屏（900ms 兜底，hook 未通知）");
                            }
                        }
                    }, 900);
                }
            } else if (cur == Motion.AOD) {
                aodPending = false;""",
     """            if (dozing) {
                // 按用户要求：按键立刻开始缩放（不等系统把熄屏露出来）。
                if (cur != Motion.AOD) {
                    renderer.setMode(Motion.AOD, true);
                    Log.i(TAG, "显示直驱 → 息屏 displayState=" + st + "（按键即缩放）");
                }
            } else if (cur == Motion.AOD) {
                aodPending = false;"""),
])
print('ok')