# -*- coding: utf-8 -*-
"""① 编辑器切场景播放官方过渡（tr0/tr2 扩散/收缩）；② 预览渲染加角度+fps 日志（量转速用）。"""
import io

BASE = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for a, b in pairs:
        c = s.count(a)
        print('%s %d <- %r' % (path, c, a[:44].replace('\n', '\\n')))
        if c != 1:
            raise SystemExit('匹配数不对: %r' % a[:80])
        s = s.replace(a, b)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


# ① LivePreviewView：切场景带过渡动画
patch('LivePreviewView.java', [
    ("""    void setScene(int scene) {
        mode = scene;
        if (renderer != null) {
            renderer.setMode(scene, false);
        }""",
     """    void setScene(int scene) {
        setScene(scene, true);
    }

    /** animate=true 时播放官方过渡（熄屏→锁屏是 950ms 圆扩散 EXPAND，→桌面是 tr2）。 */
    void setScene(int scene, boolean animate) {
        mode = scene;
        if (renderer != null) {
            renderer.setMode(scene, animate);
        }"""),
])

# ② LensRenderer：预览路径打角度/fps（每秒一行），用于核对转速
patch('LensRenderer.java', [
    ("""                    applyPendingMode();
                    drawFrame(System.nanoTime());""",
     """                    applyPendingMode();
                    drawFrame(System.nanoTime());
                    if (previewSurface != null) {
                        logPreview(nowNanos0());
                    }"""),
    ("""    private long idleWaitMs() {""",
     """    private long previewLogNanos;
    private int previewFrames;

    private long nowNanos0() {
        return System.nanoTime();
    }

    /** 预览：每秒打一行"角度 + 帧率"，用来核对自转速度与流畅度。 */
    private void logPreview(long nowNanos) {
        previewFrames++;
        if (previewLogNanos == 0) {
            previewLogNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - previewLogNanos;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        float sec = elapsed / 1_000_000_000f;
        Log.i(TAG, "预览 mode=" + (motion == null ? -1 : motion.mode())
                + " angle=" + (motion == null ? 0 : motion.photoAngle())
                + " fps=" + Math.round(previewFrames / sec));
        previewLogNanos = nowNanos;
        previewFrames = 0;
    }

    private long idleWaitMs() {"""),
])
print('ok')