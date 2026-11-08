# -*- coding: utf-8 -*-
"""壁纸引擎自带 doze 看门狗：scrim 广播 + 自己查显示状态折算出最终模式。"""
import io
import os

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\QihangWallpaper.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    n = s.count(a)
    print('%d <- %r' % (n, a[:60].replace('\n', '\\n')))
    if n != 1:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


# 1) 字段 + 看门狗
rep("""    private byte[][] snapChunks;""",
    """    private int lastScrimMode = Motion.LOCK;
    private Runnable dozeWatch;

    /** 自己看显示状态：doze 时无条件 AOD，否则用 SystemUI 广播来的场景。 */
    private void reconcileMode() {
        int want = lastScrimMode;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) {
                int st = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).getState();
                if (st == android.view.Display.STATE_DOZE
                        || st == android.view.Display.STATE_DOZE_SUSPEND
                        || st == android.view.Display.STATE_OFF) {
                    want = Motion.AOD;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "查显示状态失败: " + t);
        }
        if (want != mode) {
            Log.i(TAG, "看门狗 -> mode=" + want + " (scrim=" + lastScrimMode + ")");
            mode = want;
            if (want == Motion.AOD) {
                renderer.requestPhotoReload();
            }
            renderer.setMode(want, true);
        }
    }

    private byte[][] snapChunks;""")

# 2) 广播里记住 scrim 模式，不直接下发
rep("""                    Log.i(TAG, "壁纸收到 mode=" + next + " state=" + state + " animate=" + animate);
                    mode = next;
                    if (next == Motion.AOD) {
                        // 息屏时同步一次「当前桌面壁纸」，圆圈内容跟着桌面走
                        renderer.requestPhotoReload();
                    }
                    renderer.setMode(next, animate);""",
    """                    Log.i(TAG, "壁纸收到 mode=" + next + " state=" + state + " animate=" + animate);
                    lastScrimMode = next;
                    reconcileMode();""")

# 3) onCreate 里启动看门狗
rep("""            heartbeat = new Handler(Looper.getMainLooper());""",
    """            dozeWatch = new Runnable() {
                @Override
                public void run() {
                    try {
                        reconcileMode();
                    } catch (Throwable t) {
                        Log.e(TAG, "看门狗异常", t);
                    }
                    heartbeat.postDelayed(this, 1000);
                }
            };
            heartbeat = new Handler(Looper.getMainLooper());
            heartbeat.postDelayed(dozeWatch, 1000);""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')