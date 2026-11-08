# -*- coding: utf-8 -*-
"""即亮即动：
① 引擎自己监听 DisplayManager 的显示状态 —— 亮屏瞬间立刻从息屏态切到锁屏态（tr0 扩散），
   不再等 SystemUI 的 ScrimController 回调（实测晚 ~1s）。
② 过渡期间即使系统报 invisible 也保持满帧出帧（原来 invisible 时降到 300ms/帧，
   等系统把壁纸层设成可见时动画已经过去一大截）。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:46].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:90])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


patch('LensRenderer.java', [
    # 过渡期间满帧，不受 visible 影响
    ("""    private long idleWaitMs() {
        if (!visible) {
            return 300;
        }
        if (motion != null && (motion.animating() || motion.needsContinuousFrames())) {
            // 预览跟着屏幕刷新率走（120Hz 屏 → 8ms，过渡帧数翻倍）；壁纸保持 60fps
            return previewSurface != null ? previewWaitMs : 16;
        }
        return 150;
    }""",
     """    private long idleWaitMs() {
        if (motion != null && (motion.animating() || motion.needsContinuousFrames())) {
            // 过渡/持续动效期间**不看 visible**：系统常把壁纸层判为不可见（AOD、锁屏覆盖），
            // 若这时降帧，等它变成可见时动画已经过去一大截 → 表现为"亮屏后要等一会儿才动"。
            // 预览跟着屏幕刷新率走（120Hz → 8ms）；壁纸保持 60fps。
            return previewSurface != null ? previewWaitMs : 16;
        }
        if (!visible) {
            return 300;
        }
        return 150;
    }

    /** 当前模式（引擎用于"亮屏直驱"判断）。 */
    int currentMode() {
        return pendingMode;
    }"""),
])

patch('QihangWallpaper.java', [
    ("""    private Handler heartbeat;""",
     """    private Handler heartbeat;
    private android.hardware.display.DisplayManager displayManager;
    private android.hardware.display.DisplayManager.DisplayListener displayListener;"""),
    ("""            heartbeat = new Handler(Looper.getMainLooper());""",
     """            // ★ 即亮即动：自己监听显示状态。系统从 DOZE 变成 ON 的那一刻就切到锁屏态，
            //   交给 SystemUI 的 ScrimController 回调会晚约 1 秒（实测）。
            try {
                displayManager = (android.hardware.display.DisplayManager)
                        getSystemService(Context.DISPLAY_SERVICE);
                if (displayManager != null) {
                    displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                        @Override
                        public void onDisplayAdded(int displayId) {
                        }

                        @Override
                        public void onDisplayRemoved(int displayId) {
                        }

                        @Override
                        public void onDisplayChanged(int displayId) {
                            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                                applyDisplayState();
                            }
                        }
                    };
                    displayManager.registerDisplayListener(displayListener,
                            new Handler(Looper.getMainLooper()));
                    applyDisplayState();
                }
            } catch (Throwable t) {
                Log.w(TAG, "注册显示状态监听失败", t);
            }

            heartbeat = new Handler(Looper.getMainLooper());"""),

    ("""        @Override
        public void onDestroy() {
            if (heartbeat != null) {""",
     """        /**
         * 按显示状态直驱三态：熄屏（DOZE/DOZE_SUSPEND/OFF）→ 息屏态；亮屏且当前还在息屏态 → 锁屏态
         * （tr0 圆扩散）。**只在"当前是息屏态"时才动**，不覆盖 SystemUI 已下发的锁屏/桌面态。
         */
        private void applyDisplayState() {
            if (renderer == null || displayManager == null) {
                return;
            }
            android.view.Display d = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d == null) {
                return;
            }
            int st = d.getState();
            boolean dozing = st == android.view.Display.STATE_DOZE
                    || st == android.view.Display.STATE_DOZE_SUSPEND
                    || st == android.view.Display.STATE_OFF;
            int cur = renderer.currentMode();
            if (dozing) {
                if (cur != Motion.AOD) {
                    renderer.setMode(Motion.AOD, true);
                    Log.i(TAG, "显示直驱 → 息屏 displayState=" + st);
                }
            } else if (cur == Motion.AOD) {
                renderer.setMode(Motion.LOCK, true);
                Log.i(TAG, "显示直驱 息屏→锁屏 displayState=" + st + "（即亮即动）");
            }
        }

        @Override
        public void onDestroy() {
            if (displayManager != null && displayListener != null) {
                try {
                    displayManager.unregisterDisplayListener(displayListener);
                } catch (Throwable ignored) {
                }
                displayListener = null;
            }
            if (heartbeat != null) {"""),
])
print('ok')