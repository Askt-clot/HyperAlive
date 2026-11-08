# -*- coding: utf-8 -*-
"""两处修复：
① Motion：指纹解锁时 SystemUI 常常「熄屏→(一闪而过)锁屏→桌面」，按官方语义应走 tr2（圆扩散）；
   把闪过的锁屏态并掉；并暴露过渡已进行毫秒数。
② LensRenderer：模糊属于「息屏层」，息屏层消失后不能再糊清晰层 —— 官方 ratio[0] 在 tr2 只有 300ms，
   之前用 animating() 门控导致桌面预览整幅糊到动画结束（用户报的问题）。"""
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


patch('Motion.java', [
    ("""    private long aodEnterNanos;""",
     """    private long aodEnterNanos;
    /** 进入当前模式的时间与"上一个模式"，用于并掉指纹解锁时一闪而过的锁屏态。 */
    private long modeEnterNanos;
    private int modeBefore = -1;"""),

    ("""    void setMode(int next, boolean animate, long nowNanos) {
        int prev = mode;
        if (next == prev && !animating) {
            return;
        }""",
     """    /** 过渡已进行的毫秒数；没有正在进行的过渡时返回 -1。 */
    long transitionElapsedMs(long nowNanos) {
        if (!animating || startNanos == 0) {
            return -1;
        }
        return (nowNanos - startNanos) / 1_000_000L;
    }

    void setMode(int next, boolean animate, long nowNanos) {
        int prev = mode;
        if (next == prev && !animating) {
            return;
        }
        // 指纹从息屏直接解锁时，SystemUI 常在极短时间内报「熄屏→锁屏→桌面」两次变化；
        // 官方语义里这是「熄屏→桌面」= tr2（圆扩散）。把一闪而过的锁屏态并掉，否则会退化成
        // tr4（锁屏→桌面），在真机上表现为"指纹解锁进桌面没有扩散动画"。
        if (prev == LOCK && next == HOME && modeBefore == AOD
                && modeEnterNanos > 0 && nowNanos - modeEnterNanos < 250_000_000L) {
            prev = AOD;
        }"""),

    ("""        boolean interrupted = animating;
        int tr = transition(prev, next);
        lastTransition = tr;
        buildTracks(prev, next, tr, interrupted);
        mode = next;""",
     """        boolean interrupted = animating;
        int tr = transition(prev, next);
        lastTransition = tr;
        buildTracks(prev, next, tr, interrupted);
        modeBefore = mode;
        modeEnterNanos = nowNanos;
        mode = next;"""),
])

patch('LensRenderer.java', [
    ("""        float blurRadius = motion.blur();
        int photoTex = wallpaperTexture;
        if (blurRadius >= 2f && (pendingMode == Motion.AOD || motion.animating())) {""",
     """        float blurRadius = motion.blur();
        int photoTex = wallpaperTexture;
        if (blurRadius >= 2f && aodLayerVisible()) {"""),

    ("""    private void drawQuad(""",
     """    /**
     * 「息屏层是否还在画面上」—— 模糊只属于息屏层（官方 aod_pass 门控 ratio[0]&gt;0）。
     * ratio[0] 走完的时长：tr2（熄屏→桌面）300ms，其余方向 950/650ms；
     * 若拿 animating() 当门控，tr2 期间清晰层的桌面预览会被整幅糊到动画结束。
     */
    private boolean aodLayerVisible() {
        if (pendingMode == Motion.AOD) {
            return true;
        }
        if (!motion.animating()) {
            return false;
        }
        long el = motion.transitionElapsedMs(System.nanoTime());
        if (el < 0) {
            return true;
        }
        long aodMs = motion.lastTransition() == 2 ? 300 : 950;
        return el < aodMs;
    }

    private void drawQuad("""),
])
print('ok')