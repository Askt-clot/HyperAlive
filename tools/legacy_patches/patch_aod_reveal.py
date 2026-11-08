# -*- coding: utf-8 -*-
"""锁屏→熄屏的缩回动画要看得见：
系统熄屏后约 0.5s 是黑屏（AOD 窗口黑底未清），原来此刻就通知引擎进息屏 → 收缩动画在黑屏里播完。
改成：hook 侧先快速清黑底（60ms 一轮），**清掉的那一刻**才广播 mode=0；兜底 700ms。
引擎侧：显示直驱的"熄屏"方向也延后（900ms 兜底），优先等 hook 的通知。"""
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


patch('XposedInit.java', [
    ("""    private static int sClearedLogged;""",
     """    private static int sClearedLogged;
    /** 息屏通知待发：等 AOD 黑底被清掉（= 熄屏真正露出来）再通知引擎收缩。 */
    private static boolean sPendingAodMode;
    private static android.content.Context sSweepCtx;
    private static Runnable sAodFallback;"""),

    ("""        sLastMode = mode;
        log("-> mode=" + mode + " (scrim=" + sStateName + " doze=" + doze + ")");
        broadcastMode(ctx, mode, doze ? "doze" : sStateName);
        broadcastAlive(ctx, mode);
        if (mode == 0) {
            startSweep(sAodRoot);
        } else {
            stopSweep();
        }""",
     """        sLastMode = mode;
        log("-> mode=" + mode + " (scrim=" + sStateName + " doze=" + doze + ")");
        broadcastAlive(ctx, mode);
        if (mode == 0) {
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
        }"""),

    ("""                if (sSweep != null) {
                    sMain.postDelayed(this, 500);
                }""",
     """                if (sPendingAodMode && cleared > 0) {
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
                }"""),
])

patch('QihangWallpaper.java', [
    ("""    private android.hardware.display.DisplayManager.DisplayListener displayListener;""",
     """    private android.hardware.display.DisplayManager.DisplayListener displayListener;
    private Handler aodHandler;
    private boolean aodPending;"""),

    ("""            if (dozing) {
                if (cur != Motion.AOD) {
                    renderer.setMode(Motion.AOD, true);
                    Log.i(TAG, "显示直驱 → 息屏 displayState=" + st);
                }
            } else if (cur == Motion.AOD) {""",
     """            if (dozing) {
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
                aodPending = false;"""),
])
print('ok')