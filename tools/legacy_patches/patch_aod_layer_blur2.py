# -*- coding: utf-8 -*-
"""LensRenderer：模糊门控从 animating() 改成「息屏层是否可见」。"""
import io

P = r'D:\DSH\default-workspace-2\qihang_lsp\module\src\com\qihang\aod\LensRenderer.java'
s = io.open(P, encoding='utf-8').read()


def rep(a, b):
    global s
    c = s.count(a)
    print('%d <- %r' % (c, a[:46].replace('\n', '\\n')))
    if c != 1:
        raise SystemExit('匹配数不对: %r' % a[:90])
    s = s.replace(a, b)


rep("""        if (blurRadius >= 2f && (pendingMode == Motion.AOD || motion.animating())) {""",
    """        if (blurRadius >= 2f && aodLayerVisible()) {""")

rep("""    /** 预览的帧间隔：按屏幕刷新率算（120Hz → 8ms）；壁纸引擎固定 16ms 省电。 */""",
    """    /**
     * 「息屏层是否还在画面上」—— 模糊只属于息屏层（官方 aod_pass 门控 ratio[0]&gt;0）。
     * ratio[0] 走完的时长：tr2（熄屏→桌面）300ms，其余方向 950/650ms。
     * 若拿 animating() 当门控，tr2 期间清晰层（桌面预览）会被整幅糊到动画结束 —— 用户报的正是这个。
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
        return el < (motion.lastTransition() == 2 ? 300 : 950);
    }

    /** 预览的帧间隔：按屏幕刷新率算（120Hz → 8ms）；壁纸引擎固定 16ms 省电。 */""")

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')