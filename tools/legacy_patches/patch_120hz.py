# -*- coding: utf-8 -*-
"""预览按屏幕刷新率出帧（120Hz → 8ms，过渡帧数翻倍）；壁纸引擎仍 60fps 省电；加过渡帧数日志。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LensRenderer.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b, n=1):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:52].replace('\n', '\\n')))
    if c != n:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""    /** 是否已经成功出过帧（预览用来决定要不要退回占位图）。 */
    private volatile boolean drewFrame;""",
    """    /** 是否已经成功出过帧（预览用来决定要不要退回占位图）。 */
    private volatile boolean drewFrame;
    /** 预览的帧间隔：按屏幕刷新率算（120Hz → 8ms）；壁纸引擎固定 16ms 省电。 */
    private long previewWaitMs = 16;
    private int transitionFrames;""")

rep("""    private long idleWaitMs() {
        if (!visible) {
            return 300;
        }
        if (motion != null && (motion.animating() || motion.needsContinuousFrames())) {
            return 16;
        }
        return 150;
    }""",
    """    private long idleWaitMs() {
        if (!visible) {
            return 300;
        }
        if (motion != null && (motion.animating() || motion.needsContinuousFrames())) {
            // 预览跟着屏幕刷新率走（120Hz 屏 → 8ms，过渡帧数翻倍）；壁纸保持 60fps
            return previewSurface != null ? previewWaitMs : 16;
        }
        return 150;
    }

    /** 取屏幕刷新率（拿不到就按 60Hz）。壁纸引擎也用它算出预览的帧间隔。 */
    private void initFramePacing() {
        float hz = 60f;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            appContext.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display d = dm == null ? null
                    : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null && d.getRefreshRate() > 0) {
                hz = d.getRefreshRate();
            }
        } catch (Throwable t) {
            Log.w(TAG, "取刷新率失败，按 60Hz", t);
        }
        previewWaitMs = Math.max(6, Math.round(1000f / hz));
        Log.i(TAG, "预览帧间隔=" + previewWaitMs + "ms（屏幕 " + hz + "Hz）");
    }

    /** 统计一次过渡实际出了多少帧（验证流畅度用）。 */
    private void countTransitionFrames() {
        if (motion == null) {
            return;
        }
        if (motion.animating()) {
            transitionFrames++;
        } else if (transitionFrames > 0) {
            Log.i(TAG, "过渡帧数=" + transitionFrames + "（tr=" + motion.lastTransition()
                    + " 帧间隔=" + previewWaitMs + "ms）");
            transitionFrames = 0;
        }
    }""")

rep("""        if (!initGl()) {
            Log.e(TAG, "GL 初始化失败");""",
    """        initFramePacing();
        if (!initGl()) {
            Log.e(TAG, "GL 初始化失败");""")

rep("""                    drewFrame = true;
                    if (previewSurface != null) {
                        logPreview(nowNanos0());
                    }""",
    """                    drewFrame = true;
                    if (previewSurface != null) {
                        countTransitionFrames();
                        logPreview(nowNanos0());
                    }""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')